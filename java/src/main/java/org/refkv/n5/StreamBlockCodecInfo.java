package org.refkv.n5;

import java.util.Base64;
import java.util.Set;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.codec.BlockCodec;
import org.janelia.saalfeldlab.n5.codec.BlockCodecInfo;
import org.janelia.saalfeldlab.n5.codec.DataCodecInfo;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.serialization.NameConfig;

/**
 * refkv's {@code refkv.stream} array-to-bytes codec (spec/conventions.md section 10): a chunk of a video or audio track holds coded
 * frames, which a decoder decodes, in order, to the pictures or samples the chunk holds. refkv writes such chunks without copying the
 * frames: they are ranges of the original file (an MP4 file, say), after a header.
 * <p>
 * Pictures of H.264, HEVC, VP9 and AV1 video are decoded with FFmpeg, through
 * <a href="https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg">JavaCPP's FFmpeg</a>, which an application provides:
 * {@code org.bytedeco:ffmpeg} and the natives of its platform. The array holds one plane of the pictures, {@code y}, {@code u} or
 * {@code v}, as 8-bit samples. Audio formats are not decoded yet.
 * <p>
 * Register by being on the classpath: N5 finds the codec by its name.
 */
@NameConfig.Name(StreamBlockCodecInfo.TYPE)
public class StreamBlockCodecInfo implements BlockCodecInfo {

	private static final long serialVersionUID = 6154378245915604467L;

	public static final String TYPE = "refkv.stream";

	private static final Set<String> VIDEO = Set.of("h264", "hevc", "vp9", "av1");

	/** The coding of the frames: {@code "h264"}, {@code "hevc"}, {@code "vp9"} or {@code "av1"} here. */
	@NameConfig.Parameter(value = "format")
	private final String format;

	/** The decoder's configuration, base64: for H.264, an MP4 file's {@code avcC} box; for HEVC, its {@code hvcC} box. */
	@NameConfig.Parameter(value = "setup", optional = true)
	private final String setup;

	/** For video, the plane of the pictures the array holds: {@code "y"}, {@code "u"} or {@code "v"}. */
	@NameConfig.Parameter(value = "plane", optional = true)
	private final String plane;

	protected StreamBlockCodecInfo() {

		this(null, null, null);
	}

	public StreamBlockCodecInfo(final String format, final String setup, final String plane) {

		this.format = format;
		this.setup = setup;
		this.plane = plane;
	}

	@Override
	public String getType() {

		return TYPE;
	}

	public String getFormat() {

		return format;
	}

	public String getSetup() {

		return setup;
	}

	public String getPlane() {

		return plane;
	}

	@Override
	public <T> BlockCodec<T> create(final DataType dataType, final int[] blockSize, final DataCodecInfo... codecs) {

		if (format == null || !VIDEO.contains(format))
			throw new UnsupportedOperationException("refkv.stream: the format " + format + " is not decoded; this reader decodes " + VIDEO);
		if (!"y".equals(plane) && !"u".equals(plane) && !"v".equals(plane))
			throw new IllegalArgumentException("refkv.stream: the plane of a video array is y, u or v, not " + plane);
		if (dataType != DataType.UINT8)
			throw new UnsupportedOperationException("refkv.stream: only 8-bit samples (uint8) are decoded, not " + dataType);
		if (blockSize.length != 3)
			throw new IllegalArgumentException("refkv.stream: a video array has three axes (time, y, x), not " + blockSize.length);
		if (codecs != null && codecs.length > 0)
			throw new UnsupportedOperationException("refkv.stream: the frames of a chunk are not compressed again, so no bytes-to-bytes codec can follow");
		return new StreamBlockCodec<>(dataType, blockSize, format, setup == null ? null : Base64.getDecoder().decode(setup), plane);
	}

	/** Decodes a chunk's frames to the rows of a block. */
	private static final class StreamBlockCodec<T> implements BlockCodec<T> {

		private final DataType dataType;

		private final int[] blockSize;

		private final String format;

		private final byte[] setup;

		private final String plane;

		StreamBlockCodec(final DataType dataType, final int[] blockSize, final String format, final byte[] setup, final String plane) {

			this.dataType = dataType;
			this.blockSize = blockSize.clone();
			this.format = format;
			this.setup = setup;
			this.plane = plane;
		}

		@Override
		public ReadData encode(final DataBlock<T> dataBlock) {

			throw new UnsupportedOperationException("refkv.stream chunks are made of the frames of a file: they are not written");
		}

		@SuppressWarnings("unchecked")
		@Override
		public DataBlock<T> decode(final ReadData readData, final long[] gridPosition) throws N5Exception.N5IOException {

			final StreamChunk chunk;
			try {
				chunk = StreamChunk.parse(readData.allBytes());
			} catch (final IllegalArgumentException e) {
				throw new N5Exception.N5IOException("refkv.stream: a chunk that is not one: " + e.getMessage(), e);
			}
			final DataBlock<T> block = (DataBlock<T>)dataType.createDataBlock(blockSize, gridPosition);
			// N5 lists the fastest axis first: a block of (x, y, time) is the (time, y, x) of the array
			FfmpegPictureDecoder.decode(format, setup, plane, chunk, blockSize[0], blockSize[1], blockSize[2], (byte[])block.getData());
			return block;
		}
	}
}
