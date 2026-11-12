package org.refkv.vzip;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpHandler;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reading archives, and the sources they name, over HTTP (spec/refkv.md section 5.2). */
class HttpTest {
    static final Policy LOOPBACK = Policy.builder().allowPrivateHosts(true).build();
    RangeServer server;
    byte[] data = new byte[100_000];

    @BeforeEach
    void start() throws Exception {
        server = new RangeServer();
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + i / 256);
        }
        server.files.put("/data.bin", data);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    static ReferenceException fails(ErrorClass c, org.junit.jupiter.api.function.Executable e) {
        return VzipArchiveTest.fails(c, e);
    }

    VzipArchive serve(VzipBuilder v) {
        server.files.put("/a.vzip", v.build());
        return VzipArchive.open(server.url("/a.vzip"), LOOPBACK);
    }

    byte[] slice(int from, int to) {
        return java.util.Arrays.copyOfRange(data, from, to);
    }

    @Test
    void readsAnArchiveAndItsSourcesWithRangeRequestsOnly() throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.bytes("stored", "hello");
        int src = v.urlSource("data.bin", (long) data.length, null, null);
        v.ref("r", VzipBuilder.range(src, 100, 50, -1));
        try (VzipArchive a = serve(v)) {
            assertEquals(List.of("GET /a.vzip Range=bytes=-524288 If-Match=null"), server.requests(), "one request opens a small archive");
            server.clearLog();
            assertEquals("hello", VzipArchiveTest.s(a.get("stored")));
            assertEquals(List.of(), server.requests(), "what lies in the tail is read from it");
            assertArrayEquals(slice(100, 150), a.get("r"));
            assertEquals(List.of("GET /data.bin Range=bytes=100-149 If-Match=null"), server.requests());
            assertArrayEquals(slice(110, 120), a.get("r", 10, 10));
            assertTrue(server.requests().stream().allMatch(r -> r.startsWith("GET ")), "no HEAD, no other method");
        }
    }

    @Test
    void theDefaultPolicyRefusesLoopbackHosts() {
        VzipBuilder v = new VzipBuilder();
        v.bytes("a", "x");
        server.files.put("/a.vzip", v.build());
        ReferenceException e = fails(ErrorClass.ARCHIVE, () -> VzipArchive.open(server.url("/a.vzip"), Policy.defaults()));
        assertTrue(e.getMessage().contains("loopback"), e.getMessage());
        assertEquals(List.of(), server.requests(), "no request is sent to a refused address");
    }

    @Test
    void aSourceAtAPrivateHostIsRefusedUnlessAllowed() throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.ref("r", VzipBuilder.range(v.urlSource(server.url("/data.bin").toString()), 0, 4, -1));
        v.ref("public", VzipBuilder.range(v.urlSource("http://127.0.0.1:1/x"), 0, 4, -1));
        server.files.put("/a.vzip", v.build());
        // the archive is read with private hosts allowed, then its source is checked by the same policy
        try (VzipArchive a = VzipArchive.open(server.url("/a.vzip"), LOOPBACK)) {
            assertArrayEquals(slice(0, 4), a.get("r"));
        }
        // a prefix list that names only the archive's directory refuses a source elsewhere
        try (VzipArchive a = VzipArchive.open(server.url("/a.vzip"), Policy.builder().allowPrivateHosts(true).prefix("http://127.0.0.1:" + server.port() + "/").build())) {
            assertArrayEquals(slice(0, 4), a.get("r"));
            fails(ErrorClass.RESOLUTION, () -> a.get("public"));
        }
    }

    @Test
    void coalescesReadsThatLieCloseTogether() throws Exception {
        VzipBuilder v = new VzipBuilder();
        int src = v.urlSource("data.bin");
        v.ref("rows", VzipBuilder.range(src, 0, 10, -1), VzipBuilder.range(src, 20, 10, -1), VzipBuilder.range(src, 5000, 10, -1));
        v.ref("far", VzipBuilder.range(src, 0, 10, -1), VzipBuilder.range(src, 90_000, 10, -1));
        server.files.put("/a.vzip", v.build());
        byte[] rows = concat(slice(0, 10), slice(20, 30), slice(5000, 5010));
        try (VzipArchive a = VzipArchive.open(server.url("/a.vzip"), LOOPBACK)) {
            server.clearLog();
            assertArrayEquals(rows, a.get("rows"));
            assertEquals(List.of("GET /data.bin Range=bytes=0-5009 If-Match=null"), server.requests(), "within the 64 KiB gap: one request");
            server.clearLog();
            assertArrayEquals(concat(slice(0, 10), slice(90_000, 90_010)), a.get("far"));
            assertEquals(2, server.requests().size(), "farther apart than the gap: two requests");
        }
        Policy touching = Policy.builder().allowPrivateHosts(true).coalescingGap(0).build();
        try (VzipArchive a = VzipArchive.open(server.url("/a.vzip"), touching)) {
            server.clearLog();
            assertArrayEquals(rows, a.get("rows"));
            assertEquals(3, server.requests().size(), "a gap of 0 combines only reads that touch");
        }
        Policy fewBytes = Policy.builder().allowPrivateHosts(true).maxBytesPerGet(4000).build();
        try (VzipArchive a = VzipArchive.open(server.url("/a.vzip"), fewBytes)) {
            server.clearLog();
            assertArrayEquals(rows, a.get("rows"));
            assertEquals(2, server.requests().size(), "a join that would take the get past its bytes per get is not made");
        }
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    @Test
    void acceptsAServerThatIgnoresRange() throws Exception {
        VzipBuilder v = new VzipBuilder();
        v.bytes("stored", "hello");
        v.ref("r", VzipBuilder.range(v.urlSource("data.bin", (long) data.length, null, null), 100, 50, -1));
        server.ignoreRange.add("/a.vzip");
        server.ignoreRange.add("/data.bin");
        try (VzipArchive a = serve(v)) {
            assertEquals("hello", VzipArchiveTest.s(a.get("stored")));
            assertArrayEquals(slice(100, 150), a.get("r"));
        }
    }

    @Test
    void checksPinsAgainstTheResponse() throws Exception {
        long mna = 784111777L; // the server's Last-Modified
        VzipBuilder v = new VzipBuilder();
        v.ref("size-ok", VzipBuilder.range(v.urlSource("data.bin", (long) data.length, null, null), 0, 4, -1));
        v.ref("size-bad", VzipBuilder.range(v.urlSource("data.bin", data.length + 1L, null, null), 0, 4, -1));
        v.ref("etag-ok", VzipBuilder.range(v.urlSource("data.bin", null, "\"v1\"", null), 0, 4, -1));
        v.ref("etag-bad", VzipBuilder.range(v.urlSource("data.bin", null, "\"v2\"", null), 0, 4, -1));
        v.ref("time-ok", VzipBuilder.range(v.urlSource("data.bin", null, null, mna), 0, 4, -1));
        v.ref("time-bad", VzipBuilder.range(v.urlSource("data.bin", null, null, mna - 1), 0, 4, -1));
        v.ref("all", VzipBuilder.range(v.urlSource("data.bin", (long) data.length, "\"v1\"", mna), 0, 4, -1));
        try (VzipArchive a = serve(v)) {
            for (String ok : List.of("size-ok", "etag-ok", "time-ok", "all")) {
                assertArrayEquals(slice(0, 4), a.get(ok), ok);
            }
            server.clearLog();
            fails(ErrorClass.RESOLUTION, () -> a.get("etag-bad"));
            assertEquals(List.of("GET /data.bin Range=bytes=0-3 If-Match=\"v2\""), server.requests(), "the pin rides on the range request");
            fails(ErrorClass.RESOLUTION, () -> a.get("time-bad"));
            fails(ErrorClass.RESOLUTION, () -> a.get("size-bad"));

            // servers and proxies may ignore the conditional headers: the reader checks the response itself
            server.ignoreConditionals = true;
            fails(ErrorClass.RESOLUTION, () -> a.get("etag-bad"));
            fails(ErrorClass.RESOLUTION, () -> a.get("time-bad"));
            assertArrayEquals(slice(0, 4), a.get("all"));
            server.etag = "W/\"v1\"";
            fails(ErrorClass.RESOLUTION, () -> a.get("etag-ok"));
            server.etag = "\"v1\"";
            server.sendEtag = false;
            fails(ErrorClass.RESOLUTION, () -> a.get("etag-ok"));
            server.sendEtag = true;
            server.sendLastModified = false;
            fails(ErrorClass.RESOLUTION, () -> a.get("time-ok"));
            assertArrayEquals(slice(0, 4), a.get("size-ok"));
        }
    }

    private void respond(String path, int status, String[][] headers, byte[] body) {
        server.custom.put(path, ex -> {
            for (String[] h : headers) {
                ex.getResponseHeaders().add(h[0], h[1]);
            }
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
        });
    }

    @Test
    void refusesResponsesThatBreakTheRules() throws Exception {
        byte[] four = slice(0, 4);
        String[] paths = {"/bad-range", "/two-ranges", "/no-range", "/short", "/long", "/gzip", "/two-etags", "/two-mods", "/s500", "/s416",
                "/past-total", "/bad-unit", "/two-spaces", "/multipart"};
        respond("/bad-range", 206, new String[][] {{"Content-Range", "bytes 1-4/100"}}, four);
        respond("/two-ranges", 206, new String[][] {{"Content-Range", "bytes 0-3/100"}, {"Content-Range", "bytes 0-3/100"}}, four);
        respond("/no-range", 206, new String[][] {}, four);
        respond("/short", 206, new String[][] {{"Content-Range", "bytes 0-3/100"}}, slice(0, 3));
        respond("/long", 206, new String[][] {{"Content-Range", "bytes 0-2/100"}}, four);
        respond("/gzip", 206, new String[][] {{"Content-Range", "bytes 0-3/100"}, {"Content-Encoding", "gzip"}}, four);
        respond("/two-etags", 206, new String[][] {{"Content-Range", "bytes 0-3/100"}, {"ETag", "\"a\""}, {"ETag", "\"a\""}}, four);
        respond("/two-mods", 206, new String[][] {{"Content-Range", "bytes 0-3/100"}, {"Last-Modified", "Sun, 06 Nov 1994 08:49:37 GMT"},
                {"Last-Modified", "Sun, 06 Nov 1994 08:49:37 GMT"}}, four);
        respond("/s500", 500, new String[][] {}, new byte[0]);
        respond("/s416", 416, new String[][] {}, new byte[0]);
        respond("/past-total", 206, new String[][] {{"Content-Range", "bytes 0-3/3"}}, four);
        respond("/bad-unit", 206, new String[][] {{"Content-Range", "items 0-3/100"}}, four);
        respond("/two-spaces", 206, new String[][] {{"Content-Range", "bytes  0-3/100"}}, four);
        respond("/multipart", 206, new String[][] {{"Content-Type", "multipart/byteranges; boundary=x"}}, four);
        // a well-formed one, for contrast, and an unknown total, which only a size pin cannot live with
        respond("/ok", 206, new String[][] {{"Content-Range", "bytes 0-3/100"}, {"Content-Encoding", "identity"}}, four);
        respond("/star", 206, new String[][] {{"Content-Range", "bytes 0-3/*"}}, four);
        VzipBuilder v = new VzipBuilder();
        for (String p : paths) {
            v.ref(p, VzipBuilder.range(v.urlSource(p.substring(1)), 0, 4, -1));
        }
        v.ref("/ok", VzipBuilder.range(v.urlSource("ok"), 0, 4, -1));
        v.ref("/star", VzipBuilder.range(v.urlSource("star"), 0, 4, -1));
        v.ref("/star-pinned", VzipBuilder.range(v.urlSource("star", 100L, null, null), 0, 4, -1));
        try (VzipArchive a = serve(v)) {
            assertArrayEquals(four, a.get("/ok"));
            assertArrayEquals(four, a.get("/star"), "an unknown total is fine without a size pin");
            fails(ErrorClass.RESOLUTION, () -> a.get("/star-pinned"));
            for (String p : paths) {
                fails(ErrorClass.RESOLUTION, () -> a.get(p));
            }
        }
    }

    @Test
    void followsAtMostFiveRedirects() throws Exception {
        redirect("/hop0", 301, "/data.bin");
        for (int i = 1; i <= 6; i++) {
            redirect("/hop" + i, i % 2 == 0 ? 302 : 307, "/hop" + (i - 1));
        }
        redirect("/to-file", 302, "file:///etc/passwd");
        redirect("/no-location", 302, null);
        redirect("/bad-location", 302, "http://[::1/x");
        redirect("/loop", 302, "/loop");
        redirect("/to-ftp", 302, "ftp://h/x");
        redirect("/relative", 303, "data.bin");
        VzipBuilder v = new VzipBuilder();
        for (String p : new String[] {"hop0", "hop4", "hop5", "hop6", "to-file", "no-location", "bad-location", "loop", "to-ftp", "relative"}) {
            v.ref(p, VzipBuilder.range(v.urlSource(p), 0, 4, -1));
        }
        try (VzipArchive a = serve(v)) {
            assertArrayEquals(slice(0, 4), a.get("hop0"));
            assertArrayEquals(slice(0, 4), a.get("hop4"), "a chain of 5 redirects makes 6 requests");
            assertArrayEquals(slice(0, 4), a.get("relative"), "a relative Location resolves against the requested URL");
            for (String p : List.of("hop5", "hop6", "to-file", "no-location", "bad-location", "loop", "to-ftp")) {
                fails(ErrorClass.RESOLUTION, () -> a.get(p));
            }
            server.clearLog();
            fails(ErrorClass.RESOLUTION, () -> a.get("hop5"));
            assertEquals(6, server.requests().size(), "a redirect in reply to the 6th request is an error, and sends no 7th");
        }
    }

    private void redirect(String path, int status, String location) {
        server.custom.put(path, ex -> {
            if (location != null) {
                ex.getResponseHeaders().add("Location", location);
            }
            ex.sendResponseHeaders(status, -1);
        });
    }

    @Test
    void aRedirectKeepsItsRangeAndPins() throws Exception {
        redirect("/moved", 301, "/data.bin");
        VzipBuilder v = new VzipBuilder();
        v.ref("r", VzipBuilder.range(v.urlSource("moved", (long) data.length, "\"v1\"", null), 10, 4, -1));
        try (VzipArchive a = serve(v)) {
            server.clearLog();
            assertArrayEquals(slice(10, 14), a.get("r"));
            assertEquals(List.of("GET /moved Range=bytes=10-13 If-Match=\"v1\"", "GET /data.bin Range=bytes=10-13 If-Match=\"v1\""), server.requests());
        }
    }

    @Test
    void aChangedArchiveFailsInsteadOfMixingVersions() throws Exception {
        byte[] big = new byte[600 << 10];
        java.util.Arrays.fill(big, (byte) 7);
        VzipBuilder v = new VzipBuilder();
        v.bytes("big", big, false);
        v.bytes("small", "ok");
        try (VzipArchive a = serve(v)) {
            assertEquals("ok", VzipArchiveTest.s(a.get("small")));
            server.clearLog();
            assertEquals(1000, a.get("big", 0, 1000).length);
            assertEquals(1, server.requests().size());
            assertTrue(server.requests().get(0).contains("If-Match=\"v1\""), server.requests().get(0));
            server.etag = "\"v2\"";
            fails(ErrorClass.BODY, () -> a.get("big", 0, 1000));
        }
    }

    @Test
    void anArchiveAtAnUnreachableOrMissingUrl() {
        fails(ErrorClass.ARCHIVE, () -> VzipArchive.open(server.url("/absent.vzip"), LOOPBACK));
        fails(ErrorClass.ARCHIVE, () -> VzipArchive.open(URI.create("http://127.0.0.1:1/x.vzip"), LOOPBACK));
        fails(ErrorClass.ARCHIVE, () -> VzipArchive.open(URI.create("ftp://h/x.vzip"), LOOPBACK));
        server.files.put("/junk", "not an archive at all, but longer than the end records are".repeat(4).getBytes(StandardCharsets.UTF_8));
        fails(ErrorClass.ARCHIVE, () -> VzipArchive.open(server.url("/junk"), LOOPBACK));
        HttpHandler gone = ex -> ex.sendResponseHeaders(500, -1);
        server.custom.put("/err", gone);
        fails(ErrorClass.ARCHIVE, () -> VzipArchive.open(server.url("/err"), LOOPBACK));
    }

    @Test
    void readsAPagedArchiveOverHttpWithoutTheWholeDirectory() throws Exception {
        // an archive larger than the tail read, with a directory of many pages: a lookup reads one page
        VzipBuilder v = new VzipBuilder();
        v.pageSize = 4096;
        byte[] filler = new byte[700 << 10];
        v.bytes("a-filler", filler, false);
        for (int i = 0; i < 3000; i++) {
            v.bytes(String.format("keys/%05d", i), "v" + i);
        }
        server.files.put("/a.vzip", v.build());
        try (VzipArchive a = VzipArchive.open(server.url("/a.vzip"), LOOPBACK)) {
            assertEquals("v1234", VzipArchiveTest.s(a.get("keys/01234")));
            assertEquals(List.of("keys/02999"), a.list("keys/02999"));
            assertEquals(10, a.list("keys/0120").size());
            assertEquals(100, a.list("keys/012").size());
            assertNull(a.get("keys/99999"));
            assertEquals(3001, a.list("").size());
        }
    }
}
