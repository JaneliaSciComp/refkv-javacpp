package org.refkv.vzip;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.refkv.javacpp.PhkvStore;
import org.refkv.javacpp.Zarrify;

/**
 * A phkv store's references are resolved in Java, under a policy, with pins checked. The store is zarrified from a file
 * served over HTTP, so refkv itself records the server's size and ETag as the pins of its source.
 */
class PhkvResolutionTest {
    static final Path FIXTURES = Path.of(System.getProperty("refkv.fixtures", "../refkv/fixtures")).toAbsolutePath().normalize();
    static final int HEADER = 1024, CHUNK = 550_000;
    static final Policy LOOPBACK = Policy.builder().allowPrivateHosts(true).build();
    @TempDir Path dir;
    RangeServer server;
    byte[] mrc;
    Path phkv;

    @BeforeEach
    void zarrifyOverHttp() throws Exception {
        mrc = Files.readAllBytes(FIXTURES.resolve("mrc/mrc_chunk_rows.mrc"));
        server = new RangeServer();
        server.files.put("/volume.mrc", mrc);
        phkv = dir.resolve("volume.phkv");
        Zarrify.convert(server.url("/volume.mrc").toString(), phkv.toString(),
                Zarrify.options().format(Zarrify.Container.PHKV).allowPrivateHosts(true));
        server.clearLog();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    byte[] chunk(int i) {
        return Arrays.copyOfRange(mrc, HEADER + i * CHUNK, HEADER + (i + 1) * CHUNK);
    }

    static ReferenceException fails(ErrorClass c, org.junit.jupiter.api.function.Executable e) {
        return VzipArchiveTest.fails(c, e);
    }

    @Test
    void readsOverHttpWithTheServersPinsOnTheRequest() throws Exception {
        try (PhkvStore s = PhkvStore.open(phkv.toString(), LOOPBACK)) {
            assertArrayEquals(chunk(0), s.get("0/c/0/0"));
            assertArrayEquals(Arrays.copyOfRange(chunk(1), 100, 140), s.get("0/c/1/0", 100, 40));
            List<String> reads = server.requests();
            assertEquals(2, reads.size(), reads.toString());
            assertTrue(reads.stream().allMatch(r -> r.startsWith("GET /volume.mrc Range=bytes=")), reads.toString());
            assertTrue(reads.stream().allMatch(r -> r.endsWith("If-Match=\"v1\"")), "the ETag pin rides on the request: " + reads);
        }
    }

    @Test
    void theDefaultPolicyRefusesTheLoopbackServer() throws Exception {
        try (PhkvStore s = PhkvStore.open(phkv.toString())) {
            fails(ErrorClass.RESOLUTION, () -> s.get("0/c/0/0")).getMessage().contains("loopback");
            assertEquals(List.of(), server.requests(), "no request is sent to a refused address");
        }
    }

    @Test
    void pinsFailClosed() throws Exception {
        try (PhkvStore s = PhkvStore.open(phkv.toString(), LOOPBACK)) {
            assertArrayEquals(chunk(0), s.get("0/c/0/0"));

            // the object is replaced under the same name: the server answers 412 to the If-Match
            server.etag = "\"v2\"";
            fails(ErrorClass.RESOLUTION, () -> s.get("0/c/0/0"));
            // a server or proxy that ignores If-Match: the reader compares the ETag of the response itself
            server.ignoreConditionals = true;
            fails(ErrorClass.RESOLUTION, () -> s.get("0/c/0/0"));
            // a weak ETag is not a match, and neither is none at all
            server.etag = "W/\"v1\"";
            fails(ErrorClass.RESOLUTION, () -> s.get("0/c/0/0"));
            server.etag = "\"v1\"";
            server.sendEtag = false;
            fails(ErrorClass.RESOLUTION, () -> s.get("0/c/0/0"));
            server.sendEtag = true;
            assertArrayEquals(chunk(0), s.get("0/c/0/0"), "and it reads again once the object is what was pinned");

            // an object of another size, whatever its ETag
            server.files.put("/volume.mrc", Arrays.copyOf(mrc, mrc.length + 1));
            fails(ErrorClass.RESOLUTION, () -> s.get("0/c/0/0"));
        }
    }

    @Test
    void aPrefixConfinesWhereAStoreMayReadFrom() throws Exception {
        Policy other = Policy.builder().allowPrivateHosts(true).prefix("http://127.0.0.1:" + server.port() + "/elsewhere/").build();
        try (PhkvStore s = PhkvStore.open(phkv.toString(), other)) {
            fails(ErrorClass.RESOLUTION, () -> s.get("0/c/0/0"));
        }
        Policy here = Policy.builder().allowPrivateHosts(true).prefix("http://127.0.0.1:" + server.port() + "/").build();
        try (PhkvStore s = PhkvStore.open(phkv.toString(), here)) {
            assertArrayEquals(chunk(0), s.get("0/c/0/0"));
        }
    }

    @Test
    void limitsApplyToPhkvStoresToo() throws Exception {
        Policy tight = Policy.builder().allowPrivateHosts(true).maxBytesPerGet(1000).build();
        try (PhkvStore s = PhkvStore.open(phkv.toString(), tight)) {
            fails(ErrorClass.REQUEST, () -> s.get("0/c/0/0"));
            assertEquals(40, s.get("0/c/0/0", 0, 40).length);
        }
        assertThrows(ReferenceException.class, () -> PhkvStore.open(phkv.toString(), Policy.builder().maxSources(0).build()));
    }
}
