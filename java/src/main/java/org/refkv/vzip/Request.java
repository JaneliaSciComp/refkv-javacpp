package org.refkv.vzip;

/** A request of {@code get} (spec/refkv.md section 6.2). Offsets and counts are unsigned. */
public record Request(Type type, long a, long b) {
    public enum Type { WHOLE, RANGE, OFFSET, SUFFIX }

    public static Request whole() {
        return new Request(Type.WHOLE, 0, 0);
    }

    /** Bytes {@code [start, end)}. */
    public static Request range(long start, long end) {
        return new Request(Type.RANGE, start, end);
    }

    /** From {@code start} to the end. */
    public static Request offset(long start) {
        return new Request(Type.OFFSET, start, 0);
    }

    /** The last {@code count} bytes. */
    public static Request suffix(long count) {
        return new Request(Type.SUFFIX, count, 0);
    }

    /** The window {@code [a, b)} of a value of {@code n} bytes. */
    long[] window(long n) {
        return switch (type) {
            case WHOLE -> new long[] {0, n};
            case RANGE -> new long[] {Math.min(a, n), Math.min(b, n)};
            case OFFSET -> new long[] {Math.min(a, n), n};
            case SUFFIX -> new long[] {Math.max(n - a, 0), n};
        };
    }
}
