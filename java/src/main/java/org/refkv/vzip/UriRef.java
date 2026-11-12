package org.refkv.vzip;

import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * An RFC 3986 URI reference, with the strict resolution of section 5.2.2 that spec/refkv.md section 5 requires
 * ({@link java.net.URI#resolve} follows RFC 2396 and differs), and the {@code file:} mapping of that section.
 */
record UriRef(String scheme, String authority, String path, String query, String fragment) {

    private static boolean unreserved(int c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '.' || c == '_' || c == '~');
    }

    private static boolean subDelim(int c) {
        return "!$&'()*+,;=".indexOf(c) >= 0 && c < 128;
    }

    private static boolean pchar(int c) {
        return unreserved(c) || subDelim(c) || c == ':' || c == '@';
    }

    private interface Chars {
        boolean ok(int c);
    }

    private static boolean charsOk(String s, Chars ok) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length() || !hex(s.charAt(i + 1)) || !hex(s.charAt(i + 2))) {
                    return false;
                }
                i += 2;
            } else if (!ok.ok(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hex(char c) {
        return Character.digit(c, 16) >= 0 && c < 128;
    }

    private static boolean validScheme(String s) {
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) && s.charAt(0) < 128)) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c < 128 && (Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(c -> c >= '0' && c <= '9')
                    || (p.length() > 1 && p.startsWith("0")) || Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean h16(String g) {
        return !g.isEmpty() && g.length() <= 4 && g.chars().allMatch(c -> c < 128 && Character.digit(c, 16) >= 0);
    }

    private static int groups(String part) {
        if (part.isEmpty()) {
            return 0;
        }
        String[] gs = part.split(":", -1);
        for (String g : gs) {
            if (!h16(g)) {
                return -1;
            }
        }
        return gs.length;
    }

    private static boolean validIpv6(String s) {
        String head = s;
        boolean v4 = false;
        int colon = s.lastIndexOf(':');
        if (colon >= 0 && s.substring(colon + 1).contains(".")) {
            if (!validIpv4(s.substring(colon + 1))) {
                return false;
            }
            head = s.substring(0, colon + 1);
            v4 = true;
        }
        int needed = v4 ? 6 : 8;
        int dc = head.indexOf("::");
        if (dc >= 0) {
            String left = head.substring(0, dc);
            String right = head.substring(dc + 2);
            if (v4 && !right.isEmpty()) {
                if (!right.endsWith(":")) {
                    return false;
                }
                right = right.substring(0, right.length() - 1);
            }
            if (right.contains("::")) {
                return false;
            }
            int l = groups(left), r = groups(right);
            return l >= 0 && r >= 0 && l + r < needed;
        }
        String body = head;
        if (v4) {
            if (!head.endsWith(":")) {
                return false;
            }
            body = head.substring(0, head.length() - 1);
        }
        return !body.isEmpty() && groups(body) == needed;
    }

    /** Splits {@code host[:port]}, keeping an IP literal whole; the port is null when absent. */
    static String[] splitHostPort(String hp) {
        if (hp.startsWith("[")) {
            int e = hp.indexOf(']');
            if (e < 0) {
                return new String[] {hp, null};
            }
            String host = hp.substring(0, e + 1);
            String rest = hp.substring(e + 1);
            if (rest.isEmpty()) {
                return new String[] {host, null};
            }
            if (rest.startsWith(":")) {
                return new String[] {host, rest.substring(1)};
            }
            return new String[] {hp, null};
        }
        int i = hp.indexOf(':');
        return i < 0 ? new String[] {hp, null} : new String[] {hp.substring(0, i), hp.substring(i + 1)};
    }

    private static boolean validAuthority(String a) {
        String hostport = a;
        int at = a.lastIndexOf('@');
        if (at >= 0) {
            if (!charsOk(a.substring(0, at), c -> unreserved(c) || subDelim(c) || c == ':')) {
                return false;
            }
            hostport = a.substring(at + 1);
        }
        String[] hp = splitHostPort(hostport);
        if (hp[1] != null && !hp[1].chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        String host = hp[0];
        if (host.startsWith("[")) {
            if (!host.endsWith("]")) {
                return false;
            }
            String inner = host.substring(1, host.length() - 1);
            if (inner.startsWith("v") || inner.startsWith("V")) {
                String rest = inner.substring(1);
                int dot = rest.indexOf('.');
                if (dot < 0) {
                    return false;
                }
                String hx = rest.substring(0, dot), tail = rest.substring(dot + 1);
                return !hx.isEmpty() && hx.chars().allMatch(c -> c < 128 && Character.digit(c, 16) >= 0)
                        && !tail.isEmpty()
                        && tail.chars().allMatch(c -> unreserved(c) || subDelim(c) || c == ':');
            }
            return validIpv6(inner);
        }
        return charsOk(host, c -> unreserved(c) || subDelim(c));
    }

    private static UriRef split(String s) {
        String rest = s, fragment = null, query = null, scheme = null, authority = null;
        int i = rest.indexOf('#');
        if (i >= 0) {
            fragment = rest.substring(i + 1);
            rest = rest.substring(0, i);
        }
        i = rest.indexOf('?');
        if (i >= 0) {
            query = rest.substring(i + 1);
            rest = rest.substring(0, i);
        }
        i = rest.indexOf(':');
        if (i >= 0) {
            String cand = rest.substring(0, i);
            if (!cand.contains("/") && validScheme(cand)) {
                scheme = cand;
                rest = rest.substring(i + 1);
            }
        }
        if (rest.startsWith("//")) {
            String r = rest.substring(2);
            int end = r.indexOf('/');
            if (end < 0) {
                end = r.length();
            }
            authority = r.substring(0, end);
            rest = r.substring(end);
        }
        return new UriRef(scheme, authority, rest, query, fragment);
    }

    /** Parses and validates a {@code URI-reference}; throws {@link IllegalArgumentException} if it is not one. */
    static UriRef parse(String s) {
        if (!s.chars().allMatch(c -> c < 128)) {
            throw new IllegalArgumentException("not a URI reference (non-ASCII): " + s);
        }
        UriRef u = split(s);
        IllegalArgumentException bad = new IllegalArgumentException("not a valid URI reference: " + s);
        if (u.authority != null) {
            if (!validAuthority(u.authority)) {
                throw bad;
            }
        } else if (u.scheme == null) {
            String first = u.path.split("/", -1)[0];
            if (first.contains(":")) {
                throw bad;
            }
        }
        if (!charsOk(u.path, c -> pchar(c) || c == '/')) {
            throw bad;
        }
        for (String q : new String[] {u.query, u.fragment}) {
            if (q != null && !charsOk(q, c -> pchar(c) || c == '/' || c == '?')) {
                throw bad;
            }
        }
        return u;
    }

    static String removeDotSegments(String input) {
        String in = input;
        StringBuilder out = new StringBuilder();
        while (!in.isEmpty()) {
            if (in.startsWith("../")) {
                in = in.substring(3);
            } else if (in.startsWith("./")) {
                in = in.substring(2);
            } else if (in.startsWith("/./")) {
                in = in.substring(2);
            } else if (in.equals("/.")) {
                in = "/";
            } else if (in.startsWith("/../") || in.equals("/..")) {
                in = in.equals("/..") ? "/" : in.substring(3);
                int i = out.lastIndexOf("/");
                out.setLength(Math.max(i, 0));
            } else if (in.equals(".") || in.equals("..")) {
                in = "";
            } else {
                int start = in.startsWith("/") ? 1 : 0;
                int end = in.indexOf('/', start);
                if (end < 0) {
                    end = in.length();
                }
                out.append(in, 0, end);
                in = in.substring(end);
            }
        }
        return out.toString();
    }

    private String merge(String relPath) {
        if (authority != null && path.isEmpty()) {
            return "/" + relPath;
        }
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(0, i + 1) + relPath : relPath;
    }

    /** This (the base) resolved against the reference {@code r}: RFC 3986 section 5.2.2, strict. */
    UriRef resolve(UriRef r) {
        if (r.scheme != null) {
            return new UriRef(r.scheme, r.authority, removeDotSegments(r.path), r.query, r.fragment);
        }
        String auth, p, q;
        if (r.authority != null) {
            auth = r.authority;
            p = removeDotSegments(r.path);
            q = r.query;
        } else {
            if (r.path.isEmpty()) {
                p = path;
                q = r.query != null ? r.query : query;
            } else {
                p = removeDotSegments(r.path.startsWith("/") ? r.path : merge(r.path));
                q = r.query;
            }
            auth = authority;
        }
        return new UriRef(scheme, auth, p, q, r.fragment);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme).append(':');
        }
        if (authority != null) {
            sb.append("//").append(authority);
        }
        sb.append(path);
        if (query != null) {
            sb.append('?').append(query);
        }
        if (fragment != null) {
            sb.append('#').append(fragment);
        }
        return sb.toString();
    }

    String lowerScheme() {
        return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
    }

    // ---- file: URIs (spec/refkv.md section 5) ----
    //
    // The POSIX and Windows rules are pure functions of strings, so that both can be tested on any system; only
    // fileBase and toFilePath look at which system this is.

    private static final boolean WINDOWS = java.io.File.separatorChar == '\\';

    private static void encodeSegment(StringBuilder out, String segment) {
        for (byte c : segment.getBytes(StandardCharsets.UTF_8)) {
            int u = c & 0xff;
            if (unreserved(u) || subDelim(u) || u == ':' || u == '@') {
                out.append((char) u);
            } else {
                out.append('%').append(String.format("%02X", u));
            }
        }
    }

    /** The base URI of an archive opened from a local path: absolute, lexically normalized, percent-encoded. */
    static UriRef fileBase(Path p) {
        String abs = p.toAbsolutePath().toString();
        return WINDOWS ? windowsFileBase(abs) : posixFileBase(abs);
    }

    /** The base URI of an absolute POSIX path: empty and {@code .} segments are removed, {@code ..} lexically, and links are not resolved. */
    static UriRef posixFileBase(String abs) {
        List<String> segs = new ArrayList<>();
        for (String s : abs.split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                if (!segs.isEmpty()) {
                    segs.remove(segs.size() - 1);
                }
            } else {
                segs.add(s);
            }
        }
        StringBuilder path = new StringBuilder();
        for (String s : segs) {
            path.append('/');
            encodeSegment(path, s);
        }
        if (path.length() == 0) {
            path.append('/');
        }
        return new UriRef("file", "", path.toString(), null, null);
    }

    /**
     * The base URI of an absolute Windows path (RFC 8089 appendix E, as spec/refkv.md section 5 has it): each
     * {@code \} is written as {@code /}, a drive-letter path gets a leading {@code /} ({@code C:\data\my file.vzip} is
     * {@code file:///C:/data/my%20file.vzip}), and a UNC path's server is the authority ({@code \\server\share\x.vzip}
     * is {@code file://server/share/x.vzip}). Either separator is accepted. Extended-length and device paths
     * ({@code \\?\}, {@code \\.\}) are not mapped.
     */
    static UriRef windowsFileBase(String abs) {
        String s = abs.replace('/', '\\');
        if (s.startsWith("\\\\?\\") || s.startsWith("\\\\.\\")) {
            throw new IllegalArgumentException("an extended-length or device path is not mapped: " + abs);
        }
        String authority;
        List<String> segs = new ArrayList<>();
        String[] rest;
        if (s.startsWith("\\\\")) {
            String[] parts = s.substring(2).split("\\\\", -1);
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new IllegalArgumentException("a UNC path needs a server and a share: " + abs);
            }
            for (int i = 0; i < parts[0].length(); i++) {
                int c = parts[0].charAt(i);
                if (!unreserved(c) && !subDelim(c)) {
                    throw new IllegalArgumentException("the UNC server name " + parts[0] + " cannot be an authority without percent-encoding");
                }
            }
            authority = parts[0];
            segs.add(parts[1]);
            rest = Arrays.copyOfRange(parts, 2, parts.length);
        } else if (s.length() >= 3 && s.charAt(0) < 128 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':' && s.charAt(2) == '\\') {
            authority = "";
            segs.add(s.substring(0, 2));
            rest = s.substring(3).split("\\\\", -1);
        } else {
            throw new IllegalArgumentException("not an absolute Windows path: " + abs);
        }
        // the drive, or the server's share, is the root: a ".." there is removed
        for (String r : rest) {
            if (r.isEmpty() || r.equals(".")) {
                continue;
            }
            if (r.equals("..")) {
                if (segs.size() > 1) {
                    segs.remove(segs.size() - 1);
                }
            } else {
                segs.add(r);
            }
        }
        StringBuilder path = new StringBuilder();
        for (String seg : segs) {
            path.append('/');
            encodeSegment(path, seg);
        }
        return new UriRef("file", authority, path.toString(), null, null);
    }

    /** Whether the authority names another host than this one: absent, empty and {@code localhost} (any case) do not. */
    boolean hasFileHost() {
        return authority != null && !authority.isEmpty() && !authority.equalsIgnoreCase("localhost");
    }

    /**
     * Maps a resolved {@code file:} URI to a local path on this system, or throws {@link IllegalArgumentException} (a
     * resolution error). A UNC host is mapped only on Windows, and only if {@code allowFileHosts}.
     */
    Path toFilePath(boolean allowFileHosts) {
        return Path.of(WINDOWS ? toWindowsPath(allowFileHosts) : toPosixPath());
    }

    /** The percent-decoded path as UTF-8 text; it must hold no encoded {@code /}, no NUL, and (on Windows) no {@code \}. */
    private String decodedPath(boolean windows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%') {
                int v = Integer.parseInt(path.substring(i + 1, i + 3), 16);
                if (v == '/') {
                    throw new IllegalArgumentException("a file: URL's path has an encoded '/'");
                }
                if (v == 0) {
                    throw new IllegalArgumentException("a file: URL's path has a NUL");
                }
                if (windows && v == '\\') {
                    throw new IllegalArgumentException("a file: URL's path has a backslash, which Windows would read as a separator");
                }
                out.write(v);
                i += 2;
            } else {
                if (windows && c == '\\') {
                    throw new IllegalArgumentException("a file: URL's path has a backslash, which Windows would read as a separator");
                }
                out.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(out.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("a file: URL's path is not UTF-8, which this reader needs");
        }
    }

    private static void noDotSegments(String[] segments) {
        for (String seg : segments) {
            if (seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("a file: URL's path has a dot segment after decoding");
            }
        }
    }

    /** The POSIX path of this {@code file:} URI; a URI with a host has no mapping there, however it is allowed. */
    String toPosixPath() {
        if (hasFileHost()) {
            throw new IllegalArgumentException("a file: URL with a host (" + authority + ") has no mapping on this system");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("a file: URL's path is not absolute");
        }
        if (query != null) {
            throw new IllegalArgumentException("a file: URL has a query");
        }
        String decoded = decodedPath(false);
        noDotSegments(decoded.split("/", -1));
        return decoded;
    }

    /**
     * The Windows path of this {@code file:} URI (RFC 8089 appendix E, as spec/refkv.md section 5 has it). A local
     * path's first segment must be a drive letter and {@code :} (or {@code |}), so {@code file:///data/x}, which would
     * name a file on whichever drive is current, is an error. A host names a UNC server, {@code file://server/share/x}
     * being {@code \\server\share\x}, and reading it connects to that server, which may send it the user's credentials,
     * so it is an error unless {@code allowFileHosts}.
     */
    String toWindowsPath(boolean allowFileHosts) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("a file: URL's path is not absolute");
        }
        if (query != null) {
            throw new IllegalArgumentException("a file: URL has a query");
        }
        String[] segs = decodedPath(true).substring(1).split("/", -1);
        if (hasFileHost()) {
            if (!allowFileHosts) {
                throw new IllegalArgumentException("the UNC server " + authority + " is not read unless the unsafe setting allowFileHosts is on");
            }
            if (authority.contains("@") || authority.contains(":") || authority.contains("%") || authority.contains("[")) {
                throw new IllegalArgumentException("a UNC server must be a host name alone, with no userinfo, port or percent-encoding: " + authority);
            }
            if (segs[0].isEmpty()) {
                throw new IllegalArgumentException("a file: URL with a host must name a share");
            }
            noDotSegments(segs);
            return "\\\\" + authority + "\\" + String.join("\\", segs);
        }
        String first = segs[0];
        if (first.length() != 2 || first.charAt(0) >= 128 || !Character.isLetter(first.charAt(0)) || (first.charAt(1) != ':' && first.charAt(1) != '|')) {
            throw new IllegalArgumentException("a file: URL's path on Windows must start with a drive letter, not " + first
                    + ": it would name a file on whichever drive is current");
        }
        String[] rest = Arrays.copyOfRange(segs, 1, segs.length);
        noDotSegments(rest);
        return first.charAt(0) + ":\\" + String.join("\\", rest);
    }
}
