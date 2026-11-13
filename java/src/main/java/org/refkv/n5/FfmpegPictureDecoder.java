package org.refkv.n5;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VP9;
import static org.bytedeco.ffmpeg.global.avcodec.AV_INPUT_BUFFER_PADDING_SIZE;
import static org.bytedeco.ffmpeg.global.avcodec.av_new_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_FLAG_PLANAR;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_malloc;
import static org.bytedeco.ffmpeg.global.avutil.av_pix_fmt_desc_get;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVPixFmtDescriptor;
import org.bytedeco.javacpp.BytePointer;
import org.janelia.saalfeldlab.n5.N5Exception;

/**
 * Decodes the frames of a {@link StreamChunk} of video with FFmpeg, and puts one plane of each picture where the chunk says.
 * <p>
 * A fresh decoder takes the frames in order, each as an access unit as an MP4 file holds it (the NAL units of H.264 and HEVC with their
 * lengths in front), set up by the stream's configuration record. It gives its pictures in presentation order, after a delay, so each
 * frame carries its index in the chunk as its timestamp, which FFmpeg hands on to the picture made from it: that is how a picture
 * finds its frame, whatever the order. A frame whose position is -1 is decoded and its picture dropped.
 * <p>
 * Rows no frame fills stay as they are in the array {@code out}, zero: the fill value of the arrays refkv writes.
 */
final class FfmpegPictureDecoder {

	private FfmpegPictureDecoder() {}

	/** What FFmpeg returns when it wants more input: {@code AVERROR(EAGAIN)}, whose errno is 35 on macOS and 11 elsewhere. */
	private static final int AGAIN = System.getProperty("os.name", "").toLowerCase().contains("mac") ? -35 : -11;

	private static int codecId(final String format) {

		switch (format) {
		case "h264":
			return AV_CODEC_ID_H264;
		case "hevc":
			return AV_CODEC_ID_HEVC;
		case "vp9":
			return AV_CODEC_ID_VP9;
		case "av1":
			return AV_CODEC_ID_AV1;
		default:
			throw new UnsupportedOperationException("refkv.stream: the format " + format + " is not decoded");
		}
	}

	private static N5Exception.N5IOException failure(final String what, final int code) {

		final byte[] message = new byte[256];
		av_strerror(code, message, message.length);
		int n = 0;
		while (n < message.length && message[n] != 0)
			n++;
		return new N5Exception.N5IOException("refkv.stream: " + what + ": " + new String(message, 0, n, java.nio.charset.StandardCharsets.UTF_8) + " (" + code + ")");
	}

	/**
	 * @param format  {@code h264}, {@code hevc}, {@code vp9} or {@code av1}
	 * @param setup   the decoder's configuration record (an {@code avcC} box for H.264), or null
	 * @param plane   {@code y}, {@code u} or {@code v}
	 * @param chunk   the frames
	 * @param width   the width of the plane, in samples: the array's last axis
	 * @param height  the height of the plane
	 * @param rows    the number of pictures the chunk holds: the array's first axis
	 * @param out     {@code rows * height * width} 8-bit samples, to fill
	 */
	static void decode(final String format, final byte[] setup, final String plane, final StreamChunk chunk,
			final int width, final int height, final int rows, final byte[] out) {

		final int planeIndex = "y".equals(plane) ? 0 : "u".equals(plane) ? 1 : 2;
		final AVCodec codec = avcodec_find_decoder(codecId(format));
		if (codec == null || codec.isNull())
			throw new N5Exception.N5IOException("refkv.stream: this build of FFmpeg has no " + format + " decoder");

		AVCodecContext context = avcodec_alloc_context3(codec);
		AVPacket packet = av_packet_alloc();
		AVFrame frame = av_frame_alloc();
		try {
			if (setup != null) {
				// FFmpeg frees the configuration with the context, and wants it padded
				final BytePointer extra = new BytePointer(av_malloc(setup.length + AV_INPUT_BUFFER_PADDING_SIZE)).capacity(setup.length + AV_INPUT_BUFFER_PADDING_SIZE);
				extra.zero();
				extra.put(setup, 0, setup.length);
				context.extradata(extra);
				context.extradata_size(setup.length);
			}
			final int opened = avcodec_open2(context, codec, (AVDictionary)null);
			if (opened < 0)
				throw failure("the " + format + " decoder did not open", opened);

			for (int i = 0; i <= chunk.frames(); i++) {
				final int sent;
				if (i < chunk.frames()) {
					av_new_packet(packet, chunk.size(i));
					packet.data().put(chunk.bytes(), chunk.offset(i), chunk.size(i));
					packet.pts(i);
					packet.dts(i);
					sent = avcodec_send_packet(context, packet);
					av_packet_unref(packet);
				} else {
					sent = avcodec_send_packet(context, null); // the end: the pictures the decoder still holds
				}
				if (sent < 0)
					throw failure("frame " + i + " was refused", sent);
				drain(context, frame, chunk, planeIndex, width, height, rows, out);
			}
		} finally {
			av_frame_free(frame);
			av_packet_free(packet);
			avcodec_free_context(context);
		}
	}

	/** Takes every picture the decoder has, and places it. */
	private static void drain(final AVCodecContext context, final AVFrame frame, final StreamChunk chunk, final int planeIndex,
			final int width, final int height, final int rows, final byte[] out) {

		while (true) {
			final int received = avcodec_receive_frame(context, frame);
			if (received == AGAIN || received == AVERROR_EOF)
				return;
			if (received < 0)
				throw failure("a frame could not be decoded", received);
			try {
				place(frame, chunk, planeIndex, width, height, rows, out);
			} finally {
				av_frame_unref(frame);
			}
		}
	}

	private static int shifted(final int size, final int log2) {

		return (size + (1 << log2) - 1) >> log2;
	}

	private static void place(final AVFrame frame, final StreamChunk chunk, final int planeIndex, final int width, final int height,
			final int rows, final byte[] out) {

		final long pts = frame.pts();
		if (pts == AV_NOPTS_VALUE || pts < 0 || pts >= chunk.frames())
			throw new N5Exception.N5IOException("refkv.stream: a picture does not say which frame it comes from (timestamp " + pts + "), so it cannot be placed");
		final long row = chunk.position((int)pts);
		if (row < 0)
			return; // the lead-in: decoded for the pictures after it, and dropped
		if (row >= rows)
			throw new N5Exception.N5IOException("refkv.stream: frame " + pts + " goes to row " + row + " of a chunk of " + rows);

		final AVPixFmtDescriptor desc = av_pix_fmt_desc_get(frame.format());
		if (desc == null || desc.isNull() || (desc.flags() & AV_PIX_FMT_FLAG_PLANAR) == 0 || desc.comp(0).depth() != 8)
			throw new UnsupportedOperationException("refkv.stream: only 8-bit planar pictures are decoded (the picture's pixel format is " + frame.format() + ")");
		if (planeIndex >= desc.nb_components())
			throw new N5Exception.N5IOException("refkv.stream: the pictures have " + desc.nb_components() + " planes, and the array holds plane " + planeIndex);
		final int w = planeIndex == 0 ? frame.width() : shifted(frame.width(), desc.log2_chroma_w());
		final int h = planeIndex == 0 ? frame.height() : shifted(frame.height(), desc.log2_chroma_h());
		if (w != width || h != height)
			throw new N5Exception.N5IOException("refkv.stream: the plane is " + w + " x " + h + " in the picture, and the array's is " + width + " x " + height);

		final BytePointer data = frame.data(planeIndex);
		final int stride = frame.linesize(planeIndex);
		final long base = row * height * width;
		for (int y = 0; y < height; y++)
			data.position((long)y * stride).get(out, Math.toIntExact(base + (long)y * width), width);
	}
}
