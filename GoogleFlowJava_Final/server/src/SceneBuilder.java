import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Flow - SCENE BUILDER / VIDEO TIMELINE renderer (runs INSIDE a worker node)
 * =================================================================================
 * Takes an ordered list of scenes (clips / images from the user's Library, each with its own TRIM)
 * and renders them back-to-back as ONE movie.
 *
 *   scene source            how it is read                                   trim
 *   ----------------------  -----------------------------------------------  ---------------------------
 *   animated GIF (T2V)      javax.imageio GIF reader (frame by frame)        start / end seconds
 *   PNG / JPG (T2I)         javax.imageio                                    "hold" = how long it stays
 *   MP4 / WEBM (I2V AI)     the optional external tool ffmpeg (if installed) start / end seconds
 *
 * Everything is resampled on one fixed time grid (one step = 0.08 s = 12.5 fps, exactly what the other
 * pipelines produce), fitted into one canvas size (black bars, nothing is stretched) and streamed into
 * an encoder, so memory use stays small however long the movie is:
 *   GIF output  - pure Java (AnimatedGifWriter). Identical neighbouring frames (still images) are merged
 *                 into one frame with a longer delay, so a 3 s picture costs one frame, not 37.
 *   MP4 output  - only if ffmpeg with libx264 is installed (raw frames are piped into it).
 * The project still needs only the JDK; ffmpeg is an optional extra (MP4 sources / MP4 output).
 */
public class SceneBuilder {

    public static final int STEP_CS = 8;                    // one output step = 8 centiseconds = 0.08 s
    public static final int MAX_SCENES = 20;
    public static final int MAX_TOTAL_SECONDS = 120;
    public static final long MAX_TOTAL_BYTES = 60L * 1024 * 1024;
    static final String FFMPEG = Config.get("FLOW_FFMPEG", "ffmpeg");

    /** One timeline entry. start/end in seconds (end <= 0 means "until the end"); hold = seconds for a still image. */
    public static class Scene {
        public byte[] data;
        public String name = "scene";
        public double start, end, hold = 3;
    }

    // ------------------------------------------------------------ ffmpeg --
    private static Boolean ffmpegOk, mp4Ok;

    /** True if an `ffmpeg` program can be started (needed only for MP4/WEBM scenes and MP4 output). */
    public static synchronized boolean ffmpegAvailable() {
        if (ffmpegOk == null) ffmpegOk = runQuick(FFMPEG, "-version") != null;
        return ffmpegOk;
    }

    /** True if ffmpeg can also encode H.264 (libx264) - required for MP4 output. */
    public static synchronized boolean mp4Available() {
        if (mp4Ok == null) {
            String enc = ffmpegAvailable() ? runQuick(FFMPEG, "-hide_banner", "-encoders") : null;
            mp4Ok = enc != null && enc.contains("libx264");
        }
        return mp4Ok;
    }

    private static String runQuick(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return out;
        } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------ render --
    /**
     * @param aspect  "auto" (taken from the first scene), "16:9", "9:16" or "1:1"
     * @param format  "gif" or "mp4" (mp4 silently falls back to gif if ffmpeg/libx264 is missing)
     * @param preview true = small, quick version (2/3 size) for the "Preview sequence" button
     * @return {bytes, ext, durationSeconds, width, height, engine}
     */
    public static Object[] render(List<Scene> scenes, String aspect, String format, boolean preview) throws Exception {
        if (scenes == null || scenes.isEmpty()) throw new AiPipeline.GenerationException("The timeline is empty");
        if (scenes.size() > MAX_SCENES) throw new AiPipeline.GenerationException("Too many scenes (max " + MAX_SCENES + ")");
        boolean wantMp4 = "mp4".equalsIgnoreCase(format) && !preview && mp4Available();

        int[] wh = sizeFor(resolveAspect(aspect, scenes.get(0)), preview);
        Ctx ctx = new Ctx(wh[0], wh[1], MAX_TOTAL_SECONDS * 100 / STEP_CS);
        File tmp = File.createTempFile("flow_scene_", wantMp4 ? ".mp4" : ".gif");
        Sink sink = wantMp4 ? new Mp4Sink(tmp, ctx.w, ctx.h) : new GifSink(tmp);
        boolean closed = false;
        try {
            int n = 0;
            for (Scene sc : scenes) {
                n++;
                try { emitScene(sc, ctx, sink); }
                catch (AiPipeline.GenerationException e) { throw new AiPipeline.GenerationException("Scene " + n + ": " + e.getMessage()); }
            }
            sink.close();
            closed = true;
            byte[] bytes = Files.readAllBytes(tmp.toPath());
            double seconds = ctx.steps * STEP_CS / 100.0;
            String engine = "java-scene-builder" + (wantMp4 ? "+ffmpeg" : ctx.usedFfmpeg ? "+ffmpeg(decode)" : "");
            System.out.println("[scene] " + scenes.size() + " scene(s) -> " + (wantMp4 ? "mp4" : "gif") + ", " + ctx.w + "x" + ctx.h
                    + ", " + seconds + " s, " + bytes.length / 1024 + " KB" + (preview ? " (preview)" : ""));
            return new Object[]{bytes, wantMp4 ? "mp4" : "gif", Math.round(seconds * 100) / 100.0, ctx.w, ctx.h, engine};
        } finally {
            if (!closed) { try { sink.abort(); } catch (Exception ignored) { } }
            tmp.delete();
        }
    }

    /** Shared render state: canvas size, step counter and the safety limit. */
    private static class Ctx {
        final int w, h, maxSteps;
        int steps;
        boolean usedFfmpeg;
        Ctx(int w, int h, int maxSteps) { this.w = w; this.h = h; this.maxSteps = maxSteps; }
    }

    private interface Sink {
        void frame(BufferedImage canvas) throws Exception;     // one 0.08 s step
        void close() throws Exception;
        void abort() throws Exception;
    }

    /** Emits one 0.08 s step; enforces the maximum movie length. */
    private static void step(Ctx ctx, Sink sink, BufferedImage canvas) throws Exception {
        if (++ctx.steps > ctx.maxSteps)
            throw new AiPipeline.GenerationException("The movie is longer than " + MAX_TOTAL_SECONDS + " seconds - trim some scenes");
        sink.frame(canvas);
    }

    // --------------------------------------------------------- one scene --
    private static void emitScene(Scene sc, Ctx ctx, Sink sink) throws Exception {
        if (sc.data == null || sc.data.length < 16) throw new AiPipeline.GenerationException("clip file is empty or missing");
        switch (kindOf(sc.data)) {
            case "gif": emitGif(sc, ctx, sink); break;
            case "still": emitStill(sc, ctx, sink); break;
            default: emitVideoWithFfmpeg(sc, ctx, sink);
        }
    }

    private static String kindOf(byte[] d) {
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return "gif";
        if ((d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "still";
        if ((d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) return "still";
        return "video";                                         // mp4 / webm / anything else -> ffmpeg
    }

    /** A picture stays on screen for `hold` seconds (0.5 - 10). */
    private static void emitStill(Scene sc, Ctx ctx, Sink sink) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(sc.data));
        if (img == null) throw new AiPipeline.GenerationException("unreadable image");
        BufferedImage canvas = fit(img, ctx.w, ctx.h);
        double hold = Math.max(0.5, Math.min(10, sc.hold <= 0 ? 3 : sc.hold));
        int steps = Math.max(1, (int) Math.round(hold * 100 / STEP_CS));
        for (int i = 0; i < steps; i++) step(ctx, sink, canvas);
    }

    /** Animated GIF: read frame by frame (with proper disposal handling), keep only [start, end) on the 0.08 s grid. */
    private static void emitGif(Scene sc, Ctx ctx, Sink sink) throws Exception {
        long startCs = Math.max(0, Math.round(sc.start * 100));
        long endCs = sc.end > 0 ? Math.round(sc.end * 100) : Long.MAX_VALUE;
        if (endCs <= startCs) throw new AiPipeline.GenerationException("trim end must be after trim start");

        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(sc.data))) {
            reader.setInput(iis, false, false);
            int n = reader.getNumImages(true);
            int sw = 0, sh = 0;
            try {                                                // logical screen size = size of the whole animation
                IIOMetadata sm = reader.getStreamMetadata();
                Node lsd = sm == null ? null : child(sm.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                if (lsd != null) { sw = attr(lsd, "logicalScreenWidth", 0); sh = attr(lsd, "logicalScreenHeight", 0); }
            } catch (Exception ignored) { }
            if (sw <= 0 || sh <= 0) { sw = reader.getWidth(0); sh = reader.getHeight(0); }

            BufferedImage screen = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = screen.createGraphics();
            String prevDisposal = "none";
            int pl = 0, pt = 0, pw = 0, ph = 0;
            BufferedImage saved = null;
            long a = 0, tNext = startCs;
            boolean any = false;
            for (int i = 0; i < n; i++) {
                BufferedImage fr = reader.read(i);
                IIOMetadata md = reader.getImageMetadata(i);
                Node root = md.getAsTree("javax_imageio_gif_image_1.0");
                Node gce = child(root, "GraphicControlExtension"), desc = child(root, "ImageDescriptor");
                int delay = gce == null ? 10 : attr(gce, "delayTime", 10);
                if (delay <= 1) delay = 10;                      // browsers treat 0/1 as 10 cs
                String disposal = gce == null ? "none" : strAttr(gce, "disposalMethod", "none");
                int left = desc == null ? 0 : attr(desc, "imageLeftPosition", 0), top = desc == null ? 0 : attr(desc, "imageTopPosition", 0);

                if (prevDisposal.equals("restoreToBackgroundColor")) {          // undo the previous frame as the GIF says
                    sg.setComposite(AlphaComposite.Clear); sg.fillRect(pl, pt, pw, ph); sg.setComposite(AlphaComposite.SrcOver);
                } else if (prevDisposal.equals("restoreToPrevious") && saved != null) {
                    sg.setComposite(AlphaComposite.Src); sg.drawImage(saved, 0, 0, null); sg.setComposite(AlphaComposite.SrcOver);
                }
                if (disposal.equals("restoreToPrevious")) saved = copy(screen);
                sg.drawImage(fr, left, top, null);
                prevDisposal = disposal; pl = left; pt = top; pw = fr.getWidth(); ph = fr.getHeight();

                long b = a + delay;                              // this frame is visible during [a, b) centiseconds
                BufferedImage canvas = null;
                while (tNext < b && tNext < endCs) {
                    if (tNext >= a) {
                        if (canvas == null) canvas = fit(screen, ctx.w, ctx.h);   // scaled once, reused for every step of this frame
                        step(ctx, sink, canvas);
                        any = true;
                    }
                    tNext += STEP_CS;
                }
                a = b;
                if (a >= endCs) break;
            }
            sg.dispose();
            if (!any) throw new AiPipeline.GenerationException("trim start (" + sc.start + " s) is past the end of the clip (" + a / 100.0 + " s)");
        } finally {
            reader.dispose();
        }
    }

    /** MP4 / WEBM clips (Image -> Video AI engines): ffmpeg decodes, resizes and pads to the canvas, we read raw frames. */
    private static void emitVideoWithFfmpeg(Scene sc, Ctx ctx, Sink sink) throws Exception {
        if (!ffmpegAvailable())
            throw new AiPipeline.GenerationException("this clip is an MP4/WEBM video; reading it needs ffmpeg (install it from ffmpeg.org so that the command 'ffmpeg' works, or set FLOW_FFMPEG in config.properties)");
        ctx.usedFfmpeg = true;
        File src = File.createTempFile("flow_src_", ".bin"), log = File.createTempFile("flow_ffmpeg_", ".log");
        try {
            Files.write(src.toPath(), sc.data);
            List<String> cmd = new ArrayList<>(List.of(FFMPEG, "-v", "error", "-nostdin"));
            if (sc.start > 0) { cmd.add("-ss"); cmd.add(String.valueOf(sc.start)); }
            if (sc.end > 0) { cmd.add("-t"); cmd.add(String.valueOf(Math.max(0.1, sc.end - sc.start))); }
            cmd.addAll(List.of("-i", src.getPath(), "-an", "-vf",
                    "fps=12.5,scale=" + ctx.w + ":" + ctx.h + ":force_original_aspect_ratio=decrease,pad=" + ctx.w + ":" + ctx.h + ":(ow-iw)/2:(oh-ih)/2:black",
                    "-f", "rawvideo", "-pix_fmt", "bgr24", "-"));
            Process p = new ProcessBuilder(cmd).redirectError(log).start();
            int frameBytes = ctx.w * ctx.h * 3, got = 0;
            byte[] buf = new byte[frameBytes];
            try (InputStream in = p.getInputStream()) {
                while (readFully(in, buf)) {
                    BufferedImage img = new BufferedImage(ctx.w, ctx.h, BufferedImage.TYPE_3BYTE_BGR);
                    System.arraycopy(buf, 0, ((DataBufferByte) img.getRaster().getDataBuffer()).getData(), 0, frameBytes);
                    step(ctx, sink, img);
                    got++;
                }
            } catch (AiPipeline.GenerationException e) {
                p.destroyForcibly();
                throw e;
            }
            p.waitFor(30, TimeUnit.SECONDS);
            if (got == 0) {
                String err = new String(Files.readAllBytes(log.toPath())).trim();
                throw new AiPipeline.GenerationException("ffmpeg could not read this clip" + (err.isEmpty() ? "" : ": " + err.split("\n")[0])
                        + (sc.start > 0 ? " (is the trim start past the end of the clip?)" : ""));
            }
        } finally {
            src.delete(); log.delete();
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) return false;                             // end of stream (a partial last frame is dropped)
            off += r;
        }
        return true;
    }

    // ------------------------------------------------------ canvas helpers --
    /** Fits a picture into the canvas without stretching (black bars). */
    private static BufferedImage fit(BufferedImage src, int W, int H) {
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double k = Math.min((double) W / src.getWidth(), (double) H / src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * k)), h = Math.max(1, (int) Math.round(src.getHeight() * k));
        g.drawImage(src, (W - w) / 2, (H - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage copy(BufferedImage s) {
        BufferedImage c = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics(); g.setComposite(AlphaComposite.Src); g.drawImage(s, 0, 0, null); g.dispose();
        return c;
    }

    /** Sizes are even numbers (needed by H.264) and match the clips the other pipelines make. */
    private static int[] sizeFor(String aspect, boolean preview) {
        int[] s = "9:16".equals(aspect) ? new int[]{270, 480} : "1:1".equals(aspect) ? new int[]{360, 360} : new int[]{480, 270};
        return preview ? new int[]{s[0] * 2 / 3 / 2 * 2, s[1] * 2 / 3 / 2 * 2} : s;
    }

    private static String resolveAspect(String aspect, Scene first) throws Exception {
        if ("16:9".equals(aspect) || "9:16".equals(aspect) || "1:1".equals(aspect)) return aspect;
        int w = 16, h = 9;                                      // "auto": look at the first scene
        try {
            if (!kindOf(first.data).equals("video")) {
                try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(first.data))) {
                    ImageReader r = ImageIO.getImageReaders(iis).next();
                    r.setInput(iis); w = r.getWidth(0); h = r.getHeight(0); r.dispose();
                }
            } else if (ffmpegAvailable()) {
                File f = File.createTempFile("flow_probe_", ".bin");
                try {
                    Files.write(f.toPath(), first.data);
                    String out = runQuick(FFMPEG, "-hide_banner", "-i", f.getPath());
                    Matcher m = out == null ? null : Pattern.compile("Video:.*?, (\\d{2,5})x(\\d{2,5})").matcher(out);
                    if (m != null && m.find()) { w = Integer.parseInt(m.group(1)); h = Integer.parseInt(m.group(2)); }
                } finally { f.delete(); }
            }
        } catch (Exception ignored) { }
        double r = (double) w / h;
        return r >= 1.25 ? "16:9" : r <= 0.8 ? "9:16" : "1:1";
    }

    // ------------------------------------------------------- metadata utils --
    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) if (c.getNodeName().equals(name)) return c;
        return null;
    }
    private static String strAttr(Node n, String name, String def) {
        NamedNodeMap m = n.getAttributes(); Node a = m == null ? null : m.getNamedItem(name);
        return a == null ? def : a.getNodeValue();
    }
    private static int attr(Node n, String name, int def) {
        try { return Integer.parseInt(strAttr(n, name, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    // ------------------------------------------------------------- encoders --
    /** GIF output: identical neighbouring frames (same object) are merged into one frame with a longer delay. */
    private static class GifSink implements Sink {
        final AnimatedGifWriter gif;
        BufferedImage pending; int count;
        GifSink(File f) throws Exception { gif = new AnimatedGifWriter(f, STEP_CS); }
        public void frame(BufferedImage c) throws Exception {
            if (c == pending) { count++; return; }
            flush();
            pending = c; count = 1;
        }
        void flush() throws Exception { if (pending != null) gif.addFrame(pending, count * STEP_CS); pending = null; }
        public void close() throws Exception { flush(); gif.close(); }
        public void abort() throws Exception { gif.close(); }
    }

    /** MP4 output: raw BGR frames are piped into ffmpeg (libx264, yuv420p, fast-start so browsers can play it). */
    private static class Mp4Sink implements Sink {
        final Process p; final OutputStream out; final File log;
        BufferedImage last; byte[] lastBytes;
        Mp4Sink(File f, int w, int h) throws Exception {
            log = File.createTempFile("flow_ffmpeg_", ".log");
            p = new ProcessBuilder(FFMPEG, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", w + "x" + h, "-r", "12.5",
                    "-i", "-", "-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-pix_fmt", "yuv420p", "-movflags", "+faststart", f.getPath())
                    .redirectError(log).start();
            out = p.getOutputStream();
        }
        public void frame(BufferedImage c) throws Exception {
            if (c != last) { lastBytes = ((DataBufferByte) c.getRaster().getDataBuffer()).getData(); last = c; }
            out.write(lastBytes);
        }
        public void close() throws Exception {
            out.close();
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
                String err = new String(Files.readAllBytes(log.toPath())).trim();
                p.destroyForcibly(); log.delete();
                throw new AiPipeline.GenerationException("ffmpeg could not encode the MP4" + (err.isEmpty() ? "" : ": " + err.split("\n")[0]));
            }
            log.delete();
        }
        public void abort() { p.destroyForcibly(); log.delete(); }
    }
}
