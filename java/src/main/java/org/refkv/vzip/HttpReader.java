package org.refkv.vzip;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads byte ranges of http(s) objects as spec/refkv.md section 5.2 requires: one GET with a {@code Range}
 * header per read (never a HEAD or a request for the whole object), pins checked on the response that carries
 * the bytes, at most 5 redirects, each hop checked against the {@link Policy}.
 */
final class HttpReader {
    /** What a read gave: the bytes, the object's total size ({@code -1} if unknown) and its ETag (null if none). */
    record Result(byte[] bytes, long total, String etag) {}

    private static final int MAX_REDIRECTS = 5;
    private static final Pattern CONTENT_RANGE = Pattern.compile("(?i:bytes) (\\d+)-(\\d+)/(\\d+|\\*)");
    private static final Pattern IMF = Pattern.compile(
            "(Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\\d{2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT");
    private static final List<String> MONTHS = List.of("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec");
    private static final List<String> DAYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

    private final HttpClient client;
    private final Policy policy;

    HttpReader(Policy policy) {
        this.policy = policy;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static ReferenceException resolution(String m) {
        return new ReferenceException(ErrorClass.RESOLUTION, m);
    }

    /** Bytes {@code [from, to)} of the object at {@code url}, with its pins (and, if non-null, {@code ifMatch}) checked. */
    Result range(UriRef url, long from, long to, Pins pins, String ifMatch, long maxBody) {
        if (to < from) {
            throw new IllegalArgumentException();
        }
        if (to == from) {
            // an empty read asks the server nothing, but a pin must still be checked before any byte is returned
            if (pins.any()) {
                throw resolution("pins are checked on a response, and an empty range makes no request");
            }
            return new Result(new byte[0], -1, null);
        }
        return fetch(url, "bytes=" + from + "-" + (to - 1), from, to, false, pins, ifMatch, maxBody);
    }

    /** The last {@code n} bytes of the object, with its size: one request, so that a store's size is known. */
    Result suffix(UriRef url, long n, long maxBody) {
        return fetch(url, "bytes=-" + n, 0, n, true, Pins.NONE, null, maxBody);
    }

    private Result fetch(UriRef start, String rangeHeader, long from, long to, boolean suffix, Pins pins, String ifMatch, long maxBody) {
        UriRef url = start;
        for (int hop = 0; ; hop++) {
            check(url);
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url.toString()))
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .header("Range", rangeHeader)
                    .header("Accept-Encoding", "identity");
            String match = pins.etag() != null ? pins.etag() : ifMatch;
            if (match != null) {
                rb.header("If-Match", match);
            }
            if (pins.modifiedNotAfter() != null) {
                rb.header("If-Unmodified-Since", imfFixdate(pins.modifiedNotAfter()));
            }
            HttpResponse<InputStream> r;
            try {
                r = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new ReferenceException(ErrorClass.RESOLUTION, "reading " + url + ": " + e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ReferenceException(ErrorClass.RESOLUTION, "reading " + url + " was interrupted", e);
            }
            try (InputStream body = r.body()) {
                int status = r.statusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (hop == MAX_REDIRECTS) {
                        throw resolution(url + ": more than " + MAX_REDIRECTS + " redirects");
                    }
                    url = redirect(url, r.headers());
                    continue;
                }
                return response(url, r, body, from, to, suffix, pins, ifMatch, maxBody);
            } catch (IOException e) {
                throw new ReferenceException(ErrorClass.RESOLUTION, "reading " + url + ": " + e, e);
            }
        }
    }

    private UriRef redirect(UriRef from, HttpHeaders h) {
        List<String> loc = h.allValues("location");
        if (loc.size() != 1) {
            throw resolution(from + ": a redirect with " + loc.size() + " Location headers");
        }
        UriRef target;
        try {
            target = from.resolve(UriRef.parse(loc.get(0)));
        } catch (IllegalArgumentException e) {
            throw resolution(from + ": a redirect to " + loc.get(0) + ", which is not a URI reference");
        }
        String scheme = target.lowerScheme();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw resolution(from + ": a redirect to the scheme " + scheme);
        }
        return target;
    }

    /** Checks an http(s) URL against the syntax rules of section 5.2 and the policy (scheme, prefixes, host, proxy). */
    private void check(UriRef url) {
        String scheme = url.lowerScheme();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw resolution(url + ": not an http(s) URL");
        }
        String authority = url.authority();
        if (authority == null || authority.isEmpty() || authority.contains("@")) {
            throw resolution(url + ": an http(s) URL with userinfo or no host");
        }
        String[] hp = UriRef.splitHostPort(authority);
        if (hp[0].isEmpty()) {
            throw resolution(url + ": an http(s) URL with no host");
        }
        if (hp[1] != null && !hp[1].isEmpty()) {
            try {
                if (Integer.parseInt(hp[1]) > 65535) {
                    throw resolution(url + ": a port above 65535");
                }
            } catch (NumberFormatException e) {
                throw resolution(url + ": a port above 65535");
            }
        }
        policy.check(url);
        policy.checkHost(url);
        if (!policy.allowsPrivateHosts() && !policy.allowsUncheckedProxy()) {
            for (java.net.Proxy p : ProxySelector.getDefault().select(URI.create(url.toString()))) {
                if (p.type() != java.net.Proxy.Type.DIRECT) {
                    throw resolution(url + ": the request would go through the proxy " + p.address()
                            + ", whose destination the reader cannot check; the unsafe setting allowUncheckedProxy allows it");
                }
            }
        }
    }

    private Result response(UriRef url, HttpResponse<InputStream> r, InputStream body, long from, long to, boolean suffix,
                            Pins pins, String ifMatch, long maxBody) throws IOException {
        int status = r.statusCode();
        HttpHeaders h = r.headers();
        if (status == 412) {
            throw resolution(url + ": a pin failed (412)");
        }
        if (status == 416) {
            throw resolution(url + ": the object is shorter than the requested range (416)");
        }
        if (status != 200 && status != 206) {
            throw resolution(url + ": status " + status);
        }
        String contentRange = single(url, h, "content-range");
        String etag = single(url, h, "etag");
        String lastModified = single(url, h, "last-modified");
        for (String v : h.allValues("content-encoding")) {
            for (String c : v.split(",", -1)) {
                if (!c.trim().equalsIgnoreCase("identity")) {
                    throw resolution(url + ": Content-Encoding " + v + " is not identity");
                }
            }
        }
        byte[] bytes;
        long total;
        if (status == 206) {
            if (contentRange == null) {
                throw resolution(url + ": a 206 without Content-Range");
            }
            Matcher m = CONTENT_RANGE.matcher(contentRange);
            if (!m.matches()) {
                throw resolution(url + ": Content-Range " + contentRange + " is not valid");
            }
            long a = parse(url, m.group(1)), z = parse(url, m.group(2));
            total = m.group(3).equals("*") ? -1 : parse(url, m.group(3));
            if (total >= 0 && z >= total) {
                throw resolution(url + ": Content-Range ends at " + z + " of " + total);
            }
            if (suffix) {
                if (total < 0 || z != total - 1 || a > z) {
                    throw resolution(url + ": Content-Range " + contentRange + " is not the requested suffix");
                }
            } else if (a != from || z != to - 1) {
                throw resolution(url + ": Content-Range " + contentRange + " is not the range requested, " + from + "-" + (to - 1));
            }
            long n = z - a + 1;
            if (n > maxBody) {
                throw new ReferenceException(ErrorClass.REQUEST, url + ": a response of " + n + " bytes exceeds the limit of " + maxBody);
            }
            bytes = body.readNBytes((int) n);
            if (bytes.length != n || body.read() >= 0) {
                throw resolution(url + ": the body's length is not " + n);
            }
        } else {
            long limit = Math.min(maxBody, Integer.MAX_VALUE - 8L);
            bytes = body.readNBytes((int) limit);
            if (body.read() >= 0) {
                throw new ReferenceException(ErrorClass.REQUEST, url + ": the server sent the whole object, over the limit of " + limit + " bytes");
            }
            total = bytes.length;
            if (suffix) {
                // the whole object: its last n bytes are the tail
            } else if (to > bytes.length) {
                throw resolution(url + ": the body has " + bytes.length + " bytes, short of the requested " + to);
            } else {
                bytes = java.util.Arrays.copyOfRange(bytes, (int) from, (int) to);
            }
        }
        checkPins(url, pins, total, etag, lastModified);
        if (suffix && status == 200) {
            long n = Math.min(to, bytes.length);
            bytes = java.util.Arrays.copyOfRange(bytes, (int) (bytes.length - n), bytes.length);
        }
        return new Result(bytes, total, etag);
    }

    private static long parse(UriRef url, String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw resolution(url + ": a number too large in Content-Range");
        }
    }

    private static String single(UriRef url, HttpHeaders h, String name) {
        List<String> v = h.allValues(name);
        if (v.size() > 1) {
            throw resolution(url + ": more than one " + name + " header");
        }
        return v.isEmpty() ? null : v.get(0);
    }

    private static void checkPins(UriRef url, Pins pins, long total, String etag, String lastModified) {
        if (pins.size() != null) {
            if (total < 0) {
                throw resolution(url + ": the size pin cannot be checked, the response gives no total size");
            }
            if (total != pins.size()) {
                throw resolution(url + ": the object is " + total + " bytes, pinned to " + pins.size());
            }
        }
        if (pins.etag() != null) {
            if (etag == null) {
                throw resolution(url + ": the etag pin cannot be checked, the response has no ETag");
            }
            if (etag.startsWith("W/") || !etag.equals(pins.etag())) {
                throw resolution(url + ": the ETag " + etag + " is not the pinned " + pins.etag());
            }
        }
        if (pins.modifiedNotAfter() != null) {
            if (lastModified == null) {
                throw resolution(url + ": the modified_not_after pin cannot be checked, the response has no Last-Modified");
            }
            long t = parseImf(lastModified);
            if (t == Long.MIN_VALUE) {
                throw resolution(url + ": Last-Modified " + lastModified + " is not an IMF-fixdate");
            }
            if (t > pins.modifiedNotAfter()) {
                throw resolution(url + ": modified at " + t + ", after the pinned " + pins.modifiedNotAfter());
            }
        }
    }

    /** Seconds since the epoch of an IMF-fixdate (RFC 9110 section 5.6.7), or {@link Long#MIN_VALUE} if it is not one. */
    static long parseImf(String s) {
        Matcher m = IMF.matcher(s);
        if (!m.matches()) {
            return Long.MIN_VALUE;
        }
        int day = Integer.parseInt(m.group(2)), month = MONTHS.indexOf(m.group(3)) + 1, year = Integer.parseInt(m.group(4));
        int hour = Integer.parseInt(m.group(5)), minute = Integer.parseInt(m.group(6)), second = Integer.parseInt(m.group(7));
        if (hour > 23 || minute > 59 || second > 60) {
            return Long.MIN_VALUE;
        }
        LocalDate date;
        try {
            date = LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return Long.MIN_VALUE;
        }
        if (!DAYS.get(date.getDayOfWeek().getValue() - 1).equals(m.group(1))) {
            return Long.MIN_VALUE;
        }
        // a leap second (60) counts as the following second
        return LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).toEpochSecond(ZoneOffset.UTC) + second;
    }

    /** An IMF-fixdate of a time in seconds; a time outside the years 1 to 9999 cannot be sent, so its pin cannot be checked. */
    static String imfFixdate(long seconds) {
        LocalDateTime t = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC);
        if (t.getYear() < 1 || t.getYear() > 9999) {
            throw resolution("the modified_not_after pin " + seconds + " cannot be sent, it is outside the years 1 to 9999");
        }
        return String.format(Locale.ROOT, "%s, %02d %s %04d %02d:%02d:%02d GMT", DAYS.get(t.getDayOfWeek().getValue() - 1),
                t.getDayOfMonth(), MONTHS.get(t.getMonthValue() - 1), t.getYear(), t.getHour(), t.getMinute(), t.getSecond());
    }
}
