package org.refkv.vzip;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The parts where a slip would be a security or correctness bug: URIs, strict protobuf, HTTP dates, addresses, prefixes. */
class ComponentsTest {
    private static String resolve(String base, String ref) {
        return UriRef.parse(base).resolve(UriRef.parse(ref)).toString();
    }

    /** RFC 3986 section 5.4, the normal and abnormal examples, with the strict parser. */
    @Test
    void resolvesAsRfc3986Does() {
        String base = "http://a/b/c/d;p?q";
        String[][] cases = {
            {"g:h", "g:h"}, {"g", "http://a/b/c/g"}, {"./g", "http://a/b/c/g"}, {"g/", "http://a/b/c/g/"}, {"/g", "http://a/g"},
            {"//g", "http://g"}, {"?y", "http://a/b/c/d;p?y"}, {"g?y", "http://a/b/c/g?y"}, {"#s", "http://a/b/c/d;p?q#s"},
            {"g#s", "http://a/b/c/g#s"}, {"g?y#s", "http://a/b/c/g?y#s"}, {";x", "http://a/b/c/;x"}, {"g;x", "http://a/b/c/g;x"},
            {"g;x?y#s", "http://a/b/c/g;x?y#s"}, {"", "http://a/b/c/d;p?q"}, {".", "http://a/b/c/"}, {"./", "http://a/b/c/"},
            {"..", "http://a/b/"}, {"../", "http://a/b/"}, {"../g", "http://a/b/g"}, {"../..", "http://a/"}, {"../../", "http://a/"},
            {"../../g", "http://a/g"}, {"../../../g", "http://a/g"}, {"../../../../g", "http://a/g"}, {"/./g", "http://a/g"},
            {"/../g", "http://a/g"}, {"g.", "http://a/b/c/g."}, {".g", "http://a/b/c/.g"}, {"g..", "http://a/b/c/g.."},
            {"..g", "http://a/b/c/..g"}, {"./../g", "http://a/b/g"}, {"./g/.", "http://a/b/c/g/"}, {"g/./h", "http://a/b/c/g/h"},
            {"g/../h", "http://a/b/c/h"}, {"g;x=1/./y", "http://a/b/c/g;x=1/y"}, {"g;x=1/../y", "http://a/b/c/y"},
            {"g?y/./x", "http://a/b/c/g?y/./x"}, {"g?y/../x", "http://a/b/c/g?y/../x"}, {"g#s/./x", "http://a/b/c/g#s/./x"},
            {"g#s/../x", "http://a/b/c/g#s/../x"}, {"http:g", "http:g"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], resolve(base, c[0]), "resolving " + c[0]);
        }
    }

    @Test
    void validatesUriReferences() {
        for (String ok : List.of("a%20b.bin", "%C3%A9.bin", "/data/x.bin", "../x", "http://h:8080/p?q#f", "http://[::1]/x", "file:///a/b",
                "#frag", "", "http://u:p@h/", "a:b", "x/y:z")) {
            assertDoesNotThrow(() -> UriRef.parse(ok), ok);
        }
        for (String bad : List.of("a b", "café", "a%2", "a%zz", "1:b/c", ":x", "http://[::1/x", "http://h:80x/", "a\\b", "http://ex ample/")) {
            assertThrows(IllegalArgumentException.class, () -> UriRef.parse(bad), bad);
        }
    }

    @Test
    void mapsFileUrisToPaths() {
        assertEquals("/data/my file.vzip", UriRef.parse("file:///data/my%20file.vzip").toPosixPath());
        assertEquals("/x", UriRef.parse("file:/x").toPosixPath());
        assertEquals("/x", UriRef.parse("FILE://LocalHost/x").toPosixPath());
        assertEquals("/x", UriRef.parse("file:///x#ignored").toPosixPath());
        assertEquals("file:///data/my%20file.vzip", UriRef.fileBase(java.nio.file.Path.of("/data/./a/../my file.vzip")).toString());
        assertEquals("file:///a", UriRef.fileBase(java.nio.file.Path.of("/../a")).toString());
        assertEquals("file:///a%5Cb/c", UriRef.posixFileBase("/a\\b/c").toString(), "a backslash is a character of a POSIX name, not a separator");
        assertThrows(IllegalArgumentException.class, () -> UriRef.parse("file://server/share/x").toPosixPath(), "a host has no POSIX mapping, whatever is allowed");
        assertEquals("/a./b", UriRef.parse("file:///a%2e/b").toPosixPath(), "a name that merely ends in a dot is a name");
        for (String bad : List.of("file://server/x", "file:///a%2Fb", "file:///a%00b", "file:///%2E%2E/b", "file:///a/%2e/b", "file:///x?q", "file:///a%FF", "file:x")) {
            assertThrows(IllegalArgumentException.class, () -> UriRef.parse(bad).toPosixPath(), bad);
        }
    }

    // ---- Windows paths: the same rules, tested here on any system (RFC 8089 appendix E, spec/refkv.md section 5) ----

    @Test
    void buildsWindowsBaseUris() {
        String[][] cases = {
            {"C:\\data\\my file.vzip", "file:///C:/data/my%20file.vzip"},
            {"\\\\server\\share\\x.vzip", "file://server/share/x.vzip"},
            {"C:/data/x.vzip", "file:///C:/data/x.vzip"},
            {"c:\\a", "file:///c:/a"},
            {"C:\\a\\.\\b\\..\\c.vzip", "file:///C:/a/c.vzip"},
            {"C:\\..\\..\\a", "file:///C:/a"},
            {"\\\\server\\share\\..\\x", "file://server/share/x"},
            {"C:\\\u00e9.vzip", "file:///C:/%C3%A9.vzip"},
            {"C:\\a;b'c@d,e.vzip", "file:///C:/a;b'c@d,e.vzip"},
            {"C:\\a\\\\b", "file:///C:/a/b"},
            {"C:\\", "file:///C:"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], UriRef.windowsFileBase(c[0]).toString(), c[0]);
        }
        for (String bad : List.of("\\\\?\\C:\\x", "\\\\.\\pipe\\x", "\\data\\x", "data\\x", "C:x", "C:", "\\\\server", "\\\\server\\", "\\\\\\share\\x",
                "\\\\ser ver\\share\\x", "\\\\s\u00e9rver\\share\\x")) {
            assertThrows(IllegalArgumentException.class, () -> UriRef.windowsFileBase(bad), bad);
        }
    }

    @Test
    void mapsFileUrisToWindowsPaths() {
        String[][] cases = {
            {"file:///C:/data/x", "C:\\data\\x"},
            {"file:///c%7C/data/x", "c:\\data\\x"}, // RFC 8089 allows | for :, and a URI reference can only spell it %7C
            {"file:///C:/my%20file.vzip", "C:\\my file.vzip"},
            {"file:///C:/%C3%A9", "C:\\\u00e9"},
            {"file:///C:", "C:\\"},
            {"file:///C:/", "C:\\"},
            {"file:///C:/a/b/", "C:\\a\\b\\"},
            {"file://localhost/C:/x", "C:\\x"},
            {"FILE://LOCALHOST/C:/x", "C:\\x"},
            {"file:/C:/x", "C:\\x"},
            {"file:///C:/x#ignored", "C:\\x"},
            {"file:///%43:/x", "C:\\x"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], UriRef.parse(c[0]).toWindowsPath(false), c[0]);
        }
        for (String bad : List.of(
                "file:///data/x",             // no drive letter: it would name a file on whichever drive is current
                "file:///x",
                "file:///1:/x", "file:///CC:/x", "file:///C/x", "file:///:/x",
                "file:///C:/a%5Cb", "file:///C:/a%5cb",    // a backslash would be read as a separator
                "file:///C:/a%2Fb", "file:///C:/a%00b",
                "file:///C:/%2E%2E/x", "file:///C:/a/%2e/b", // dot segments after decoding
                "file:///C:/a%FF",                          // not UTF-8
                "file:///C:/x?q",
                "file:x")) {
            assertThrows(IllegalArgumentException.class, () -> UriRef.parse(bad).toWindowsPath(true), bad);
        }
    }

    @Test
    void mapsUncPathsOnlyWhenFileHostsAreAllowed() {
        UriRef unc = UriRef.parse("file://server/share/dir/x.vzip");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> unc.toWindowsPath(false));
        assertTrue(e.getMessage().contains("allowFileHosts"), e.getMessage());
        assertEquals("\\\\server\\share\\dir\\x.vzip", unc.toWindowsPath(true));
        assertEquals("\\\\server\\share", UriRef.parse("file://server/share").toWindowsPath(true));
        assertEquals("\\\\server\\share\\", UriRef.parse("file://server/share/").toWindowsPath(true));
        assertEquals("\\\\host.example.com\\s\\\u00e9", UriRef.parse("file://host.example.com/s/%C3%A9").toWindowsPath(true));
        for (String bad : List.of("file://server/", "file://server", "file://user@server/share/x", "file://server:445/share/x", "file://%73erver/share/x",
                "file://[::1]/share/x", "file://server/share/%5C", "file://server/share/%2E%2E/x", "file://server/share/a%2Fb", "file://server/share/x?q")) {
            assertThrows(IllegalArgumentException.class, () -> UriRef.parse(bad).toWindowsPath(true), bad);
        }
        // a store opened from a UNC path has a base URI whose host is the server, and its relative sources stay on it
        UriRef base = UriRef.windowsFileBase("\\\\server\\share\\volumes\\a.vzip");
        assertEquals("file://server/share/volumes/data.bin", base.resolve(UriRef.parse("data.bin")).toString());
        assertEquals("file://server/share/other/data.bin", base.resolve(UriRef.parse("../other/data.bin")).toString());
        // a reference that climbs out of the share names another share of the same server: RFC 3986 keeps the host and
        // drops what ".." removes, so allowFileHosts alone does not confine a store to its share; a prefix does
        assertEquals("file://server/x", base.resolve(UriRef.parse("../../../x")).toString());
        assertEquals("\\\\server\\x", base.resolve(UriRef.parse("../../../x")).toWindowsPath(true));
        assertFalse(allowedBy("file://server/share/", base.resolve(UriRef.parse("../../../x")).toString()));
        // and one that climbs out of a drive loses the drive letter, which is the error the spec asks for
        UriRef drive = UriRef.windowsFileBase("C:\\data\\a.vzip");
        assertEquals("file:///C:/data/x", drive.resolve(UriRef.parse("x")).toString());
        assertThrows(IllegalArgumentException.class, () -> drive.resolve(UriRef.parse("../../../x")).toWindowsPath(false));
    }

    // ---- protobuf ----

    private static byte[] bytes(int... b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            r[i] = (byte) b[i];
        }
        return r;
    }

    @Test
    void decodesStrictly() throws Exception {
        // a Range: source 1, offset 3, length 4, with an unknown field of each wire type and the reserved field 2
        Messages.Range r = Messages.Range.decode(new Wire(bytes(0x08, 1, 0x10, 9, 0x18, 3, 0x20, 4, 0x38, 7, 0x41, 1, 2, 3, 4, 5, 6, 7, 8, 0x4a, 2, 9, 9, 0x55, 1, 2, 3, 4)));
        assertEquals(1, r.source());
        assertEquals(3, r.offset());
        assertEquals(4, r.length());
        assertFalse(r.literal());
        // the last occurrence wins, and non-minimal varints are accepted
        assertEquals(5, Messages.Range.decode(new Wire(bytes(0x20, 4, 0x20, 0x85, 0x80, 0x00))).length());
        // a literal range, empty
        assertTrue(Messages.Range.decode(new Wire(bytes(0x2a, 0))).literal());
        // a source range with a checksum
        assertEquals(0xFFFFFFFFL, Messages.Range.decode(new Wire(bytes(0x30, 0xff, 0xff, 0xff, 0xff, 0x0f))).crc32c());

        List<byte[]> malformed = List.of(
            bytes(0x1b),                                          // wire type 3
            bytes(0x1c),                                          // wire type 4
            bytes(0x1e),                                          // wire type 6
            bytes(0x1f),                                          // wire type 7
            bytes(0x00, 0x01),                                    // field number 0
            bytes(0x08),                                          // truncated: no value
            bytes(0x08, 0x80),                                    // truncated varint
            bytes(0x08, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00), // a varint of 11 bytes
            bytes(0x18, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x7f),       // a varint over 64 bits
            bytes(0x08, 0x80, 0x80, 0x80, 0x80, 0x10),            // source over 2^32 - 1
            bytes(0x08, 0x80, 0x80, 0x80, 0x80, 0x10, 0x08, 1),   // an overridden overflow still counts
            bytes(0x0a, 0x00),                                    // source as LEN
            bytes(0x2a, 0x05, 1),                                 // a LEN field past the end
            bytes(0x2a, 0x00, 0x18, 1),                           // a literal range with an offset
            bytes(0x2a, 0x00, 0x30, 1),                           // a literal range with a crc
            bytes(0x18, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01, 0x20, 2) // offset + length over 2^64 - 1
        );
        for (byte[] m : malformed) {
            assertThrows(Wire.Malformed.class, () -> Messages.Range.decode(new Wire(m)), java.util.HexFormat.of().formatHex(m));
        }
        // strings: valid UTF-8 only (no overlong forms, no surrogates)
        assertEquals("é", Messages.decodeSourceTable(sourceWithUrl(bytes(0xc3, 0xa9)), 10).get(0).text());
        for (byte[] bad : List.of(bytes(0xc3, 0x28), bytes(0xc0, 0xaf), bytes(0xed, 0xa0, 0x80), bytes(0xff), bytes(0xf4, 0x90, 0x80, 0x80))) {
            assertThrows(Wire.Malformed.class, () -> Messages.decodeSourceTable(sourceWithUrl(bad), 10), java.util.HexFormat.of().formatHex(bad));
        }
        // the oneof: the last member wins
        Messages.Source s = Messages.decodeSourceTable(bytes(0x0a, 6, 0x0a, 1, 'u', 0x12, 1, 'k'), 10).get(0);
        assertEquals(Messages.SourceKind.KEY, s.kind());
        assertEquals("k", s.text());
        // pins: a size of 0 is a pin; absent is not
        assertEquals(0L, Messages.decodeSourceTable(bytes(0x0a, 5, 0x0a, 1, 'u', 0x20, 0), 10).get(0).size());
        assertNull(Messages.decodeSourceTable(bytes(0x0a, 3, 0x0a, 1, 'u'), 10).get(0).size());
    }

    private static byte[] sourceWithUrl(byte[] url) {
        byte[] src = new byte[2 + url.length];
        src[0] = 0x0a;
        src[1] = (byte) url.length;
        System.arraycopy(url, 0, src, 2, url.length);
        byte[] table = new byte[2 + src.length];
        table[0] = 0x0a;
        table[1] = (byte) src.length;
        System.arraycopy(src, 0, table, 2, src.length);
        return table;
    }

    @Test
    void entityTags() {
        assertTrue(Messages.strongEtag("\"abc\""));
        assertTrue(Messages.strongEtag("\"\""));
        assertFalse(Messages.strongEtag("W/\"abc\""));
        assertFalse(Messages.strongEtag("abc"));
        assertFalse(Messages.strongEtag("\"a\"b\""));
        assertFalse(Messages.strongEtag("\"a b\""));
        assertFalse(Messages.strongEtag("\"é\""));
    }

    // ---- HTTP dates ----

    @Test
    void parsesImfFixdates() {
        assertEquals(784111777L, HttpReader.parseImf("Sun, 06 Nov 1994 08:49:37 GMT"));
        assertEquals(1483228800L, HttpReader.parseImf("Sat, 31 Dec 2016 23:59:60 GMT"), "a leap second counts as the following second");
        for (String bad : List.of("Mon, 06 Nov 1994 08:49:37 GMT",          // the wrong day name
                "sun, 06 Nov 1994 08:49:37 GMT", "Sun, 06 nov 1994 08:49:37 GMT", // names are case-sensitive
                "Sunday, 06-Nov-94 08:49:37 GMT",                              // RFC 850
                "Sun Nov  6 08:49:37 1994",                                    // asctime
                "Sun, 6 Nov 1994 08:49:37 GMT", "Sun, 06 Nov 1994 08:49:37 UTC", "Sun, 31 Nov 1994 08:49:37 GMT",
                "Sun, 06 Nov 1994 24:49:37 GMT", "Sun, 06 Nov 1994 08:60:37 GMT", "Sun, 06 Nov 1994 08:49:61 GMT", "")) {
            assertEquals(Long.MIN_VALUE, HttpReader.parseImf(bad), bad);
        }
        assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", HttpReader.imfFixdate(784111777L));
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", HttpReader.imfFixdate(0));
        assertThrows(ReferenceException.class, () -> HttpReader.imfFixdate(-100_000_000_000L));
        assertThrows(ReferenceException.class, () -> HttpReader.imfFixdate(300_000_000_000L));
    }

    // ---- addresses and prefixes ----

    private static Policy.AddressClass cls(String ip) throws Exception {
        return Policy.classify(InetAddress.getByName(ip));
    }

    @Test
    void classifiesAddresses() throws Exception {
        for (String ip : List.of("127.0.0.1", "127.255.255.254", "::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::")) {
            assertEquals(Policy.AddressClass.LOOPBACK, cls(ip), ip);
        }
        for (String ip : List.of("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "fc00::1", "fd12::1", "::ffff:10.0.0.1")) {
            assertEquals(Policy.AddressClass.PRIVATE, cls(ip), ip);
        }
        for (String ip : List.of("169.254.169.254", "fe80::1", "febf::1", "::ffff:169.254.169.254")) {
            assertEquals(Policy.AddressClass.LINK_LOCAL, cls(ip), ip);
        }
        for (String ip : List.of("0.0.0.0", "100.64.0.1", "192.0.2.1", "198.18.0.1", "203.0.113.5", "224.0.0.1", "255.255.255.255", "::",
                "2001:db8::1", "ff02::1", "fec0::1", "100::1", "64:ff9b:1::1")) {
            assertEquals(Policy.AddressClass.SPECIAL, cls(ip), ip);
        }
        for (String ip : List.of("8.8.8.8", "1.1.1.1", "172.32.0.1", "172.15.0.1", "100.63.0.1", "2606:4700::1111", "::ffff:8.8.8.8")) {
            assertEquals(Policy.AddressClass.PUBLIC, cls(ip), ip);
        }
    }

    /** Whether a URL matches the prefix, as a prefix list decides (string comparison only: no path is resolved). */
    private static boolean allowedBy(String prefix, String url) {
        return url.startsWith(prefix) && (prefix.endsWith("/") || url.length() == prefix.length() || "/?#".indexOf(url.charAt(prefix.length())) >= 0);
    }

    private static boolean allowed(Policy p, String url) {
        try {
            p.check(UriRef.parse(url));
            return true;
        } catch (ReferenceException e) {
            return false;
        }
    }

    @Test
    void appliesSchemesAndPrefixes() {
        Policy d = Policy.defaults();
        assertTrue(allowed(d, "http://h/x"));
        assertTrue(allowed(d, "HTTPS://h/x"));
        for (String no : List.of("file:///etc/passwd", "s3://bucket/k", "gs://b/k", "data:,x", "ftp://h/x", "/relative")) {
            assertFalse(allowed(d, no), no);
        }
        assertTrue(allowed(Policy.builder().allowScheme("file").build(), "file:///x"));
        assertFalse(allowed(Policy.builder().allowScheme("file").build(), "file://host/x"), "a file URL with a host is refused whatever the schemes allow");
        Policy p = Policy.builder().prefix("https://data.example/a").prefix("https://other.example/dir/").build();
        assertTrue(allowed(p, "https://data.example/a"));
        assertTrue(allowed(p, "https://data.example/a/x.bin"));
        assertTrue(allowed(p, "https://data.example/a?q=1"));
        assertTrue(allowed(p, "https://data.example/a#f"));
        assertFalse(allowed(p, "https://data.example/ab"));
        assertFalse(allowed(p, "https://data.example.evil.test/a"));
        assertTrue(allowed(p, "https://other.example/dir/anything"));
        assertFalse(allowed(p, "https://other.example/dir"));
        assertFalse(allowed(p, "https://data.example/a/%2e%2e/b"), "a path with %2E matches no prefix");
        assertFalse(allowed(p, "https://data.example/a/x%2Fy"));
        assertFalse(allowed(p, "https://data.example/a/x%5cy"));
        assertFalse(allowed(p, "http://data.example/a"), "a prefix list replaces the scheme rule, and matches as a string");
    }

    @Test
    void fileHostsAreRefusedUnlessAllowedWhateverTheSchemesAndPrefixesSay() {
        String unc = "file://server/share/x";
        assertFalse(allowed(Policy.builder().allowScheme("file").build(), unc));
        Policy on = Policy.builder().allowScheme("file").allowFileHosts(true).build();
        assertTrue(allowed(on, unc));
        assertTrue(on.allowsFileHosts());
        assertFalse(Policy.defaults().allowsFileHosts());
        assertFalse(allowed(Policy.builder().allowFileHosts(true).build(), unc), "it lifts only the host rule: file is still not an allowed scheme");
        assertTrue(allowed(Policy.builder().allowScheme("file").build(), "file://localhost/x"));
    }

    @Test
    void anUnsafePolicyAllowsPrivateHosts() throws Exception {
        UriRef loopback = UriRef.parse("http://127.0.0.1:1/x");
        ReferenceException e = assertThrows(ReferenceException.class, () -> Policy.defaults().checkHost(loopback));
        assertEquals(ErrorClass.RESOLUTION, e.errorClass());
        assertTrue(e.getMessage().contains("loopback") && e.getMessage().contains("allowPrivateHosts"), e.getMessage());
        assertDoesNotThrow(() -> Policy.builder().allowPrivateHosts(true).build().checkHost(loopback));
        assertThrows(ReferenceException.class, () -> Policy.defaults().checkHost(UriRef.parse("http://[::1]/x")));
        assertThrows(ReferenceException.class, () -> Policy.defaults().checkHost(UriRef.parse("http://2130706433/x")), "an integer IPv4 literal");
        assertThrows(ReferenceException.class, () -> Policy.defaults().checkHost(UriRef.parse("http://0x7f.1/x")), "a hexadecimal one");
        assertThrows(ReferenceException.class, () -> Policy.defaults().checkHost(UriRef.parse("http://localhost/x")));
        assertDoesNotThrow(() -> Policy.defaults().checkHost(UriRef.parse("http://8.8.8.8/x")));
    }

    @Test
    void comparesInUtf8Order() {
        assertTrue(VzipArchive.compareUtf8("～", "𐀀") < 0);
        assertTrue("～".compareTo("𐀀") > 0, "UTF-16 order differs, which is why the comparison is explicit");
        assertTrue(VzipArchive.compareUtf8("a", "ab") < 0);
        assertEquals(0, VzipArchive.compareUtf8("x", "x"));
        assertTrue(VzipArchive.compareUtf8("b", "a/") > 0);
        assertEquals(new String("é".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), "é");
    }
}
