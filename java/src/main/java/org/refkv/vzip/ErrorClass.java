package org.refkv.vzip;

/** The classes of error of spec/refkv.md section 6.4. */
public enum ErrorClass {
    /** The store is not a valid one (everything section 6.1 checks): opening fails. */
    ARCHIVE,
    /** The key's own record breaks a rule of the binding: classify, get and raw fail for that key. */
    ENTRY,
    /** The bytes the store holds under a key cannot be read as the binding requires. */
    BODY,
    /** A reference payload is malformed: get fails for that key. */
    PAYLOAD,
    /** A source cannot be read, or its policy or pins refuse it: get fails for the ranges that need it. */
    RESOLUTION,
    /** {@code start > end}, or a request that exceeds a resource limit. */
    REQUEST
}
