package org.refkv.vzip;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.refkv.javacpp.PhkvStore;

/**
 * References whose ranges come from {@code key} sources, through {@code PhkvStore}: refkv never writes such sources when it
 * zarrifies, so the store is written by capi/tests/key_sources.rs and committed. The expected bytes are computed here
 * from the same definitions, not read from the store.
 */
class PhkvKeySourcesTest {
    static final String STORE = "src/test/resources/keysources.phkv";
    static final byte[] SMALL = "small-value".getBytes(StandardCharsets.US_ASCII);

    static byte[] big() {
        byte[] b = new byte[10_000];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i * 7 % 251);
        }
        return b;
    }

    static byte[] zstd() {
        byte[] b = new byte[8_000];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i / 40);
        }
        return b;
    }

    static byte[] cat(byte[]... parts) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] r(byte[] b, int from, int to) {
        return Arrays.copyOfRange(b, from, to);
    }

    static byte[] expected(String key) {
        byte[] big = big(), z = zstd();
        return switch (key) {
            case "r/small" -> ascii("mall-");
            case "r/big" -> cat(r(big, 100, 140), r(big, 9990, 10_000));
            case "r/zstd" -> r(z, 10, 30);
            case "r/crc" -> r(big, 200, 300);
            case "r/mixed" -> cat(ascii("small"), ascii("|"), r(big, 5000, 5010), r(z, 0, 4), ascii("2345"), ascii("value"));
            default -> throw new IllegalArgumentException(key);
        };
    }

    static final List<String> REFERENCES = List.of("r/small", "r/big", "r/zstd", "r/crc", "r/mixed");

    static ReferenceException fails(ErrorClass c, org.junit.jupiter.api.function.Executable e) {
        return VzipArchiveTest.fails(c, e);
    }

    @Test
    void resolvesEveryKindOfKeySourceWithoutAnyPolicyForFiles() {
        // the sources are other keys of the store and bytes in its table: no file or network is read, so the default policy is enough
        try (PhkvStore s = PhkvStore.open(STORE)) {
            assertEquals(List.of("k/big", "k/small", "k/zstd", "r/big", "r/crc", "r/mixed", "r/small", "r/zstd"), s.list(""));
            for (String k : REFERENCES) {
                assertEquals(ReferenceStore.Kind.REFERENCE, s.classify(k));
                assertArrayEquals(expected(k), s.get(k), k);
                assertEquals(expected(k).length, s.size(k), k);
                assertArrayEquals(expected(k), s.unsafeNativeGet(k), k + ": the native resolver agrees");
            }
            assertArrayEquals(SMALL, s.get("k/small"));
            assertArrayEquals(big(), s.get("k/big"));
            assertArrayEquals(zstd(), s.get("k/zstd"));
        }
    }

    @Test
    void everyWindowOfAReferenceThatMixesSources() {
        try (PhkvStore s = PhkvStore.open(STORE)) {
            byte[] mixed = expected("r/mixed");
            for (int from = 0; from <= mixed.length; from++) {
                for (int to = from; to <= mixed.length; to++) {
                    assertArrayEquals(r(mixed, from, to), s.get("r/mixed", from, to - from), from + ".." + to);
                }
            }
            assertArrayEquals(r(mixed, 20, mixed.length), s.get("r/mixed", Request.offset(20)));
            assertArrayEquals(r(mixed, mixed.length - 6, mixed.length), s.get("r/mixed", Request.suffix(6)));
            // a range with a checksum is read whole and checked, and the window is cut from it
            assertArrayEquals(r(big(), 210, 215), s.get("r/crc", 10, 5));
        }
    }

    @Test
    void holdingAKeySourceWholeCountsTowardTheBytesPerGet() {
        // k/zstd is compressed, so a reader holds it whole (8000 bytes) to read 20 of them; k/big is read by window,
        // and k/small is inline, held whole at 11 bytes
        Policy tight = Policy.builder().maxBytesPerGet(5000).build();
        try (PhkvStore s = PhkvStore.open(STORE, tight)) {
            fails(ErrorClass.REQUEST, () -> s.get("r/zstd"));
            fails(ErrorClass.REQUEST, () -> s.get("r/mixed"));
            assertArrayEquals(expected("r/big"), s.get("r/big"), "a window of an uncompressed value is read by itself");
            assertArrayEquals(expected("r/small"), s.get("r/small"));
            assertArrayEquals(expected("r/crc"), s.get("r/crc"));
        }
        try (PhkvStore s = PhkvStore.open(STORE, Policy.builder().maxBytesPerGet(9000).build())) {
            assertArrayEquals(expected("r/zstd"), s.get("r/zstd"));
            assertArrayEquals(expected("r/mixed"), s.get("r/mixed"));
        }
    }

    @Test
    void readsFromSeveralThreads() throws Exception {
        try (PhkvStore s = PhkvStore.open(STORE)) {
            var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 64; t++) {
                    String k = REFERENCES.get(t % REFERENCES.size());
                    futures.add(pool.submit(() -> assertArrayEquals(expected(k), s.get(k), k)));
                }
                for (var f : futures) {
                    f.get();
                }
            } finally {
                pool.shutdown();
            }
        }
    }
}
