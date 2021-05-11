/*************************************************************************
 * ADOBE CONFIDENTIAL
 * ___________________
 *
 * Copyright 2021 Adobe
 * All Rights Reserved.
 *
 * NOTICE: All information contained herein is, and remains
 * the property of Adobe and its suppliers, if any. The intellectual
 * and technical concepts contained herein are proprietary to Adobe
 * and its suppliers and are protected by all applicable intellectual
 * property laws, including trade secret and copyright laws.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Adobe.
 **************************************************************************/
package org.apache.jackrabbit.oak.segment.blob;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a BlobId in Direct Binary Access (DBA) format (random UUID + timestamp).
 */
public class BlobIdDBA extends BlobId {

    private final UUID uuid;
    private final long timestamp;

    // A blob ID string can be provided with or without hyphens
    private static final int BLOB_ID_LENGTH_WITH_HYPHENS = 50;
    private static final int BLOB_ID_LENGTH_NO_HYPHENS = 45;

    public BlobIdDBA(String blobId) {
        if (blobId == null || !(blobId.length() == BLOB_ID_LENGTH_NO_HYPHENS ||
                blobId.length() == BLOB_ID_LENGTH_WITH_HYPHENS)) {
            throw new IllegalArgumentException("BlobIdDBA can only instantiate a blobId from a 45 or 50 character long String");
        }

        if (blobId.length() == BLOB_ID_LENGTH_NO_HYPHENS) {
            // This is a DBA blob ID with no hyphens. Add them back in.
            blobId = blobId.substring(0, 8) + "-" + blobId.substring(8, 12) + "-" + blobId.substring(12, 16) + "-" +
                    blobId.substring(16, 20) + "-" + blobId.substring(20, 32) + "-" + blobId.substring(32);
        }

        this.uuid = UUID.fromString(blobId.substring(0, 36));
        this.timestamp = Long.parseLong(blobId.substring(37));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlobIdDBA blobId = (BlobIdDBA) o;
        return uuid.equals(blobId.uuid) && timestamp == blobId.timestamp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, timestamp);
    }

    @Override
    public String toString() {
        return uuid.toString() + "-" + Long.toString(timestamp);
    }
}
