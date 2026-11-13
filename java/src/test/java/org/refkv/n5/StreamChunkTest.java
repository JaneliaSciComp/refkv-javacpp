package org.refkv.n5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/** The header of a chunk of a coded stream (spec/conventions.md section 10). */
class StreamChunkTest {

	private static void varint(final ByteArrayOutputStream o, long v) {

		while ((v & ~0x7fL) != 0) {
			o.write((int)((v & 0x7f) | 0x80));
			v >>>= 7;
		}
		o.write((int)v);
	}

	private static void zigzag(final ByteArrayOutputStream o, final long d) {

		varint(o, d >= 0 ? 2 * d : -2 * d - 1);
	}

	/** A chunk: the frames' sizes and positions, then the frames, each filled with its own index. */
	private static byte[] chunk(final int[] sizes, final long[] positions) {

		final ByteArrayOutputStream o = new ByteArrayOutputStream();
		varint(o, sizes.length);
		long previous = -1;
		for (int i = 0; i < sizes.length; i++) {
			varint(o, sizes[i]);
			zigzag(o, positions[i] - previous);
			previous = positions[i];
		}
		for (int i = 0; i < sizes.length; i++)
			for (int b = 0; b < sizes[i]; b++)
				o.write(i + 1);
		return o.toByteArray();
	}

	@Test
	void readsTheFramesAndWhereTheyGo() {

		// a lead-in frame (position -1), then pictures that arrive out of order, as with B-frames: 0, 2, 1, 3
		final int[] sizes = {5, 3, 130, 1, 200};
		final long[] positions = {-1, 0, 2, 1, 3};
		final StreamChunk c = StreamChunk.parse(chunk(sizes, positions));
		assertEquals(5, c.frames());
		for (int i = 0; i < sizes.length; i++) {
			assertEquals(sizes[i], c.size(i), "size of frame " + i);
			assertEquals(positions[i], c.position(i), "position of frame " + i);
			assertEquals((byte)(i + 1), c.bytes()[c.offset(i)], "first byte of frame " + i);
			assertEquals((byte)(i + 1), c.bytes()[c.offset(i) + sizes[i] - 1], "last byte of frame " + i);
		}
		assertEquals(c.offset(0) + 5, c.offset(1));
		assertEquals(c.bytes().length, c.offset(4) + 200);
	}

	@Test
	void anEmptyChunkHasNoFrames() {

		final StreamChunk c = StreamChunk.parse(new byte[] {0});
		assertEquals(0, c.frames());
	}

	@Test
	void positionsThatStepBackAreDecodedFromTheirZigzag() {

		final StreamChunk c = StreamChunk.parse(chunk(new int[] {1, 1, 1, 1}, new long[] {10, 7, 300, -1}));
		assertArrayEquals(new long[] {10, 7, 300, -1}, new long[] {c.position(0), c.position(1), c.position(2), c.position(3)});
	}

	@Test
	void refusesAHeaderThatEndsInsideAVarint() {

		assertThrows(IllegalArgumentException.class, () -> StreamChunk.parse(new byte[0]));
		assertThrows(IllegalArgumentException.class, () -> StreamChunk.parse(new byte[] {(byte)0x81}));
		// one frame, whose size starts a varint that never ends
		assertThrows(IllegalArgumentException.class, () -> StreamChunk.parse(new byte[] {1, (byte)0x80}));
		// one frame of size 1, whose position is missing
		assertThrows(IllegalArgumentException.class, () -> StreamChunk.parse(new byte[] {1, 1}));
	}

	@Test
	void refusesSizesThatDoNotAddUp() {

		final byte[] good = chunk(new int[] {4, 4}, new long[] {0, 1});
		final byte[] shorter = java.util.Arrays.copyOf(good, good.length - 1);
		final byte[] longer = java.util.Arrays.copyOf(good, good.length + 1);
		assertEquals(2, StreamChunk.parse(good).frames());
		final IllegalArgumentException tooFew = assertThrows(IllegalArgumentException.class, () -> StreamChunk.parse(shorter));
		assertTrue(tooFew.getMessage().contains("add up"), tooFew.getMessage());
		assertThrows(IllegalArgumentException.class, () -> StreamChunk.parse(longer));
	}

	@Test
	void refusesACountThatCannotBeTrue() {

		final ByteArrayOutputStream o = new ByteArrayOutputStream();
		varint(o, 1L << 40); // a trillion frames in a few bytes
		o.write(1);
		assertThrows(IllegalArgumentException.class, () -> StreamChunk.parse(o.toByteArray()));
		final ByteArrayOutputStream tooLong = new ByteArrayOutputStream();
		for (int i = 0; i < 11; i++)
			tooLong.write(0x80);
		tooLong.write(0);
		assertThrows(IllegalArgumentException.class, () -> StreamChunk.parse(tooLong.toByteArray()));
	}
}
