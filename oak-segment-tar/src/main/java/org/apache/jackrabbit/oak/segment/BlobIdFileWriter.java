/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.segment;

import org.apache.jackrabbit.oak.segment.blob.BlobId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

// Appends a blob ID to a file
public class BlobIdFileWriter {

    private static final Logger LOG = LoggerFactory.getLogger(BlobIdFileWriter.class);

    private static final String BLOB_LIST_FILE_PATH = "crx-quickstart/cloud-migration/oak-blob-list-" + new Date().getTime();
    private static final int NUMBER_OF_BLOBS_UNTIL_LOG_PRINTED = 10000;
    private static long totalBlobsLogged = 0;

    public static synchronized void appendBlobIdToBlobListFile(BlobId blobId) {
        // Log a path to the blob list file
        if (totalBlobsLogged == 0) {
            LOG.info("Listing blob IDs to {}", BLOB_LIST_FILE_PATH);
        }

        String blobEntry = blobId.getCloudBlobName() + System.lineSeparator();
        try {
            // FIXME: optimization: this is going to be slower than keeping a BufferedWriter open
            Files.write(
                    Paths.get(BLOB_LIST_FILE_PATH),
                    blobEntry.getBytes(),
                    CREATE, APPEND
            );
        } catch (IOException ioException) {
            LOG.error("Unable to record blobId: {}", blobId.getCloudBlobName(), ioException);
        }

        totalBlobsLogged++;

        // Every NUMBER_OF_BLOBS_UNTIL_LOG_PRINTED blobs, log an update
        if (totalBlobsLogged % NUMBER_OF_BLOBS_UNTIL_LOG_PRINTED == 0) {
            LOG.info("Listed {} blob IDs so far", totalBlobsLogged);
        }
    }
}
