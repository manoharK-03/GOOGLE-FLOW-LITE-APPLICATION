import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Flow - Scene Builder, BACKEND side
 * ==========================================
 * Commands (all need a login):
 *   scene_info    what the server can do (MP4 output needs ffmpeg), limits
 *   scene_preview quick low-resolution render of the timeline, returned at once (nothing is saved to the Library)
 *   scene_submit  queue the FINAL movie as a job of kind "scene" - it then travels the normal distributed path:
 *                 job queue -> dispatcher -> Scheduler (least-loaded, failover, retry) -> worker command "stitch"
 *
 * A timeline is: {"scenes":[{"asset_id":..,"start":sec,"end":sec(0 = to the end),"hold":sec (pictures)}], "aspect":..,"format":..}
 * The backend owns the files, so it reads the chosen Library clips from storage and ships them to the (stateless)
 * worker inside the request, exactly like Image -> Video ships the uploaded picture.
 * Building a movie from clips you already own is NOT an AI generation, so it does not use the monthly quota.
 */
public class SceneCommands {

    private static final Set<String> ASPECTS = Set.of("auto", "16:9", "9:16", "1:1");
    private static final Map<String, Long> lastPreview = new ConcurrentHashMap<>();

    /** Preview files from an earlier run are not needed any more. */
    static void cleanup(File storageDir) {
        File[] old = storageDir.listFiles((d, n) -> n.startsWith("preview_"));
        if (old != null) for (File f : old) f.delete();
    }

    static Map<String, Object> info() {
        Map<String, Object> m = FlowBackend.ok("ffmpeg", SceneBuilder.ffmpegAvailable());
        m.put("mp4", SceneBuilder.mp4Available());
        m.put("max_scenes", SceneBuilder.MAX_SCENES);
        m.put("max_seconds", SceneBuilder.MAX_TOTAL_SECONDS);
        return m;
    }

    // --------------------------------------------------------- validation --
    /** The checked timeline: scenes with plain numbers, aspect and format. Throws IllegalArgumentException with a user-friendly text. */
    static class Spec {
        List<Map<String, Object>> scenes = new ArrayList<>();
        String aspect = "auto", format = "gif";
    }

    @SuppressWarnings("unchecked")
    static Spec parse(Map<String, Object> r, String uid, Database db) {
        Spec sp = new Spec();
        Object raw = r.get("scenes");
        if (!(raw instanceof List) || ((List<Object>) raw).isEmpty()) throw new IllegalArgumentException("Add at least one clip to the timeline");
        List<Object> in = (List<Object>) raw;
        if (in.size() > SceneBuilder.MAX_SCENES) throw new IllegalArgumentException("Too many scenes (max " + SceneBuilder.MAX_SCENES + ")");
        int i = 0;
        for (Object o : in) {
            i++;
            if (!(o instanceof Map)) throw new IllegalArgumentException("Scene " + i + " is invalid");
            Map<String, Object> s = (Map<String, Object>) o;
            Models.Asset a = db.getOwnedAsset(uid, s.get("asset_id") == null ? null : s.get("asset_id").toString());
            if (a == null) throw new IllegalArgumentException("Scene " + i + ": that clip is not in your Library any more");
            double start = num(s.get("start"), 0), end = num(s.get("end"), 0), hold = num(s.get("hold"), 3);
            if (start < 0 || end < 0) throw new IllegalArgumentException("Scene " + i + ": trim times cannot be negative");
            if (!a.isImage() && end > 0 && end - start < 0.1) throw new IllegalArgumentException("Scene " + i + ": trim end must be at least 0.1 s after the start");
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("asset_id", a.assetId);
            n.put("start", round(start)); n.put("end", round(end));
            n.put("hold", round(Math.max(0.5, Math.min(10, hold))));
            sp.scenes.add(n);
        }
        String asp = r.get("aspect") == null ? "auto" : r.get("aspect").toString();
        sp.aspect = ASPECTS.contains(asp) ? asp : "auto";
        sp.format = "mp4".equalsIgnoreCase(String.valueOf(r.get("format"))) ? "mp4" : "gif";
        return sp;
    }

    private static double num(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return o == null ? def : Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return def; }
    }
    private static double round(double d) { return Math.round(d * 100) / 100.0; }

    /** Builds the request for the worker command "stitch": the clip files travel as base64 (workers share nothing). */
    static Map<String, Object> workerRequest(String uid, Database db, List<Map<String, Object>> scenes, String aspect, String format, boolean preview) throws IOException {
        List<Object> list = new ArrayList<>();
        long total = 0;
        int i = 0;
        for (Map<String, Object> s : scenes) {
            i++;
            Models.Asset a = db.getOwnedAsset(uid, (String) s.get("asset_id"));
            File f = a == null ? null : new File(FlowBackend.storageDir, a.fileName);
            if (f == null || !f.isFile()) throw new IOException("Scene " + i + ": the clip file is missing (was it deleted?)");
            byte[] bytes = Files.readAllBytes(f.toPath());
            total += bytes.length;
            if (total > SceneBuilder.MAX_TOTAL_BYTES) throw new IOException("The chosen clips are too large together (max " + SceneBuilder.MAX_TOTAL_BYTES / 1024 / 1024 + " MB)");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", a.prompt == null ? "scene" : a.prompt.substring(0, Math.min(40, a.prompt.length())));
            m.put("data", Base64.getEncoder().encodeToString(bytes));
            m.put("start", s.get("start")); m.put("end", s.get("end")); m.put("hold", s.get("hold"));
            list.add(m);
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cmd", "stitch");
        req.put("scenes", list);
        req.put("aspect", aspect); req.put("format", format); req.put("preview", preview);
        return req;
    }

    // ------------------------------------------------------------ preview --
    static Map<String, Object> preview(Map<String, Object> r, String uid, Database db) {
        Spec sp;
        try { sp = parse(r, uid, db); } catch (IllegalArgumentException e) { return FlowBackend.err(e.getMessage()); }
        long now = System.currentTimeMillis();
        Long last = lastPreview.put(uid, now);
        if (last != null && now - last < 1000) return FlowBackend.err("Please wait a second before previewing again");
        try {
            Map<String, Object> w = Scheduler.dispatch(workerRequest(uid, db, sp.scenes, sp.aspect, "gif", true));
            if (!Boolean.TRUE.equals(w.get("ok"))) return FlowBackend.err("Preview failed: " + w.get("error"));
            String file = "preview_" + uid.substring(0, 8) + ".gif";                  // one preview file per user, overwritten each time
            Files.write(new File(FlowBackend.storageDir, file).toPath(), Base64.getDecoder().decode((String) w.get("video")));
            Map<String, Object> m = FlowBackend.ok("media_url", "/media/" + file);
            m.put("duration", w.get("duration")); m.put("node", w.get("node"));
            m.put("width", w.get("width")); m.put("height", w.get("height"));
            return m;
        } catch (IOException e) {
            return FlowBackend.err("Preview failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- submit --
    static Map<String, Object> submit(Map<String, Object> r, String uid, Database db) throws Exception {
        Spec sp;
        try { sp = parse(r, uid, db); } catch (IllegalArgumentException e) { return FlowBackend.err(e.getMessage()); }
        if (sp.format.equals("mp4") && !SceneBuilder.mp4Available())
            return FlowBackend.err("MP4 output needs ffmpeg (with libx264) installed on the server - choose GIF instead");

        String title = r.get("title") == null ? "" : r.get("title").toString().trim();
        if (title.length() > 80) title = title.substring(0, 80);
        for (String bad : FlowBackend.BLOCKED)
            if (title.toLowerCase().contains(bad)) return FlowBackend.err("Title rejected by content moderation policy");
        if (title.isEmpty()) title = "Scene movie (" + sp.scenes.size() + (sp.scenes.size() == 1 ? " scene)" : " scenes)");

        Models.Job job = db.createJob(uid, title, "scene", "static", sp.aspect, "fast", null, "scene");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("scenes", sp.scenes); spec.put("aspect", sp.aspect); spec.put("format", sp.format);
        db.setSceneInfo(job.jobId, Json.stringify(spec), sp.scenes.size());
        FlowBackend.jobQueue.put(job.jobId);
        Map<String, Object> m = FlowBackend.ok("message", "Scene movie queued");
        m.put("job_id", job.jobId);
        return m;
    }

    /** Called by the dispatcher: the worker request for a queued scene job. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> requestForJob(Models.Job job, Database db) throws IOException {
        Map<String, Object> spec = Json.parseObject(job.sceneSpec);
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (Object o : (List<Object>) spec.get("scenes")) scenes.add((Map<String, Object>) o);
        return workerRequest(job.userId, db, scenes, String.valueOf(spec.get("aspect")), String.valueOf(spec.get("format")), false);
    }
}
