package org.refkv.javacpp;

/** A failure reported by the refkv library: the native status code and its message. */
public class RefkvException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public RefkvException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** A {@code REFKV_*} status: {@code REFKV_REJECTED} for an input the zarrifiers do not accept, and so on. */
    public int status() {
        return status;
    }

    /** Whether the input was one the zarrifiers do not accept ({@code REFKV_REJECTED}). */
    public boolean isRejected() {
        return status == org.refkv.javacpp.global.refkv.REFKV_REJECTED;
    }
}
