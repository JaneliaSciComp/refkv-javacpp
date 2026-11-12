package org.refkv.javacpp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader;
import org.junit.jupiter.api.BeforeAll;
import org.refkv.vzip.Policy;
import org.refkv.vzip.ReferenceStore;
import org.refkv.vzip.VzipArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** mrc_chunk_rows.mrc: a 1000 x 1100 int8 image, in chunks of 550 rows, after a 1024-byte header. */
class RefkvTest {
    static final Path FIXTURES = Path.of(System.getProperty("refkv.fixtures", "../refkv/fixtures")).toAbsolutePath().normalize();
    static final int HEADER = 1024, ROWS = 550, WIDTH = 1000;
    static Path mrc;
    static byte[] mrcBytes;

    @BeforeAll
    static void load() throws Exception {
        mrc = FIXTURES.resolve("mrc/mrc_chunk_rows.mrc");
        mrcBytes = Files.readAllBytes(mrc);
    }

    static byte[] chunk(int i) {
        return Arrays.copyOfRange(mrcBytes, HEADER + i * ROWS * WIDTH, HEADER + (i + 1) * ROWS * WIDTH);
    }

    static PhkvStore zarrified(Path dir, Zarrify.Container container) {
        Path out = dir.resolve("mrc." + container.name().toLowerCase());
        Zarrify.Result r = Zarrify.convert(mrc.toString(), out.toString(), Zarrify.options().format(container));
        assertTrue(r.summaryJson().contains("\"format\": \"mrc\""), r.summaryJson());
        return PhkvStore.open(out.toString());
    }

    @Test
    void readsChunksFromTheOriginalFile(@TempDir Path dir) {
        try (PhkvStore s = zarrified(dir, Zarrify.Container.PHKV)) {
            assertEquals(ReferenceStore.Kind.REFERENCE, s.classify("0/c/0/0"));
            assertEquals(ReferenceStore.Kind.BYTES, s.classify("zarr.json"));
            assertEquals(ReferenceStore.Kind.MISSING, s.classify("nope"));
            assertEquals(ROWS * WIDTH, s.size("0/c/1/0"));
            assertEquals(-1, s.size("nope"));
            assertArrayEquals(chunk(0), s.get("0/c/0/0"));
            assertArrayEquals(chunk(1), s.get("0/c/1/0"));
            assertArrayEquals(Arrays.copyOfRange(chunk(1), 100, 300), s.get("0/c/1/0", 100, 200));
            assertEquals(10, s.get("0/c/1/0", ROWS * WIDTH - 10, 500).length, "a range is clipped to the value's end");
            assertNull(s.get("nope"));
            assertTrue(new String(s.get("zarr.json")).contains("\"zarr_format\":3"));
            List<String> keys = s.list("0/");
            assertEquals(List.of("0/c/0/0", "0/c/1/0", "0/zarr.json"), keys);
            assertEquals(s.keyCount(), s.list("").size() + 0L);
            assertTrue(s.entryInfo("0/c/0/0").contains("\"reference\""));
        }
    }

    @Test
    void readsAHybridArchive(@TempDir Path dir) {
        try (PhkvStore s = zarrified(dir, Zarrify.Container.HYBRID)) {
            assertArrayEquals(chunk(1), s.get("0/c/1/0"));
        }
    }

    @Test
    void n5ReadsTheZarrifiedFile(@TempDir Path dir) throws Exception {
        try (PhkvStore s = zarrified(dir, Zarrify.Container.PHKV)) {
            N5Reader reader = new ZarrV3KeyValueReader(new RefkvKeyValueAccess(s), "", new GsonBuilder(), false);
            assertTrue(reader.exists("0"));
            assertTrue(reader.datasetExists("0"));
            assertFalse(reader.datasetExists("nope"));
            assertTrue(Arrays.asList(reader.list("")).contains("0"));

            DatasetAttributes attrs = reader.getDatasetAttributes("0");
            assertEquals(2, attrs.getNumDimensions());
            assertEquals(WIDTH * 1100L, attrs.getDimensions()[0] * attrs.getDimensions()[1]);

            // N5 lists the fastest axis first: a block is at (x, y) = (0, 0), (0, 1)
            for (int i = 0; i < 2; i++) {
                DataBlock<?> block = reader.readBlock("0", attrs, 0, i);
                assertNotNull(block, "block " + i);
                assertArrayEquals(chunk(i), (byte[]) block.getData(), "block " + i);
            }
        }
    }

    @Test
    void n5ReadsTheZarrifiedFileThroughAVzipArchive(@TempDir Path dir) throws Exception {
        // the default container is a plain vzip archive, which the Java reader opens without the native library's reader
        Path out = dir.resolve("mrc.vzip");
        Zarrify.convert(mrc.toString(), out.toString());
        Policy policy = Policy.filesBelow(FIXTURES);
        try (VzipArchive a = VzipArchive.open(out, policy)) {
            N5Reader reader = new ZarrV3KeyValueReader(new RefkvKeyValueAccess(a), "", new GsonBuilder(), false);
            DatasetAttributes attrs = reader.getDatasetAttributes("0");
            for (int i = 0; i < 2; i++) {
                assertArrayEquals(chunk(i), (byte[]) reader.readBlock("0", attrs, 0, i).getData(), "block " + i);
            }
            assertTrue(Arrays.asList(reader.list("")).contains("0"));
        }
        try (VzipArchive a = VzipArchive.open(out, Policy.defaults())) {
            N5Reader reader = new ZarrV3KeyValueReader(new RefkvKeyValueAccess(a), "", new GsonBuilder(), false);
            DatasetAttributes attrs = reader.getDatasetAttributes("0");
            assertThrows(N5Exception.N5IOException.class, () -> reader.readBlock("0", attrs, 0, 0),
                    "the default policy refuses the file: source the archive names");
        }
    }

    @Test
    void aStoreIsReadOnly(@TempDir Path dir) {
        try (PhkvStore s = zarrified(dir, Zarrify.Container.PHKV)) {
            RefkvKeyValueAccess kva = new RefkvKeyValueAccess(s);
            assertThrows(N5Exception.N5IOException.class, () -> kva.createDirectories("x"));
            assertThrows(N5Exception.N5IOException.class, () -> kva.delete("0"));
            assertThrows(N5Exception.N5NoSuchKeyException.class, () -> kva.size("nope"));
            assertTrue(kva.isDirectory("0") && !kva.isFile("0"));
            assertTrue(kva.isFile("0/zarr.json") && !kva.isDirectory("0/zarr.json"));
            assertTrue(kva.exists("/0/c/../zarr.json"), "paths are normalized");
            assertArrayEquals(new String[] {"c", "zarr.json"}, kva.list("0"));
            assertArrayEquals(new String[] {"c"}, kva.listDirectories("0"));
        }
    }

    @Test
    void rejectsAnInputItCannotZarrify(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("junk.bin");
        Files.write(junk, "not an image".getBytes());
        RefkvException e = assertThrows(RefkvException.class, () -> Zarrify.convert(junk.toString(), dir.resolve("o").toString()));
        assertTrue(e.isRejected(), e.getMessage());
        assertFalse(Files.exists(dir.resolve("o")));
    }

    @Test
    void reportsAMissingInputAndAMissingStore(@TempDir Path dir) {
        assertThrows(RefkvException.class, () -> Zarrify.convert(dir.resolve("absent.tif").toString(), dir.resolve("o").toString()));
        assertThrows(RefkvException.class, () -> PhkvStore.open(dir.resolve("absent.phkv").toString()));
    }

    @Test
    void aClosedStoreRefusesReads(@TempDir Path dir) {
        PhkvStore s = zarrified(dir, Zarrify.Container.PHKV);
        s.close();
        s.close(); // idempotent
        assertThrows(IllegalStateException.class, () -> s.get("zarr.json"));
    }

    @Test
    void readsFromSeveralThreads(@TempDir Path dir) throws Exception {
        try (PhkvStore s = zarrified(dir, Zarrify.Container.PHKV)) {
            var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int t = 0; t < 32; t++) {
                    final int i = t % 2;
                    futures.add(pool.submit(() -> assertArrayEquals(chunk(i), s.get("0/c/" + i + "/0"))));
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
