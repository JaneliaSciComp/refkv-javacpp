package org.refkv.vzip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Reads byte ranges of {@code file:} sources (spec/refkv.md sections 5 and 5.1, 6.7 rule 2). */
final class FileReader {
    private FileReader() {}

    private static ReferenceException resolution(String m) {
        return new ReferenceException(ErrorClass.RESOLUTION, m);
    }

    /**
     * Bytes {@code [from, to)} of the file {@code url} names, its pins checked: a size pin against its size, a
     * modified_not_after pin against its modification time in whole seconds rounded down, and an etag pin cannot be
     * checked, so it fails. When a file prefix allowed the URL, the file's real path, resolved after it is opened,
     * must be below the prefix's.
     */
    static byte[] read(UriRef url, long from, long to, Pins pins, Policy.Allowed allowed) {
        Path path;
        try {
            path = url.toFilePath(allowed.allowFileHosts());
        } catch (IllegalArgumentException e) {
            throw resolution(url + ": " + e.getMessage());
        }
        if (pins.etag() != null) {
            throw resolution(url + ": an etag pin cannot be checked for a file: URL");
        }
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            if (allowed.realPrefix() != null) {
                Path real = path.toRealPath();
                if (!real.startsWith(allowed.realPrefix())) {
                    throw Policy.refused(url.toString(), "its real path " + real + " is not below " + allowed.realPrefix());
                }
            }
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                throw resolution(path + " is not a regular file");
            }
            long len = ch.size();
            if (pins.size() != null && pins.size() != len) {
                throw resolution(url + ": the file is " + len + " bytes, pinned to " + pins.size());
            }
            if (pins.modifiedNotAfter() != null) {
                long m = Math.floorDiv(attrs.lastModifiedTime().toMillis(), 1000L);
                if (m > pins.modifiedNotAfter()) {
                    throw resolution(url + ": modified at " + m + ", after the pinned " + pins.modifiedNotAfter());
                }
            }
            if (to > len) {
                throw resolution(url + ": the file has " + len + " bytes, short of the " + to + " needed");
            }
            return readFully(ch, from, to - from, url);
        } catch (IOException e) {
            throw new ReferenceException(ErrorClass.RESOLUTION, "reading " + url + ": " + e, e);
        }
    }

    static byte[] readFully(FileChannel ch, long from, long n, Object what) throws IOException {
        if (n > Integer.MAX_VALUE - 8) {
            throw new ReferenceException(ErrorClass.REQUEST, "a read of " + n + " bytes of " + what + " is too long");
        }
        ByteBuffer buf = ByteBuffer.allocate((int) n);
        long pos = from;
        while (buf.hasRemaining()) {
            int r = ch.read(buf, pos);
            if (r < 0) {
                throw new IOException("unexpected end of " + what + " at " + pos);
            }
            pos += r;
        }
        return buf.array();
    }
}
