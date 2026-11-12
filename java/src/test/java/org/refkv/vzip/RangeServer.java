package org.refkv.vzip;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A loopback HTTP server that serves byte ranges, records its requests, and can be made to misbehave. */
final class RangeServer implements AutoCloseable {
    private final HttpServer server;
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /** A handler of its own for a path, in place of the range handler. */
    final Map<String, HttpHandler> custom = new ConcurrentHashMap<>();
    /** Each request as {@code "METHOD /path Range=... If-Match=..."}. */
    final List<String> log = Collections.synchronizedList(new java.util.ArrayList<>());
    volatile String etag = "\"v1\"";
    volatile String lastModified = "Sun, 06 Nov 1994 08:49:37 GMT";
    /** Paths that get the whole object whatever the Range header says. */
    final java.util.Set<String> ignoreRange = ConcurrentHashMap.newKeySet();
    volatile boolean ignoreConditionals;
    volatile boolean sendEtag = true, sendLastModified = true;

    RangeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    URI url(String path) {
        return URI.create("http://127.0.0.1:" + port() + path);
    }

    List<String> requests() {
        synchronized (log) {
            return List.copyOf(log);
        }
    }

    void clearLog() {
        log.clear();
    }

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        log.add(ex.getRequestMethod() + " " + path + " Range=" + ex.getRequestHeaders().getFirst("Range")
                + " If-Match=" + ex.getRequestHeaders().getFirst("If-Match"));
        HttpHandler h = custom.get(path);
        if (h != null) {
            h.handle(ex);
            ex.close();
            return;
        }
        byte[] body = files.get(path);
        if (body == null) {
            reply(ex, 404, new byte[0], -1, -1, -1);
            return;
        }
        if (!ignoreConditionals) {
            String match = ex.getRequestHeaders().getFirst("If-Match");
            if (match != null && !match.equals("*") && !match.equals(etag)) {
                reply(ex, 412, new byte[0], -1, -1, -1);
                return;
            }
            String since = ex.getRequestHeaders().getFirst("If-Unmodified-Since");
            if (since != null && HttpReader.parseImf(lastModified) > HttpReader.parseImf(since)) {
                reply(ex, 412, new byte[0], -1, -1, -1);
                return;
            }
        }
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range == null || ignoreRange.contains(path)) {
            reply(ex, 200, body, -1, -1, body.length);
            return;
        }
        Matcher m = RANGE.matcher(range);
        if (!m.matches()) {
            // a form of Range this server does not do, such as several ranges: a server may ignore Range, and send the whole object
            reply(ex, 200, body, -1, -1, body.length);
            return;
        }
        long a, z;
        if (m.group(1).isEmpty()) {
            long n = Long.parseLong(m.group(2));
            a = Math.max(body.length - n, 0);
            z = body.length - 1;
        } else {
            a = Long.parseLong(m.group(1));
            z = m.group(2).isEmpty() ? body.length - 1 : Math.min(Long.parseLong(m.group(2)), body.length - 1);
        }
        if (a >= body.length) {
            reply(ex, 416, new byte[0], -1, -1, body.length);
            return;
        }
        reply(ex, 206, java.util.Arrays.copyOfRange(body, (int) a, (int) z + 1), a, z, body.length);
    }

    private void reply(HttpExchange ex, int status, byte[] body, long a, long z, long total) throws IOException {
        if (sendEtag) {
            ex.getResponseHeaders().add("ETag", etag);
        }
        if (sendLastModified) {
            ex.getResponseHeaders().add("Last-Modified", lastModified);
        }
        ex.getResponseHeaders().add("Accept-Ranges", "bytes");
        if (status == 206) {
            ex.getResponseHeaders().add("Content-Range", "bytes " + a + "-" + z + "/" + total);
        }
        ex.sendResponseHeaders(status, body.length == 0 && status != 200 ? -1 : body.length);
        if (body.length > 0) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
