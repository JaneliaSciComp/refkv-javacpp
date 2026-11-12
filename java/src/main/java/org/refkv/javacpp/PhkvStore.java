package org.refkv.javacpp;

import static org.refkv.javacpp.global.refkv.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.refkv.javacpp.global.refkv.RefkvBuffer;
import org.refkv.javacpp.global.refkv.RefkvPhkv;
import org.refkv.javacpp.global.refkv.RefkvStrings;
import org.refkv.vzip.ErrorClass;
import org.refkv.vzip.Policy;
import org.refkv.vzip.ReferenceException;
import org.refkv.vzip.ReferenceResolver;
import org.refkv.vzip.ReferenceStore;
import org.refkv.vzip.Request;

/**
 * A refkv store opened for reading with refkv's Rust phkv reader: a standalone phkv store, or a hybrid vzip archive.
 *
 * <p>The native reader reads the store's own records; the references in them (a zarrified TIFF's chunks, say) are
 * resolved here, in Java, by the same {@link ReferenceResolver} that {@link org.refkv.vzip.VzipArchive} uses, under a
 * {@link Policy}: its scheme and prefix rules, address checks, pins, redirects and limits. {@link Policy#defaults()}
 * refuses {@code file:} sources, so a store from an untrusted place cannot make a reader read local files; allow them
 * with {@link Policy#filesBelow}.
 *
 * <p>Thread-safe: reads take turns in the native store, and references are resolved without holding it.
 * {@link #close()} waits for native reads under way.
 */
public final class PhkvStore implements ReferenceStore {
    private final RefkvPhkv handle;
    private final ReferenceResolver resolver;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    private PhkvStore(RefkvPhkv handle, byte[] sourceTable, String baseUri, Policy policy) {
        this.handle = handle;
        this.resolver = new ReferenceResolver(sourceTable, baseUri, policy, this::openKey);
    }

    /** Opens the store in the file at {@code path}, with {@link Policy#defaults()}. */
    public static PhkvStore open(String path) {
        return open(path, Policy.defaults());
    }

    /**
     * Opens the store in the file at {@code path}. Its sources resolve against the file's own URI, and the policy
     * decides which of them are read.
     *
     * @throws ReferenceException an archive error if the source table is malformed or over the policy's limit
     */
    public static PhkvStore open(String path, Policy policy) {
        RefkvPhkv h = new RefkvPhkv(null);
        Native.check(refkv_phkv_open(path, h));
        try {
            RefkvBuffer table = new RefkvBuffer(null);
            Native.check(refkv_phkv_sources(h, table));
            return new PhkvStore(h, Native.take(table), ReferenceResolver.baseUri(Path.of(path)), policy);
        } catch (RuntimeException e) {
            refkv_phkv_close(h);
            throw e;
        }
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

    private <T> T locked(Call<T> call) {
        lock.readLock().lock();
        try {
            return call.run(live());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The number of keys, hidden ones included. */
    public long keyCount() {
        return locked(h -> refkv_phkv_key_count(h));
    }

    @Override
    public Kind classify(String key) {
        return locked(h -> switch (Native.check(refkv_phkv_classify(h, key))) {
            case REFKV_BYTES -> Kind.BYTES;
            case REFKV_REFERENCE -> Kind.REFERENCE;
            default -> Kind.MISSING;
        });
    }

    @Override
    public byte[] get(String key) {
        return get(key, Request.whole());
    }

    @Override
    public byte[] get(String key, long offset, long length) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset " + offset + ", length " + length);
        }
        long end = offset + length;
        return get(key, Request.range(offset, end < 0 ? Long.MAX_VALUE : end));
    }

    private static int mode(Request r) {
        return switch (r.type()) {
            case WHOLE -> REFKV_REQUEST_WHOLE;
            case RANGE -> REFKV_REQUEST_RANGE;
            case OFFSET -> REFKV_REQUEST_OFFSET;
            case SUFFIX -> REFKV_REQUEST_SUFFIX;
        };
    }

    private static int kindOf(int nativeKind) {
        return nativeKind == 1 ? ReferenceResolver.RANGE : ReferenceResolver.CONCAT;
    }

    /**
     * The value at {@code key} for any of the four requests of spec/refkv.md section 6.2; null if there is no such key. A bytes
     * value is read by the native reader. A reference's payload is too, and its ranges are resolved in Java.
     *
     * @throws ReferenceException a payload, resolution or request error from resolving a reference
     */
    public byte[] get(String key, Request request) {
        if (request.type() == Request.Type.RANGE && Long.compareUnsigned(request.a(), request.b()) > 0) {
            throw new ReferenceException(ErrorClass.REQUEST, "range start " + request.a() + " > end " + request.b());
        }
        RefkvBuffer out = new RefkvBuffer(null);
        int status = locked(h -> Native.check(refkv_phkv_fetch(h, key, mode(request), request.a(), request.b(), out)));
        if (status == REFKV_MISSING) {
            return null;
        }
        if (status == REFKV_BYTES) {
            return Native.take(out);
        }
        int kind = kindOf(refkv_buffer_kind(out) & 0xff); // before the buffer is freed
        return resolver.resolve(kind, Native.take(out), request);
    }

    @Override
    public long size(String key) {
        return switch (classify(key)) {
            case MISSING -> -1L;
            case BYTES -> locked(h -> {
                LongPointer size = new LongPointer(1);
                try {
                    return Native.check(refkv_phkv_size(h, key, size)) == REFKV_MISSING ? -1L : size.get();
                } finally {
                    size.close();
                }
            });
            case REFERENCE -> {
                RefkvBuffer out = new RefkvBuffer(null);
                int status = locked(h -> Native.check(refkv_phkv_fetch(h, key, REFKV_REQUEST_WHOLE, 0, 0, out)));
                if (status != REFKV_REFERENCE) {
                    yield -1L;
                }
                int kind = kindOf(refkv_buffer_kind(out) & 0xff);
                yield resolver.size(kind, Native.take(out));
            }
        };
    }

    /** The keys that are not hidden and start with {@code prefix} ("" for all), in UTF-8 order. */
    @Override
    public List<String> list(String prefix) {
        return locked(h -> {
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
        return locked(h -> {
            try (BytePointer k = new BytePointer(key); PointerPointer<BytePointer> out = new PointerPointer<>(1)) {
                return Native.check(refkv_phkv_entry_info(h, k, out)) == REFKV_MISSING ? null : Native.takeString(out);
            }
        });
    }

    /**
     * Resolves a reference in the native reader instead, which applies <b>no</b> reader policy: any {@code file:} source
     * the store names is read, and over http(s) only a size pin is checked, and only if the response gives a total.
     * It exists to cross-check {@link #get} against an independent implementation; do not use it on a store you do not trust.
     */
    public byte[] unsafeNativeGet(String key) {
        return locked(h -> {
            RefkvBuffer out = new RefkvBuffer(null);
            if (Native.check(refkv_phkv_get(h, key, REFKV_REQUEST_WHOLE, 0, 0, out)) == REFKV_MISSING) {
                return null;
            }
            return Native.take(out);
        });
    }

    // ---- key sources: a reference's range of another key of this store ----

    private ReferenceResolver.KeyValue openKey(String key) {
        long[] found = locked(h -> {
            LongPointer size = new LongPointer(1), held = new LongPointer(1);
            try {
                int status = Native.check(refkv_phkv_key_found(h, key, size, held));
                return status == REFKV_MISSING ? null : new long[] {size.get(), held.get()};
            } catch (RefkvException e) {
                throw new ReferenceException(ErrorClass.RESOLUTION, "the key source " + key + ": " + e.getMessage(), e);
            } finally {
                size.close();
                held.close();
            }
        });
        if (found == null) {
            throw new ReferenceException(ErrorClass.RESOLUTION, "the key source " + key + " is missing or is a reference");
        }
        long size = found[0], held = found[1]; // held is UINT64_MAX, that is -1, when a window is read by itself
        return new ReferenceResolver.KeyValue() {
            private byte[] whole;

            public long size() {
                return size;
            }

            public long held() {
                return held;
            }

            public synchronized byte[] read(long from, long to) {
                if (held >= 0) {
                    if (whole == null) {
                        whole = keyBytes(key, 0, size);
                    }
                    return Arrays.copyOfRange(whole, (int) from, (int) to);
                }
                return keyBytes(key, from, to);
            }
        };
    }

    private byte[] keyBytes(String key, long start, long end) {
        return locked(h -> {
            RefkvBuffer out = new RefkvBuffer(null);
            try {
                Native.check(refkv_phkv_key_bytes(h, key, start, end, out));
            } catch (RefkvException e) {
                throw new ReferenceException(ErrorClass.RESOLUTION, "the key source " + key + ": " + e.getMessage(), e);
            }
            return Native.take(out);
        });
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
