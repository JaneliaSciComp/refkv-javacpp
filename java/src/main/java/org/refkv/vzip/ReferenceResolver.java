package org.refkv.vzip;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32C;

/**
 * Resolves reference payloads (spec/refkv.md section 6.3) for any store: it reads the ranges a window needs from
 * the sources the store's source table names, under a {@link Policy}, with pins and checksums checked, and combines
 * reads that lie close together. A store supplies what differs between bindings: its source table, its base URI, and
 * how a {@code key} source is read ({@link KeySources}).
 *
 * <p>Thread-safe if the {@link KeySources} are.
 */
public final class ReferenceResolver {
    /** The kind of a reference whose payload is a Range (the ZIP binding's header ID 0x7A76). */
    public static final int RANGE = 0x7A76;
    /** The kind of a reference whose payload is a Concat (0x7A77). */
    public static final int CONCAT = 0x7A77;
    static final int MAX_PAYLOAD = 65519;

    /** The bytes value a {@code key} source names, looked up for one {@code get}. */
    public interface KeyValue {
        long size();

        /**
         * The bytes that reading a window of this value holds in memory, if the reader holds it whole (the larger of its size
         * and its stored bytes, spec/refkv.md section 6.7 rule 5), else -1: a window is then read by itself.
         */
        long held();

        /** Bytes {@code [from, to)}, which the resolver has checked lie within {@link #size()}. */
        byte[] read(long from, long to);
    }

    /** How a store reads the {@code key} sources of its references. */
    public interface KeySources {
        /**
         * Looks up the bytes value named {@code key}: hidden ones included, and a reference is not one. Throws a
         * {@link ReferenceException} if it is missing, is a reference, or cannot be read; any class but a request error
         * is reported as a resolution error.
         */
        KeyValue open(String key);
    }

    private final List<Messages.Source> sources;
    private final UriRef base;
    private final Policy policy;
    private final HttpReader http;
    private final KeySources keys;
    private final Map<Integer, UriRef> resolvedUrls = new HashMap<>();

    /**
     * @param sourceTable the store's source table, an encoded {@code SourceTable} message
     * @param baseUri the store's base URI, which relative {@code url} sources resolve against: {@link #baseUri(Path)} for
     *     a store in a local file, else the URL it was read from
     * @throws ReferenceException an archive error if the table is malformed or over the policy's limit
     */
    public ReferenceResolver(byte[] sourceTable, String baseUri, Policy policy, KeySources keys) {
        this(decodeTable(sourceTable, policy), parseBase(baseUri), policy, new HttpReader(policy), keys);
    }

    ReferenceResolver(List<Messages.Source> sources, UriRef base, Policy policy, HttpReader http, KeySources keys) {
        this.sources = sources;
        this.base = base;
        this.policy = policy;
        this.http = http;
        this.keys = keys;
    }

    /** The base URI of a store opened from a local path (spec/refkv.md section 5). */
    public static String baseUri(Path path) {
        try {
            return UriRef.fileBase(path).toString();
        } catch (IllegalArgumentException e) {
            throw new ReferenceException(ErrorClass.ARCHIVE, "cannot build a base URI for " + path + ": " + e.getMessage(), e);
        }
    }

    private static List<Messages.Source> decodeTable(byte[] table, Policy policy) {
        try {
            return Messages.decodeSourceTable(table, policy.maxSources());
        } catch (Wire.Malformed e) {
            throw new ReferenceException(ErrorClass.ARCHIVE, "the source table is malformed: " + e.getMessage());
        }
    }

    private static UriRef parseBase(String baseUri) {
        try {
            return UriRef.parse(baseUri);
        } catch (IllegalArgumentException e) {
            throw new ReferenceException(ErrorClass.ARCHIVE, "the base URI is not a URI: " + e.getMessage(), e);
        }
    }

    /** The number of sources in the table. */
    public int sourceCount() {
        return sources.size();
    }

    static ReferenceException tooMuch(Policy policy, String what, long n) {
        return new ReferenceException(ErrorClass.REQUEST, what + " of " + Long.toUnsignedString(n) + " bytes exceeds the limit of "
                + policy.maxBytesPerGet() + " bytes per get");
    }

    private ReferenceException tooMuch(String what, long n) {
        return tooMuch(policy, what, n);
    }

    // ---- payloads ----

    private static ReferenceException payloadError(String m) {
        return new ReferenceException(ErrorClass.PAYLOAD, m);
    }

    private List<Messages.Range> decodePayload(int kind, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw payloadError("the reference payload is " + payload.length + " bytes, over " + MAX_PAYLOAD);
        }
        List<Messages.Range> parts;
        try {
            parts = kind == RANGE ? Messages.decodeRange(payload) : Messages.decodeConcat(payload);
        } catch (Wire.Malformed e) {
            throw payloadError("malformed " + (kind == RANGE ? "Range" : "Concat") + ": " + e.getMessage());
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

    /** The size of the value a reference describes, from its payload alone: no source is read. */
    public long size(int kind, byte[] payload) {
        long total = 0;
        for (Messages.Range p : decodePayload(kind, payload)) {
            total += p.size();
        }
        return total;
    }

    // ---- resolving ----

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

    /**
     * Resolves a reference: the window {@code request} asks for, of the value that a payload of kind {@link #RANGE} or
     * {@link #CONCAT} describes.
     *
     * @throws ReferenceException a payload error if the payload is malformed, a resolution error if a source cannot be
     *     read or its policy, pins or checksum fail, a request error past the policy's limits
     */
    public byte[] resolve(int kind, byte[] payload, Request req) {
        List<Messages.Range> ranges = decodePayload(kind, payload);
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
        Map<String, KeyValue> opened = new HashMap<>();
        long fetched = openKeySources(parts, opened);
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
        Map<Part, Span> spanOf = new IdentityHashMap<>();
        for (Span s : spans) {
            for (Part p : s.parts) {
                spanOf.put(p, s);
            }
        }
        for (Part p : parts) {
            Messages.Range r = p.range();
            int from;
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
                        byte[] v = readKey(src.text(), opened.get(src.text()), p.from(), p.to());
                        whole = v;
                        if (p.wholeRange()) {
                            checkCrc(r, v);
                            from = (int) p.i();
                        } else {
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
            System.arraycopy(whole, from, out, p.outAt(), (int) (p.j() - p.i()));
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

    // ---- key sources ----

    /** A key source's failure is a resolution error, except a request error, which is the limit's own. */
    private static ReferenceException asResolution(String key, ReferenceException x) {
        if (x.errorClass() == ErrorClass.RESOLUTION || x.errorClass() == ErrorClass.REQUEST) {
            return x;
        }
        return new ReferenceException(ErrorClass.RESOLUTION, "the key source " + key + ": " + x.detail(), x);
    }

    /**
     * Looks up the key sources this get uses, once each (rule 5), and counts the bytes of those the store holds whole,
     * which count as fetched.
     */
    private long openKeySources(List<Part> parts, Map<String, KeyValue> opened) {
        long bytes = 0;
        Set<String> seen = new HashSet<>();
        for (Part p : parts) {
            if (p.range().literal()) {
                continue;
            }
            Messages.Source src = sources.get((int) p.range().source());
            if (src.kind() != Messages.SourceKind.KEY || !seen.add(src.text())) {
                continue;
            }
            KeyValue kv;
            try {
                kv = keys.open(src.text());
            } catch (ReferenceException x) {
                throw asResolution(src.text(), x);
            }
            opened.put(src.text(), kv);
            if (kv.held() >= 0) {
                bytes += kv.held();
                if (bytes < 0 || bytes > policy.maxBytesPerGet()) {
                    throw tooMuch("the key sources of a value", bytes);
                }
            }
        }
        return bytes;
    }

    private static byte[] readKey(String key, KeyValue kv, long from, long to) {
        checkLength(kv.size(), to, "the key source " + key);
        try {
            return kv.read(from, to);
        } catch (ReferenceException x) {
            throw asResolution(key, x);
        }
    }

    // ---- url sources ----

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
