import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Google Flow - IMAGE -> VIDEO pipeline (runs INSIDE a worker node, command "generate_i2v")
 * =========================================================================================
 * Input : the user's uploaded picture + a prompt ("make the dog jump out of the swimming pool") + 5-6 seconds.
 * Output: a short video clip (.mp4 / .webm from an AI model, or an animated .gif from the local fallback).
 *
 * Engines are tried in the order given by FLOW_I2V_PROVIDER (default "gradio,fal"):
 *
 *   gradio : FREE.  Calls a public Hugging Face Space (LTX-Video, Wan2.2 ... see FLOW_I2V_SPACES) through its
 *            Gradio HTTP API:  GET /info -> POST /upload -> POST /call/<api> -> read the event stream -> download.
 *            It reads /info and builds the argument list itself (image, prompt, duration, defaults for the rest),
 *            so no API name has to be hard-coded.  HF_TOKEN (free) is optional but gives a much bigger GPU quota.
 *   fal    : fal.ai queue API (needs FAL_KEY; new accounts get free credits).  Model = FAL_I2V_MODEL.
 *   local  : (fallback, FLOW_I2V_FALLBACK=true) NOT an AI model - a prompt-driven virtual-camera / motion effect
 *            on your picture (jump, zoom, pan, fall, shake ...).  It guarantees a clip even when every AI
 *            service is busy, asleep, or out of quota.  The job history shows which engine really produced the clip.
 *
 * Everything is plain java.net.http / java.awt / javax.imageio - no libraries.
 */
public class I2VPipeline {

    static final String PROVIDERS  = Config.get("FLOW_I2V_PROVIDER", "gradio,fal").toLowerCase();
    static final String SPACES     = Config.get("FLOW_I2V_SPACES",
            "https://lightricks-ltx-video-distilled.hf.space,https://zerogpu-aoti-wan2-2-fast.hf.space");
    static final String API_NAME   = Config.get("FLOW_I2V_API_NAME", "");          // force one endpoint, e.g. /generate_video
    static final String FAL_KEY    = Config.get("FAL_KEY", "");
    static final String FAL_MODEL  = Config.get("FAL_I2V_MODEL", "fal-ai/ltx-video/image-to-video");
    static final boolean FALLBACK  = Config.getBool("FLOW_I2V_FALLBACK", true);
    static final int TOTAL_SECONDS = Config.getInt("FLOW_I2V_TIMEOUT_S", 200);      // budget for ALL AI attempts of one job

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    private static final Random RAND = new Random();

    /** Returns {bytes, extension("mp4"/"webm"/"gif"), durationSeconds(Double), engineName}. */
    public static Object[] generate(byte[] imageBytes, String prompt, double seconds, double failProbability) throws Exception {
        if (failProbability > 0 && RAND.nextDouble() < failProbability)
            throw new AiPipeline.GenerationException("Simulated transient failure (FLOW_FAIL_PROB)");
        BufferedImage src = decodeImage(imageBytes);
        byte[] jpeg = toJpeg(src, 1024);
        if (prompt == null || prompt.isBlank()) prompt = "subtle natural motion, cinematic";
        seconds = Math.max(2, Math.min(8, seconds));

        long deadline = System.currentTimeMillis() + TOTAL_SECONDS * 1000L;
        String lastError = null;
        for (String p : PROVIDERS.split("[,\\s]+")) {
            try {
                if (p.equals("gradio")) {
                    for (String space : SPACES.split("[,\\s]+")) {
                        if (space.isBlank()) continue;
                        if (System.currentTimeMillis() > deadline - 5000) break;
                        try {
                            return viaGradio(space.replaceAll("/+$", ""), jpeg, prompt, seconds, deadline);
                        } catch (AiPipeline.GenerationException e) {
                            lastError = e.getMessage();
                            System.out.println("[i2v] " + space + " failed: " + lastError);
                        }
                    }
                } else if (p.equals("fal") && !FAL_KEY.isBlank()) {
                    return viaFal(jpeg, prompt, seconds, deadline);
                }
            } catch (AiPipeline.GenerationException e) {
                lastError = e.getMessage();
                System.out.println("[i2v] " + p + " failed: " + lastError);
            } catch (Exception e) {
                lastError = p + " error: " + e;
                System.out.println("[i2v] " + lastError);
            }
        }
        if (FALLBACK) {
            System.out.println("[i2v] no AI video service answered (" + lastError + ") -> LOCAL motion effect");
            return localMotion(src, prompt, seconds);
        }
        throw new AiPipeline.GenerationException(lastError == null
                ? "No image-to-video provider available (set HF_TOKEN / FAL_KEY or enable FLOW_I2V_FALLBACK)" : lastError);
    }

    // ================================================================ GRADIO ==
    @SuppressWarnings("unchecked")
    private static Object[] viaGradio(String base, byte[] jpeg, String prompt, double secs, long deadline) throws Exception {
        long myDeadline = Math.min(deadline, System.currentTimeMillis() + (long) (TOTAL_SECONDS * 600L));   // one Space gets <= 60% of the budget
        String prefix = null;
        Map<String, Object> info = null;
        for (String pf : new String[]{"/gradio_api", ""}) {                      // Gradio 5 = /gradio_api, Gradio 4 = no prefix
            try {
                HttpResponse<String> r = HTTP.send(req(base + pf + "/info", myDeadline).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    Object o = Json.parse(r.body());
                    if (o instanceof Map && ((Map<String, Object>) o).get("named_endpoints") instanceof Map) {
                        info = (Map<String, Object>) o; prefix = pf; break;
                    }
                }
            } catch (java.io.IOException ignored) { }
        }
        if (info == null) throw new AiPipeline.GenerationException(base + " has no readable Gradio API (asleep, private or unsupported)");

        String api = chooseEndpoint((Map<String, Object>) info.get("named_endpoints"));
        if (api == null) throw new AiPipeline.GenerationException(base + ": no image->video endpoint found");
        Map<String, Object> ep = (Map<String, Object>) ((Map<String, Object>) info.get("named_endpoints")).get(api);
        System.out.println("[i2v] using Space " + base + " endpoint " + api);

        Map<String, Object> file = upload(base + prefix + "/upload", jpeg, myDeadline);
        String resultJson;
        try {
            resultJson = callAndWait(base, prefix, api, buildArgs(ep, file, prompt, secs, true), myDeadline);
        } catch (AiPipeline.GenerationException e) {
            if (System.currentTimeMillis() > myDeadline - 20000) throw e;
            System.out.println("[i2v] retrying with the Space's default settings (" + e.getMessage() + ")");
            resultJson = callAndWait(base, prefix, api, buildArgs(ep, file, prompt, secs, false), myDeadline);
        }
        Map<String, Object> ref = findFile(Json.parse(resultJson));
        if (ref == null) throw new AiPipeline.GenerationException(base + ": reply contained no video file");
        String url = ref.get("url") instanceof String ? (String) ref.get("url") : null;
        if (url == null || url.isBlank()) url = base + prefix + "/file=" + ref.get("path");
        else if (url.startsWith("/")) url = base + url;
        byte[] video = download(url, myDeadline, true);
        return packVideo(video, url, secs, "huggingface-space " + base.replace("https://", "").replace(".hf.space", ""));
    }

    /** Picks the endpoint that takes a picture and returns a video. */
    @SuppressWarnings("unchecked")
    private static String chooseEndpoint(Map<String, Object> named) {
        if (!API_NAME.isBlank()) { String n = API_NAME.startsWith("/") ? API_NAME : "/" + API_NAME; return named.containsKey(n) ? n : null; }
        String best = null; int bestScore = 0;
        for (Map.Entry<String, Object> e : named.entrySet()) {
            Map<String, Object> ep = (Map<String, Object>) e.getValue();
            boolean img = false, prompt = false, vid = false;
            for (Object o : list(ep.get("parameters"))) {
                Map<String, Object> p = (Map<String, Object>) o;
                String c = lower(p.get("component")), n = lower(p.get("parameter_name")) + " " + lower(p.get("label"));
                if (isFileComponent(c)) img = true;
                if (n.contains("prompt")) prompt = true;
            }
            for (Object o : list(ep.get("returns"))) if (lower(((Map<String, Object>) o).get("component")).contains("video")) vid = true;
            int score = (img && vid ? 10 : 0) + (prompt ? 2 : 0);
            if (score > bestScore) { bestScore = score; best = e.getKey(); }
        }
        return bestScore >= 10 ? best : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> buildArgs(Map<String, Object> ep, Map<String, Object> file, String prompt, double secs, boolean override) {
        List<Object> data = new ArrayList<>();
        boolean fileUsed = false;
        for (Object o : list(ep.get("parameters"))) {
            Map<String, Object> p = (Map<String, Object>) o;
            String comp = lower(p.get("component")), nm = lower(p.get("parameter_name")) + " " + lower(p.get("label"));
            boolean has = Boolean.TRUE.equals(p.get("parameter_has_default"));
            Object def = p.get("parameter_default");
            if (isFileComponent(comp)) { data.add(fileUsed ? def : file); fileUsed = true; }
            else if (nm.contains("negative")) data.add(has ? def : "");
            else if (nm.contains("prompt")) data.add(prompt);
            else if (override && (nm.contains("duration") || nm.contains("seconds")) && def instanceof Number) data.add(secs);
            else if (has) data.add(def);
            else if (comp.contains("checkbox")) data.add(false);
            else if (comp.contains("slider") || comp.contains("number")) data.add(0);
            else data.add("");
        }
        return data;
    }

    private static boolean isFileComponent(String c) { return c.contains("image") || c.contains("file") || c.contains("upload"); }

    private static Map<String, Object> upload(String url, byte[] jpeg, long deadline) throws Exception {
        String b = "----flow" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"input.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        bos.write(jpeg);
        bos.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = HTTP.send(req(url, deadline).header("Content-Type", "multipart/form-data; boundary=" + b)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new AiPipeline.GenerationException("upload to Space failed: HTTP " + r.statusCode() + " " + cut(r.body()));
        Object o = Json.parse(r.body());
        if (!(o instanceof List) || ((List<?>) o).isEmpty()) throw new AiPipeline.GenerationException("upload reply not understood: " + cut(r.body()));
        Map<String, Object> fd = new LinkedHashMap<>();
        fd.put("path", String.valueOf(((List<?>) o).get(0)));
        fd.put("orig_name", "input.jpg");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("_type", "gradio.FileData");
        fd.put("meta", meta);
        return fd;
    }

    private static String callAndWait(String base, String prefix, String api, List<Object> data, long deadline) throws Exception {
        String name = api.startsWith("/") ? api.substring(1) : api;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        HttpResponse<String> r = HTTP.send(req(base + prefix + "/call/" + name, deadline).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new AiPipeline.GenerationException("Space rejected the request: HTTP " + r.statusCode() + " " + cut(r.body()));
        Object eid = Json.parseObject(r.body()).get("event_id");
        if (eid == null) throw new AiPipeline.GenerationException("Space gave no event_id: " + cut(r.body()));

        HttpResponse<Stream<String>> s = HTTP.send(req(base + prefix + "/call/" + name + "/" + eid, deadline).GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        if (s.statusCode() != 200) throw new AiPipeline.GenerationException("event stream HTTP " + s.statusCode());
        String ev = null;
        try (Stream<String> lines = s.body()) {                                  // server-sent events: "event: x" / "data: y"
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                if (System.currentTimeMillis() > deadline) throw new AiPipeline.GenerationException("Space timed out (queue too long)");
                String l = it.next();
                if (l.startsWith("event:")) ev = l.substring(6).trim();
                else if (l.startsWith("data:")) {
                    String d = l.substring(5).trim();
                    if ("complete".equals(ev)) return d;
                    if ("error".equals(ev)) throw new AiPipeline.GenerationException("Space error: " + (d.equals("null") ? "GPU quota / model error (try HF_TOKEN)" : cut(d)));
                }
            }
        }
        throw new AiPipeline.GenerationException("Space closed the stream without a result");
    }

    /** Finds the first {path/url} file reference in a Gradio result, preferring video files. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findFile(Object o) {
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.get("video") != null) { Map<String, Object> v = findFile(m.get("video")); if (v != null) return v; }
            if (m.get("url") instanceof String || m.get("path") instanceof String) return m;
            for (Object v : m.values()) { Map<String, Object> f = findFile(v); if (f != null) return f; }
        } else if (o instanceof List) {
            for (Object v : (List<Object>) o) { Map<String, Object> f = findFile(v); if (f != null) return f; }
        }
        return null;
    }

    // ================================================================== FAL ==
    @SuppressWarnings("unchecked")
    private static Object[] viaFal(byte[] jpeg, String prompt, double secs, long deadline) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        body.put("image_url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
        HttpResponse<String> r = HTTP.send(req("https://queue.fal.run/" + FAL_MODEL, deadline, false)
                .header("Authorization", "Key " + FAL_KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new AiPipeline.GenerationException("fal.ai HTTP " + r.statusCode() + ": " + cut(r.body()));
        Map<String, Object> q = Json.parseObject(r.body());
        String statusUrl = (String) q.get("status_url"), responseUrl = (String) q.get("response_url");
        if (statusUrl == null || responseUrl == null) throw new AiPipeline.GenerationException("fal.ai queue reply not understood: " + cut(r.body()));
        while (true) {
            if (System.currentTimeMillis() > deadline) throw new AiPipeline.GenerationException("fal.ai timed out");
            Thread.sleep(3000);
            HttpResponse<String> s = HTTP.send(req(statusUrl, deadline, false).header("Authorization", "Key " + FAL_KEY).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String st = String.valueOf(Json.parseObject(s.body()).get("status"));
            if (st.equals("COMPLETED")) break;
            if (s.statusCode() / 100 != 2 || st.contains("ERROR") || st.contains("FAIL")) throw new AiPipeline.GenerationException("fal.ai job failed: " + cut(s.body()));
        }
        HttpResponse<String> res = HTTP.send(req(responseUrl, deadline, false).header("Authorization", "Key " + FAL_KEY).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> ref = findFile(Json.parse(res.body()));
        if (ref == null || !(ref.get("url") instanceof String)) throw new AiPipeline.GenerationException("fal.ai reply had no video: " + cut(res.body()));
        String url = (String) ref.get("url");
        return packVideo(download(url, deadline, false), url, secs, "fal.ai " + FAL_MODEL);
    }

    // ============================================================ http utils ==
    private static HttpRequest.Builder req(String url, long deadline) { return req(url, deadline, true); }

    private static HttpRequest.Builder req(String url, long deadline, boolean hfAuth) {
        long ms = Math.max(3000, deadline - System.currentTimeMillis());
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(ms)).header("User-Agent", "GoogleFlowJava/1.0");
        if (hfAuth && !AiPipeline.HF_TOKEN.isBlank() && (url.contains(".hf.space") || url.contains("huggingface.co")))
            b.header("Authorization", "Bearer " + AiPipeline.HF_TOKEN);
        return b;
    }

    private static byte[] download(String url, long deadline, boolean hfAuth) throws Exception {
        HttpResponse<byte[]> r = HTTP.send(req(url, deadline, hfAuth).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200 || r.body().length < 1000) throw new AiPipeline.GenerationException("video download failed: HTTP " + r.statusCode());
        return r.body();
    }

    private static Object[] packVideo(byte[] video, String url, double secs, String engine) {
        String u = url.toLowerCase();
        String ext = u.contains(".webm") ? "webm" : "mp4";
        double dur = ext.equals("mp4") ? mp4Duration(video, secs) : secs;
        System.out.println("[i2v] AI clip ready: " + ext + ", " + video.length / 1024 + " KB, " + dur + "s via " + engine);
        return new Object[]{video, ext, dur, engine};
    }

    /** Reads the length from the MP4 'mvhd' box (no library); falls back to the requested seconds. */
    static double mp4Duration(byte[] b, double dflt) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(b);
            for (int i = 4; i < b.length - 40; i++) {
                if (b[i] == 'm' && b[i + 1] == 'v' && b[i + 2] == 'h' && b[i + 3] == 'd') {
                    int ver = b[i + 4];
                    long ts, du;
                    if (ver == 1) { ts = bb.getInt(i + 24) & 0xffffffffL; du = bb.getLong(i + 28); }
                    else          { ts = bb.getInt(i + 16) & 0xffffffffL; du = bb.getInt(i + 20) & 0xffffffffL; }
                    double d = ts == 0 ? dflt : du / (double) ts;
                    return d > 0.5 && d < 60 ? Math.round(d * 10) / 10.0 : dflt;
                }
            }
        } catch (Exception ignored) { }
        return dflt;
    }

    // ============================================================ image utils ==
    static BufferedImage decodeImage(byte[] data) throws AiPipeline.GenerationException {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img != null) return img;
        } catch (Exception ignored) { }
        throw new AiPipeline.GenerationException("Uploaded file is not a readable image (use JPG / PNG / GIF)");
    }

    static byte[] toJpeg(BufferedImage src, int maxSide) throws Exception {
        double s = Math.min(1.0, maxSide / (double) Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(16, (int) Math.round(src.getWidth() * s)), h = Math.max(16, (int) Math.round(src.getHeight() * s));
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", bos);
        return bos.toByteArray();
    }

    // ===================================================== LOCAL MOTION FALLBACK ==
    /**
     * NOT an AI model: a virtual camera / motion effect chosen from the words of the prompt.
     * jump, fall, zoom in/out, pan left/right, tilt, rise, spin, shake, swim/sway ... (default: slow push-in).
     */
    private static Object[] localMotion(BufferedImage src, String prompt, double secs) throws Exception {
        boolean portrait = src.getHeight() > src.getWidth() * 1.1;
        int W = portrait ? 270 : 480, H = portrait ? 480 : 270;
        int fps = 10, n = (int) Math.round(secs * fps);
        String p = " " + prompt.toLowerCase() + " ";
        boolean jump = has(p, "jump", "leap", "hop", "bounce", "spring"), fall = has(p, "fall", "drop", "dive", "plunge", "sink", "splash");
        boolean zin = has(p, "zoom in", "closer", "approach", "push in", "towards", "toward"), zout = has(p, "zoom out", "away", "reveal", "pull back");
        boolean right = has(p, "right", "run", "walk", "race", "drive", "ride", "move", "fly"), left = has(p, "left");
        boolean up = has(p, "rise", "float", "ascend", "launch", "lift", "up"), down = has(p, "descend", "lower", "down");
        boolean spin = has(p, "spin", "rotate", "turn", "twirl", "roll"), shake = has(p, "shake", "tremble", "earthquake", "vibrate", "rumble");
        boolean sway = has(p, "swim", "wave", "sway", "dance", "wiggle", "flutter", "wag");
        boolean none = !(jump || fall || zin || zout || right || left || up || down || spin || shake || sway);
        double z0 = 1.18 + (jump ? 0.12 : 0) + (spin ? 0.12 : 0);

        double cover = Math.max((double) W / src.getWidth(), (double) H / src.getHeight());
        File tmp = File.createTempFile("flow_i2v_", ".gif");
        try {
            AnimatedGifWriter gif = new AnimatedGifWriter(tmp, 100 / fps);
            for (int i = 0; i < n; i++) {
                double t = i / (double) (n - 1), e = t * t * (3 - 2 * t);
                double zoom = 0, dx = 0, dy = 0, rot = 0;
                if (none) zoom += 0.14 * e;
                if (zin) zoom += 0.32 * e;
                if (zout) zoom += 0.32 * (1 - e);
                if (jump) { double a = Math.abs(Math.sin(3 * Math.PI * t)); dy -= 0.13 * a; zoom += 0.05 * a; }
                if (fall) { dy += -0.10 + 0.20 * e; zoom += 0.08 * e; }
                if (right && !left) dx += -0.07 + 0.14 * e;
                if (left) dx += 0.07 - 0.14 * e;
                if (up && !fall && !jump) dy += 0.08 - 0.16 * e;
                if (down && !fall) dy += -0.08 + 0.16 * e;
                if (spin) rot += 9 * Math.sin(2 * Math.PI * t);
                if (shake) { dx += 0.012 * Math.sin(50 * t); dy += 0.012 * Math.cos(43 * t); }
                if (sway) { dx += 0.03 * Math.sin(4 * Math.PI * t); rot += 3 * Math.sin(4 * Math.PI * t); }

                double z = z0 + zoom;
                double maxX = (z - 1) / 2 * W * 0.9, maxY = (z - 1) / 2 * H * 0.9;
                double tx = Math.max(-maxX, Math.min(maxX, dx * W)), ty = Math.max(-maxY, Math.min(maxY, dy * H));
                BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = out.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, W, H);
                AffineTransform at = new AffineTransform();
                at.translate(W / 2.0 + tx, H / 2.0 + ty);
                at.rotate(Math.toRadians(rot));
                at.scale(cover * z, cover * z);
                at.translate(-src.getWidth() / 2.0, -src.getHeight() / 2.0);
                g.drawImage(src, at, null);
                g.dispose();
                gif.addFrame(out);
            }
            gif.close();
            byte[] bytes = java.nio.file.Files.readAllBytes(tmp.toPath());
            System.out.println("[i2v] local motion clip: " + n + " frames, " + bytes.length / 1024 + " KB");
            return new Object[]{bytes, "gif", n / (double) fps, "local-motion-effect (not AI)"};
        } finally {
            tmp.delete();
        }
    }

    private static boolean has(String text, String... words) {
        for (String w : words) if (text.contains(w.contains(" ") ? w : " " + w)) return true;   // word start; "right" also matches "rightwards"
        return false;
    }

    // ----------------------------------------------------------------- misc --
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }
    private static String lower(Object o) { return o == null ? "" : o.toString().toLowerCase(); }
    private static String cut(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").substring(0, Math.min(160, s.replaceAll("\\s+", " ").length())); }
}
