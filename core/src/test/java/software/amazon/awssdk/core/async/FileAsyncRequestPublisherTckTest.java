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

import java.nio.file.attribute.PosixFilePermissions;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.TestEnvironment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

public class FileAsyncRequestPublisherTckTest extends org.reactivestreams.tck.PublisherVerification<ByteBuffer> {

    // same as `FileAsyncRequestProvider.DEFAULT_CHUNK_SIZE`:
    final int DEFAULT_CHUNK_SIZE = 16 * 1024;
    final int MAX_ELEMENTS = 1000;

    public FileAsyncRequestPublisherTckTest() {
        super(new TestEnvironment());
    }

    @Override
    public Publisher<ByteBuffer> createPublisher(long elements) {
        if (elements < MAX_ELEMENTS) {
            Path testFile = createFileWith(elements);
            return AsyncRequestProvider.fromFile(testFile);
        } else {
            return null; // we don't support more elements
        }
    }

    private Path createFileWith(long elements) {
        try {
            // mock file system:
            final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
            final Path testFile = Files.createFile(fs.getPath("/test-file.tmp"));
            final BufferedWriter writer = Files.newBufferedWriter(testFile);

            final char[] chars = new char[DEFAULT_CHUNK_SIZE];
            Arrays.fill(chars, 'A');

            for (int i = 0; i < elements; i++) {
                writer.write(chars); // write one chunk
            }
            return testFile;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Publisher<ByteBuffer> createFailedPublisher() {
        Path unreadable = createUnreadableFile();
        // tests properly failing on non existing files:
        return AsyncRequestProvider.fromFile(unreadable);
    }

    private Path createUnreadableFile() {
        try {
            File dne = File.createTempFile("prefix", "suffix");
            dne.deleteOnExit();
            Path path = dne.toPath();

            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("-w--w--w-"));
            return path;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
