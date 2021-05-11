/*************************************************************************
 * ADOBE CONFIDENTIAL
 * ___________________
 *
 * Copyright 2019 Adobe
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BlobId {

    private static Logger LOG = LoggerFactory.getLogger(BlobId.class.getName());

    /**
     * Instantiate a new BlobId based on the provided string.
     * @param blobId the string representation of a blob's ID. Supports SHA-1, SHA-256, or DBA (direct binary access) formats
     * @return a BlobId object, or null if the provided blobId cannot be interpreted
     */
    public static BlobId fromString(String blobId) {
        try {
            // Remove size component if present (follows the "#")
            int hashIndex = blobId.indexOf("#");
            if (hashIndex > -1) {
                blobId = blobId.substring(0, blobId.indexOf("#"));
            }

            if (blobId.length() >= 64) {
                // This is a blob Id generated with the SHA-256 algorithm
                return new BlobIdSHA256(blobId);
            } else if (blobId.length() >= 45) {
                // This is a "direct binary access" blob Id (random UUID + timestamp)
                return new BlobIdDBA(blobId);
            } else if (blobId.length() >= 40) {
                // This is a blob Id generated with the SHA-1 algorithm
                return new BlobIdSHA1(blobId);
            } else {
                LOG.error("Unknown BlobID format encountered (not SHA-256, SHA-1, or DBA). blobId=\"{}\"", blobId);
                return null;
            }
        } catch (DecoderException de) {
            LOG.error("DecoderException encountered interpreting provided blobId=\"{}\"", blobId, de);
            return null;
        } catch (Exception e) {
            LOG.error("Exception encountered interpreting provided blobId=\"{}\"", blobId, e);
            return null;
        }
    }

    /**
     * Determine if the provided blob ID matches an accepted format.
     * @param blobId the blob ID to check
     * @return true if the provided blob ID is valid; false otherwise
     */
    public static boolean isValidBlodId(String blobId) {
        if (blobId == null) {
            return false;
        }

        // SHA-256 format blob Ids
        String sha256BlobIdRegex = "^[0-9a-f]{4}-[0-9a-f]{60}$";

        // SHA-1 format blob Ids
        String sha1BlobIdRegex = "^[0-9a-f]{4}-[0-9a-f]{36}$";

        // Direct Binary Access format blob Ids
        String dbaBlobIdRegex = "^[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]{13}$";

        return blobId.matches(sha256BlobIdRegex) || blobId.matches(sha1BlobIdRegex) || blobId.matches(dbaBlobIdRegex);
    }

    @Override
    public abstract boolean equals(Object o);

    @Override
    public abstract int hashCode();

    @Override
    public abstract String toString();

    public String getCloudBlobName() {
        String blobId = toString();
        return blobId.substring(0, 4) + "-" + blobId.substring(4);
    }

}
