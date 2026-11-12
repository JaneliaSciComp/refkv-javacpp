package org.refkv.demo;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader;
import org.refkv.javacpp.RefkvKeyValueAccess;
import org.refkv.javacpp.Zarrify;
import org.refkv.vzip.Policy;
import org.refkv.vzip.ReferenceStore;
import org.refkv.vzip.VzipArchive;

/**
 * Opens an image in BigDataViewer through a refkv vzip archive: the image is a file in a foreign format (a Zeiss CZI,
 * by default), which refkv zarrifies into a small archive of references to its bytes. N5 reads that archive as a Zarr
 * dataset, and the pixels are read from the original file, over HTTP, as BigDataViewer asks for them.
 *
 * <pre>
 * BdvDemo [source] [--out FILE.vzip] [--files-below DIR] [--check] [--preview FILE.png]
 * </pre>
 *
 * {@code --check} reads one block and exits. {@code --preview} needs no display: it composites the middle z-plane of
 * every channel, with the channel colours and display windows the image's metadata gives, into a PNG, reading the
 * pixels the same way the viewer does. It is not BigDataViewer's rendering.
 *
 * <p>{@code source} is an archive ({@code .vzip}, a path or an http(s) URL) or an image to zarrify first (a path or an http(s)
 * URL of any format refkv knows). Local image files need {@code --files-below}, the directory the archive may read.
 */
public final class BdvDemo {
    /** The demo image: a 3-channel yeast z-stack from the Image Data Resource (idr0011, CC BY 4.0), 44 MB at the EBI. */
    static final String DEFAULT_SOURCE = "https://ftp.ebi.ac.uk/pub/databases/IDR/idr0011-ledesmafernandez-dad4/"
            + "20150826-peter_thorpe/Stinger%20plates-extra%20images%20screen%20T34%20x%20TS-more%20repeats/"
            + "Plate1-Blue-A_TS-Stinger/Plate1-Blue-A-02-Scene-1-P2-E1-01.czi";

    record Options(String source, Path out, Path filesBelow, boolean check, Path preview) {
        static Options parse(String[] args) {
            String source = DEFAULT_SOURCE;
            Path out = null, below = null, shot = null;
            boolean check = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> out = Path.of(args[++i]);
                    case "--files-below" -> below = Path.of(args[++i]);
                    case "--preview" -> shot = Path.of(args[++i]);
                    case "--check" -> check = true;
                    case "-h", "--help" -> {
                        System.out.println("usage: BdvDemo [source] [--out FILE.vzip] [--files-below DIR] [--check] [--preview FILE.png]");
                        System.exit(0);
                    }
                    default -> {
                        if (args[i].startsWith("--")) {
                            throw new IllegalArgumentException("unknown option " + args[i]);
                        }
                        source = args[i];
                    }
                }
            }
            return new Options(source, out, below, check, shot);
        }
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        try (VzipArchive archive = open(o)) {
            N5Reader n5 = new ZarrV3KeyValueReader(new RefkvKeyValueAccess(archive), "", new GsonBuilder(), false);
            Image image = Image.find(archive, n5);
            System.out.println(image);
            requireReadable(archive, n5, image);
            if (o.check()) {
                image.check(n5);
                SharedQueue queue = new SharedQueue(2);
                checkVolatile(channelViews(n5, image, queue));
                queue.shutdown();
                return;
            }
            if (o.preview() != null) {
                preview(n5, image, o.preview());
                return;
            }
            show(n5, image);
        }
    }

    // ---- the archive ----

    /** What the archive may read: https and http sources, and, if asked, files below one directory. */
    static Policy policy(Options o) {
        Policy.Builder b = Policy.builder();
        if (o.filesBelow() != null) {
            b.prefix("https://").prefix("http://").filesBelow(o.filesBelow().toAbsolutePath());
        }
        return b.build();
    }

    static boolean isUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    static VzipArchive open(Options o) throws IOException {
        Policy policy = policy(o);
        String source = o.source();
        if (source.endsWith(".vzip")) {
            System.out.println("opening the archive " + source);
            return isUrl(source) ? VzipArchive.open(URI.create(source), policy) : VzipArchive.open(Path.of(source), policy);
        }
        Path out = o.out() != null ? o.out() : Files.createTempFile("refkv-demo-", ".vzip");
        long t0 = System.nanoTime();
        Zarrify.Result r = Zarrify.convert(source, out.toString());
        System.out.printf("zarrified %s in %.1f s into %s (%d bytes), whatever its size%n", source, (System.nanoTime() - t0) / 1e9, out, Files.size(out));
        System.out.println("  " + r.summaryJson().replaceAll("\"planner\".*", "...").replaceAll("^\\{", "{").trim());
        return VzipArchive.open(out, policy);
    }

    // ---- what the archive holds: an OME-Zarr image ----

    /** An OME-NGFF image: its full-resolution array, axes, voxel size and channels, read from the group's metadata. */
    record Image(String group, String array, List<String> axes, double[] scale, List<Channel> channels, String name) {
        record Channel(String label, int color, double min, double max) {}

        @Override
        public String toString() {
            return "image " + (name == null ? group : name) + ": array " + array + " axes " + axes + " voxel size " + java.util.Arrays.toString(scale)
                    + " channels " + channels.stream().map(Channel::label).toList();
        }

        /** The first group of the archive with OME-NGFF multiscales, and its first resolution level. */
        static Image find(ReferenceStore archive, N5Reader n5) {
            for (String key : archive.list("")) {
                if (!key.endsWith("zarr.json") || key.startsWith("refkv_source/")) {
                    continue;
                }
                JsonObject node = JsonParser.parseString(new String(archive.get(key), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject ome = node.has("attributes") && node.getAsJsonObject("attributes").has("ome")
                        ? node.getAsJsonObject("attributes").getAsJsonObject("ome") : null;
                if (ome == null || !ome.has("multiscales")) {
                    continue;
                }
                String group = key.equals("zarr.json") ? "" : key.substring(0, key.length() - "/zarr.json".length());
                JsonObject ms = ome.getAsJsonArray("multiscales").get(0).getAsJsonObject();
                List<String> axes = new ArrayList<>();
                for (JsonElement a : ms.getAsJsonArray("axes")) {
                    axes.add(a.getAsJsonObject().get("name").getAsString());
                }
                JsonObject level0 = ms.getAsJsonArray("datasets").get(0).getAsJsonObject();
                double[] scale = new double[axes.size()];
                java.util.Arrays.fill(scale, 1.0);
                for (JsonElement t : level0.getAsJsonArray("coordinateTransformations")) {
                    JsonObject tr = t.getAsJsonObject();
                    if (tr.get("type").getAsString().equals("scale")) {
                        for (int i = 0; i < scale.length; i++) {
                            scale[i] = tr.getAsJsonArray("scale").get(i).getAsDouble();
                        }
                    }
                }
                List<Channel> channels = new ArrayList<>();
                if (ome.has("omero")) {
                    for (JsonElement c : ome.getAsJsonObject("omero").getAsJsonArray("channels")) {
                        JsonObject ch = c.getAsJsonObject();
                        JsonObject w = ch.has("window") ? ch.getAsJsonObject("window") : null;
                        int rgb = ch.has("color") ? Integer.parseInt(ch.get("color").getAsString(), 16) : 0xFFFFFF;
                        channels.add(new Channel(ch.has("label") ? ch.get("label").getAsString() : "channel " + channels.size(), 0xFF000000 | rgb,
                                w == null ? 0 : w.get("start").getAsDouble(), w == null ? 0 : w.get("end").getAsDouble()));
                    }
                }
                String array = (group.isEmpty() ? "" : group + "/") + level0.get("path").getAsString();
                return new Image(group, array, axes, scale, channels, ms.has("name") ? ms.get("name").getAsString() : null);
            }
            throw new IllegalArgumentException("the archive holds no OME-NGFF image");
        }

        /** Reads one block from the file, to show what a viewer will do. */
        void check(N5Reader n5) {
            DatasetAttributes attrs = n5.getDatasetAttributes(array);
            System.out.println("array " + array + ": " + attrs.getDataType() + " " + java.util.Arrays.toString(attrs.getDimensions())
                    + " in blocks of " + java.util.Arrays.toString(attrs.getBlockSize()) + " (N5 order: x first)");
            long t0 = System.nanoTime();
            var block = n5.readBlock(array, attrs, new long[attrs.getNumDimensions()]);
            System.out.printf("read the first block: %d elements in %.0f ms%n", block.getNumElements(), (System.nanoTime() - t0) / 1e6);
        }
    }

    /**
     * N5 returns no dataset, with no word why, for an array whose codecs or data type it does not implement: JPEG, JPEG 2000 and
     * JPEG XR tiles, PackBits, half floats and so on. Say which, so that the demo does not end in a NullPointerException.
     */
    static void requireReadable(ReferenceStore archive, N5Reader n5, Image image) {
        if (n5.getDatasetAttributes(image.array()) != null) {
            return;
        }
        JsonObject node = JsonParser.parseString(new String(archive.get(image.array() + "/zarr.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> codecs = new ArrayList<>();
        for (JsonElement c : node.getAsJsonArray("codecs")) {
            codecs.add(c.getAsJsonObject().get("name").getAsString());
        }
        throw new IllegalStateException("N5 cannot read the array " + image.array() + " (data type " + node.get("data_type") + ", codecs " + codecs
                + "): n5-zarr implements raw bytes, transpose and gzip, for the common integer and float types. The image is in the archive, "
                + "but this viewer cannot decode its pixels; try an uncompressed one (a CZI without a JPEG XR pyramid, an Imaris, NIfTI, NRRD or MRC file).");
    }

    // ---- BigDataViewer ----

    /** One channel as BigDataViewer gets it: a volatile view, whose blocks load in the background as they are asked for. */
    record ChannelView(String name, RandomAccessibleInterval<?> view, Image.Channel look) {}

    /** The channels of the image as volatile views over N5, which reads their blocks from the original file. Needs no display. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<ChannelView> channelViews(N5Reader n5, Image image, SharedQueue queue) {
        RandomAccessibleInterval<?> img = N5Utils.openVolatile(n5, image.array());
        // N5 lists the fastest axis first, so a Zarr (c, z, y, x) array is (x, y, z, c) here
        int channelDim = image.axes().indexOf("c") < 0 ? -1 : image.axes().size() - 1 - image.axes().indexOf("c");
        int channels = channelDim < 0 ? 1 : (int) img.dimension(channelDim);
        List<ChannelView> views = new ArrayList<>();
        for (int c = 0; c < channels; c++) {
            RandomAccessibleInterval<?> channel = channelDim < 0 ? img : Views.hyperSlice((RandomAccessibleInterval) img, channelDim, c);
            Image.Channel look = c < image.channels().size() ? image.channels().get(c) : null;
            String name = look != null ? look.label() : (image.name() != null ? image.name() : "image") + (channels > 1 ? " c" + c : "");
            views.add(new ChannelView(name, (RandomAccessibleInterval) VolatileViews.wrapAsVolatile((RandomAccessibleInterval) channel, queue), look));
        }
        return views;
    }

    /** Waits for one voxel of the first channel, in the middle of the image, to load: what the viewer does for every pixel it draws. */
    static void checkVolatile(List<ChannelView> views) throws InterruptedException {
        RandomAccessibleInterval<?> view = views.get(0).view();
        long[] middle = new long[view.numDimensions()];
        for (int d = 0; d < middle.length; d++) {
            middle[d] = view.min(d) + view.dimension(d) / 2;
        }
        long t0 = System.nanoTime();
        while (true) {
            // BigDataViewer loads a block only while the thread that asks has I/O time to spend, and its renderer gives it some
            // each frame; and it draws with a new access each frame, since an access remembers the cell it last visited, which
            // may still be the invalid one it got before the block loaded
            net.imglib2.cache.iotiming.CacheIoTiming.getIoTimeBudget().reset(new long[] {1_000_000_000L});
            var ra = view.randomAccess();
            ra.setPosition(middle);
            Volatile<?> voxel = (Volatile<?>) ra.get();
            if (voxel.isValid()) {
                System.out.printf("%d volatile channel views; the voxel at %s loaded in %.1f s: %s%n", views.size(),
                        java.util.Arrays.toString(middle), (System.nanoTime() - t0) / 1e9, voxel.get());
                return;
            }
            if ((System.nanoTime() - t0) > 120e9) {
                throw new IllegalStateException("the voxel at " + java.util.Arrays.toString(middle) + " did not load in 120 s");
            }
            Thread.sleep(100);
        }
    }

    /** Opens BigDataViewer on the image: one source per channel, coloured, windowed and calibrated from the image's metadata. */
    static void show(N5Reader n5, Image image) {
        SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        List<Double> voxel = new ArrayList<>();
        for (int i = image.axes().size() - 1; i >= 0; i--) {
            if (!image.axes().get(i).equals("c") && !image.axes().get(i).equals("t")) {
                voxel.add(image.scale()[i]);
            }
        }
        AffineTransform3D calibration = new AffineTransform3D();
        for (int d = 0; d < Math.min(3, voxel.size()); d++) {
            calibration.set(voxel.get(d), d, d);
        }
        boolean is2d = !image.axes().contains("z");
        BdvStackSource<?> first = null;
        for (ChannelView cv : channelViews(n5, image, queue)) {
            BdvOptions options = BdvOptions.options().sourceTransform(calibration);
            if (is2d) {
                options = options.is2D();
            }
            if (first != null) {
                options = options.addTo(first.getBdvHandle());
            }
            BdvStackSource<?> source = add(cv.view(), cv.name(), options);
            if (cv.look() != null) {
                source.setColor(new ARGBType(cv.look().color()));
                if (cv.look().max() > cv.look().min()) {
                    source.setDisplayRange(cv.look().min(), cv.look().max());
                }
            }
            if (first == null) {
                first = source;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <V extends Volatile<?> & NumericType<V> & NativeType<V>> BdvStackSource<?> add(
            RandomAccessibleInterval<?> view, String name, BdvOptions options) {
        return BdvFunctions.show((RandomAccessibleInterval<V>) (RandomAccessibleInterval) view, name, options);
    }

    // ---- a preview that needs no display ----

    /** Composites the middle z-plane of every channel into a PNG, as the viewer would colour it. */
    static void preview(N5Reader n5, Image image, Path file) throws IOException {
        RandomAccessibleInterval<?> img = N5Utils.open(n5, image.array());
        List<String> axes = new ArrayList<>(image.axes());
        java.util.Collections.reverse(axes); // N5 lists the fastest axis first
        int cDim = axes.indexOf("c");
        int channels = cDim < 0 ? 1 : (int) img.dimension(cDim);
        int width = (int) img.dimension(axes.indexOf("x")), height = (int) img.dimension(axes.indexOf("y"));
        double[][] sum = new double[3][width * height];
        for (int c = 0; c < channels; c++) {
            RandomAccessibleInterval<?> plane = img;
            for (int d = axes.size() - 1; d >= 0; d--) { // from the highest dimension down, so the lower ones keep their numbers
                String a = axes.get(d);
                if (!a.equals("x") && !a.equals("y")) {
                    plane = Views.hyperSlice((RandomAccessibleInterval) plane, d, a.equals("c") ? c : a.equals("z") ? img.dimension(d) / 2 : 0);
                }
            }
            long t0 = System.nanoTime();
            double[] v = new double[width * height];
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            var ra = plane.randomAccess();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    ra.setPosition(new long[] {x, y});
                    double d = ((net.imglib2.type.numeric.RealType<?>) ra.get()).getRealDouble();
                    v[y * width + x] = d;
                    lo = Math.min(lo, d);
                    hi = Math.max(hi, d);
                }
            }
            Image.Channel look = c < image.channels().size() ? image.channels().get(c) : null;
            double min = look != null && look.max() > look.min() ? look.min() : lo, max = look != null && look.max() > look.min() ? look.max() : hi;
            int rgb = look != null ? look.color() : 0xFFFFFFFF;
            System.out.printf("channel %d %s: read %d x %d in %.1f s, values %.0f to %.0f, shown over %.0f to %.0f%n", c,
                    look != null ? look.label() : "", width, height, (System.nanoTime() - t0) / 1e9, lo, hi, min, max);
            for (int i = 0; i < v.length; i++) {
                double f = Math.max(0, Math.min(1, (v[i] - min) / Math.max(1e-9, max - min)));
                sum[0][i] += f * ((rgb >> 16) & 0xff);
                sum[1][i] += f * ((rgb >> 8) & 0xff);
                sum[2][i] += f * (rgb & 0xff);
            }
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < width * height; i++) {
            int r = (int) Math.min(255, sum[0][i]), g = (int) Math.min(255, sum[1][i]), b = (int) Math.min(255, sum[2][i]);
            out.setRGB(i % width, i / width, (r << 16) | (g << 8) | b);
        }
        javax.imageio.ImageIO.write(out, "png", file.toFile());
        System.out.println("wrote " + file + " (" + width + " x " + height + ")");
    }
}
