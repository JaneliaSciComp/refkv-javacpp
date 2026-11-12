package org.refkv.vzip;

import java.util.List;

/**
 * A refkv store opened for reading (spec/refkv.md section 6.2): keys whose values are bytes, or
 * references to byte ranges of other objects, which {@code get} resolves.
 *
 * <p>Implementations are thread-safe. Failures are {@link ReferenceException}s (or, for the native
 * phkv reader, its own runtime exception); a missing key is not a failure.
 */
public interface ReferenceStore extends AutoCloseable {
    /** How a key is marked. */
    enum Kind { MISSING, BYTES, REFERENCE }

    Kind classify(String key);

    /** The value at {@code key}, references resolved; null if there is no such key. */
    byte[] get(String key);

    /**
     * Bytes {@code [offset, offset + length)} of the value at {@code key}, clipped to its end; null if there
     * is no such key.
     */
    byte[] get(String key, long offset, long length);

    /** The size of the value {@link #get(String)} gives, without reading it; -1 if there is no such key. */
    long size(String key);

    /** The keys that start with {@code prefix} ("" for all), in UTF-8 order. Hidden keys are not listed. */
    List<String> list(String prefix);

    @Override
    void close();
}
