package org.refkv.javacpp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5Exception.N5IOException;
import org.janelia.saalfeldlab.n5.N5Exception.N5NoSuchKeyException;
import org.janelia.saalfeldlab.n5.readdata.LazyRead;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.readdata.VolatileReadData;
import org.refkv.vzip.ReferenceException;
import org.refkv.vzip.ReferenceStore;

/**
 * A read-only N5 {@link KeyValueAccess} over a refkv store (a {@link org.refkv.vzip.VzipArchive} or a {@link PhkvStore}), so that N5 readers (N5, Zarr, OME-Zarr)
 * read foreign file formats that refkv zarrifies, with chunks read from the original files on demand.
 *
 * <pre>{@code
 * Zarrify.convert("volume.mrc", "volume.phkv", Zarrify.options().format(Zarrify.Container.PHKV));
 * PhkvStore store = PhkvStore.open("volume.phkv");
 * N5Reader reader = new ZarrKeyValueReader(new RefkvKeyValueAccess(store), "", new GsonBuilder(), false);
 * }</pre>
 *
 * <p>Keys are the store's, '/'-separated with no leading slash; a "directory" is a key prefix. Writing,
 * creating and deleting throw {@link N5IOException}. The access does not own the store: closing the store
 * is the caller's job, and reads after it fail.
 */
public class RefkvKeyValueAccess implements KeyValueAccess {
    private final ReferenceStore store;

    public RefkvKeyValueAccess(ReferenceStore store) {
        this.store = store;
    }

    private static final String SCHEME = "refkv:";

    /**
     * Strips the leading slashes, empty and "." components and resolves ".." ("" is the root). N5
     * composes keys through {@link #uri}, so a path may also be a {@code refkv:/...} URI, whose
     * percent-escapes are decoded.
     */
    @Override
    public String normalize(String path) {
        if (path.startsWith(SCHEME)) {
            String decoded = null;
            try {
                decoded = new URI(path).getPath();
            } catch (URISyntaxException e) {
                // not a URI after all: a key that starts with "refkv:"
            }
            path = decoded != null ? decoded : path.substring(SCHEME.length());
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(p);
            }
        }
        return String.join("/", parts);
    }

    private static String prefixOf(String key) {
        return key.isEmpty() ? "" : key + "/";
    }

    @Override
    public URI uri(String normalPath) throws URISyntaxException {
        // names the key in the store, not where the store is: a browser does not open it
        return new URI("refkv", null, "/" + normalize(normalPath), null);
    }

    @Override
    public boolean isFile(String normalPath) {
        String key = normalize(normalPath);
        return !key.isEmpty() && store.classify(key) != ReferenceStore.Kind.MISSING;
    }

    @Override
    public boolean isDirectory(String normalPath) {
        String key = normalize(normalPath);
        // the root is always a directory, however few keys the store holds
        return key.isEmpty() || !store.list(prefixOf(key)).isEmpty();
    }

    @Override
    public boolean exists(String normalPath) {
        return isFile(normalPath) || isDirectory(normalPath);
    }

    @Override
    public long size(String normalPath) throws N5NoSuchKeyException {
        String key = normalize(normalPath);
        long n = key.isEmpty() ? -1 : store.size(key);
        if (n < 0) {
            throw new N5NoSuchKeyException("No such key: " + key);
        }
        return n;
    }

    @Override
    public VolatileReadData createReadData(String normalPath) throws N5IOException {
        String key = normalize(normalPath);
        return VolatileReadData.from(new KeyLazyRead(key));
    }

    private final class KeyLazyRead implements LazyRead {
        private final String key;
        private long size = -1;

        KeyLazyRead(String key) {
            this.key = key;
        }

        @Override
        public long size() throws N5IOException {
            if (size < 0) {
                size = RefkvKeyValueAccess.this.size(key);
            }
            return size;
        }

        @Override
        public ReadData materialize(long offset, long length) throws N5IOException {
            byte[] bytes;
            try {
                bytes = key.isEmpty() ? null : length < 0 ? store.get(key) : store.get(key, offset, length);
            } catch (RefkvException | ReferenceException e) {
                throw new N5IOException("Reading " + key + ": " + e.getMessage(), e);
            }
            if (bytes == null) {
                throw new N5NoSuchKeyException("No such key: " + key);
            }
            if (length < 0 && offset > 0) {
                return ReadData.from(bytes, (int) Math.min(offset, bytes.length), bytes.length - (int) Math.min(offset, bytes.length));
            }
            return ReadData.from(bytes);
        }

        @Override
        public void close() {}
    }

    @Override
    public String[] list(String normalPath) throws N5IOException {
        return children(normalize(normalPath), false);
    }

    @Override
    public String[] listDirectories(String normalPath) throws N5IOException {
        return children(normalize(normalPath), true);
    }

    /** The names directly below {@code key}: files and directories, or only directories. */
    private String[] children(String key, boolean directoriesOnly) {
        String prefix = prefixOf(key);
        Set<String> names = new LinkedHashSet<>();
        try {
            for (String k : store.list(prefix)) {
                String rest = k.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash >= 0) {
                    names.add(rest.substring(0, slash));
                } else if (!directoriesOnly && !rest.isEmpty()) {
                    names.add(rest);
                }
            }
        } catch (RefkvException | ReferenceException e) {
            throw new N5IOException("Listing " + key + ": " + e.getMessage(), e);
        }
        return names.toArray(new String[0]);
    }

    @Override
    public void write(String normalPath, ReadData data) throws N5IOException {
        throw readOnly();
    }

    @Override
    public void createDirectories(String normalPath) throws N5IOException {
        throw readOnly();
    }

    @Override
    public void delete(String normalPath) throws N5IOException {
        throw readOnly();
    }

    private static N5IOException readOnly() {
        return new N5IOException("A refkv store is read-only");
    }
}
