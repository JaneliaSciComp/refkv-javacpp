package org.refkv.vzip;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A reader of vzip archives (spec/bindings/vzip.md): ZIP files whose entries are a store's keys, a reference
 * being an entry whose central directory record carries its payload. It reads local files and http(s) objects,
 * resolving references from {@code file:} and http(s) sources under a {@link Policy}.
 *
 * <p>The archive is opened from the end of the file, with one tail read; with a page index, no more of the
 * central directory is read than a lookup needs. DEFLATE is the JDK's {@link Inflater}; no ZIP library reads a
 * vzip archive's directory this way (see "The choice of ZIP library" in the project README), so the container
 * is parsed here.
 *
 * <p>Thread-safe. Close it to release the file.
 */
public final class VzipArchive implements ReferenceStore {
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

    static final String RESERVED = "__vz__/";
    static final String SOURCES_KEY = "__vz__/sources";
    static final String INDEX_KEY = "__vz__/index";
    private static final int EXTRA_RANGE = 0x7A76, EXTRA_CONCAT = 0x7A77;
    private static final int MAX_PAYLOAD = 65519;
    /** The bytes of the end of an http(s) archive read first: its end records, and what lies in them. */
    private static final int TAIL = 512 << 10;
    private static final int PAGE_CACHE = 64;

    // ---- how the archive's own bytes are read ----

    private interface IO extends AutoCloseable {
        long size();

        byte[] read(long off, long len);

        @Override
        void close();
    }

    private static final class FileIO implements IO {
        private final FileChannel ch;
        private final long size;

        FileIO(FileChannel ch) throws IOException {
            this.ch = ch;
            this.size = ch.size();
        }

        public long size() {
            return size;
        }

        public byte[] read(long off, long len) {
            try {
                return FileReader.readFully(ch, off, len, "the archive");
            } catch (IOException e) {
                throw new ReferenceException(ErrorClass.RESOLUTION, "reading the archive: " + e, e);
            }
        }

        public void close() {
            try {
                ch.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
    }

    private static final class HttpIO implements IO {
        private final HttpReader http;
        private final UriRef url;
        private final long size;
        private final String etag;
        private final long tailStart;
        private final byte[] tail;
        private final long maxBody;

        HttpIO(HttpReader http, UriRef url, long size, String etag, long tailStart, byte[] tail, long maxBody) {
            this.http = http;
            this.url = url;
            this.size = size;
            this.etag = etag != null && etag.startsWith("\"") ? etag : null;
            this.tailStart = tailStart;
            this.tail = tail;
            this.maxBody = maxBody;
        }

        public long size() {
            return size;
        }

        public byte[] read(long off, long len) {
            if (off >= tailStart && off + len <= size) {
                int from = (int) (off - tailStart);
                return Arrays.copyOfRange(tail, from, from + (int) len);
            }
            // If-Match with the ETag the first response gave: a changed archive fails instead of mixing two versions
            HttpReader.Result r = http.range(url, off, off + len, Pins.NONE, etag, maxBody);
            if (r.total() >= 0 && r.total() != size) {
                throw new ReferenceException(ErrorClass.RESOLUTION, url + " changed size while it was read");
            }
            return r.bytes();
        }

        public void close() {}
    }

    // ---- state ----

    private final IO io;
    private final UriRef base;
    private final Policy policy;
    private final HttpReader http;
    private final long cdOffset;
    private final long cdSize;
    private final List<Messages.Source> sources;
    private final byte[] sourcesRaw;
    private final byte[] indexRaw; // null without a page index

    // without a page index: every record, parsed at open
    private final Map<String, CdRecord> byName;
    private final List<CdRecord> records;
    // with one: the index, its pinned entries by key, and the pages parsed so far
    private final Messages.CdIndex index;
    private final Map<String, Messages.Pinned> pinned;
    private final Map<Integer, List<CdRecord>> pages = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, List<CdRecord>> e) {
            return size() > PAGE_CACHE;
        }
    };
    private final Map<Integer, UriRef> resolvedUrls = new HashMap<>();

    private record CdRecord(String name, int flags, int method, long csize, long usize, long lho, byte[] extra) {}

    private record Body(long off, long csize, long usize, int method) {}

    private sealed interface Entry permits BytesEntry, RefEntry {}

    private record BytesEntry(Body body) implements Entry {}

    private record RefEntry(int id, byte[] payload, Body body) implements Entry {}

    private VzipArchive(IO io, UriRef base, Policy policy, HttpReader http, long cdOffset, long cdSize,
                        List<Messages.Source> sources, byte[] sourcesRaw, byte[] indexRaw, Map<String, CdRecord> byName,
                        List<CdRecord> records, Messages.CdIndex index, Map<String, Messages.Pinned> pinned) {
        this.io = io;
        this.base = base;
        this.policy = policy;
        this.http = http;
        this.cdOffset = cdOffset;
        this.cdSize = cdSize;
        this.sources = sources;
        this.sourcesRaw = sourcesRaw;
        this.indexRaw = indexRaw;
        this.byName = byName;
        this.records = records;
        this.index = index;
        this.pinned = pinned;
    }

    // ---- opening ----

    /**
     * Opens the archive in a local file. Its sources are resolved against the file's own URI (a relative
     * source is a file beside it); {@code file:} sources are refused unless {@code policy} allows them.
     */
    public static VzipArchive open(Path path, Policy policy) {
        FileChannel ch;
        FileIO io;
        try {
            ch = FileChannel.open(path, StandardOpenOption.READ);
            io = new FileIO(ch);
        } catch (IOException e) {
            throw new ReferenceException(ErrorClass.ARCHIVE, "cannot open " + path + ": " + e, e);
        }
        try {
            return open(io, UriRef.fileBase(path), policy, new HttpReader(policy));
        } catch (RuntimeException e) {
            io.close();
            throw e;
        }
    }

    /**
     * Opens the archive at an http(s) URL, reading it by range requests under {@code policy}: its tail first, and
     * after that only what a lookup needs. Its sources resolve against the URL.
     */
    public static VzipArchive open(URI url, Policy policy) {
        UriRef u;
        try {
            u = UriRef.parse(url.toASCIIString());
        } catch (IllegalArgumentException e) {
            throw new ReferenceException(ErrorClass.ARCHIVE, e.getMessage(), e);
        }
        String scheme = u.lowerScheme();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ReferenceException(ErrorClass.ARCHIVE, "only http(s) URLs are opened as URLs, not " + url + "; pass a Path for a file");
        }
        HttpReader http = new HttpReader(policy);
        HttpReader.Result tail;
        try {
            tail = http.suffix(u, TAIL, policy.maxOpenBytes());
        } catch (ReferenceException e) {
            throw new ReferenceException(ErrorClass.ARCHIVE, e.detail(), e);
        }
        if (tail.total() < 0) {
            throw new ReferenceException(ErrorClass.ARCHIVE, url + " gave no total size");
        }
        long tailStart = tail.total() - tail.bytes().length;
        return open(new HttpIO(http, u, tail.total(), tail.etag(), tailStart, tail.bytes(), policy.maxOpenBytes()), u, policy, http);
    }

    private static ReferenceException archive(String m) {
        return new ReferenceException(ErrorClass.ARCHIVE, m);
    }

    private static boolean within(long size, long off, long len) {
        return off >= 0 && len >= 0 && off <= size && len <= size - off;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int o) {
        return (u16(b, o) | (long) u16(b, o + 2) << 16) & 0xFFFF_FFFFL;
    }

    private static long u64(byte[] b, int o) {
        return u32(b, o) | u32(b, o + 4) << 32;
    }

    private static byte[] rd(IO io, long off, long len, String what) {
        try {
            return io.read(off, len);
        } catch (ReferenceException e) {
            throw archive(what + ": " + e.detail());
        }
    }

    private static VzipArchive open(IO io, UriRef base, Policy policy, HttpReader http) {
        long size = io.size();
        // spec/bindings/vzip.md section 10.4: the end of central directory record is 60 or 44 bytes from the end
        long eocdAt = -1;
        if (size >= 60) {
            byte[] b = rd(io, size - 60, 22, "the end records");
            if (u32(b, 0) == 0x06054b50L && u16(b, 20) == 38) {
                eocdAt = size - 60;
            }
        }
        if (eocdAt < 0 && size >= 44) {
            byte[] b = rd(io, size - 44, 22, "the end records");
            if (u32(b, 0) == 0x06054b50L && u16(b, 20) == 22) {
                eocdAt = size - 44;
            }
        }
        if (eocdAt < 0) {
            throw archive("not a vzip archive: no end of central directory record with a vzip comment");
        }
        byte[] eocd = rd(io, eocdAt, size - eocdAt, "the end records");
        byte[] comment = Arrays.copyOfRange(eocd, 22, eocd.length);
        if (comment.length < 6 || !new String(comment, 0, 5, StandardCharsets.ISO_8859_1).equals("vzip/")) {
            throw archive("not a vzip archive: the comment does not start with vzip/");
        }
        if (comment[5] != '0') {
            throw archive("unsupported vzip version " + (char) comment[5]);
        }
        long sourcesOff = u64(comment, 6), sourcesLen = u64(comment, 14);
        boolean paged = comment.length == 38;
        long indexOff = paged ? u64(comment, 22) : 0, indexLen = paged ? u64(comment, 30) : 0;

        // section 10.2: the directory's size and offset come from the zip64 record; the counts are ignored
        if (eocdAt < 20) {
            throw archive("the zip64 end of central directory locator is missing");
        }
        byte[] loc = rd(io, eocdAt - 20, 20, "the zip64 locator");
        if (u32(loc, 0) != 0x07064b50L) {
            throw archive("the zip64 end of central directory locator is missing");
        }
        long zOff = u64(loc, 8);
        if (!within(size, zOff, 56)) {
            throw archive("the zip64 end of central directory record lies outside the file");
        }
        byte[] z = rd(io, zOff, 56, "the zip64 record");
        if (u32(z, 0) != 0x06064b50L) {
            throw archive("bad zip64 end of central directory signature");
        }
        if (u64(z, 4) != 44) {
            throw archive("the zip64 end of central directory record's size is not 44");
        }
        long cdSize = u64(z, 40), cdOffset = u64(z, 48);
        if (!within(size, cdOffset, cdSize)) {
            throw archive("the central directory lies outside the file");
        }

        byte[] sourcesRaw = formatEntry(io, size, sourcesOff, sourcesLen, "__vz__/sources", policy);
        List<Messages.Source> sources;
        try {
            sources = Messages.decodeSourceTable(sourcesRaw, policy.maxSources());
        } catch (Wire.Malformed e) {
            throw archive("the source table is malformed: " + e.getMessage());
        }

        if (paged) {
            byte[] indexRaw = formatEntry(io, size, indexOff, indexLen, "__vz__/index", policy);
            Messages.CdIndex index;
            try {
                index = Messages.CdIndex.decode(indexRaw);
            } catch (Wire.Malformed e) {
                throw archive("the page index does not decode: " + e.getMessage());
            }
            Map<String, Messages.Pinned> pinned = checkIndex(index, size, cdSize);
            return new VzipArchive(io, base, policy, http, cdOffset, cdSize, sources, sourcesRaw, indexRaw, null, null, index, pinned);
        }
        byte[] cd = rd(io, cdOffset, cdSize, "the central directory");
        List<CdRecord> records;
        try {
            records = parseRecords(cd);
        } catch (IllegalArgumentException e) {
            throw archive("the central directory: " + e.getMessage());
        }
        Map<String, CdRecord> byName = new HashMap<>();
        for (CdRecord r : records) {
            if (r.name() == null) {
                continue; // a name that is empty or not UTF-8 names no key
            }
            if (r.name().equals(INDEX_KEY)) {
                throw archive("a __vz__/index entry in an archive without a page index");
            }
            byName.putIfAbsent(r.name(), r); // duplicates are unspecified: the first wins
        }
        return new VzipArchive(io, base, policy, http, cdOffset, cdSize, sources, sourcesRaw, null, byName, records, null, null);
    }

    private static Map<String, Messages.Pinned> checkIndex(Messages.CdIndex index, long size, long cdSize) {
        long expect = 0;
        List<Messages.Page> pages = index.pages();
        for (int i = 0; i < pages.size(); i++) {
            Messages.Page p = pages.get(i);
            if (p.length() == 0) {
                throw archive("page " + i + " has length 0");
            }
            if (p.length() < 0 || p.offset() < 0 || p.offset() + p.length() < 0 || p.offset() + p.length() > cdSize) {
                throw archive("page " + i + " lies outside the central directory");
            }
            if (p.offset() != expect) {
                throw archive("page " + i + " is not contiguous");
            }
            expect = p.offset() + p.length();
            if (p.firstKey().isEmpty()) {
                throw archive("page " + i + " has an empty first_key");
            }
            if (i > 0 && compareUtf8(pages.get(i - 1).firstKey(), p.firstKey()) >= 0) {
                throw archive("the pages' first_key values do not strictly increase");
            }
        }
        Map<String, Messages.Pinned> pinned = new HashMap<>();
        for (Messages.Pinned p : index.pinned()) {
            if (p.key().isEmpty()) {
                throw archive("a pinned key is empty");
            }
            if (p.key().equals(SOURCES_KEY) || p.key().equals(INDEX_KEY)) {
                throw archive("a pinned key is a format entry");
            }
            if (pinned.put(p.key(), p) != null) {
                throw archive("the pinned key " + p.key() + " is listed twice");
            }
            if (p.method() != 0 && p.method() != 8) {
                throw archive("the pinned method " + p.method() + " is not 0 or 8");
            }
            boolean large = Long.compareUnsigned(p.size(), 0xFFFF_FFFFL) >= 0 || Long.compareUnsigned(p.csize(), 0xFFFF_FFFFL) >= 0;
            if (p.method() != 0 && large) {
                throw archive("the pinned " + p.key() + " is large and not STORED");
            }
            if (!within(size, p.dataOffset(), p.csize())) {
                throw archive("the pinned body of " + p.key() + " lies outside the file");
            }
        }
        return pinned;
    }

    private static byte[] formatEntry(IO io, long size, long off, long len, String what, Policy policy) {
        if (!within(size, off, len)) {
            throw archive(what + "'s body lies outside the file");
        }
        byte[] stored = rd(io, off, len, what);
        try {
            return inflateClean(stored, policy.maxOpenBytes(), -1);
        } catch (ReferenceException e) {
            throw archive(what + ": " + e.detail());
        }
    }

    /** Parses a sequence of whole central directory records that exactly fills {@code buf}. */
    private static List<CdRecord> parseRecords(byte[] buf) {
        List<CdRecord> v = new ArrayList<>();
        int i = 0;
        while (i < buf.length) {
            if (buf.length - i < 46) {
                throw new IllegalArgumentException("a truncated record at " + i);
            }
            if (u32(buf, i) != 0x02014b50L) {
                throw new IllegalArgumentException("a bad signature at " + i);
            }
            int n = u16(buf, i + 28), m = u16(buf, i + 30), k = u16(buf, i + 32);
            long end = (long) i + 46 + n + m + k;
            if (end > buf.length) {
                throw new IllegalArgumentException("the record at " + i + " extends past its region");
            }
            v.add(new CdRecord(validName(buf, i + 46, n), u16(buf, i + 8), u16(buf, i + 10), u32(buf, i + 20), u32(buf, i + 24),
                    u32(buf, i + 42), Arrays.copyOfRange(buf, i + 46 + n, i + 46 + n + m)));
            i = (int) end;
        }
        return v;
    }

    /** The name as a string, or null if it is empty or not valid UTF-8 (such a record names no key). */
    private static String validName(byte[] b, int off, int n) {
        if (n == 0) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b, off, n))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // ---- DEFLATE ----

    /**
     * Inflates a body that must be a single raw DEFLATE stream that ends exactly at the end of the body (spec/bindings/vzip.md
     * section 13.1): trailing bytes and a missing final block are errors, and inflating stops once the output passes
     * {@code limit}. If {@code expected} is not negative, the output must be that long. Failures are body errors.
     */
    static byte[] inflateClean(byte[] data, long limit, long expected) {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(data);
            byte[] scratch = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.min(Math.max(expected, 64), 1 << 20));
            long total = 0;
            while (!inf.finished()) {
                int r = inf.inflate(scratch);
                if (r > 0) {
                    total += r;
                    if (total > limit) {
                        throw new ReferenceException(ErrorClass.BODY, "the DEFLATE body inflates to more than " + limit + " bytes");
                    }
                    out.write(scratch, 0, r);
                } else if (!inf.finished()) {
                    if (inf.needsInput()) {
                        throw new ReferenceException(ErrorClass.BODY, "the DEFLATE stream is truncated (no final block)");
                    }
                    if (inf.needsDictionary()) {
                        throw new ReferenceException(ErrorClass.BODY, "the DEFLATE stream needs a dictionary");
                    }
                }
            }
            if (inf.getRemaining() > 0) {
                throw new ReferenceException(ErrorClass.BODY, "the DEFLATE stream ends " + inf.getRemaining() + " bytes before the end of its body");
            }
            if (expected >= 0 && total != expected) {
                throw new ReferenceException(ErrorClass.BODY, "the body inflates to " + total + " bytes, its record says " + expected);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new ReferenceException(ErrorClass.BODY, "an invalid DEFLATE stream: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
    }

    // ---- entries ----

    private static ReferenceException entryError(String m) {
        return new ReferenceException(ErrorClass.ENTRY, m);
    }

    /** Classifies a record (spec/bindings/vzip.md sections 10.2, 11.1 and 11.3); an entry error if it breaks a rule. */
    private static Entry analyze(CdRecord r) {
        if ((r.flags() & 1) != 0) {
            throw entryError("the entry is encrypted (bit 0 set)");
        }
        if (r.method() != 0 && r.method() != 8) {
            throw entryError("unsupported compression method " + r.method());
        }
        byte[] x = r.extra();
        int i = 0;
        List<int[]> refs = new ArrayList<>(); // {id, from, to}
        List<int[]> z64 = new ArrayList<>();
        while (i < x.length) {
            if (x.length - i < 4) {
                throw entryError("the extra field does not parse");
            }
            int id = u16(x, i), sz = u16(x, i + 2);
            if (x.length - i - 4 < sz) {
                throw entryError("the extra field does not parse");
            }
            if (id == EXTRA_RANGE || id == EXTRA_CONCAT) {
                refs.add(new int[] {id, i + 4, i + 4 + sz});
            } else if (id == 0x0001) {
                z64.add(new int[] {id, i + 4, i + 4 + sz});
            }
            i += 4 + sz;
        }
        if (z64.size() > 1) {
            throw entryError("more than one ZIP64 extra block");
        }
        if ((r.usize() == 0xFFFF_FFFFL) != (r.csize() == 0xFFFF_FFFFL)) {
            throw entryError("exactly one size field is 0xFFFFFFFF");
        }
        boolean large = r.usize() == 0xFFFF_FFFFL;
        int need = (large ? 16 : 0) + (r.lho() == 0xFFFF_FFFFL ? 8 : 0);
        long usize = r.usize(), csize = r.csize(), lho = r.lho();
        if (need > 0) {
            if (z64.isEmpty() || z64.get(0)[2] - z64.get(0)[1] < need) {
                throw entryError("the ZIP64 extra block is missing or too short");
            }
            int d = z64.get(0)[1];
            if (large) {
                usize = u64(x, d);
                csize = u64(x, d + 8);
            }
            if (r.lho() == 0xFFFF_FFFFL) {
                lho = u64(x, d + (large ? 16 : 0));
            }
        }
        if (refs.size() > 1) {
            throw entryError("more than one reference extra block");
        }
        if (large && r.method() != 0) {
            throw entryError("a large entry is not STORED");
        }
        // the body starts after the local header: 30 bytes, the name, and a large entry's 20-byte ZIP64 extra field
        long nameLen = r.name() == null ? 0 : r.name().getBytes(StandardCharsets.UTF_8).length;
        Body body = new Body(lho + 30 + nameLen + (large ? 20 : 0), csize, usize, r.method());
        if (refs.isEmpty()) {
            return new BytesEntry(body);
        }
        if (r.method() == 8) {
            throw entryError("a reference entry uses method 8");
        }
        if (large) {
            throw entryError("a reference entry is large");
        }
        int[] ref = refs.get(0);
        return new RefEntry(ref[0], Arrays.copyOfRange(x, ref[1], ref[2]), body);
    }

    private static boolean hidden(String key) {
        return key.startsWith(RESERVED);
    }

    /** Orders strings as their UTF-8 encodings are ordered, which is by code point. */
    static int compareUtf8(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private List<CdRecord> readPage(int i) {
        synchronized (pages) {
            List<CdRecord> cached = pages.get(i);
            if (cached != null) {
                return cached;
            }
        }
        Messages.Page p = index.pages().get(i);
        List<CdRecord> recs;
        try {
            byte[] buf = io.read(cdOffset + p.offset(), p.length());
            recs = parseRecords(buf);
        } catch (IllegalArgumentException e) {
            throw entryError("page " + i + " cannot be parsed: " + e.getMessage());
        } catch (ReferenceException e) {
            throw entryError("page " + i + " cannot be read: " + e.detail());
        }
        synchronized (pages) {
            pages.put(i, recs);
        }
        return recs;
    }

    /** The entry of a key that is not a format entry; null if it is missing (spec/bindings/vzip.md section 12.2). */
    private Entry lookup(String key) {
        if (index == null) {
            CdRecord r = byName.get(key);
            return r == null ? null : analyze(r);
        }
        Messages.Pinned p = pinned.get(key);
        if (p != null) {
            return new BytesEntry(new Body(p.dataOffset(), p.csize(), p.size(), (int) p.method()));
        }
        // the last page whose first_key is at most the key
        List<Messages.Page> ps = index.pages();
        int lo = 0, hi = ps.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compareUtf8(ps.get(mid).firstKey(), key) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo == 0) {
            return null;
        }
        for (CdRecord r : readPage(lo - 1)) {
            if (key.equals(r.name())) {
                return analyze(r);
            }
        }
        return null;
    }

    // ---- operations (spec/refkv.md section 6.2, spec/bindings/vzip.md section 13.2) ----

    @Override
    public Kind classify(String key) {
        if (hidden(key)) {
            return Kind.MISSING;
        }
        Entry e = lookup(key);
        return e == null ? Kind.MISSING : e instanceof BytesEntry ? Kind.BYTES : Kind.REFERENCE;
    }

    @Override
    public byte[] get(String key) {
        return get(key, Request.whole());
    }

    @Override
    public byte[] get(String key, long offset, long length) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset " + offset + ", length " + length);
        }
        long end = offset + length;
        return get(key, Request.range(offset, end < 0 ? Long.MAX_VALUE : end));
    }

    /** The value at {@code key}, references resolved, for any of the four requests; null if there is no such key. */
    public byte[] get(String key, Request request) {
        if (request.type() == Request.Type.RANGE && Long.compareUnsigned(request.a(), request.b()) > 0) {
            throw new ReferenceException(ErrorClass.REQUEST, "range start " + request.a() + " > end " + request.b());
        }
        if (hidden(key)) {
            return null;
        }
        Entry e = lookup(key);
        if (e == null) {
            return null;
        }
        if (e instanceof BytesEntry b) {
            return bodyValue(b.body(), request, false);
        }
        RefEntry r = (RefEntry) e;
        return resolveReference(r.id(), r.payload(), request);
    }

    @Override
    public long size(String key) {
        if (hidden(key)) {
            return -1;
        }
        Entry e = lookup(key);
        if (e == null) {
            return -1;
        }
        if (e instanceof BytesEntry b) {
            return b.body().usize();
        }
        RefEntry r = (RefEntry) e;
        long total = 0;
        for (Messages.Range p : decodePayload(r.id(), r.payload())) {
            total += p.size();
        }
        return total;
    }

    /**
     * The inflated body of the key's entry, hidden and format entries included: what a ZIP tool that knows
     * nothing of vzip sees. A reference entry gives its body, which is empty or its payload. Null if missing.
     */
    public byte[] raw(String key) {
        if (key.equals(SOURCES_KEY)) {
            return sourcesRaw.clone();
        }
        if (key.equals(INDEX_KEY)) {
            return indexRaw == null ? null : indexRaw.clone();
        }
        Entry e = lookup(key);
        if (e == null) {
            return null;
        }
        return e instanceof BytesEntry b ? bodyValue(b.body(), Request.whole(), false) : bodyValue(((RefEntry) e).body(), Request.whole(), true);
    }

    @Override
    public List<String> list(String prefix) {
        TreeSet<String> out = new TreeSet<>(VzipArchive::compareUtf8);
        if (index == null) {
            for (CdRecord r : records) {
                if (wanted(r.name(), prefix)) {
                    out.add(r.name());
                }
            }
            return new ArrayList<>(out);
        }
        for (String k : pinned.keySet()) {
            if (wanted(k, prefix)) {
                out.add(k);
            }
        }
        List<Messages.Page> ps = index.pages();
        for (int i = 0; i < ps.size(); i++) {
            String lo = ps.get(i).firstKey();
            String hi = i + 1 < ps.size() ? ps.get(i + 1).firstKey() : null;
            boolean read = (hi == null || compareUtf8(prefix, hi) < 0) && (compareUtf8(lo, prefix) <= 0 || lo.startsWith(prefix));
            if (!read) {
                continue;
            }
            for (CdRecord r : readPage(i)) {
                String n = r.name();
                if (wanted(n, prefix) && compareUtf8(lo, n) <= 0 && (hi == null || compareUtf8(n, hi) < 0)) {
                    out.add(n);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean wanted(String name, String prefix) {
        return name != null && !hidden(name) && name.startsWith(prefix);
    }

    /** The number of sources in the source table. */
    public int sourceCount() {
        return sources.size();
    }

    @Override
    public void close() {
        io.close();
    }

    // ---- bodies ----

    private ReferenceException tooMuch(String what, long n) {
        return new ReferenceException(ErrorClass.REQUEST, what + " of " + Long.toUnsignedString(n) + " bytes exceeds the limit of "
                + policy.maxBytesPerGet() + " bytes per get");
    }

    /** The window of a bytes entry's value; body errors (spec/bindings/vzip.md section 13.3). */
    private byte[] bodyValue(Body b, Request req, boolean rawRef) {
        if (!within(io.size(), b.off(), b.csize())) {
            throw new ReferenceException(ErrorClass.BODY, "the entry's body lies outside the file");
        }
        if (b.method() == 8 && !rawRef) {
            if (b.usize() < 0 || b.usize() > policy.maxBytesPerGet()) {
                throw tooMuch("an entry", b.usize());
            }
            byte[] stored = readBody(b.off(), b.csize());
            byte[] v = inflateClean(stored, b.usize(), b.usize());
            long[] w = req.window(b.usize());
            return Arrays.copyOfRange(v, (int) w[0], (int) w[1]);
        }
        if (b.csize() != b.usize()) {
            throw new ReferenceException(ErrorClass.BODY, "a STORED entry's compressed and uncompressed sizes differ");
        }
        long[] w = req.window(b.usize());
        if (w[1] - w[0] > policy.maxBytesPerGet()) {
            throw tooMuch("a window", w[1] - w[0]);
        }
        return readBody(b.off() + w[0], w[1] - w[0]);
    }

    private byte[] readBody(long off, long len) {
        try {
            return io.read(off, len);
        } catch (ReferenceException e) {
            if (e.errorClass() == ErrorClass.REQUEST) {
                throw e;
            }
            throw new ReferenceException(ErrorClass.BODY, e.detail(), e);
        }
    }

    // ---- references (spec/refkv.md section 6.3) ----

    private static ReferenceException payloadError(String m) {
        return new ReferenceException(ErrorClass.PAYLOAD, m);
    }

    private List<Messages.Range> decodePayload(int id, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw payloadError("the reference payload is " + payload.length + " bytes, over " + MAX_PAYLOAD);
        }
        List<Messages.Range> parts;
        try {
            parts = id == EXTRA_RANGE ? Messages.decodeRange(payload) : Messages.decodeConcat(payload);
        } catch (Wire.Malformed e) {
            throw payloadError("malformed " + (id == EXTRA_RANGE ? "Range" : "Concat") + ": " + e.getMessage());
        }
        long total = 0;
        for (int i = 0; i < parts.size(); i++) {
            Messages.Range p = parts.get(i);
            if (!p.literal() && Long.compareUnsigned(p.source(), sources.size()) >= 0) {
                throw payloadError("range " + i + ": source " + p.source() + " is out of bounds");
            }
            long next = total + p.size();
            if (Long.compareUnsigned(next, total) < 0) {
                throw payloadError("the reference's size exceeds 2^64 - 1");
            }
            total = next;
        }
        return parts;
    }

    /** A part of a value the window needs: a range, and which of its bytes ({@code i}, {@code j}, relative to its start). */
    private record Part(Messages.Range range, long i, long j, int outAt) {
        boolean wholeRange() {
            return range.crc32c() >= 0;
        }

        /** The bytes of the source this part must read: its range whole if it has a checksum, else its window. */
        long from() {
            return wholeRange() ? range.offset() : range.offset() + i;
        }

        long to() {
            return wholeRange() ? range.offset() + range.length() : range.offset() + j;
        }
    }

    /** One read of a url source, combined from the parts it serves. */
    private static final class Span {
        final int source;
        long from, to;
        final List<Part> parts = new ArrayList<>();
        byte[] bytes;

        Span(int source, long from, long to, Part first) {
            this.source = source;
            this.from = from;
            this.to = to;
            parts.add(first);
        }
    }

    private byte[] resolveReference(int id, byte[] payload, Request req) {
        List<Messages.Range> ranges = decodePayload(id, payload);
        long total = 0;
        for (Messages.Range r : ranges) {
            total += r.size();
        }
        if (total < 0) {
            throw tooMuch("a value", total);
        }
        long[] w = req.window(total);
        long a = w[0], b = w[1];
        if (b - a > policy.maxBytesPerGet()) {
            throw tooMuch("a window", b - a);
        }
        // the parts whose bytes overlap the window in a non-empty interval; no other range is resolved
        List<Part> parts = new ArrayList<>();
        long pos = 0;
        for (Messages.Range r : ranges) {
            long sz = r.size();
            long s = Math.max(a, pos), e = Math.min(b, pos + sz);
            if (s < e) {
                parts.add(new Part(r, s - pos, e - pos, (int) (s - a)));
            }
            pos += sz;
        }
        // rule 5: what the get builds its value from, and what it fetches, before any source is read
        long built = 0;
        for (Part p : parts) {
            built += p.wholeRange() ? p.range().length() : p.j() - p.i();
            if (built < 0 || built > policy.maxBytesPerGet()) {
                throw tooMuch("the ranges of a value", built);
            }
        }
        Map<String, byte[]> held = new HashMap<>();
        long fetched = holdKeySources(parts, held);
        List<Span> spans = coalesce(parts);
        if (spans.size() > policy.maxReadsPerGet()) {
            throw new ReferenceException(ErrorClass.REQUEST, "a get needs " + spans.size() + " reads of url sources, over the limit of "
                    + policy.maxReadsPerGet());
        }
        for (Span s : spans) {
            fetched += s.to - s.from;
            if (fetched < 0 || fetched > policy.maxBytesPerGet()) {
                throw tooMuch("the reads of a value", fetched);
            }
        }
        for (Span s : spans) {
            s.bytes = readUrl(s.source, s.from, s.to);
        }
        byte[] out = new byte[(int) (b - a)];
        Map<Part, Span> spanOf = new java.util.IdentityHashMap<>();
        for (Span s : spans) {
            for (Part p : s.parts) {
                spanOf.put(p, s);
            }
        }
        for (Part p : parts) {
            Messages.Range r = p.range();
            byte[] chunk; // bytes [p.i, p.j) of the range
            int from = 0;
            byte[] whole;
            if (r.literal()) {
                whole = r.data();
                from = (int) p.i();
            } else {
                Messages.Source src = sources.get((int) r.source());
                switch (src.kind()) {
                    case DATA -> {
                        checkLength(src.data().length, p.to(), "a data source");
                        whole = src.data();
                        from = (int) p.from();
                        if (p.wholeRange()) {
                            checkCrc(r, Arrays.copyOfRange(whole, from, (int) p.to()));
                            from += (int) p.i();
                        }
                    }
                    case KEY -> {
                        byte[] v = keySourceBytes(src.text(), p.from(), p.to(), held);
                        if (p.wholeRange()) {
                            checkCrc(r, v);
                            whole = v;
                            from = (int) p.i();
                        } else {
                            whole = v;
                            from = 0;
                        }
                    }
                    default -> {
                        Span s = spanOf.get(p);
                        whole = s.bytes;
                        from = (int) (p.from() - s.from);
                        if (p.wholeRange()) {
                            checkCrc(r, Arrays.copyOfRange(whole, from, from + (int) r.length()));
                            from += (int) p.i();
                        }
                    }
                }
            }
            chunk = whole;
            System.arraycopy(chunk, from, out, p.outAt(), (int) (p.j() - p.i()));
        }
        return out;
    }

    private static void checkLength(long have, long need, String what) {
        if (Long.compareUnsigned(need, have) > 0) {
            throw new ReferenceException(ErrorClass.RESOLUTION, what + " has " + have + " bytes, short of the " + Long.toUnsignedString(need) + " needed");
        }
    }

    private static void checkCrc(Messages.Range r, byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        if (crc.getValue() != r.crc32c()) {
            throw new ReferenceException(ErrorClass.RESOLUTION, "a range of source " + r.source() + " does not match its CRC-32C");
        }
    }

    /** Combines the reads of url sources that lie close together into one (spec/refkv.md section 6.3, coalescing). */
    private List<Span> coalesce(List<Part> parts) {
        Map<Integer, List<Part>> bySource = new LinkedHashMap<>();
        for (Part p : parts) {
            if (!p.range().literal()) {
                int s = (int) p.range().source();
                if (sources.get(s).kind() == Messages.SourceKind.URL) {
                    bySource.computeIfAbsent(s, k -> new ArrayList<>()).add(p);
                }
            }
        }
        List<Span> spans = new ArrayList<>();
        long budget = policy.maxBytesPerGet();
        for (Map.Entry<Integer, List<Part>> en : bySource.entrySet()) {
            List<Part> ps = en.getValue();
            ps.sort(Comparator.comparingLong(Part::from));
            Span cur = null;
            for (Part p : ps) {
                if (cur != null && p.from() - cur.to <= policy.coalescingGap() && Math.max(cur.to, p.to()) - cur.from <= budget) {
                    cur.to = Math.max(cur.to, p.to());
                    cur.parts.add(p);
                } else {
                    cur = new Span(en.getKey(), p.from(), p.to(), p);
                    spans.add(cur);
                }
            }
        }
        return spans;
    }

    /**
     * Looks up the entries of the key sources this get uses, and holds the compressed ones whole, once a get (rule 5).
     * Returns the bytes this holds, which count as fetched.
     */
    private long holdKeySources(List<Part> parts, Map<String, byte[]> held) {
        long bytes = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Part p : parts) {
            if (p.range().literal()) {
                continue;
            }
            Messages.Source src = sources.get((int) p.range().source());
            if (src.kind() != Messages.SourceKind.KEY || !seen.add(src.text())) {
                continue;
            }
            Entry e = keyEntry(src.text());
            if (e instanceof BytesEntry b && b.body().method() == 8) {
                bytes += Math.max(b.body().usize(), b.body().csize());
                if (bytes < 0 || bytes > policy.maxBytesPerGet()) {
                    throw tooMuch("the key sources of a value", bytes);
                }
            }
        }
        return bytes;
    }

    /** The bytes entry a key source names; a resolution error if it is missing, a reference, a format entry or broken. */
    private Entry keyEntry(String key) {
        if (key.equals(SOURCES_KEY) || key.equals(INDEX_KEY)) {
            throw new ReferenceException(ErrorClass.RESOLUTION, "the key source " + key + " names a format entry");
        }
        Entry e;
        try {
            e = lookup(key);
        } catch (ReferenceException x) {
            throw new ReferenceException(ErrorClass.RESOLUTION, "the key source " + key + ": " + x.detail(), x);
        }
        if (e == null) {
            throw new ReferenceException(ErrorClass.RESOLUTION, "the key source " + key + " is missing");
        }
        if (e instanceof RefEntry) {
            throw new ReferenceException(ErrorClass.RESOLUTION, "the key source " + key + " is a reference");
        }
        return e;
    }

    /** Bytes {@code [from, to)} of a key source's value. */
    private byte[] keySourceBytes(String key, long from, long to, Map<String, byte[]> held) {
        BytesEntry e = (BytesEntry) keyEntry(key);
        Body body = e.body();
        checkLength(body.usize(), to, "the key source " + key);
        try {
            if (body.method() == 8) {
                byte[] v = held.get(key);
                if (v == null) {
                    v = bodyValue(body, Request.whole(), false);
                    held.put(key, v);
                }
                return Arrays.copyOfRange(v, (int) from, (int) to);
            }
            return bodyValue(body, Request.range(from, to), false);
        } catch (ReferenceException x) {
            if (x.errorClass() == ErrorClass.REQUEST) {
                throw x;
            }
            throw new ReferenceException(ErrorClass.RESOLUTION, "the key source " + key + ": " + x.detail(), x);
        }
    }

    /** Bytes {@code [from, to)} of a url source: resolved against the store's URI, checked against the policy and the source's pins. */
    private byte[] readUrl(int idx, long from, long to) {
        Messages.Source src = sources.get(idx);
        UriRef url;
        synchronized (resolvedUrls) {
            url = resolvedUrls.get(idx);
        }
        if (url == null) {
            try {
                url = base.resolve(UriRef.parse(src.text()));
            } catch (IllegalArgumentException e) {
                throw new ReferenceException(ErrorClass.RESOLUTION, "source " + idx + ": " + e.getMessage());
            }
            synchronized (resolvedUrls) {
                resolvedUrls.put(idx, url);
            }
        }
        Pins pins = new Pins(src.size(), src.etag(), src.modifiedNotAfter());
        Policy.Allowed allowed = policy.check(url);
        return switch (url.lowerScheme()) {
            case "file" -> FileReader.read(url, from, to, pins, allowed);
            case "http", "https" -> http.range(url, from, to, pins, null, policy.maxBytesPerGet()).bytes();
            default -> throw new ReferenceException(ErrorClass.RESOLUTION, url + ": the scheme " + url.lowerScheme() + " is not read");
        };
    }
}
