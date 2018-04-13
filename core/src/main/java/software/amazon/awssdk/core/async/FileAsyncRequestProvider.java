/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.core.async;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.utils.builder.SdkBuilder;

/**
 * Implementation of {@link AsyncRequestProvider} that reads data from a file.
 */
public final class FileAsyncRequestProvider implements AsyncRequestProvider {

    /**
     * Default size (in bytes) of ByteBuffer chunks read from the file and delivered to the subscriber.
     */
    private static final int DEFAULT_CHUNK_SIZE = 16 * 1024;

    /**
     * File to read.
     */
    private final Path file;

    /**
     * Size (in bytes) of ByteBuffer chunks read from the file and delivered to the subscriber.
     */
    private final int chunkSizeInBytes;

    private FileAsyncRequestProvider(DefaultBuilder builder) {
        this.file = builder.path;
        this.chunkSizeInBytes = builder.chunkSizeInBytes == null ? DEFAULT_CHUNK_SIZE : builder.chunkSizeInBytes;
    }

    @Override
    public long contentLength() {
        return file.toFile().length();
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> s) {
        new FileSubscription(file, s, chunkSizeInBytes);
    }

    /**
     * @return Builder instance to construct a {@link FileAsyncRequestProvider}.
     */
    public static Builder builder() {
        return new DefaultBuilder();
    }

    /**
     * A builder for {@link FileAsyncRequestProvider}.
     */
    public interface Builder extends SdkBuilder<Builder, FileAsyncRequestProvider> {

        /**
         * Sets the file to send to the service.
         *
         * @param path Path to file to read.
         * @return This builder for method chaining.
         */
        Builder path(Path path);

        /**
         * Sets the size of chunks read from the file. Increasing this will cause more data to be buffered into memory but
         * may yield better latencies. Decreasing this will reduce memory usage but may cause reduced latency. Setting this value
         * is very dependent on upload speed and requires some performance testing to tune.
         *
         * <p>The default chunk size is {@value #DEFAULT_CHUNK_SIZE} bytes</p>
         *
         * @param chunkSize New chunk size in bytes.
         * @return This builder for method chaining.
         */
        Builder chunkSizeInBytes(Integer chunkSize);

    }

    private static final class DefaultBuilder implements Builder {

        private Path path;
        private Integer chunkSizeInBytes;

        @Override
        public Builder path(Path path) {
            this.path = path;
            return this;
        }

        public void setPath(Path path) {
            path(path);
        }

        @Override
        public Builder chunkSizeInBytes(Integer chunkSizeInBytes) {
            this.chunkSizeInBytes = chunkSizeInBytes;
            return this;
        }

        public void setChunkSizeInBytes(Integer chunkSizeInBytes) {
            chunkSizeInBytes(chunkSizeInBytes);
        }

        @Override
        public FileAsyncRequestProvider build() {
            return new FileAsyncRequestProvider(this);
        }
    }

    /**
     * Reads the file for one subscriber.
     */
    private static class FileSubscription implements Subscription {

        private AsynchronousFileChannel inputChannel;
        private final Subscriber<? super ByteBuffer> subscriber;
        private final int chunkSize;

        private long position = 0;
        private AtomicLong outstandingDemand = new AtomicLong(0);
        private boolean writeInProgress = false;
        private boolean cancelled = false; // This flag will track whether this `Subscription` is to be considered cancelled or not

        private FileSubscription(Path file, Subscriber<? super ByteBuffer> subscriber, int chunkSize) {
            // As per rule 1.09, we need to throw a `java.lang.NullPointerException` if the `Subscriber` is `null`
            if (subscriber == null) {
                throw null;
            }
            this.subscriber = subscriber;
            this.chunkSize = chunkSize;

            try {
                this.inputChannel = openInputChannel(file);

            } catch (final Throwable t) {
                subscriber.onSubscribe(new Subscription() { // We need to make sure we signal onSubscribe before onError, obeying rule 1.9
                    @Override
                    public void cancel() {
                    }

                    @Override
                    public void request(long n) {
                    }
                });
                terminateDueTo(t);
            }

            if (!cancelled) {
                // Deal with setting up the subscription with the subscriber
                try {
                    subscriber.onSubscribe(this);
                } catch (final Throwable t) { // Due diligence to obey 2.13
                    terminateDueTo(new IllegalStateException(subscriber + " violated the Reactive Streams rule 2.13 by throwing an exception from onSubscribe.", t));
                }

            }
        }

        @Override
        public void request(long n) {
            if (n < 1) {
                IllegalArgumentException ex =
                    new IllegalArgumentException(subscriber + " violated the Reactive Streams rule 3.9 by requesting a non-positive number of elements.");
                terminateDueTo(ex);
            } else {
                try {
                    long initialDemand = outstandingDemand.get();
                    long newDemand = initialDemand + n;
                    if (newDemand < 1) {
                        // As governed by rule 3.17, when demand overflows `Long.MAX_VALUE` we treat the signalled demand as "effectively unbounded"
                        outstandingDemand.set(Long.MAX_VALUE);
                    } else {
                        outstandingDemand.set(newDemand);
                    }

                    synchronized (this) {
                        if (!writeInProgress) {
                            writeInProgress = true;
                            readData();
                        }
                    }
                } catch (Exception e) {
                    terminateDueTo(e);
                }
            }
        }

        @Override
        public void cancel() {
            this.cancelled = true;
            closeFile();
        }

        private void readData() {
            // It's possible to have another request for data come in after we've closed the file.
            if (!inputChannel.isOpen()) {
                return;
            }
            final ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
            inputChannel.read(buffer, position, buffer, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer result, ByteBuffer attachment) {
                    if (result > 0) {
                        attachment.flip();
                        position += attachment.remaining();
                        subscriber.onNext(attachment);
                        // If we have more permits, queue up another read.
                        if (outstandingDemand.decrementAndGet() > 0) {
                            readData();
                            return;
                        }
                    } else {
                        // Reached the end of the file, notify the subscriber and cleanup
                        subscriber.onComplete();
                        closeFile();
                    }

                    synchronized (FileSubscription.this) {
                        writeInProgress = false;
                    }
                }

                @Override
                public void failed(Throwable exc, ByteBuffer attachment) {
                    terminateDueTo(exc);
                    closeFile();
                }
            });
        }

        private void closeFile() {
            try {
                inputChannel.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void terminateDueTo(final Throwable t) {
            cancelled = true; // When we signal onError, the subscription must be considered as cancelled, as per rule 1.6
            try {
                subscriber.onError(t); // Then we signal the error downstream, to the `Subscriber`
            } catch (final Throwable t2) { // If `onError` throws an exception, this is a spec violation according to rule 1.9, and all we can do is to log it.
                (new IllegalStateException(subscriber + " violated the Reactive Streams rule 2.13 by throwing an exception from onError.", t2)).printStackTrace(System.err);
            }
        }

    }

    private static AsynchronousFileChannel openInputChannel(Path path) {
        try {
            if (!Files.exists(path)) Files.createFile(path);
            return AsynchronousFileChannel.open(path, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


}
