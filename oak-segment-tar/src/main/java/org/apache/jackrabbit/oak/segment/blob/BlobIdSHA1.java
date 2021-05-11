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

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Represents a BlobId which was generated with the SHA-1 algorithm.
 */
public class BlobIdSHA1 extends BlobId {

    private static Logger LOG = LoggerFactory.getLogger(BlobIdSHA1.class.getName());

    // Represent the SHA-1 blob Id as an array of bytes (20)
    private final byte[] blobIdBytes;

    public BlobIdSHA1(String blobId) throws DecoderException {
        if (blobId == null || blobId.length() != 40) {
            throw new IllegalArgumentException("BlobIdSHA1 can only instantiate a blobId from a 40 character long String");
        }

        this.blobIdBytes = Hex.decodeHex(blobId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlobIdSHA1 blobId = (BlobIdSHA1) o;
        return Arrays.equals(blobIdBytes, blobId.blobIdBytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(blobIdBytes);
    }

    @Override
    public String toString() {
        return Hex.encodeHexString(blobIdBytes);
    }

}
