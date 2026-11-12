package org.refkv.javacpp;

import static org.refkv.javacpp.global.refkv.*;

import java.util.ArrayList;
import java.util.List;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.refkv.javacpp.global.refkv.RefkvOptions;

/**
 * Zarrify a file or a store in one of refkv's formats (TIFF, HDF5, N5, MRC, ...): the result is a
 * Zarr hierarchy whose chunks are references to the original bytes, saved as one small archive.
 *
 * <pre>{@code
 * Zarrify.Result r = Zarrify.convert("slide.svs", "slide.phkv", Zarrify.options().format(Container.PHKV));
 * try (PhkvStore store = PhkvStore.open("slide.phkv")) { ... }
 * }</pre>
 */
public final class Zarrify {
    private Zarrify() {}

    /** The container the archive is written as. */
    public enum Container {
        /** A vzip archive: a plain ZIP, which a {@link PhkvStore} cannot read. */
        VZIP(REFKV_FORMAT_VZIP),
        /** A standalone phkv store. */
        PHKV(REFKV_FORMAT_PHKV),
        /** A vzip archive that also carries a phkv container: readable by ZIP tools and by {@link PhkvStore}. */
        HYBRID(REFKV_FORMAT_HYBRID);

        final int code;

        Container(int code) {
            this.code = code;
        }
    }

    /** What {@link #convert} was asked to do besides the defaults. Mutable; not thread-safe. */
    public static final class Options {
        private String url;
        private boolean checksums;
        private boolean allowPrivateHosts;
        private final List<String> scope = new ArrayList<>();
        private long connections;
        private double byteCost = -1;
        private long workers = 16;
        private Container container = Container.VZIP;

        /** The URL the archive names for the input, which the input is served from; required for a local directory. */
        public Options url(String url) {
            this.url = url;
            return this;
        }

        /** Record the CRC-32C of every range of a url source, which reads every byte the archive references. */
        public Options checksums(boolean on) {
            this.checksums = on;
            return this;
        }

        /** UNSAFE: read hosts at loopback, private or link-local addresses. */
        public Options allowPrivateHosts(boolean on) {
            this.allowPrivateHosts = on;
            return this;
        }

        /** Allows a file input to read this path besides itself (spec/zarrify.md section 1.9). */
        public Options scope(String path) {
            this.scope.add(path);
            return this;
        }

        /** Requests at a time to a remote input; 0 for the default. */
        public Options connections(long n) {
            this.connections = n;
            return this;
        }

        /** Seconds per megabyte the read planner charges for bytes fetched; negative for the default. */
        public Options byteCost(double secondsPerMb) {
            this.byteCost = secondsPerMb;
            return this;
        }

        /** Threads that read a store's objects. */
        public Options workers(long n) {
            this.workers = n;
            return this;
        }

        public Options format(Container container) {
            this.container = container;
            return this;
        }
    }

    /** What a conversion found: the input's format and the summary, as the JSON object refkv gives. */
    public record Result(String summaryJson) {}

    public static Options options() {
        return new Options();
    }

    /** Zarrifies with the defaults, into a vzip archive. */
    public static Result convert(String input, String output) {
        return convert(input, output, null);
    }

    /**
     * Zarrifies {@code input}, a path or an http(s) URL of a file or store, and writes the archive to
     * {@code output}. Blocks until it is done, and reads the network for a remote input.
     *
     * @throws RefkvException with {@link RefkvException#isRejected()} if the input is not in a supported format
     */
    public static Result convert(String input, String output, Options options) {
        RefkvOptions o = refkv_options_new();
        try {
            if (options != null) {
                apply(o, options);
            }
            try (BytePointer in = new BytePointer(input);
                    BytePointer out = new BytePointer(output);
                    PointerPointer<BytePointer> summary = new PointerPointer<>(1)) {
                Native.check(refkv_zarrify(in, out, o, summary));
                return new Result(Native.takeString(summary));
            }
        } finally {
            refkv_options_free(o);
        }
    }

    private static void apply(RefkvOptions o, Options x) {
        if (x.url != null) {
            Native.check(refkv_options_set_url(o, x.url));
        }
        Native.check(refkv_options_set_checksums(o, x.checksums));
        Native.check(refkv_options_set_allow_private_hosts(o, x.allowPrivateHosts));
        for (String s : x.scope) {
            Native.check(refkv_options_add_scope(o, s));
        }
        Native.check(refkv_options_set_connections(o, x.connections));
        Native.check(refkv_options_set_byte_cost(o, x.byteCost));
        Native.check(refkv_options_set_workers(o, x.workers));
        Native.check(refkv_options_set_format(o, x.container.code));
    }
}
