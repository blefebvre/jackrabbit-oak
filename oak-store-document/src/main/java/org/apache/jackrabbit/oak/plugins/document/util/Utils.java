/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.document.util;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.AbstractIterator;

import org.apache.jackrabbit.oak.commons.OakVersion;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.commons.StringUtils;
import org.apache.jackrabbit.oak.plugins.document.ClusterNodeInfo;
import org.apache.jackrabbit.oak.plugins.document.ClusterNodeInfoDocument;
import org.apache.jackrabbit.oak.plugins.document.Collection;
import org.apache.jackrabbit.oak.plugins.document.DocumentStore;
import org.apache.jackrabbit.oak.plugins.document.DocumentStoreException;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.Path;
import org.apache.jackrabbit.oak.plugins.document.Revision;
import org.apache.jackrabbit.oak.plugins.document.RevisionVector;
import org.apache.jackrabbit.oak.plugins.document.StableRevisionComparator;
import org.apache.jackrabbit.oak.stats.Clock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.transform;
import static org.apache.jackrabbit.oak.plugins.document.NodeDocument.isDeletedEntry;
import static org.apache.jackrabbit.oak.plugins.document.NodeDocument.isCommitRootEntry;
import static org.apache.jackrabbit.oak.plugins.document.NodeDocument.isRevisionsEntry;

/**
 * Utility methods.
 */
public class Utils {
    private static final Logger LOG = LoggerFactory.getLogger(Utils.class);

    private static String MODULE_VERSION = null;

    /**
     * Approximate length of a Revision string.
     */
    private static final int REVISION_LENGTH =
            new Revision(System.currentTimeMillis(), 0, 0).toString().length();

    /**
     * The length of path (in characters), whose UTF-8 representation can not
     * possibly be too large to be used for the primary key for the document
     * store.
     */
    public static final int PATH_SHORT = Integer.getInteger("oak.pathShort", 165);

    /**
     * The maximum length of the parent path, in bytes. If the parent path is
     * longer, then the id of a document is no longer the path, but the hash of
     * the parent, and then the node name.
     */
    public static final int PATH_LONG = Integer.getInteger("oak.pathLong", 350);

    /**
     * The maximum size a node name, in bytes. This is only a problem for long path.
     */
    public static final int NODE_NAME_LIMIT = Integer.getInteger("oak.nodeNameLimit", 150);

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * A predicate for property and _deleted names.
     */
    public static final Predicate<String> PROPERTY_OR_DELETED = new Predicate<String>() {
        @Override
        public boolean apply(@Nullable String input) {
            return Utils.isPropertyName(input) || isDeletedEntry(input);
        }
    };

    /**
     * A predicate for property, _deleted, _commitRoot or _revisions names.
     */
    public static final Predicate<String> PROPERTY_OR_DELETED_OR_COMMITROOT_OR_REVISIONS = new Predicate<String>() {
        @Override
        public boolean apply(@Nullable String input) {
            return Utils.isPropertyName(input) || isDeletedEntry(input) || isCommitRootEntry(input) || isRevisionsEntry(input);
        }
    };

    /**
     * A predicate for _commitRoot and _revisions names.
     */
    public static final Predicate<String> COMMITROOT_OR_REVISIONS = new Predicate<String>() {
        @Override
        public boolean apply(@Nullable String input) {
            return isCommitRootEntry(input) || isRevisionsEntry(input);
        }
    };

    public static int pathDepth(String path) {
        if (path.equals("/")) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    /**
     * Calculates the depth prefix of the id for the given {@code path}. The is
     * the same as {@link #pathDepth(String)}, but takes a {@link Path}
     * argument.
     *
     * @param path a path.
     * @return the id depth prefix for the given {@code path}.
     */
    public static int getIdDepth(Path path) {
        int depth = path.getDepth();
        if (!path.isAbsolute()) {
            depth--;
        }
        return depth;
    }

    @SuppressWarnings("unchecked")
    public static int estimateMemoryUsage(Map<?, Object> map) {
        if (map == null) {
            return 0;
        }
        long size = 0;

        for (Entry<?, Object> e : map.entrySet()) {
            if (e.getKey() instanceof Revision) {
                size += 32;
            } else {
                size += StringUtils.estimateMemoryUsage(e.getKey().toString());
            }
            Object o = e.getValue();
            if (o instanceof String) {
                size += StringUtils.estimateMemoryUsage((String) o);
            } else if (o instanceof Long) {
                size += 16;
            } else if (o instanceof Boolean) {
                size += 8;
            } else if (o instanceof Integer) {
                size += 8;
            } else if (o instanceof Map) {
                size += 8 + (long)estimateMemoryUsage((Map<String, Object>) o);
            } else if (o == null) {
                // zero
            } else {
                throw new IllegalArgumentException("Can't estimate memory usage of " + o);
            }
        }

        // overhead for map object
        // TreeMap (80) + unmodifiable wrapper (32)
        size += 112;
        // 64 bytes per entry
        size += (long)map.size() * 64;

        if (size > Integer.MAX_VALUE) {
            LOG.debug("Estimated memory footprint larger than Integer.MAX_VALUE: {}.", size);
            size = Integer.MAX_VALUE;
        }
        return (int) size;
    }

    public static String escapePropertyName(String propertyName) {
        int len = propertyName.length();
        if (len == 0) {
            return "_";
        }
        // avoid creating a buffer if escaping is not needed
        StringBuilder buff = null;
        char c = propertyName.charAt(0);
        int i = 0;
        if (c == '_' || c == '$') {
            buff = new StringBuilder(len + 1);
            buff.append('_').append(c);
            i++;
        }
        for (; i < len; i++) {
            c = propertyName.charAt(i);
            char rep;
            switch (c) {
            case '.':
                rep = 'd';
                break;
            case '\\':
                rep = '\\';
                break;
            default:
                rep = 0;
            }
            if (rep != 0) {
                if (buff == null) {
                    buff = new StringBuilder(propertyName.substring(0, i));
                }
                buff.append('\\').append(rep);
            } else if (buff != null) {
                buff.append(c);
            }
        }
        return buff == null ? propertyName : buff.toString();
    }

    public static String unescapePropertyName(String key) {
        int len = key.length();
        if (key.startsWith("_")
                && (key.startsWith("__") || key.startsWith("_$") || len == 1)) {
            key = key.substring(1);
            len--;
        }
        // avoid creating a buffer if escaping is not needed
        StringBuilder buff = null;
        for (int i = 0; i < len; i++) {
            char c = key.charAt(i);
            if (c == '\\') {
                if (buff == null) {
                    buff = new StringBuilder(key.substring(0, i));
                }
                c = key.charAt(++i);
                if (c == '\\') {
                    // ok
                } else if (c == 'd') {
                    c = '.';
                }
                buff.append(c);
            } else if (buff != null) {
                buff.append(c);
            }
        }
        return buff == null ? key : buff.toString();
    }

    public static boolean isPropertyName(String key) {
        return !key.startsWith("_") || key.startsWith("__") || key.startsWith("_$");
    }

    public static String getIdFromPath(@NotNull String path) {
        int depth = Utils.pathDepth(path);
        if (isLongPath(path)) {
            String parent = PathUtils.getParentPath(path);
            byte[] hash = createSHA256Digest(parent);
            return createHashedId(depth, hash, PathUtils.getName(path));
        }
        return depth + ":" + path;
    }

    public static String getIdFromPath(@NotNull Path path) {
        checkNotNull(path);
        int depth = getIdDepth(path);
        Path parent = path.getParent();
        if (parent != null && isLongPath(path)) {
            byte[] hash = createSHA256Digest(parent.toString());
            return createHashedId(depth, hash, path.getName());
        }
        return depth + ":" + path;
    }

    private static String createHashedId(int depth, byte[] hash, String name) {
        StringBuilder sb = new StringBuilder(hash.length * 2 + name.length() + 6);
        sb.append(depth).append(":h");
        encodeHexString(hash, sb).append("/").append(name);
        return sb.toString();
    }

    private static byte[] createSHA256Digest(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Encodes the given data as hexadecimal string representation and appends
     * it to the {@code StringBuilder}. The hex digits are in lower case.
     *
     * @param data the bytes to encode.
     * @param sb the hexadecimal string representation is appended to this
     *           {@code StringBuilder}.
     * @return the {@code StringBuilder} passed to this method.
     */
    public static StringBuilder encodeHexString(byte[] data, StringBuilder sb) {
        for (byte b : data) {
            sb.append(HEX_DIGITS[(b >> 4) & 0xF]);
            sb.append(HEX_DIGITS[(b & 0xF)]);
        }
        return sb;
    }

    /**
     * Returns the parent id for given id if possible
     *
     * <p>It would return null in following cases
     * <ul>
     *     <li>If id is from long path</li>
     *     <li>If id is for root path</li>
     *     <li>If id is for an invalid path</li>
     * </ul>
     * @param id id for which parent id needs to be determined
     * @return parent id. null if parent id cannot be determined
     */
    @Nullable
    public static String getParentId(String id){
        if(Utils.isIdFromLongPath(id)){
            return null;
        }
        String path = Utils.getPathFromId(id);
        if (!PathUtils.isValid(path)) {
            return null;
        }
        if(PathUtils.denotesRoot(path)){
            return null;
        }
        String parentPath = PathUtils.getParentPath(path);
        return Utils.getIdFromPath(parentPath);
    }

    private static boolean isLongPath(String path) {
        // the most common case: a short path
        // avoid calculating the parent path
        if (path.length() < PATH_SHORT) {
            return false;
        }
        // check if the parent path is long
        byte[] parent = PathUtils.getParentPath(path).getBytes(UTF_8);
        if (parent.length < PATH_LONG) {
            return false;
        }
        String name = PathUtils.getName(path);
        if (name.getBytes(UTF_8).length > NODE_NAME_LIMIT) {
            throw new IllegalArgumentException("Node name is too long: " + path);
        }
        return true;
    }

    public static boolean isLongPath(Path path) {
        // the most common case: a short path
        // avoid calculating the parent path
        if (path.length() < PATH_SHORT) {
            return false;
        }
        // check if the parent path is long
        Path parent = path.getParent();
        if (parent == null) {
            return false;
        }
        if (parent.toString().getBytes(UTF_8).length < PATH_LONG) {
            return false;
        }
        if (path.getName().getBytes(UTF_8).length > NODE_NAME_LIMIT) {
            throw new IllegalArgumentException("Node name is too long: " + path);
        }
        return true;
    }

    public static boolean isIdFromLongPath(String id) {
        int index = id.indexOf(':');
        return index != -1 && index < id.length() - 1 && id.charAt(index + 1) == 'h';
    }

    // TODO deprecate?
    public static String getPathFromId(String id) {
        if (isIdFromLongPath(id)) {
            throw new IllegalArgumentException("Id is hashed: " + id);
        }
        int index = id.indexOf(':');
        return id.substring(index + 1);
    }

    public static int getDepthFromId(String id) throws IllegalArgumentException {
        try {
            int index = id.indexOf(':');
            if (index >= 0) {
                return Integer.parseInt(id.substring(0, index));
            }
        } catch (NumberFormatException e) {
            // ignore and throw IllegalArgumentException
        }
        throw new IllegalArgumentException("Invalid id: " + id);
    }

    public static Path getPreviousPathFor(Path path, Revision r, int height) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("path must be absolute: " + path);
        }
        Path prev = new Path("p");
        for (String name : path.elements()) {
            prev = new Path(prev, name);
        }
        prev = new Path(prev, r.toString());
        return new Path(prev, String.valueOf(height));
    }

    public static String getPreviousIdFor(Path path, Revision r, int height) {
        return getIdFromPath(getPreviousPathFor(path, r, height));
    }

    /**
     * Determines if the passed id belongs to a previous doc
     *
     * @param id id to check
     * @return true if the id belongs to a previous doc
     */
    public static boolean isPreviousDocId(String id){
        int indexOfColon = id.indexOf(':');
        if (indexOfColon > 0 && indexOfColon < id.length() - 1){
            return id.charAt(indexOfColon + 1) == 'p';
        }
        return false;
    }

    /**
     * Determines if the passed id belongs to a leaf level previous doc
     *
     * @param id id to check
     * @return true if the id belongs to a leaf level previous doc
     */
    public static boolean isLeafPreviousDocId(String id){
        return isPreviousDocId(id) && id.endsWith("/0");
    }

    /**
     * Deep copy of a map that may contain map values.
     *
     * @param source the source map
     * @param target the target map
     * @param <K> the type of the map key
     */
    public static <K> void deepCopyMap(Map<K, Object> source, Map<K, Object> target) {
        for (Entry<K, Object> e : source.entrySet()) {
            Object value = e.getValue();
            Comparator<? super K> comparator = null;
            if (value instanceof SortedMap) {
                @SuppressWarnings("unchecked")
                SortedMap<K, Object> map = (SortedMap<K, Object>) value;
                comparator = map.comparator();
            }
            if (value instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<K, Object> old = (Map<K, Object>) value;
                Map<K, Object> c = new TreeMap<K, Object>(comparator);
                deepCopyMap(old, c);
                value = c;
            }
            target.put(e.getKey(), value);
        }
    }

    /**
     * Returns the lower key limit to retrieve the children of the given
     * <code>path</code>.
     *
     * @param path a path.
     * @return the lower key limit.
     */
    public static String getKeyLowerLimit(Path path) {
        String from = getIdFromPath(new Path(path, "a"));
        from = from.substring(0, from.length() - 1);
        return from;
    }

    /**
     * Returns the upper key limit to retrieve the children of the given
     * <code>path</code>.
     *
     * @param path a path.
     * @return the upper key limit.
     */
    public static String getKeyUpperLimit(Path path) {
        String to = getIdFromPath(new Path(path, "z"));
        to = to.substring(0, to.length() - 2) + "0";
        return to;
    }

    /**
     * Returns parentId extracted from the fromKey. fromKey is usually constructed
     * using Utils#getKeyLowerLimit
     *
     * @param fromKey key used as start key in queries
     * @return parentId if possible.
     */
    @Nullable
    public static String getParentIdFromLowerLimit(String fromKey){
        //If key just ends with slash 2:/foo/ then append a fake
        //name to create a proper id
        if(fromKey.endsWith("/")){
            fromKey = fromKey + "a";
        }
        return getParentId(fromKey);
    }

    /**
     * Returns <code>true</code> if a revision tagged with the given revision
     * should be considered committed, <code>false</code> otherwise. Committed
     * revisions have a tag, which equals 'c' or starts with 'c-'.
     *
     * @param tag the tag (may be <code>null</code>).
     * @return <code>true</code> if committed; <code>false</code> otherwise.
     */
    public static boolean isCommitted(@Nullable String tag) {
        return tag != null && (tag.equals("c") || tag.startsWith("c-"));
    }

    /**
     * Resolve the commit revision for the given revision <code>rev</code> and
     * the associated commit tag.
     *
     * @param rev a revision.
     * @param tag the associated commit tag.
     * @return the actual commit revision for <code>rev</code>.
     */
    @NotNull
    public static Revision resolveCommitRevision(@NotNull Revision rev,
                                                 @NotNull String tag) {
        return checkNotNull(tag).startsWith("c-") ?
                Revision.fromString(tag.substring(2)) : rev;
    }

    /**
     * Closes the obj its of type {@link java.io.Closeable}. It is mostly
     * used to close Iterator/Iterables which are backed by say DBCursor
     *
     * @param obj object to close
     */
    public static void closeIfCloseable(Object obj){
        if(obj instanceof Closeable){
            try{
                ((Closeable) obj).close();
            } catch (IOException e) {
                LOG.warn("Error occurred while closing {}", obj, e);
            }
        }
    }

    /**
     * Provides a readable string for given timestamp
     */
    public static String timestampToString(long timestamp){
        return (new Timestamp(timestamp) + "00").substring(0, 23);
    }

    /**
     * Returns the revision with the newer timestamp or {@code null} if both
     * revisions are {@code null}. The implementation will return the first
     * revision if both have the same timestamp.
     *
     * @param a the first revision (or {@code null}).
     * @param b the second revision (or {@code null}).
     * @return the revision with the newer timestamp.
     */
    @Nullable
    public static Revision max(@Nullable Revision a, @Nullable Revision b) {
        return max(a, b, StableRevisionComparator.INSTANCE);
    }

    /**
     * Returns the revision which is considered more recent or {@code null} if
     * both revisions are {@code null}. The implementation will return the first
     * revision if both are considered equal. The comparison is done using the
     * provided comparator.
     *
     * @param a the first revision (or {@code null}).
     * @param b the second revision (or {@code null}).
     * @param c the comparator.
     * @return the revision considered more recent.
     */
    @Nullable
    public static Revision max(@Nullable Revision a,
                               @Nullable Revision b,
                               @NotNull Comparator<Revision> c) {
        if (a == null) {
            return b;
        } else if (b == null) {
            return a;
        }
        return c.compare(a, b) >= 0 ? a : b;
    }

    /**
     * Returns the revision with the older timestamp or {@code null} if both
     * revisions are {@code null}. The implementation will return the first
     * revision if both have the same timestamp.
     *
     * @param a the first revision (or {@code null}).
     * @param b the second revision (or {@code null}).
     * @return the revision with the older timestamp.
     */
    @Nullable
    public static Revision min(@Nullable Revision a, @Nullable Revision b) {
        return min(a, b, StableRevisionComparator.INSTANCE);
    }

    /**
     * Returns the revision which is considered older or {@code null} if
     * both revisions are {@code null}. The implementation will return the first
     * revision if both are considered equal. The comparison is done using the
     * provided comparator.
     *
     * @param a the first revision (or {@code null}).
     * @param b the second revision (or {@code null}).
     * @param c the comparator.
     * @return the revision considered more recent.
     */
    @Nullable
    public static Revision min(@Nullable Revision a,
                               @Nullable Revision b,
                               @NotNull Comparator<Revision> c) {
        if (a == null) {
            return b;
        } else if (b == null) {
            return a;
        }
        return c.compare(a, b) <= 0 ? a : b;
    }

    // default batch size for paging through a document store
    private static final int DEFAULT_BATCH_SIZE = 100;

    /**
     * Returns an {@link Iterable} over all {@link NodeDocument}s in the given
     * store. The returned {@linkplain Iterable} does not guarantee a consistent
     * view on the store. it may return documents that have been added to the
     * store after this method had been called.
     *
     * @param store
     *            a {@link DocumentStore}.
     * @return an {@link Iterable} over all documents in the store.
     */
    public static Iterable<NodeDocument> getAllDocuments(final DocumentStore store) {
        return internalGetSelectedDocuments(store, null, 0, DEFAULT_BATCH_SIZE);
    }

    /**
     * Returns the root node document of the given document store. The returned
     * document is retrieved from the document store via
     * {@link DocumentStore#find(Collection, String)}, which means the
     * implementation is allowed to return a cached version of the document.
     * The document is therefore not guaranteed to be up-to-date.
     *
     * @param store a document store.
     * @return the root document.
     * @throws IllegalStateException if there is no root document.
     */
    @NotNull
    public static NodeDocument getRootDocument(@NotNull DocumentStore store) {
        String rootId = Utils.getIdFromPath(Path.ROOT);
        NodeDocument root = store.find(Collection.NODES, rootId);
        if (root == null) {
            throw new IllegalStateException("missing root document");
        }
        return root;
    }

    /**
     * Returns an {@link Iterable} over all {@link NodeDocument}s in the given
     * store matching a condition on an <em>indexed property</em>. The returned
     * {@link Iterable} does not guarantee a consistent view on the store.
     * it may return documents that have been added to the store after this
     * method had been called.
     *
     * @param store
     *            a {@link DocumentStore}.
     * @param indexedProperty the name of the indexed property.
     * @param startValue the lower bound value for the indexed property
     *                   (inclusive).
     * @param batchSize number of documents to fetch at once
     * @return an {@link Iterable} over all documents in the store matching the
     *         condition
     */
    public static Iterable<NodeDocument> getSelectedDocuments(
            DocumentStore store, String indexedProperty, long startValue, int batchSize) {
        return internalGetSelectedDocuments(store, indexedProperty, startValue, batchSize);
    }

    /**
     * Like {@link #getSelectedDocuments(DocumentStore, String, long, int)} with
     * a default {@code batchSize}.
     */
    public static Iterable<NodeDocument> getSelectedDocuments(
            DocumentStore store, String indexedProperty, long startValue) {
        return internalGetSelectedDocuments(store, indexedProperty, startValue, DEFAULT_BATCH_SIZE);
    }

    private static Iterable<NodeDocument> internalGetSelectedDocuments(
            final DocumentStore store, final String indexedProperty,
            final long startValue, final int batchSize) {
        if (batchSize < 2) {
            throw new IllegalArgumentException("batchSize must be > 1");
        }
        return new Iterable<NodeDocument>() {
            @Override
            public Iterator<NodeDocument> iterator() {
                return new AbstractIterator<NodeDocument>() {

                    private String startId = NodeDocument.MIN_ID_VALUE;

                    private Iterator<NodeDocument> batch = nextBatch();

                    @Override
                    protected NodeDocument computeNext() {
                        // read next batch if necessary
                        if (!batch.hasNext()) {
                            batch = nextBatch();
                        }

                        NodeDocument doc;
                        if (batch.hasNext()) {
                            doc = batch.next();
                            // remember current id
                            startId = doc.getId();
                        } else {
                            doc = endOfData();
                        }
                        return doc;
                    }

                    private Iterator<NodeDocument> nextBatch() {
                        List<NodeDocument> result = indexedProperty == null ? store.query(Collection.NODES, startId,
                                NodeDocument.MAX_ID_VALUE, batchSize) : store.query(Collection.NODES, startId,
                                NodeDocument.MAX_ID_VALUE, indexedProperty, startValue, batchSize);
                        return result.iterator();
                    }
                };
            }
        };
    }

    /**
     * @return if {@code path} represent oak's internal path. That is, a path
     *          element start with a colon.
     */
    public static boolean isHiddenPath(@NotNull String path) {
        return path.contains("/:");
    }

    /**
     * Transforms the given {@link Iterable} from {@link String} to
     * {@link StringValue} elements. The {@link Iterable} must no have
     * {@code null} values.
     */
    public static Iterable<StringValue> asStringValueIterable(
            @NotNull Iterable<String> values) {
        return transform(values, new Function<String, StringValue>() {
            @Override
            public StringValue apply(String input) {
                return new StringValue(input);
            }
        });
    }

    /**
     * Transforms the given paths into ids using {@link #getIdFromPath(String)}.
     */
    public static Iterable<String> pathToId(@NotNull Iterable<String> paths) {
        return transform(paths, new Function<String, String>() {
            @Override
            public String apply(String input) {
                return getIdFromPath(input);
            }
        });
    }

    /**
     * Returns the highest timestamp of all the passed external revisions.
     * A revision is considered external if the clusterId is different from the
     * passed {@code localClusterId}.
     *
     * @param revisions the revisions to consider.
     * @param localClusterId the id of the local cluster node.
     * @return the highest timestamp or {@link Long#MIN_VALUE} if none of the
     *          revisions is external.
     */
    public static long getMaxExternalTimestamp(Iterable<Revision> revisions,
                                               int localClusterId) {
        long maxTime = Long.MIN_VALUE;
        for (Revision r : revisions) {
            if (r.getClusterId() == localClusterId) {
                continue;
            }
            maxTime = Math.max(maxTime, r.getTimestamp());
        }
        return maxTime;
    }

    /**
     * Returns the given number instance as a {@code Long}.
     *
     * @param n a number or {@code null}.
     * @return the number converted to a {@code Long} or {@code null}
     *      if {@code n} is {@code null}.
     */
    public static Long asLong(@Nullable Number n) {
        if (n == null) {
            return null;
        } else if (n instanceof Long) {
            return (Long) n;
        } else {
            return n.longValue();
        }
    }

    /**
     * Returns a revision vector that contains a revision for each of the passed
     * cluster nodes with a revision timestamp that corresponds to the last
     * known time when the cluster node was started.
     *
     * @param clusterNodes the cluster node information.
     * @return revision vector representing the last known time when the cluster
     *      nodes were started.
     */
    @NotNull
    public static RevisionVector getStartRevisions(@NotNull Iterable<ClusterNodeInfoDocument> clusterNodes) {
        List<Revision> revs = new ArrayList<>();
        for (ClusterNodeInfoDocument doc : clusterNodes) {
            revs.add(new Revision(doc.getStartTime(), 0, doc.getClusterId()));
        }
        return new RevisionVector(revs);
    }

    /**
     * Returns the minimum timestamp to use for a query for child documents that
     * have been modified between {@code fromRev} and {@code toRev}.
     *
     * @param fromRev the from revision.
     * @param toRev the to revision.
     * @param minRevisions the minimum revisions of foreign cluster nodes. These
     *                     are derived from the startTime of a cluster node.
     * @return the minimum timestamp.
     */
    public static long getMinTimestampForDiff(@NotNull RevisionVector fromRev,
                                              @NotNull RevisionVector toRev,
                                              @NotNull RevisionVector minRevisions) {
        // make sure we have minimum revisions for all known cluster nodes
        fromRev = fromRev.pmax(minRevisions);
        toRev = toRev.pmax(minRevisions);
        // keep only revision entries that changed
        RevisionVector from = fromRev.difference(toRev);
        RevisionVector to = toRev.difference(fromRev);
        // now calculate minimum timestamp
        long min = Long.MAX_VALUE;
        for (Revision r : from) {
            min = Math.min(r.getTimestamp(), min);
        }
        for (Revision r : to) {
            min = Math.min(r.getTimestamp(), min);
        }
        return min;
    }

    /**
     * Returns true if all the revisions in the {@code a} greater or equals
     * to their counterparts in {@code b}. If {@code b} contains revisions
     * for cluster nodes that are not present in {@code a}, return false.
     *
     * @param a
     * @param b
     * @return true if all the revisions in the {@code a} are at least
     * as recent as their counterparts in the {@code b}
     */
    public static boolean isGreaterOrEquals(@NotNull RevisionVector a,
                                            @NotNull RevisionVector b) {
        return a.pmax(b).equals(a);
    }

    /**
     * Returns {@code true} if changes identified by the {@code from} and
     * {@code to} {@code RevisionVector} are considered local changes. That is
     * the only difference between the two revision vectors are for the given
     * (local) {@code clusterId}.
     *
     * @param from the from revision vector.
     * @param to the to revision vector.
     * @param clusterId the local clusterId.
     * @return whether the changes are considered local.
     */
    public static boolean isLocalChange(@NotNull RevisionVector from,
                                        @NotNull RevisionVector to,
                                        int clusterId) {
        RevisionVector diff = to.difference(from);
        return diff.getDimensions() == 1 && diff.getRevision(clusterId) != null;
    }

    /**
     * Wraps the given iterable and aborts iteration over elements when the
     * predicate on an element evaluates to {@code false}. Calling
     * {@code close()} on the returned iterable will close the passed iterable
     * if it is {@link Closeable}.
     *
     * @param iterable the iterable to wrap.
     * @param p the predicate.
     * @return the aborting iterable.
     */
    public static <T> CloseableIterable<T> abortingIterable(Iterable<T> iterable,
                                                            Predicate<T> p) {
        checkNotNull(iterable);
        checkNotNull(p);
        return new CloseableIterable<T>(() -> {
            final Iterator<T> it = iterable.iterator();
            return new AbstractIterator<T>() {
                @Override
                protected T computeNext() {
                    if (it.hasNext()) {
                        T next = it.next();
                        if (p.apply(next)) {
                            return next;
                        }
                    }
                    return endOfData();
                }
            };
        }, () -> closeIfCloseable(iterable));
    }

    /**
     * Makes sure the current time is after the most recent external revision
     * timestamp in the _lastRev map of the given root document. If necessary
     * the current thread waits until {@code clock} is after the external
     * revision timestamp.
     *
     * @param rootDoc the root document.
     * @param clock the clock.
     * @param clusterId the local clusterId.
     * @throws InterruptedException if the current thread is interrupted while
     *          waiting. The interrupted status on the current thread is cleared
     *          when this exception is thrown.
     */
    public static void alignWithExternalRevisions(@NotNull NodeDocument rootDoc,
                                                  @NotNull Clock clock,
                                                  int clusterId)
            throws InterruptedException {
        Map<Integer, Revision> lastRevMap = checkNotNull(rootDoc).getLastRev();
        long externalTime = Utils.getMaxExternalTimestamp(lastRevMap.values(), clusterId);
        long localTime = clock.getTime();
        if (localTime < externalTime) {
            LOG.warn("Detected clock differences. Local time is '{}', " +
                            "while most recent external time is '{}'. " +
                            "Current _lastRev entries: {}",
                    new Date(localTime), new Date(externalTime), lastRevMap.values());
            double delay = ((double) externalTime - localTime) / 1000d;
            String fmt = "Background read will be delayed by %.1f seconds. " +
                    "Please check system time on cluster nodes.";
            String msg = String.format(fmt, delay);
            LOG.warn(msg);
            while (localTime + 60000 < externalTime) {
                clock.waitUntil(localTime + 60000);
                localTime = clock.getTime();
                delay = ((double) externalTime - localTime) / 1000d;
                LOG.warn(String.format(fmt, delay));
            }
            clock.waitUntil(externalTime + 1);
        } else if (localTime == externalTime) {
            // make sure local time is past external time
            // but only log at debug
            LOG.debug("Local and external time are equal. Waiting until local" +
                    "time is more recent than external reported time.");
            clock.waitUntil(externalTime + 1);
        }
    }

    /**
     * Calls {@link Thread#join()} on each of the passed threads and catches
     * any potentially thrown {@link InterruptedException}.
     *
     * @param threads the threads to join.
     */
    public static void joinQuietly(Thread... threads) {
        for (Thread t : threads) {
            try {
                if (t != null) {
                    t.join();
                }
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    /**
     * Returns the version of the module that contains the DocumentNodeStore.
     *
     * @return the module version or "SNAPSHOT" if unknown.
     */
    public static String getModuleVersion() {
        String v = MODULE_VERSION;
        if (v == null) {
            v = OakVersion.getVersion("oak-store-document", Utils.class);
            MODULE_VERSION = v;
        }
        return v;
    }

    /**
     * Check the revision age on the root document for the given cluster node
     * info. The check will fail with a {@link DocumentStoreException} if the
     * {@code _lastRev} timestamp for the cluster node is newer then the current
     * {@code clock} time. The check will not fail if the root document does
     * not exist or does not have a {@code _lastRev} entry for the cluster node.
     *
     * @param store the document store from where to read the root document.
     * @param info the cluster node info with the clusterId.
     * @param clock the clock to get the current time.
     * @throws DocumentStoreException if the check fails.
     */
    public static void checkRevisionAge(DocumentStore store,
                                        ClusterNodeInfo info,
                                        Clock clock)
            throws DocumentStoreException {
        NodeDocument root = store.find(Collection.NODES, getIdFromPath(Path.ROOT));
        if (root == null) {
            return;
        }
        int clusterId = info.getId();
        Revision rev = root.getLastRev().get(clusterId);
        if (rev == null) {
            return;
        }
        long now = clock.getTime();
        if (rev.getTimestamp() > now) {
            String msg = String.format("Cluster id %d has a _lastRev %s (%s) " +
                    "newer than current time %s. Please check system time on " +
                    "cluster nodes.", clusterId, rev.toString(),
                    timestampToString(rev.getTimestamp()), timestampToString(now));
            throw new DocumentStoreException(msg);
        }
    }

    /**
     * Calculates the sum of the given long values. The implementation protects
     * against overflow by returning {@code Long#MAX_VALUE} when the result
     * would actually be bigger than that. Similarly, {@code Long#MIN_VALUE} is
     * returned when the result would actually be smaller than that.
     *
     * @param addends the values.
     * @return the sum of the values.
     */
    public static long sum(long... addends) {
        BigInteger result = BigInteger.ZERO;
        for (long value : addends) {
            result = result.add(BigInteger.valueOf(value));
        }
        result = result.max(BigInteger.valueOf(Long.MIN_VALUE));
        result = result.min(BigInteger.valueOf(Long.MAX_VALUE));
        return result.longValue();
    }
}
