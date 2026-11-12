package org.refkv.vzip;

/** The pins of a {@code url} source (spec/refkv.md section 5.1); a pin is null when absent. */
record Pins(Long size, String etag, Long modifiedNotAfter) {
    static final Pins NONE = new Pins(null, null, null);

    boolean any() {
        return size != null || etag != null || modifiedNotAfter != null;
    }
}
