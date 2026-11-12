package org.refkv.vzip;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.refkv.javacpp.PhkvStore;
import org.refkv.javacpp.RefkvException;
import org.refkv.javacpp.RefkvKeyValueAccess;
import org.refkv.javacpp.Zarrify;

/**
 * A hybrid archive is both a vzip archive and a phkv store, so one file can be read by this Java vzip reader and by
 * refkv's Rust phkv reader. They must agree on every key.
 */
class CrossCheckTest {
    static final Path FIXTURES = Path.of(System.getProperty("refkv.fixtures", "../refkv/fixtures")).toAbsolutePath().normalize();
    @TempDir Path dir;

    private static List<Path> candidates() throws Exception {
        List<Path> out = new ArrayList<>();
        for (String d : List.of("mrc", "hdf5", "nifti", "npy", "flac", "mp3", "mp4", "fits", "nrrd", "czi", "nd2", "tiff", "qptiff", "dicom", "ims", "lif", "grib", "safetensors")) {
            Path p = FIXTURES.resolve(d);
            if (!Files.isDirectory(p)) {
                continue;
            }
            try (Stream<Path> files = Files.list(p)) {
                files.filter(Files::isRegularFile)
                        .filter(f -> !f.getFileName().toString().contains("reject") && !f.getFileName().toString().contains("edge"))
                        .sorted()
                        .limit(4)
                        .forEach(out::add);
            }
        }
        return out;
    }

    private Path zarrify(Path in, int n) {
        Path out = dir.resolve("f" + n + ".vzip");
        try {
            Zarrify.convert(in.toString(), out.toString(), Zarrify.options().format(Zarrify.Container.HYBRID));
            return out;
        } catch (RefkvException e) {
            if (e.isRejected()) {
                return null; // a fixture refkv does not zarrify: that is what its rejection says
            }
            throw e; // anything else is a mistake of ours, not to be skipped
        }
    }

    private static byte[] nativeOrNull(PhkvStore s, String key) {
        try {
            return s.unsafeNativeGet(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] getOrNull(ReferenceStore s, String key) {
        try {
            return s.get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Test
    void theJavaVzipReaderAgreesWithThePhkvReaderAndTheNativeResolver() throws Exception {
        Policy policy = Policy.filesBelow(FIXTURES);
        int converted = 0, keys = 0, references = 0, bothFailed = 0;
        for (Path in : candidates()) {
            Path archive = zarrify(in, converted);
            if (archive == null) {
                continue;
            }
            converted++;
            try (VzipArchive vzip = VzipArchive.open(archive, policy); PhkvStore phkv = PhkvStore.open(archive.toString(), policy)) {
                List<String> vk = vzip.list("");
                assertEquals(phkv.list(""), vk, in + ": the same keys, in the same order");
                for (String k : vk) {
                    keys++;
                    String what = in.getFileName() + " " + k;
                    assertEquals(phkv.classify(k).name(), vzip.classify(k).name(), what);
                    if (vzip.classify(k) == ReferenceStore.Kind.REFERENCE) {
                        references++;
                    }
                    // three resolvers: Java over the vzip directory, Java over the Rust phkv reader, and the Rust resolver itself
                    byte[] a = getOrNull(vzip, k), b = getOrNull(phkv, k), c = nativeOrNull(phkv, k);
                    if (a == null && b == null && c == null) {
                        bothFailed++;
                        continue;
                    }
                    assertNotNull(a, what + ": the Java vzip reader failed where another did not");
                    assertNotNull(b, what + ": the Java phkv reader failed where another did not");
                    assertNotNull(c, what + ": the native resolver failed where another did not");
                    assertArrayEquals(c, a, what + " (vzip against the native resolver)");
                    assertArrayEquals(c, b, what + " (phkv against the native resolver)");
                    assertEquals(c.length, vzip.size(k), what + " size");
                    assertEquals(phkv.size(k), vzip.size(k), what + " size");
                    if (a.length > 3) {
                        assertArrayEquals(vzip.get(k, 1, a.length - 3), phkv.get(k, 1, a.length - 3), what + " range");
                    }
                }
                assertNull(vzip.get("no/such/key"));
                assertNull(phkv.get("no/such/key"));
            }
        }
        assertTrue(converted >= 6, "only " + converted + " fixtures were zarrified; the check proves little");
        assertTrue(references > 0, "no reference was compared");
        System.out.printf("cross-checked %d archives, %d keys (%d references); %d keys failed in all three readers%n", converted, keys, references, bothFailed);
    }

    @Test
    void n5ReadsAForeignFileThroughAVzipArchiveOverHttp() throws Exception {
        Path mrc = FIXTURES.resolve("mrc/mrc_chunk_rows.mrc");
        byte[] mrcBytes = Files.readAllBytes(mrc);
        Path archive = dir.resolve("mrc.vzip");
        Zarrify.convert(mrc.toString(), archive.toString());
        try (RangeServer server = new RangeServer()) {
            server.files.put("/mrc.vzip", Files.readAllBytes(archive));
            server.files.put(mrc.toUri().getRawPath(), mrcBytes); // the archive names its source by absolute path
            Policy loopback = Policy.builder().allowPrivateHosts(true).build();
            try (VzipArchive a = VzipArchive.open(server.url("/mrc.vzip"), loopback)) {
                N5Reader reader = new ZarrV3KeyValueReader(new RefkvKeyValueAccess(a), "", new GsonBuilder(), false);
                DatasetAttributes attrs = reader.getDatasetAttributes("0");
                assertEquals(2, attrs.getNumDimensions());
                server.clearLog();
                for (int i = 0; i < 2; i++) {
                    DataBlock<?> block = reader.readBlock("0", attrs, 0, i);
                    byte[] want = Arrays.copyOfRange(mrcBytes, 1024 + i * 550_000, 1024 + (i + 1) * 550_000);
                    assertArrayEquals(want, (byte[]) block.getData(), "block " + i);
                }
                List<String> reads = server.requests();
                assertEquals(2, reads.size(), "one range request per block: " + reads);
                assertTrue(reads.stream().allMatch(r -> r.startsWith("GET " + mrc.toUri().getRawPath() + " Range=bytes=")), reads.toString());
            }
        }
    }
}
