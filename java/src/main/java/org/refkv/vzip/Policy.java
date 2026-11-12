package org.refkv.vzip;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The reader policy of spec/refkv.md section 6.7: which URLs a store may make a reader read, and the
 * resource limits. A store from an untrusted place can name any URL, so the defaults refuse every
 * scheme but {@code http} and {@code https}, and every host that is not a public address.
 *
 * <p>Limitation: a reader MUST connect only to an address it has checked, so that a name that resolves
 * differently the second time (DNS rebinding) cannot reach a private host. {@link java.net.http.HttpClient}
 * does not let a caller choose the address it connects to, so this reader resolves and checks every address of
 * a host just before the request and cannot rule out that the connection resolves it again. For untrusted
 * stores, prefer {@link Builder#prefix prefixes}, which do not depend on addresses.
 */
public final class Policy {
    /** A URL prefix, with, for a {@code file:} prefix, its real path, resolved once. */
    record Prefix(String text, Path realPath) {}

    /** The classes of address of rule 3. */
    enum AddressClass { LOOPBACK, PRIVATE, LINK_LOCAL, SPECIAL, PUBLIC }

    private final Set<String> schemes;
    private final List<Prefix> prefixes;
    private final boolean allowPrivateHosts;
    private final boolean allowUncheckedProxy;
    private final boolean allowFileHosts;
    private final int maxSources;
    private final int maxReadsPerGet;
    private final long maxBytesPerGet;
    private final long coalescingGap;
    private final long maxOpenBytes;

    private Policy(Builder b) {
        this.schemes = Set.copyOf(b.schemes);
        this.prefixes = b.prefixes == null ? null : List.copyOf(b.prefixes);
        this.allowPrivateHosts = b.allowPrivateHosts;
        this.allowUncheckedProxy = b.allowUncheckedProxy;
        this.allowFileHosts = b.allowFileHosts;
        this.maxSources = b.maxSources;
        this.maxReadsPerGet = b.maxReadsPerGet;
        this.maxBytesPerGet = b.maxBytesPerGet;
        this.coalescingGap = b.coalescingGap;
        this.maxOpenBytes = b.maxOpenBytes;
    }

    /** {@code http} and {@code https} only, public addresses only, and the default limits. */
    public static Policy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The defaults, and {@code file:} sources allowed below {@code dir}, whose real path is resolved now
     * (a link put in its place later does not move it).
     */
    public static Policy filesBelow(Path dir) {
        return builder().prefix(UriRef.fileBase(dir).toString() + "/").build();
    }

    public boolean allowsPrivateHosts() {
        return allowPrivateHosts;
    }

    public boolean allowsUncheckedProxy() {
        return allowUncheckedProxy;
    }

    public boolean allowsFileHosts() {
        return allowFileHosts;
    }

    int maxSources() {
        return maxSources;
    }

    int maxReadsPerGet() {
        return maxReadsPerGet;
    }

    long maxBytesPerGet() {
        return maxBytesPerGet;
    }

    long coalescingGap() {
        return coalescingGap;
    }

    long maxOpenBytes() {
        return maxOpenBytes;
    }

    /** Builds a {@link Policy}. The defaults are the ones spec/refkv.md section 6.7 lists. */
    public static final class Builder {
        private final Set<String> schemes = new LinkedHashSet<>(List.of("http", "https"));
        private List<Prefix> prefixes;
        private boolean allowPrivateHosts;
        private boolean allowUncheckedProxy;
        private boolean allowFileHosts;
        private int maxSources = 1 << 22;
        private int maxReadsPerGet = 1024;
        // a Java array holds at most 2^31 - 1 bytes
        private long maxBytesPerGet = Integer.MAX_VALUE - 8;
        private long coalescingGap = 64 << 10;
        private long maxOpenBytes = 256L << 20;

        /** Allows a scheme besides {@code http} and {@code https}, such as {@code file}. Ignored when prefixes are given. */
        public Builder allowScheme(String scheme) {
            schemes.add(scheme.toLowerCase(Locale.ROOT));
            return this;
        }

        /**
         * Allows exactly the URLs that match this prefix, replacing the scheme rule (rule 2). A URL matches
         * {@code p} if it starts with it and either {@code p} ends with '/', or the URL equals it, or its next
         * character is '/', '?' or '#'. A {@code file:} prefix's real path is resolved now, and a file read
         * under it must resolve to a path below that.
         */
        public Builder prefix(String prefix) {
            if (prefixes == null) {
                prefixes = new ArrayList<>();
            }
            Path real = null;
            if (prefix.regionMatches(true, 0, "file:", 0, 5)) {
                try {
                    // the prefix is the application's own choice, so a UNC prefix needs no separate permission to be resolved here;
                    // a URL with a host is still refused by check() unless allowFileHosts
                    real = UriRef.parse(prefix).toFilePath(true).toRealPath();
                } catch (IOException | IllegalArgumentException e) {
                    throw new IllegalArgumentException("the file prefix " + prefix + " does not name a directory: " + e.getMessage(), e);
                }
            }
            prefixes.add(new Prefix(prefix, real));
            return this;
        }

        /** UNSAFE: lets a request reach loopback, private, link-local and special addresses, and use any proxy. */
        public Builder allowPrivateHosts(boolean on) {
            this.allowPrivateHosts = on;
            return this;
        }

        /** UNSAFE: sends requests through a proxy, whose destination the reader cannot check. */
        public Builder allowUncheckedProxy(boolean on) {
            this.allowUncheckedProxy = on;
            return this;
        }

        /**
         * UNSAFE, Windows only: lets a {@code file:} URL name a UNC server ({@code file://server/share/x}). Reading one connects
         * to that server over the network, which may send it the user's credentials. A store opened from a UNC path reads
         * itself without this; the sources it names need it. It is a setting of its own, apart from {@link #allowPrivateHosts}.
         */
        public Builder allowFileHosts(boolean on) {
            this.allowFileHosts = on;
            return this;
        }

        public Builder maxSources(int n) {
            this.maxSources = n;
            return this;
        }

        public Builder maxReadsPerGet(int n) {
            this.maxReadsPerGet = n;
            return this;
        }

        public Builder maxBytesPerGet(long n) {
            this.maxBytesPerGet = Math.min(n, Integer.MAX_VALUE - 8);
            return this;
        }

        /** How far apart reads of one source may lie and still be combined into one request; 0 combines only reads that touch. */
        public Builder coalescingGap(long bytes) {
            this.coalescingGap = bytes;
            return this;
        }

        public Builder maxOpenBytes(long n) {
            this.maxOpenBytes = n;
            return this;
        }

        public Policy build() {
            return new Policy(this);
        }
    }

    // ---- URL rules (rules 1 and 2) ----

    /** What a URL matched: the prefix, when a prefix list is in force and the prefix is a file one. */
    record Allowed(Path realPrefix, boolean allowFileHosts) {}

    /**
     * Checks a resolved URL against rules 1 and 2.
     *
     * @throws ReferenceException a resolution error if the policy refuses it
     */
    Allowed check(UriRef url) {
        String s = url.toString();
        String scheme = url.lowerScheme();
        if (scheme.equals("file") && url.hasFileHost() && !allowFileHosts) {
            throw refused(s, "a file: URL with a host (a UNC path on Windows) is refused, whatever the schemes and prefixes allow; "
                    + "the unsafe setting allowFileHosts allows it");
        }
        if (prefixes != null) {
            String p = url.path().toUpperCase(Locale.ROOT);
            if (p.contains("%2E") || p.contains("%2F") || p.contains("%5C")) {
                throw refused(s, "its path holds %2E, %2F or %5C, which matches no prefix");
            }
            for (Prefix pre : prefixes) {
                if (matches(s, pre.text())) {
                    return new Allowed(pre.realPath(), allowFileHosts);
                }
            }
            throw refused(s, "it matches none of the allowed prefixes");
        }
        if (!schemes.contains(scheme)) {
            throw refused(s, "the scheme " + (scheme.isEmpty() ? "(none)" : scheme) + " is not allowed");
        }
        return new Allowed(null, allowFileHosts);
    }

    private static boolean matches(String url, String prefix) {
        if (!url.startsWith(prefix)) {
            return false;
        }
        if (prefix.endsWith("/") || url.length() == prefix.length()) {
            return true;
        }
        char c = url.charAt(prefix.length());
        return c == '/' || c == '?' || c == '#';
    }

    static ReferenceException refused(String url, String why) {
        return new ReferenceException(ErrorClass.RESOLUTION, "the reader policy refuses " + url + ": " + why);
    }

    // ---- hosts (rule 3) ----

    /** Throws a resolution error if the host of {@code url} has an address of a class the policy does not allow. */
    void checkHost(UriRef url) {
        if (allowPrivateHosts) {
            return;
        }
        String host = UriRef.splitHostPort(hostPortOf(url.authority()))[0];
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new ReferenceException(ErrorClass.RESOLUTION, "cannot resolve " + host + ": " + e.getMessage(), e);
        }
        for (InetAddress a : all) {
            AddressClass c = classify(a);
            if (c != AddressClass.PUBLIC) {
                throw refused(url.toString(), host + " is a " + c.name().toLowerCase(Locale.ROOT).replace('_', '-')
                        + " address (" + a.getHostAddress() + "); the unsafe setting allowPrivateHosts allows it");
            }
        }
    }

    private static String hostPortOf(String authority) {
        int at = authority.lastIndexOf('@');
        return at >= 0 ? authority.substring(at + 1) : authority;
    }

    private record Cidr(byte[] addr, int bits) {
        boolean contains(byte[] a) {
            int full = bits / 8, rem = bits % 8;
            for (int i = 0; i < full; i++) {
                if (a[i] != addr[i]) {
                    return false;
                }
            }
            return rem == 0 || ((a[full] ^ addr[full]) & (0xff << (8 - rem)) & 0xff) == 0;
        }
    }

    private static Cidr v4(String s) {
        String[] p = s.split("[./]");
        return new Cidr(new byte[] {(byte) Integer.parseInt(p[0]), (byte) Integer.parseInt(p[1]), (byte) Integer.parseInt(p[2]),
                (byte) Integer.parseInt(p[3])}, Integer.parseInt(p[4]));
    }

    private static Cidr v6(String s) {
        int slash = s.indexOf('/');
        try {
            return new Cidr(InetAddress.getByName(s.substring(0, slash)).getAddress(), Integer.parseInt(s.substring(slash + 1)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final List<Cidr> V4_PRIVATE = List.of(v4("10.0.0.0/8"), v4("172.16.0.0/12"), v4("192.168.0.0/16"));
    private static final List<Cidr> V4_SPECIAL = List.of(v4("0.0.0.0/8"), v4("100.64.0.0/10"), v4("192.0.0.0/24"),
            v4("192.0.2.0/24"), v4("198.18.0.0/15"), v4("198.51.100.0/24"), v4("203.0.113.0/24"), v4("224.0.0.0/3"));
    private static final List<Cidr> V6_SPECIAL = List.of(v6("::/8"), v6("64:ff9b:1::/48"), v6("100::/63"), v6("2001::/23"),
            v6("2001:db8::/32"), v6("3fff::/20"), v6("5f00::/16"), v6("fec0::/10"), v6("ff00::/8"));

    /** The class of an address (rule 3). An IPv6 address that embeds an IPv4 one has that address's class. */
    static AddressClass classify(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            mapped &= (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
            if (mapped) {
                return classify4(java.util.Arrays.copyOfRange(b, 12, 16));
            }
            if (v6("64:ff9b::/96").contains(b)) {
                return classify4(java.util.Arrays.copyOfRange(b, 12, 16));
            }
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
                return classify4(java.util.Arrays.copyOfRange(b, 2, 6));
            }
            if (v6("::1/128").contains(b)) {
                return AddressClass.LOOPBACK;
            }
            if (v6("fc00::/7").contains(b)) {
                return AddressClass.PRIVATE;
            }
            if (v6("fe80::/10").contains(b)) {
                return AddressClass.LINK_LOCAL;
            }
            for (Cidr c : V6_SPECIAL) {
                if (c.contains(b)) {
                    return AddressClass.SPECIAL;
                }
            }
            return AddressClass.PUBLIC;
        }
        assert a instanceof Inet4Address;
        return classify4(b);
    }

    private static AddressClass classify4(byte[] b) {
        if ((b[0] & 0xff) == 127) {
            return AddressClass.LOOPBACK;
        }
        for (Cidr c : V4_PRIVATE) {
            if (c.contains(b)) {
                return AddressClass.PRIVATE;
            }
        }
        if ((b[0] & 0xff) == 169 && (b[1] & 0xff) == 254) {
            return AddressClass.LINK_LOCAL;
        }
        for (Cidr c : V4_SPECIAL) {
            if (c.contains(b)) {
                return AddressClass.SPECIAL;
            }
        }
        return AddressClass.PUBLIC;
    }
}
