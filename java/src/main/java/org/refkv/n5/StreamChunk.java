package org.refkv.n5;

/**
 * A chunk of a coded stream, as refkv's {@code refkv.stream} codec stores it (spec/conventions.md section 10): a header, then the
 * frames, in decode order.
 *
 * <ul>
 * <li>the count of frames, a varint;</li>
 * <li>for each frame, its size (a varint) and its position (a zigzag varint of the difference from the position of the frame before,
 * the first frame's from -1): the row of the chunk its output goes to, or -1 for a frame decoded only to bring the decoder to the
 * state the next ones need (the <em>lead-in</em>), whose output is dropped;</li>
 * <li>the frames, one after the other.</li>
 * </ul>
 *
 * A varint is a protobuf's: seven bits a byte, the least significant first, the high bit set on every byte but the last.
 */
public final class StreamChunk {

	private final byte[] bytes;

	private final int[] offset;

	private final int[] size;

	private final long[] position;

	private StreamChunk(final byte[] bytes, final int[] offset, final int[] size, final long[] position) {

		this.bytes = bytes;
		this.offset = offset;
		this.size = size;
		this.position = position;
	}

	/**
	 * @param bytes the chunk's bytes
	 * @return the chunk
	 * @throws IllegalArgumentException
	 *             if the header ends inside a varint, or its sizes do not add up to the bytes after it
	 */
	public static StreamChunk parse(final byte[] bytes) {

		final int[] at = {0};
		final long count = varint(bytes, at);
		// each frame costs at least two bytes of header, so a count past that is not a count
		if (count > bytes.length / 2)
			throw new IllegalArgumentException("the header claims " + count + " frames in a chunk of " + bytes.length + " bytes");
		final int n = (int)count;
		final int[] size = new int[n];
		final long[] position = new long[n];
		long previous = -1, total = 0;
		for (int i = 0; i < n; i++) {
			final long s = varint(bytes, at);
			if (s > Integer.MAX_VALUE)
				throw new IllegalArgumentException("a frame of " + s + " bytes");
			size[i] = (int)s;
			final long zigzag = varint(bytes, at);
			previous += (zigzag >>> 1) ^ -(zigzag & 1);
			position[i] = previous;
			total += s;
		}
		if (total != bytes.length - at[0])
			throw new IllegalArgumentException("the frames' sizes add up to " + total + " bytes, and " + (bytes.length - at[0]) + " follow the header");
		final int[] offset = new int[n];
		int o = at[0];
		for (int i = 0; i < n; i++) {
			offset[i] = o;
			o += size[i];
		}
		return new StreamChunk(bytes, offset, size, position);
	}

	private static long varint(final byte[] b, final int[] at) {

		long value = 0;
		for (int shift = 0; shift < 64; shift += 7) {
			if (at[0] >= b.length)
				throw new IllegalArgumentException("the header ends inside a varint");
			final int c = b[at[0]++] & 0xff;
			value |= (long)(c & 0x7f) << shift;
			if ((c & 0x80) == 0)
				return value;
		}
		throw new IllegalArgumentException("a varint of more than 64 bits");
	}

	public int frames() {

		return size.length;
	}

	/** @return the bytes of the chunk, which the frames are in */
	public byte[] bytes() {

		return bytes;
	}

	/** @return the offset of a frame in {@link #bytes()} */
	public int offset(final int frame) {

		return offset[frame];
	}

	public int size(final int frame) {

		return size[frame];
	}

	/** @return the row a frame's output goes to, or -1 if it is dropped */
	public long position(final int frame) {

		return position[frame];
	}
}
