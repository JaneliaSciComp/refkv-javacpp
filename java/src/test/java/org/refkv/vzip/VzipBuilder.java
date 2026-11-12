package org.refkv.vzip;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** A minimal writer of vzip archives (spec/bindings/vzip.md section 14), for tests: it can also write what a reader must refuse. */
final class VzipBuilder {
    /** One entry as written. {@code usize} and {@code csize} are what the headers say, which tests may make wrong. */
    static final class Entry {
        String name;
        byte[] body;
        int method;
        long usize, csize;
        byte[] extra = new byte[0];
        int flags = 0x0800;
    }

    final List<Entry> entries = new ArrayList<>();
    private final ByteArrayOutputStream sources = new ByteArrayOutputStream();
    private int sourceCount;
    /** The comment's magic, which tests may corrupt. */
    String magic = "vzip/0";
    /** Whether to write the zip64 locator and record (a valid archive always does). */
    boolean zip64 = true;
    /** A page size of at least 1 writes a page index with pages of about that many bytes of directory; 0 writes none. */
    int pageSize = 0;
    /** Keys to pin in the page index. */
    final List<String> pin = new ArrayList<>();

    // ---- protobuf ----

    static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7fL) != 0) {
            o.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    static void field(ByteArrayOutputStream o, int number, byte[] bytes) {
        varint(o, (long) number << 3 | 2);
        varint(o, bytes.length);
        o.writeBytes(bytes);
    }

    static void field(ByteArrayOutputStream o, int number, long v) {
        varint(o, (long) number << 3);
        varint(o, v);
    }

    /** A source range; {@code crc} is -1 for none. */
    static byte[] range(int source, long offset, long length, long crc) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (source != 0) {
            field(o, 1, source);
        }
        if (offset != 0) {
            field(o, 3, offset);
        }
        if (length != 0) {
            field(o, 4, length);
        }
        if (crc >= 0) {
            field(o, 6, crc);
        }
        return o.toByteArray();
    }

    static byte[] literal(byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        field(o, 5, data);
        return o.toByteArray();
    }

    static byte[] concat(byte[]... ranges) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] r : ranges) {
            field(o, 1, r);
        }
        return o.toByteArray();
    }

    // ---- sources ----

    int urlSource(String url, Long size, String etag, Long mna) {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        field(s, 1, url.getBytes(StandardCharsets.UTF_8));
        if (size != null) {
            field(s, 4, size);
        }
        if (etag != null) {
            field(s, 5, etag.getBytes(StandardCharsets.UTF_8));
        }
        if (mna != null) {
            field(s, 6, mna);
        }
        return add(s);
    }

    int urlSource(String url) {
        return urlSource(url, null, null, null);
    }

    int keySource(String key) {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        field(s, 2, key.getBytes(StandardCharsets.UTF_8));
        return add(s);
    }

    int dataSource(byte[] data) {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        field(s, 3, data);
        return add(s);
    }

    /** A source with exactly these message bytes, valid or not. */
    int rawSource(byte[] message) {
        field(sources, 1, message);
        return sourceCount++;
    }

    private int add(ByteArrayOutputStream source) {
        field(sources, 1, source.toByteArray());
        return sourceCount++;
    }

    // ---- entries ----

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(9, true);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) {
            o.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return o.toByteArray();
    }

    Entry bytes(String key, byte[] data, boolean compress) {
        Entry e = new Entry();
        e.name = key;
        e.usize = data.length;
        e.body = compress ? deflate(data) : data;
        e.method = compress ? 8 : 0;
        e.csize = e.body.length;
        entries.add(e);
        return e;
    }

    Entry bytes(String key, String data) {
        return bytes(key, data.getBytes(StandardCharsets.UTF_8), false);
    }

    /** A reference whose payload is {@code payload}, a Range if {@code single} (extra field 0x7A76) else a Concat (0x7A77); the body mirrors it. */
    Entry ref(String key, byte[] payload, boolean single) {
        Entry e = bytes(key, payload, false);
        e.extra = block(single ? 0x7A76 : 0x7A77, payload);
        return e;
    }

    Entry ref(String key, byte[]... ranges) {
        return ranges.length == 1 ? ref(key, ranges[0], true) : ref(key, concat(ranges), false);
    }

    static byte[] block(int id, byte[] data) {
        byte[] b = new byte[4 + data.length];
        b[0] = (byte) id;
        b[1] = (byte) (id >> 8);
        b[2] = (byte) (data.length);
        b[3] = (byte) (data.length >> 8);
        System.arraycopy(data, 0, b, 4, data.length);
        return b;
    }

    // ---- writing ----

    private static void le(ByteArrayOutputStream o, long v, int bytes) {
        for (int i = 0; i < bytes; i++) {
            o.write((int) (v >>> (8 * i)) & 0xff);
        }
    }

    private static long crc(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return c.getValue();
    }

    private record Written(Entry e, long lho, byte[] cd) {}

    private Written write(ByteArrayOutputStream out, Entry e) {
        long lho = out.size();
        byte[] name = e.name.getBytes(StandardCharsets.UTF_8);
        long crc = crc(e.method == 8 ? inflated(e) : e.body);
        le(out, 0x04034b50L, 4);
        le(out, 45, 2);
        le(out, e.flags, 2);
        le(out, e.method, 2);
        le(out, 0, 2);
        le(out, 0x21, 2);
        le(out, crc, 4);
        le(out, e.csize, 4);
        le(out, e.usize, 4);
        le(out, name.length, 2);
        le(out, 0, 2);
        out.writeBytes(name);
        out.writeBytes(e.body);
        ByteArrayOutputStream cd = new ByteArrayOutputStream();
        le(cd, 0x02014b50L, 4);
        le(cd, 45, 2);
        le(cd, 45, 2);
        le(cd, e.flags, 2);
        le(cd, e.method, 2);
        le(cd, 0, 2);
        le(cd, 0x21, 2);
        le(cd, crc, 4);
        le(cd, e.csize, 4);
        le(cd, e.usize, 4);
        le(cd, name.length, 2);
        le(cd, e.extra.length, 2);
        le(cd, 0, 2);
        le(cd, 0, 2);
        le(cd, 0, 2);
        le(cd, 0, 4);
        le(cd, lho, 4);
        cd.writeBytes(name);
        cd.writeBytes(e.extra);
        return new Written(e, lho, cd.toByteArray());
    }

    private static byte[] inflated(Entry e) {
        try {
            java.util.zip.Inflater i = new java.util.zip.Inflater(true);
            i.setInput(e.body);
            byte[] b = new byte[(int) Math.min(Math.max(e.usize, 1), 1 << 20)]; // a test may claim a size it does not have
            int n = i.inflate(b);
            i.end();
            return java.util.Arrays.copyOf(b, n);
        } catch (java.util.zip.DataFormatException x) {
            return new byte[0]; // a test's broken body: the header's CRC is whatever
        }
    }

    byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Written> body = new ArrayList<>();
        for (Entry e : entries) {
            body.add(write(out, e));
        }
        if (pageSize > 0) {
            body.sort(Comparator.comparing((Written w) -> w.e.name, VzipArchive::compareUtf8));
        }
        Entry src = new Entry();
        src.name = "__vz__/sources";
        byte[] table = sources.toByteArray();
        src.body = deflate(table);
        src.method = 8;
        src.usize = table.length;
        src.csize = src.body.length;
        long srcOffset = out.size() + 30 + src.name.length();
        Written srcW = write(out, src);

        List<byte[]> cdRecords = new ArrayList<>();
        for (Written w : body) {
            cdRecords.add(w.cd);
        }
        Written idxW = null;
        long idxOffset = 0, idxLen = 0;
        if (pageSize > 0) {
            ByteArrayOutputStream idx = new ByteArrayOutputStream();
            long pos = 0, start = 0;
            int first = 0;
            for (int i = 0; i < body.size(); i++) {
                if (i > first && pos - start >= pageSize) {
                    page(idx, body.get(first).e.name, start, pos - start);
                    start = pos;
                    first = i;
                }
                pos += cdRecords.get(i).length;
            }
            if (!body.isEmpty()) {
                page(idx, body.get(first).e.name, start, pos - start);
            }
            for (String k : pin) {
                Written w = body.stream().filter(x -> x.e.name.equals(k)).findFirst().orElseThrow();
                ByteArrayOutputStream p = new ByteArrayOutputStream();
                field(p, 1, k.getBytes(StandardCharsets.UTF_8));
                field(p, 2, w.lho + 30 + k.getBytes(StandardCharsets.UTF_8).length);
                field(p, 3, w.e.usize);
                field(p, 4, w.e.csize);
                field(p, 5, w.e.method);
                field(idx, 2, p.toByteArray());
            }
            Entry ie = new Entry();
            ie.name = "__vz__/index";
            byte[] raw = idx.toByteArray();
            ie.body = deflate(raw);
            ie.method = 8;
            ie.usize = raw.length;
            ie.csize = ie.body.length;
            idxOffset = out.size() + 30 + ie.name.length();
            idxLen = ie.body.length;
            idxW = write(out, ie);
        }
        long cdOffset = out.size();
        for (byte[] r : cdRecords) {
            out.writeBytes(r);
        }
        out.writeBytes(srcW.cd);
        if (idxW != null) {
            out.writeBytes(idxW.cd);
        }
        long cdSize = out.size() - cdOffset;
        int count = entries.size() + 1 + (idxW != null ? 1 : 0);
        long z64At = out.size();
        if (zip64) {
            le(out, 0x06064b50L, 4);
            le(out, 44, 8);
            le(out, 45, 2);
            le(out, 45, 2);
            le(out, 0, 4);
            le(out, 0, 4);
            le(out, count, 8);
            le(out, count, 8);
            le(out, cdSize, 8);
            le(out, cdOffset, 8);
            le(out, 0x07064b50L, 4);
            le(out, 0, 4);
            le(out, z64At, 8);
            le(out, 1, 4);
        }
        le(out, 0x06054b50L, 4);
        le(out, 0, 2);
        le(out, 0, 2);
        le(out, 0xFFFF, 2);
        le(out, 0xFFFF, 2);
        le(out, 0xFFFFFFFFL, 4);
        le(out, 0xFFFFFFFFL, 4);
        le(out, idxW != null ? 38 : 22, 2);
        out.writeBytes(magic.getBytes(StandardCharsets.US_ASCII));
        le(out, srcOffset, 8);
        le(out, src.csize, 8);
        if (idxW != null) {
            le(out, idxOffset, 8);
            le(out, idxLen, 8);
        }
        return out.toByteArray();
    }

    private static void page(ByteArrayOutputStream idx, String firstKey, long offset, long length) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        field(p, 1, firstKey.getBytes(StandardCharsets.UTF_8));
        if (offset != 0) {
            field(p, 2, offset);
        }
        field(p, 3, length);
        field(idx, 1, p.toByteArray());
    }
}
