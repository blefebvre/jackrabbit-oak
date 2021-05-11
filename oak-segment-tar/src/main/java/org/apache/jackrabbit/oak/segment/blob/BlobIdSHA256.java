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

import java.util.Arrays;

/**
 * Represents a BlobId which was generated with the SHA-256 algorithm.
 */
public class BlobIdSHA256 extends BlobId {

    private final long[] segments;

    protected BlobIdSHA256(String blobId) {
        if (blobId == null || blobId.length() != 64) {
            throw new IllegalArgumentException("BlobIdSHA256 can only instantiate a blobId from a 64 character long String.");
        }

        this.segments = new long[4];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = Long.parseUnsignedLong(blobId.substring(i * 16, (i + 1) * 16), 16);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlobIdSHA256 blobId = (BlobIdSHA256) o;
        return Arrays.equals(segments, blobId.segments);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(segments);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        for (long s : segments) {
            b.append(String.format("%016x", s));
        }
        return b.toString();
    }

}
