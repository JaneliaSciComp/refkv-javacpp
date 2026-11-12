package org.refkv.javacpp;

import static org.refkv.javacpp.global.refkv.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.refkv.javacpp.global.refkv.RefkvBuffer;
import org.refkv.javacpp.global.refkv.RefkvPhkv;
import org.refkv.javacpp.global.refkv.RefkvStrings;
import org.refkv.vzip.ReferenceStore;

/**
 * A refkv store opened for reading: a standalone phkv store, or a hybrid vzip archive. Values that
 * are references to other files' bytes (a zarrified TIFF's chunks, say) are resolved on read, from
 * {@code file:} and {@code http(s):} sources, relative ones against the store's own file.
 *
 * <p>Thread-safe: reads take turns in the native store. {@link #close()} waits for reads under way.
 */
public final class PhkvStore implements ReferenceStore {
    private final RefkvPhkv handle;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    private PhkvStore(RefkvPhkv handle) {
        this.handle = handle;
    }

    /** Opens the store in the file at {@code path}. */
    public static PhkvStore open(String path) {
        RefkvPhkv h = new RefkvPhkv(null);
        Native.check(refkv_phkv_open(path, h));
        return new PhkvStore(h);
    }

    private RefkvPhkv live() {
        if (closed) {
            throw new IllegalStateException("the store is closed");
        }
        return handle;
    }

    private interface Call<T> {
        T run(RefkvPhkv h);
    }

    private <T> T read(Call<T> call) {
        lock.readLock().lock();
        try {
            return call.run(live());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The number of keys, hidden ones included. */
    public long keyCount() {
        return read(h -> refkv_phkv_key_count(h));
    }

    @Override
    public Kind classify(String key) {
        return read(h -> switch (Native.check(refkv_phkv_classify(h, key))) {
            case REFKV_BYTES -> Kind.BYTES;
            case REFKV_REFERENCE -> Kind.REFERENCE;
            default -> Kind.MISSING;
        });
    }

    /** The value at {@code key}, references resolved; null if there is no such key. */
    @Override
    public byte[] get(String key) {
        return get(key, REFKV_REQUEST_WHOLE, 0, 0);
    }

    /** Bytes {@code [offset, offset + length)} of the value at {@code key}, clipped to its end; null if there is no such key. */
    @Override
    public byte[] get(String key, long offset, long length) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset " + offset + ", length " + length);
        }
        return get(key, REFKV_REQUEST_RANGE, offset, offset + length < 0 ? Long.MAX_VALUE : offset + length);
    }

    private byte[] get(String key, int request, long a, long b) {
        return read(h -> {
            RefkvBuffer out = new RefkvBuffer(null);
            if (Native.check(refkv_phkv_get(h, key, request, a, b, out)) == REFKV_MISSING) {
                return null;
            }
            return Native.take(out);
        });
    }

    /** The size of the value at {@code key} as {@link #get(String)} gives it, without reading it; -1 if there is no such key. */
    @Override
    public long size(String key) {
        return read(h -> {
            LongPointer size = new LongPointer(1);
            try {
                return Native.check(refkv_phkv_size(h, key, size)) == REFKV_MISSING ? -1L : size.get();
            } finally {
                size.close();
            }
        });
    }

    /** The keys that are not hidden and start with {@code prefix} ("" for all), in UTF-8 order. */
    @Override
    public List<String> list(String prefix) {
        return read(h -> {
            RefkvStrings out = new RefkvStrings(null);
            Native.check(refkv_phkv_list(h, prefix, out));
            try {
                long n = refkv_strings_len(out);
                List<String> keys = new ArrayList<>((int) Math.min(n, 1 << 20));
                for (long i = 0; i < n; i++) {
                    BytePointer s = refkv_strings_get(out, i);
                    keys.add(s.getString());
                }
                return keys;
            } finally {
                refkv_strings_free(out);
            }
        });
    }

    /** How a key's value is stored, as a JSON object; null if there is no such key. */
    public String entryInfo(String key) {
        return read(h -> {
            try (BytePointer k = new BytePointer(key); PointerPointer<BytePointer> out = new PointerPointer<>(1)) {
                return Native.check(refkv_phkv_entry_info(h, k, out)) == REFKV_MISSING ? null : Native.takeString(out);
            }
        });
    }

    /** UNSAFE: allows reading http(s) sources at loopback, private and link-local addresses. */
    public void allowPrivateHosts(boolean on) {
        read(h -> Native.check(refkv_phkv_set_allow_private_hosts(h, on)));
    }

    /**
     * UNSAFE, Windows only: allows reading a {@code file:} source that names a UNC server ({@code file://server/share/x}).
     * Reading one connects to that server, which may send it the user's credentials. On other systems such a URL has
     * no mapping and is refused whatever this is set to.
     */
    public void allowFileHosts(boolean on) {
        read(h -> Native.check(refkv_phkv_set_allow_file_hosts(h, on)));
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                refkv_phkv_close(handle);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
