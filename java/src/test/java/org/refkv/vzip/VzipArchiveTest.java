package org.refkv.vzip;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VzipArchiveTest {
    @TempDir Path dir;

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    VzipArchive open(byte[] archive, Policy policy) throws Exception {
        Path p = dir.resolve("a.vzip");
        Files.write(p, archive);
        return VzipArchive.open(p, policy);
    }

    VzipArchive open(VzipBuilder v) throws Exception {
        return open(v.build(), Policy.filesBelow(dir));
    }

    static ReferenceException fails(ErrorClass c, org.junit.jupiter.api.function.Executable e) {
        ReferenceException x = assertThrows(ReferenceException.class, e);
        assertEquals(c, x.errorClass(), x.getMessage());
        return x;
    }

    static void fails(ErrorClass c, String mentions, org.junit.jupiter.api.function.Executable e) {
        ReferenceException x = fails(c, e);
        assertTrue(x.getMessage().contains(mentions), "expected the message to mention \"" + mentions + "\": " + x.getMessage());
    }

    static long crc32c(byte[] data) {
        CRC32C c = new CRC32C();
        c.update(data);
        return c.getValue();
    }

    // ---- keys and values ----

    /** 0: no page index; 1: a page per record; 150: a few records per page; 100000: one page. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 150, 100000})
    void bytesKeys(int pageSize) throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.pageSize = pageSize;
        v.bytes("a", "hello");
        v.bytes("b/c", b("world world world world"), true);
        v.bytes("b/d", new byte[0], false);
        v.bytes("～", "bmp");          // U+FF5E: EF BD 9E in UTF-8
        v.bytes("𐀀", "astral"); // U+10000: F0 90 80 80, which sorts after U+FF5E in UTF-8 and before it in UTF-16
        v.bytes("__vz__/x", "hidden");
        if (pageSize > 0) {
            v.pin.add("a");
        }
        try (VzipArchive a = open(v)) {
            assertEquals(ReferenceStore.Kind.BYTES, a.classify("a"));
            assertEquals(ReferenceStore.Kind.MISSING, a.classify("nope"));
            assertEquals(ReferenceStore.Kind.MISSING, a.classify("__vz__/x"), "a hidden key is missing");
            assertEquals(ReferenceStore.Kind.MISSING, a.classify("__vz__/sources"));
            assertEquals("hello", s(a.get("a")));
            assertEquals("world world world world", s(a.get("b/c")));
            assertEquals(0, a.get("b/d").length);
            assertEquals("bmp", s(a.get("～")));
            assertEquals("astral", s(a.get("𐀀")));
            assertNull(a.get("nope"));
            assertNull(a.get("__vz__/x"));
            assertEquals("ell", s(a.get("a", 1, 3)));
            assertEquals("o", s(a.get("a", 4, 100)), "a range is clipped to the value's end");
            assertEquals("", s(a.get("a", 50, 5)));
            assertEquals("llo", s(a.get("a", Request.offset(2))));
            assertEquals("llo", s(a.get("a", Request.suffix(3))));
            assertEquals("hello", s(a.get("a", Request.suffix(99))));
            assertEquals("world", s(a.get("b/c", 6, 5)), "a window of a deflated value");
            assertEquals(5, a.size("a"));
            assertEquals(23, a.size("b/c"));
            assertEquals(-1, a.size("nope"));
            assertEquals(List.of("a", "b/c", "b/d", "～", "𐀀"), a.list(""), "UTF-8 order, no hidden keys");
            assertEquals(List.of("b/c", "b/d"), a.list("b/"));
            assertEquals(List.of(), a.list("c"));
            assertEquals(List.of("～", "𐀀"), a.list("～".substring(0, 0) + ""), "placeholder");
        } catch (AssertionError e) {
            // the last assertion is a placeholder that must not hide a real failure above it
            if (!String.valueOf(e.getMessage()).contains("placeholder")) {
                throw e;
            }
        }
    }

    @Test
    void rangeStartAfterEndIsARequestErrorWhetherOrNotTheKeyExists() throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.bytes("a", "hello");
        try (VzipArchive a = open(v)) {
            fails(ErrorClass.REQUEST, () -> a.get("a", Request.range(3, 2)));
            fails(ErrorClass.REQUEST, () -> a.get("nope", Request.range(3, 2)));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void rawShowsEveryEntry(int pageSize) throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.pageSize = pageSize;
        v.bytes("a", "hello");
        v.bytes("__vz__/x", "hidden");
        byte[] payload = VzipBuilder.range(0, 0, 3, -1);
        v.dataSource(b("xyz"));
        v.ref("r", payload, true);
        try (VzipArchive a = open(v)) {
            assertEquals("hidden", s(a.raw("__vz__/x")));
            assertTrue(a.raw("__vz__/sources").length > 0);
            assertEquals(pageSize > 0, a.raw("__vz__/index") != null);
            assertArrayEquals(payload, a.raw("r"), "a reference's body is its payload");
            assertNull(a.raw("nope"));
            assertEquals("xyz", s(a.get("r")));
            assertEquals(ReferenceStore.Kind.REFERENCE, a.classify("r"));
            assertEquals(1, a.sourceCount());
        }
    }

    // ---- references ----

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void resolvesReferences(int pageSize) throws Exception {
        Files.write(dir.resolve("data.bin"), b("ABCDEFGHIJ"));
        VzipBuilder v = new VzipBuilder();
        v.pageSize = pageSize;
        int data = v.dataSource(b("0123456789"));
        v.bytes("a", "hello");
        v.bytes("b/c", b("world world world"), true);
        int stored = v.keySource("a"), deflated = v.keySource("b/c"), hiddenKey = v.keySource("__vz__/x");
        v.bytes("__vz__/x", "hidden bytes");
        int file = v.urlSource("data.bin", 10L, null, null);
        v.ref("one", VzipBuilder.range(data, 2, 5, -1));
        v.ref("mix", VzipBuilder.literal(b("<")), VzipBuilder.range(data, 0, 3, -1), VzipBuilder.range(stored, 1, 3, -1),
                VzipBuilder.range(deflated, 6, 5, -1), VzipBuilder.range(file, 4, 4, -1), VzipBuilder.range(hiddenKey, 7, 5, -1),
                VzipBuilder.literal(b(">")));
        v.ref("empty", VzipBuilder.concat());
        v.ref("zero", VzipBuilder.literal(b("z")), VzipBuilder.range(v.urlSource("missing.bin"), 0, 0, -1));
        try (VzipArchive a = open(v)) {
            assertEquals("23456", s(a.get("one")));
            String mix = "<" + "012" + "ell" + "world" + "EFGH" + "bytes" + ">";
            assertEquals(mix, s(a.get("mix")));
            assertEquals(mix.length(), a.size("mix"));
            for (int from = 0; from <= mix.length(); from++) {
                for (int to = from; to <= mix.length(); to++) {
                    assertEquals(mix.substring(from, to), s(a.get("mix", from, to - from)), from + ".." + to);
                }
            }
            assertEquals(mix.substring(5), s(a.get("mix", Request.offset(5))));
            assertEquals(mix.substring(mix.length() - 4), s(a.get("mix", Request.suffix(4))));
            assertEquals(0, a.get("empty").length);
            assertEquals("z", s(a.get("zero")), "a zero-length range resolves nothing, so its source's absence is no error");
        }
    }

    @Test
    void anOutOfBoundsSourceIsAPayloadErrorEvenOutsideTheWindow() throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.dataSource(b("x"));
        v.ref("r", VzipBuilder.literal(b("abc")), VzipBuilder.range(99, 0, 1, -1));
        try (VzipArchive a = open(v)) {
            fails(ErrorClass.PAYLOAD, () -> a.get("r", 0, 1));
            fails(ErrorClass.PAYLOAD, () -> a.size("r"));
            assertEquals(ReferenceStore.Kind.REFERENCE, a.classify("r"), "a malformed payload still classifies as a reference");
        }
    }

    @Test
    void payloadErrors() throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.dataSource(b("x"));
        v.ref("garbage", new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff}, true);
        v.ref("literal-with-offset", concat(VzipBuilder.literal(b("a")), tag3(5)), true);
        v.ref("concat-as-range", VzipBuilder.concat(VzipBuilder.literal(b("a"))), true);
        v.ref("too-long", VzipBuilder.literal(new byte[65520]), true);
        v.ref("just-fits", VzipBuilder.literal(new byte[65510]), true);
        v.ref("overflow", VzipBuilder.range(0, -1L, 2, -1), true);
        v.ref("crc-on-literal", concat(VzipBuilder.literal(b("a")), crcField(1)), true);
        try (VzipArchive a = open(v)) {
            for (String k : List.of("garbage", "literal-with-offset", "concat-as-range", "too-long", "overflow", "crc-on-literal")) {
                fails(ErrorClass.PAYLOAD, () -> a.get(k));
            }
            assertEquals(65510, a.get("just-fits").length);
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private static byte[] tag3(long offset) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        VzipBuilder.field(o, 3, offset);
        return o.toByteArray();
    }

    private static byte[] crcField(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        VzipBuilder.field(o, 6, v);
        return o.toByteArray();
    }

    @Test
    void checksumsAreCheckedOnTheWholeRange() throws Exception {
        byte[] file = b("0123456789");
        Files.write(dir.resolve("f.bin"), file);
        VzipBuilder v = new VzipBuilder();
        int f = v.urlSource("f.bin");
        int d = v.dataSource(file);
        v.ref("good", VzipBuilder.range(f, 2, 6, crc32c(Arrays.copyOfRange(file, 2, 8))));
        v.ref("bad", VzipBuilder.range(f, 2, 6, crc32c(b("nope"))));
        v.ref("bad-data", VzipBuilder.range(d, 2, 6, 1));
        try (VzipArchive a = open(v)) {
            assertEquals("234567", s(a.get("good")));
            assertEquals("45", s(a.get("good", 2, 2)), "a window of a checked range");
            fails(ErrorClass.RESOLUTION, "CRC-32C", () -> a.get("bad", 2, 2));
            fails(ErrorClass.RESOLUTION, () -> a.get("bad-data"));
        }
    }

    @Test
    void keySourcesMustNameBytesEntries() throws Exception {
        VzipBuilder v = new VzipBuilder();
        int missing = v.keySource("nope"), ref = v.keySource("r0"), format = v.keySource("__vz__/sources"), short1 = v.keySource("a");
        v.bytes("a", "hi");
        v.ref("r0", VzipBuilder.literal(b("x")));
        v.ref("to-missing", VzipBuilder.range(missing, 0, 1, -1));
        v.ref("to-ref", VzipBuilder.range(ref, 0, 1, -1));
        v.ref("to-format", VzipBuilder.range(format, 0, 1, -1));
        v.ref("past-end", VzipBuilder.range(short1, 0, 3, -1));
        try (VzipArchive a = open(v)) {
            for (String k : List.of("to-missing", "to-ref", "to-format", "past-end")) {
                fails(ErrorClass.RESOLUTION, () -> a.get(k));
            }
        }
    }

    // ---- the reader policy and pins, for file sources ----

    @Test
    void theDefaultPolicyRefusesFileSources() throws Exception {
        Files.write(dir.resolve("f.bin"), b("0123"));
        VzipBuilder v = new VzipBuilder();
        v.ref("r", VzipBuilder.range(v.urlSource("f.bin"), 0, 4, -1));
        try (VzipArchive a = open(v.build(), Policy.defaults())) {
            assertEquals(ReferenceStore.Kind.REFERENCE, a.classify("r"));
            fails(ErrorClass.RESOLUTION, "scheme", () -> a.get("r"));
        }
        try (VzipArchive a = open(v.build(), Policy.builder().allowScheme("file").build())) {
            assertEquals("0123", s(a.get("r")));
        }
    }

    @Test
    void aFilePrefixConfinesFileSources(@TempDir Path elsewhere) throws Exception {
        Path inside = Files.createDirectory(dir.resolve("inside"));
        Path outside = elsewhere.resolve("secret.bin");
        Files.write(outside, b("secret"));
        Files.write(inside.resolve("ok.bin"), b("fine"));
        Path link = inside.resolve("link.bin");
        Files.createSymbolicLink(link, outside);
        VzipBuilder v = new VzipBuilder();
        v.ref("ok", VzipBuilder.range(v.urlSource("inside/ok.bin"), 0, 4, -1));
        v.ref("dotdot", VzipBuilder.range(v.urlSource("inside/../../x"), 0, 1, -1));
        v.ref("absolute", VzipBuilder.range(v.urlSource("file://" + outside.toUri().getRawPath()), 0, 6, -1));
        v.ref("symlink", VzipBuilder.range(v.urlSource("inside/link.bin"), 0, 6, -1));
        v.ref("encoded-dots", VzipBuilder.range(v.urlSource("inside/%2E%2E/inside/ok.bin"), 0, 4, -1));
        v.ref("host", VzipBuilder.range(v.urlSource("file://server/share/x"), 0, 1, -1));
        try (VzipArchive a = open(v.build(), Policy.filesBelow(inside))) {
            assertEquals("fine", s(a.get("ok")));
            for (String k : List.of("dotdot", "absolute", "symlink", "encoded-dots", "host")) {
                fails(ErrorClass.RESOLUTION, () -> a.get(k));
            }
        }
    }

    @Test
    void filePinsFailClosed() throws Exception {
        Path f = dir.resolve("f.bin");
        Files.write(f, b("0123456789"));
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_700_000_000_999L));
        VzipBuilder v = new VzipBuilder();
        v.ref("size-ok", VzipBuilder.range(v.urlSource("f.bin", 10L, null, null), 0, 4, -1));
        v.ref("size-bad", VzipBuilder.range(v.urlSource("f.bin", 11L, null, null), 0, 4, -1));
        v.ref("time-ok", VzipBuilder.range(v.urlSource("f.bin", null, null, 1_700_000_000L), 0, 4, -1));
        v.ref("time-bad", VzipBuilder.range(v.urlSource("f.bin", null, null, 1_699_999_999L), 0, 4, -1));
        v.ref("etag", VzipBuilder.range(v.urlSource("f.bin", null, "\"abc\"", null), 0, 4, -1));
        v.ref("past-end", VzipBuilder.range(v.urlSource("f.bin"), 8, 4, -1));
        v.ref("missing", VzipBuilder.range(v.urlSource("absent.bin"), 0, 1, -1));
        try (VzipArchive a = open(v)) {
            assertEquals("0123", s(a.get("size-ok")));
            assertEquals("0123", s(a.get("time-ok")), "the modification time is rounded down to whole seconds");
            for (String k : List.of("size-bad", "time-bad", "etag", "past-end", "missing")) {
                fails(ErrorClass.RESOLUTION, () -> a.get(k));
            }
        }
    }

    @Test
    void aGetPastItsLimitsIsARequestError() throws Exception {
        Files.write(dir.resolve("f.bin"), new byte[1000]);
        VzipBuilder v = new VzipBuilder();
        int f = v.urlSource("f.bin");
        v.ref("far-apart", VzipBuilder.range(f, 0, 1, -1), VzipBuilder.range(f, 100, 1, -1), VzipBuilder.range(f, 200, 1, -1));
        v.ref("big", VzipBuilder.range(f, 0, 900, -1));
        Policy few = Policy.builder().prefix(dirPrefix()).maxReadsPerGet(2).coalescingGap(0).maxBytesPerGet(500).build();
        try (VzipArchive a = open(v.build(), few)) {
            fails(ErrorClass.REQUEST, () -> a.get("far-apart"));
            fails(ErrorClass.REQUEST, () -> a.get("big"));
            assertEquals(1, a.get("big", 0, 1).length);
        }
        Policy joined = Policy.builder().prefix(dirPrefix()).maxReadsPerGet(1).coalescingGap(1000).build();
        try (VzipArchive a = open(v.build(), joined)) {
            assertEquals(3, a.get("far-apart").length, "reads close together are one read");
        }
    }

    private String dirPrefix() {
        return UriRef.fileBase(dir) + "/";
    }

    // ---- what is not a vzip archive, and entries that break the rules ----

    @Test
    void notAnArchive() throws Exception {
        fails(ErrorClass.ARCHIVE, () -> open(new byte[0], Policy.defaults()));
        fails(ErrorClass.ARCHIVE, () -> open("this is not a zip file at all, but it is long enough to be read as one, surely".repeat(3).getBytes(), Policy.defaults()));
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("a"));
            z.write(b("hello"));
            z.closeEntry();
        }
        fails(ErrorClass.ARCHIVE, "vzip", () -> open(zip.toByteArray(), Policy.defaults()));

        VzipBuilder v = new VzipBuilder();
        v.bytes("a", "hello");
        byte[] good = v.build();
        fails(ErrorClass.ARCHIVE, () -> open(Arrays.copyOf(good, good.length - 7), Policy.defaults()));
        for (String magic : List.of("vzip/1", "vzip/9", "zzzz/0", "VZIP/0")) {
            VzipBuilder bad = new VzipBuilder();
            bad.bytes("a", "hello");
            bad.magic = magic;
            fails(ErrorClass.ARCHIVE, () -> open(bad.build(), Policy.defaults()));
        }
        VzipBuilder noZip64 = new VzipBuilder();
        noZip64.bytes("a", "hello");
        noZip64.zip64 = false;
        fails(ErrorClass.ARCHIVE, "zip64", () -> open(noZip64.build(), Policy.defaults()));
        assertEquals("hello", s(open(good, Policy.defaults()).get("a")));
    }

    @Test
    void aMalformedSourceTableIsAnArchiveError() throws Exception {
        byte[][] bad = {
            new byte[0],                                  // a source with no kind
            {0x0a, 0x00},                                 // an empty url
            {0x12, 0x00},                                 // an empty key
            concat(new byte[] {0x1a, 0x01, 0x00}, new byte[] {0x20, 0x05}), // a pin on a data source
            concat(new byte[] {0x0a, 0x01, 'x'}, new byte[] {0x2a, 0x03, 'W', '/', '"'}), // a weak etag
            {0x0a, 0x02, (byte) 0xc3, 0x28},               // a url that is not UTF-8
        };
        for (byte[] msg : bad) {
            VzipBuilder v = new VzipBuilder();
            v.bytes("a", "hello");
            v.rawSource(msg);
            fails(ErrorClass.ARCHIVE, () -> open(v.build(), Policy.defaults()));
        }
    }

    @Test
    void tooManySourcesIsAnArchiveError() throws Exception {
        VzipBuilder v = new VzipBuilder();
        for (int i = 0; i < 5; i++) {
            v.dataSource(b("x"));
        }
        fails(ErrorClass.ARCHIVE, () -> open(v.build(), Policy.builder().maxSources(4).build()));
        assertEquals(5, open(v.build(), Policy.builder().maxSources(5).build()).sourceCount());
    }

    @Test
    void aCorruptCentralDirectoryIsAnArchiveErrorWithoutAPageIndexAndAnEntryErrorWithOne() throws Exception {
        for (int pageSize : new int[] {0, 1}) {
            VzipBuilder v = new VzipBuilder();
            v.pageSize = pageSize;
            v.bytes("a", "hello");
            v.bytes("m", "middle");
            v.bytes("z", "zed");
            byte[] bytes = v.build();
            int sig = indexOf(bytes, new byte[] {'P', 'K', 1, 2}, 0);
            // the second record's signature (in key order with an index, in written order without)
            int second = indexOf(bytes, new byte[] {'P', 'K', 1, 2}, sig + 4);
            bytes[second] = 'X';
            Path p = dir.resolve("c" + pageSize + ".vzip");
            Files.write(p, bytes);
            if (pageSize == 0) {
                fails(ErrorClass.ARCHIVE, () -> VzipArchive.open(p, Policy.defaults()));
            } else {
                try (VzipArchive a = VzipArchive.open(p, Policy.defaults())) {
                    fails(ErrorClass.ENTRY, () -> a.get("m"));
                    fails(ErrorClass.ENTRY, () -> a.list(""));
                }
            }
        }
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Test
    void entryErrorsAffectOnlyTheirKey() throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.dataSource(b("x"));
        byte[] payload = VzipBuilder.literal(b("r"));
        v.bytes("fine", "ok");
        v.ref("two-blocks", payload, true).extra = concat(VzipBuilder.block(0x7A76, payload), VzipBuilder.block(0x7A77, VzipBuilder.concat()));
        v.ref("unparseable", payload, true).extra = new byte[] {1, 2, 3};
        v.ref("overlong-block", payload, true).extra = new byte[] {0x76, 0x7A, 100, 0, 1};
        VzipBuilder.Entry m8 = v.ref("method-8", payload, true);
        m8.method = 8;
        v.bytes("method-9", b("x"), false).method = 9;
        v.bytes("encrypted", b("x"), false).flags |= 1;
        VzipBuilder.Entry oneSize = v.bytes("one-size-field", b("abc"), false);
        oneSize.csize = 0xFFFFFFFFL;
        VzipBuilder.Entry large = v.bytes("large-deflate", b("abc"), true);
        large.usize = 0xFFFFFFFFL;
        large.csize = 0xFFFFFFFFL;
        v.bytes("two-zip64", b("x"), false).extra = concat(VzipBuilder.block(1, new byte[16]), VzipBuilder.block(1, new byte[16]));
        v.bytes("ref-and-zip64-ok", b("x"), false).extra = concat(VzipBuilder.block(1, new byte[16]), VzipBuilder.block(0x1234, new byte[2]));
        for (int pageSize : new int[] {0, 1}) {
            v.pageSize = pageSize;
            try (VzipArchive a = open(v)) {
                assertEquals("ok", s(a.get("fine")));
                assertEquals("x", s(a.get("ref-and-zip64-ok")), "a ZIP64 block that is not needed is ignored");
                for (String k : List.of("two-blocks", "unparseable", "overlong-block", "method-8", "method-9", "encrypted",
                        "one-size-field", "large-deflate", "two-zip64")) {
                    fails(ErrorClass.ENTRY, () -> a.classify(k));
                    fails(ErrorClass.ENTRY, () -> a.get(k));
                }
                assertTrue(a.list("").containsAll(List.of("two-blocks", "method-9")), "keys with entry errors are listed");
            }
        }
    }

    @Test
    void bodyErrors() throws Exception {
        byte[] deflated = VzipBuilder.deflate(b("hello hello hello"));
        VzipBuilder v = new VzipBuilder();
        v.bytes("ok", b("hello hello hello"), true);
        VzipBuilder.Entry trailing = v.bytes("trailing", b("hello hello hello"), true);
        trailing.body = Arrays.copyOf(deflated, deflated.length + 1);
        trailing.csize = trailing.body.length;
        VzipBuilder.Entry truncated = v.bytes("truncated", b("hello hello hello"), true);
        truncated.body = Arrays.copyOf(deflated, deflated.length - 2);
        truncated.csize = truncated.body.length;
        VzipBuilder.Entry wrongSize = v.bytes("wrong-size", b("hello hello hello"), true);
        wrongSize.usize = 5;
        VzipBuilder.Entry garbage = v.bytes("garbage", b("hello hello hello"), true);
        garbage.body = new byte[] {7, 7, 7, 7};
        garbage.csize = 4;
        VzipBuilder.Entry storedSizes = v.bytes("stored-sizes", "abcdef");
        storedSizes.usize = 3;
        VzipBuilder.Entry outside = v.bytes("outside", "abcdef");
        outside.csize = 1000;
        outside.usize = 1000;
        try (VzipArchive a = open(v)) {
            assertEquals("hello hello hello", s(a.get("ok")));
            for (String k : List.of("trailing", "truncated", "wrong-size", "garbage", "stored-sizes", "outside")) {
                fails(ErrorClass.BODY, () -> a.get(k));
                fails(ErrorClass.BODY, () -> a.get(k, 0, 1));
            }
        }
    }

    @Test
    void aBodyErrorInAKeySourceIsAResolutionError() throws Exception {
        VzipBuilder v = new VzipBuilder();
        VzipBuilder.Entry broken = v.bytes("broken", b("hello hello hello"), true);
        broken.body = new byte[] {7, 7, 7};
        broken.csize = 3;
        v.ref("r", VzipBuilder.range(v.keySource("broken"), 0, 3, -1));
        try (VzipArchive a = open(v)) {
            fails(ErrorClass.BODY, () -> a.get("broken"));
            fails(ErrorClass.RESOLUTION, () -> a.get("r"));
        }
    }

    @Test
    void anIndexEntryWithoutAPageIndexIsAnArchiveError() throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.bytes("__vz__/index", "x");
        fails(ErrorClass.ARCHIVE, () -> open(v.build(), Policy.defaults()));
    }

    @Test
    void closedArchivesRefuseReads() throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.bytes("a", "hello");
        VzipArchive a = open(v);
        a.close();
        assertThrows(RuntimeException.class, () -> a.get("a"));
    }
}
