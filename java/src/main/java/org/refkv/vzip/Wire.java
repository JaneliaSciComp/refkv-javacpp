package org.refkv.vzip;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A strict Protocol Buffers reader for refkv's messages (spec/refkv.md section 4.1): wire types 3, 4, 6 and 7,
 * field number 0 or above 2^29 - 1, truncation, varints over 10 bytes or 64 bits, and strings that are not
 * valid UTF-8 (RFC 3629) are all malformed.
 */
final class Wire {
    /** A message that breaks section 4.1. */
    static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String m) {
            super(m, null, false, false);
        }
    }

    static final int VARINT = 0, I64 = 1, LEN = 2, I32 = 5;

    private final byte[] b;
    private int pos;
    private final int end;
    private int wire;
    private int field;

    Wire(byte[] b) {
        this(b, 0, b.length);
    }

    Wire(byte[] b, int from, int to) {
        this.b = b;
        this.pos = from;
        this.end = to;
    }

    boolean next() throws Malformed {
        if (pos >= end) {
            return false;
        }
        long tag = varint();
        long f = tag >>> 3;
        wire = (int) (tag & 7);
        if (f == 0 || f > (1L << 29) - 1) {
            throw new Malformed("field number " + f);
        }
        field = (int) f;
        if (wire != VARINT && wire != I64 && wire != LEN && wire != I32) {
            throw new Malformed("wire type " + wire);
        }
        return true;
    }

    int field() {
        return field;
    }

    int wire() {
        return wire;
    }

    long varint() throws Malformed {
        long v = 0;
        for (int i = 0; i < 10; i++) {
            if (pos >= end) {
                throw new Malformed("truncated varint");
            }
            int c = b[pos++] & 0xff;
            if (i == 9 && c > 1) {
                throw new Malformed("varint over 64 bits");
            }
            v |= (long) (c & 0x7f) << (7 * i);
            if ((c & 0x80) == 0) {
                return v;
            }
        }
        throw new Malformed("varint longer than 10 bytes");
    }

    /** The value of the current field, which must be a varint (an integer field). */
    long integer() throws Malformed {
        expect(VARINT);
        return varint();
    }

    long uint32() throws Malformed {
        long v = integer();
        if (v < 0 || v > 0xFFFF_FFFFL) {
            throw new Malformed("uint32 field " + field + " out of range");
        }
        return v;
    }

    private void expect(int w) throws Malformed {
        if (wire != w) {
            throw new Malformed("field " + field + " has wire type " + wire + ", not " + w);
        }
    }

    /** The bytes of the current field, which must be LEN. */
    byte[] bytes() throws Malformed {
        int[] r = lenRegion();
        return java.util.Arrays.copyOfRange(b, r[0], r[1]);
    }

    /** The region {@code [from, to)} of the current LEN field, within the enclosing message. */
    int[] lenRegion() throws Malformed {
        expect(LEN);
        long n = varint();
        if (n < 0 || n > end - pos) {
            throw new Malformed("field " + field + " extends past its message");
        }
        int from = pos;
        pos += (int) n;
        return new int[] {from, pos};
    }

    String string() throws Malformed {
        int[] r = lenRegion();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, r[0], r[1] - r[0]))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new Malformed("field " + field + " is not valid UTF-8");
        }
    }

    /** A reader over the current LEN field, which holds a message. */
    Wire message() throws Malformed {
        int[] r = lenRegion();
        return new Wire(b, r[0], r[1]);
    }

    /** Skips the current field (an unknown one). */
    void skip() throws Malformed {
        switch (wire) {
            case VARINT -> varint();
            case I64 -> advance(8);
            case I32 -> advance(4);
            default -> lenRegion();
        }
    }

    private void advance(int n) throws Malformed {
        if (end - pos < n) {
            throw new Malformed("truncated field " + field);
        }
        pos += n;
    }
}
