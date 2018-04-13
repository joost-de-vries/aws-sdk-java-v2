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

import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
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
    final int ELEMENTS = 1000;

    // mock file system:
    final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());

    final Path testFile;
    final Path unreadable;

    public FileAsyncRequestPublisherTckTest() throws IOException {
        super(new TestEnvironment());
        testFile = Files.createFile(fs.getPath("/test-file.tmp"));

        this.unreadable=createUnreadableFile();

        final BufferedWriter writer = Files.newBufferedWriter(testFile);

        final char[] chars = new char[DEFAULT_CHUNK_SIZE];
        Arrays.fill(chars, 'A');

        for (int i = 0; i < ELEMENTS; i++) {
            writer.write(chars); // write one chunk
        }
    }

    private Path createUnreadableFile() throws IOException {
        File dne = File.createTempFile("prefix", "suffix");
        dne.deleteOnExit();
        Path path = dne.toPath();

        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("-w--w--w-"));
        return path;
    }

    @Override
    public Publisher<ByteBuffer> createPublisher(long elements) {
        if (elements < ELEMENTS) return AsyncRequestProvider.fromFile(testFile);
        else return null; // we don't support more elements
    }

    @Override
    public Publisher<ByteBuffer> createFailedPublisher() {
        // tests properly failing on non existing files:
        return AsyncRequestProvider.fromFile(unreadable);
    }
}
