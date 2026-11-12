package org.refkv.javacpp;

import static org.refkv.javacpp.global.refkv.*;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.refkv.javacpp.global.refkv.RefkvBuffer;

/** Helpers over the raw bindings. */
final class Native {
    private Native() {}

    /**
     * Throws for a failure status. The message is the native thread's last error, read right after
     * the failing call, so it can be another call's if a virtual thread moves carriers in between.
     */
    static int check(int status) {
        if (status == REFKV_OK || status == REFKV_MISSING || status == REFKV_BYTES || status == REFKV_REFERENCE) {
            return status;
        }
        BytePointer m = refkv_last_error();
        throw new RefkvException(status, m == null || m.isNull() ? "refkv status " + status : m.getString());
    }

    /** Copies out a buffer's bytes and frees it. */
    static byte[] take(RefkvBuffer buffer) {
        try {
            long n = refkv_buffer_len(buffer);
            byte[] out = new byte[Math.toIntExact(n)];
            if (n > 0) {
                refkv_buffer_data(buffer).capacity(n).get(out);
            }
            return out;
        } finally {
            refkv_buffer_free(buffer);
        }
    }

    /** Reads and frees a {@code char*} result. */
    static String takeString(PointerPointer<BytePointer> out) {
        BytePointer p = out.get(BytePointer.class, 0);
        if (p == null || p.isNull()) {
            return null;
        }
        try {
            return p.getString();
        } finally {
            refkv_string_free(p);
        }
    }
}
