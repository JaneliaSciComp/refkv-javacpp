package org.refkv.n5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.refkv.javacpp.RefkvKeyValueAccess;
import org.refkv.javacpp.Zarrify;
import org.refkv.vzip.Policy;
import org.refkv.vzip.VzipArchive;

/**
 * H.264 video through the {@code refkv.stream} codec: an MP4 file is zarrified, and N5 reads its planes, decoding each chunk's frames
 * with FFmpeg. The expected planes are what the FFmpeg command-line tool decodes the original file to (frame by frame, unscaled, in
 * presentation order), as the SHA-256 of each plane's frames; they are not made by the code under test.
 */
class StreamCodecTest {

	static final Path FIXTURES = Path.of(System.getProperty("refkv.fixtures", "../refkv/fixtures")).toAbsolutePath().normalize();

	static final int WIDTH = 64, HEIGHT = 48, FRAMES = 30;

	/** {@code ffmpeg -i FILE -an -f rawvideo -pix_fmt yuv420p -fps_mode passthrough}, split into planes: both files give the same frames. */
	static final String Y = "de7456b413d38987d0318da1203d72928a84fabab69e3dcc1e363c35e0f1f86c";

	static final String U = "f7843037acbbc9a554da7af0b4f05f9a9f48835f43f87a6d3045089c87c975f0";

	static final String V = "b4022fac07106bedaec8673bed43edb962b1cb11d4d1afc40c8e6d4bac8f7ca2";

	@TempDir
	Path dir;

	/** The array of a plane, read block by block through N5, in the layout of the array: (time, y, x), x fastest. */
	private static byte[] readPlane(final N5Reader n5, final String path, final int width, final int height, final int frames) {

		final DatasetAttributes attributes = n5.getDatasetAttributes(path);
		assertNotNull(attributes, path + " is a dataset for N5, so its codec is known");
		assertArrayEquals(new long[] {width, height, frames}, attributes.getDimensions());
		final int[] block = attributes.getBlockSize();
		final byte[] plane = new byte[width * height * frames];
		for (long bt = 0; bt * block[2] < frames; bt++) {
			for (long by = 0; by * block[1] < height; by++) {
				for (long bx = 0; bx * block[0] < width; bx++) {
					final DataBlock<?> b = n5.readBlock(path, attributes, bx, by, bt);
					assertNotNull(b, "block " + bx + "," + by + "," + bt + " of " + path);
					final byte[] data = (byte[])b.getData();
					for (int t = 0; t < block[2]; t++)
						for (int y = 0; y < block[1]; y++)
							for (int x = 0; x < block[0]; x++) {
								final long gx = bx * block[0] + x, gy = by * block[1] + y, gt = bt * block[2] + t;
								if (gx < width && gy < height && gt < frames)
									plane[(int)(gx + gy * width + gt * width * height)] = data[x + y * block[0] + t * block[0] * block[1]];
							}
				}
			}
		}
		return plane;
	}

	private static String sha256(final byte[] bytes) throws Exception {

		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private void assertPlanes(final String fixture) throws Exception {

		final Path in = FIXTURES.resolve("mp4").resolve(fixture);
		final Path vzip = dir.resolve(fixture + ".vzip");
		Zarrify.convert(in.toString(), vzip.toString());
		try (VzipArchive archive = VzipArchive.open(vzip, Policy.filesBelow(FIXTURES))) {
			final String track = archive.list("tracks/").stream().filter(k -> k.endsWith("/y/zarr.json")).findFirst().orElseThrow()
					.split("/")[1];
			final String zarrJson = new String(archive.get("tracks/" + track + "/y/zarr.json"));
			assumeTrue(!zarrJson.contains("rectilinear") || rectilinearSupported(),
					"this array is on a rectilinear chunk grid, which needs n5-zarr with rectilinear support");
			assertEquals(true, zarrJson.contains("refkv.stream"), "the planes are coded as a stream");

			final N5Reader n5 = new ZarrV3KeyValueReader(new RefkvKeyValueAccess(archive), "", new GsonBuilder(), false);
			assertEquals(Y, sha256(readPlane(n5, "tracks/" + track + "/y", WIDTH, HEIGHT, FRAMES)), fixture + ": plane y");
			assertEquals(U, sha256(readPlane(n5, "tracks/" + track + "/u", WIDTH / 2, HEIGHT / 2, FRAMES)), fixture + ": plane u");
			assertEquals(V, sha256(readPlane(n5, "tracks/" + track + "/v", WIDTH / 2, HEIGHT / 2, FRAMES)), fixture + ": plane v");
		}
	}

	private static boolean rectilinearSupported() {

		try {
			Class.forName("org.janelia.saalfeldlab.n5.zarr.chunks.RectilinearChunkGrid");
			return true;
		} catch (final ClassNotFoundException e) {
			return false;
		}
	}

	@Test
	void decodesTheThreePlanesOfAnH264Video() throws Exception {

		assertPlanes("mp4_h264_aac.mp4");
	}

	@Test
	void decodesVideoWhosePicturesAreReorderedAgainstTheirFrames() throws Exception {

		// negative composition offsets: the decoder emits pictures in presentation order, after a delay
		assertPlanes("mov_h264_negative_ctts_aac.mov");
	}

	// ---- what the codec refuses, which it says when the array is opened ----

	private static void assertRefused(final Class<? extends RuntimeException> kind, final String format, final String plane,
			final DataType type, final int[] blockSize, final String reason) {

		final RuntimeException e = assertThrows(kind, () -> new StreamBlockCodecInfo(format, null, plane).create(type, blockSize),
				reason);
		assertEquals(true, e.getMessage().contains("refkv.stream"), e.getMessage());
	}

	@Test
	void refusesWhatItDoesNotDecode() {

		final int[] shape = {WIDTH, HEIGHT, FRAMES};
		assertRefused(UnsupportedOperationException.class, "mp3", null, DataType.FLOAT32, new int[] {1152, 2}, "audio is not decoded yet");
		assertRefused(UnsupportedOperationException.class, "nonsense", "y", DataType.UINT8, shape, "an unknown format");
		assertRefused(UnsupportedOperationException.class, null, "y", DataType.UINT8, shape, "no format");
		assertRefused(UnsupportedOperationException.class, "h264", "y", DataType.UINT16, shape, "10-bit samples");
		assertRefused(IllegalArgumentException.class, "h264", "w", DataType.UINT8, shape, "a plane that is not y, u or v");
		assertRefused(IllegalArgumentException.class, "h264", null, DataType.UINT8, shape, "no plane");
		assertRefused(IllegalArgumentException.class, "h264", "y", DataType.UINT8, new int[] {WIDTH, HEIGHT}, "a flat array");
		assertThrows(UnsupportedOperationException.class,
				() -> new StreamBlockCodecInfo("h264", null, "y").create(DataType.UINT8, shape, new org.janelia.saalfeldlab.n5.GzipCompression()),
				"a codec after the stream");
	}
}
