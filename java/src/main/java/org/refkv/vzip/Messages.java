package org.refkv.vzip;

import java.util.ArrayList;
import java.util.List;

/** The messages of spec/refkv.md section 4 and 5 and spec/proto/vzip.proto, decoded strictly. */
final class Messages {
    private Messages() {}

    /**
     * A Range (section 4.2). {@code data} is non-null for a literal range. {@code crc32c} is -1 when absent.
     * {@code source} is an unsigned 32-bit value; {@code offset} and {@code length} are unsigned 64-bit.
     */
    record Range(long source, long offset, long length, byte[] data, long crc32c) {
        boolean literal() {
            return data != null;
        }

        /** The range's size: a literal's length, or {@code length}; unsigned. */
        long size() {
            return data != null ? data.length : length;
        }

        static Range decode(Wire w) throws Wire.Malformed {
            long source = 0, offset = 0, length = 0, crc = -1;
            byte[] data = null;
            while (w.next()) {
                switch (w.field()) {
                    case 1 -> source = w.uint32();
                    case 3 -> offset = w.integer();
                    case 4 -> length = w.integer();
                    case 5 -> data = w.bytes();
                    case 6 -> crc = w.uint32();
                    default -> w.skip(); // includes the reserved field 2
                }
            }
            if (data != null && (source != 0 || offset != 0 || length != 0)) {
                throw new Wire.Malformed("a literal range with a source, offset or length");
            }
            if (data != null && crc >= 0) {
                throw new Wire.Malformed("a literal range with a crc32c");
            }
            if (data == null && Long.compareUnsigned(offset + length, offset) < 0) {
                throw new Wire.Malformed("offset + length exceeds 2^64 - 1");
            }
            return new Range(source, offset, length, data, crc);
        }
    }

    /** Decodes a payload that is a Range (kind 0x7A76). */
    static List<Range> decodeRange(byte[] payload) throws Wire.Malformed {
        return List.of(Range.decode(new Wire(payload)));
    }

    /** Decodes a payload that is a Concat (kind 0x7A77). */
    static List<Range> decodeConcat(byte[] payload) throws Wire.Malformed {
        Wire w = new Wire(payload);
        List<Range> parts = new ArrayList<>();
        while (w.next()) {
            if (w.field() == 1) {
                parts.add(Range.decode(w.message()));
            } else {
                w.skip();
            }
        }
        return parts;
    }

    enum SourceKind { URL, KEY, DATA }

    /** A source (section 5). Pins are null when absent; they are allowed only on URL sources. */
    record Source(SourceKind kind, String text, byte[] data, Long size, String etag, Long modifiedNotAfter) {
        static Source decode(Wire w) throws Wire.Malformed {
            SourceKind kind = null;
            String text = null;
            byte[] data = null;
            Long size = null, mna = null;
            String etag = null;
            while (w.next()) {
                switch (w.field()) {
                    case 1 -> { kind = SourceKind.URL; text = w.string(); data = null; }
                    case 2 -> { kind = SourceKind.KEY; text = w.string(); data = null; }
                    case 3 -> { kind = SourceKind.DATA; data = w.bytes(); text = null; }
                    case 4 -> size = w.integer();
                    case 5 -> etag = w.string();
                    case 6 -> mna = w.integer();
                    default -> w.skip();
                }
            }
            if (kind == null) {
                throw new Wire.Malformed("a source with no kind");
            }
            if (kind != SourceKind.URL && (size != null || etag != null || mna != null)) {
                throw new Wire.Malformed("a pin on a " + kind.name().toLowerCase() + " source");
            }
            if (kind != SourceKind.DATA && text.isEmpty()) {
                throw new Wire.Malformed("an empty " + kind.name().toLowerCase());
            }
            if (etag != null && !strongEtag(etag)) {
                throw new Wire.Malformed("etag pin " + etag + " is not a strong entity tag");
            }
            return new Source(kind, text, data, size, etag, mna);
        }
    }

    /** {@code DQUOTE *etagc DQUOTE} (RFC 9110 section 8.8.3): etagc is any ASCII character from ! to ~ except a quote. */
    static boolean strongEtag(String s) {
        int n = s.length();
        if (n < 2 || s.charAt(0) != '"' || s.charAt(n - 1) != '"') {
            return false;
        }
        for (int i = 1; i < n - 1; i++) {
            char c = s.charAt(i);
            if (c == '"' || c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    /** Decodes {@code __vz__/sources}' SourceTable; the revision is dropped, readers only report it. */
    static List<Source> decodeSourceTable(byte[] b, int maxSources) throws Wire.Malformed {
        Wire w = new Wire(b);
        List<Source> out = new ArrayList<>();
        while (w.next()) {
            switch (w.field()) {
                case 1 -> {
                    if (out.size() >= maxSources) {
                        throw new Wire.Malformed("more than " + maxSources + " sources");
                    }
                    out.add(Source.decode(w.message()));
                }
                case 2 -> w.uint32();
                default -> w.skip();
            }
        }
        return out;
    }

    record Page(String firstKey, long offset, long length) {}

    record Pinned(String key, long dataOffset, long size, long csize, long method) {}

    record CdIndex(List<Page> pages, List<Pinned> pinned) {
        static CdIndex decode(byte[] b) throws Wire.Malformed {
            Wire w = new Wire(b);
            List<Page> pages = new ArrayList<>();
            List<Pinned> pinned = new ArrayList<>();
            while (w.next()) {
                switch (w.field()) {
                    case 1 -> {
                        Wire p = w.message();
                        String key = "";
                        long off = 0, len = 0;
                        while (p.next()) {
                            switch (p.field()) {
                                case 1 -> key = p.string();
                                case 2 -> off = p.integer();
                                case 3 -> len = p.integer();
                                default -> p.skip();
                            }
                        }
                        pages.add(new Page(key, off, len));
                    }
                    case 2 -> {
                        Wire p = w.message();
                        String key = "";
                        long off = 0, size = 0, csize = 0, method = 0;
                        while (p.next()) {
                            switch (p.field()) {
                                case 1 -> key = p.string();
                                case 2 -> off = p.integer();
                                case 3 -> size = p.integer();
                                case 4 -> csize = p.integer();
                                case 5 -> method = p.uint32();
                                default -> p.skip();
                            }
                        }
                        pinned.add(new Pinned(key, off, size, csize, method));
                    }
                    default -> w.skip();
                }
            }
            return new CdIndex(pages, pinned);
        }
    }
}
