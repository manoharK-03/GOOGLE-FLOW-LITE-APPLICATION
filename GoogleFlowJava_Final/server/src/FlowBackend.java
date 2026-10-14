import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * Google Flow - Core Backend (shared by the web AND terminal modes)
 * ====================================================================
 *  Browser --HTTP--> GoogleFlowWebServer --\
 *                                           >--TCP/JSON-lines--> FlowBackend --TCP--> FlowWorkerNode x N
 *  Terminal app -------------------------- /
 *
 * Protocol: client sends ONE JSON line {"cmd":"...", ...}, backend replies ONE JSON line.
 * The backend owns users, clips, playlists, the job queue and the scheduler.
 * Dispatcher threads pull jobs from the queue and hand them to worker nodes.
 */
public class FlowBackend {

    public static final int PORT = 9090;
    static final int[] DEFAULT_NODE_PORTS = {9101, 9102, 9103};
    static final int DISPATCHERS = 3, MAX_RETRIES = 2;
    static final double BASE_BACKOFF_MS = 1000;
    static final Set<String> BLOCKED = Set.of("violence", "gore", "nudity", "hate", "terror");
    static final Set<String> NO_AUTH = Set.of("register", "login", "media");

    static final BlockingQueue<String> jobQueue = new LinkedBlockingQueue<>();
    static final Map<String, String> sessions = new ConcurrentHashMap<>();       // token -> userId
    static final Map<Integer, FlowWorkerNode> embedded = new ConcurrentHashMap<>();
    static File storageDir = new File("storage");

    // ---------------------------------------------------------- startup --
    /** Starts the backend inside this JVM unless one is already listening. Returns true if started here. */
    public static boolean ensureRunning(String storage) throws Exception {
        if (Net.isUp("localhost", PORT)) return false;
        start(PORT, storage, DEFAULT_NODE_PORTS, true);
        return true;
    }

    public static void start(int port, String storage, int[] nodePorts, boolean embedNodes) throws Exception {
        storageDir = new File(storage).getCanonicalFile();
        storageDir.mkdirs();
        SceneCommands.cleanup(storageDir);                       // Scene Builder: delete old preview files
        Database.get();
        for (int p : nodePorts) {
            if (embedNodes) { FlowWorkerNode n = new FlowWorkerNode(p); n.start(); embedded.put(p, n); }
            Scheduler.addNode("localhost", p);
        }
        Scheduler.startHeartbeat();
        for (int i = 1; i <= DISPATCHERS; i++) {
            Thread t = new Thread(() -> dispatcherLoop(), "dispatcher-" + i);
            t.setDaemon(true);
            t.start();
        }
        ServerSocket server = new ServerSocket(port);
        Thread acceptor = new Thread(() -> {
            while (true) {
                try {
                    Socket c = server.accept();
                    Thread h = new Thread(() -> serve(c));       // thread-per-connection
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) { return; }
            }
        }, "backend-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        System.out.println("[backend] listening on TCP port " + port + " | worker nodes: " + Arrays.toString(nodePorts));
    }

    private static void serve(Socket c) {
        try (c) {
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8), true);
            String line;
            while ((line = in.readLine()) != null) {
                Map<String, Object> resp;
                try { resp = handle(Json.parseObject(line)); }
                catch (Exception e) { resp = err("Bad request: " + e); }
                out.println(Json.stringify(resp));
            }
        } catch (IOException ignored) {}
    }

    // ------------------------------------------------------- dispatcher --
    private static void dispatcherLoop() {
        Database db = Database.get();
        while (true) {
            try {
                process(db, jobQueue.take());
            } catch (InterruptedException e) { return; }
            catch (Exception e) { System.err.println("[dispatcher] " + e); }
        }
    }

    private static void process(Database db, String jobId) {
        Models.Job job = db.getJob(jobId);
        if (job == null) return;
        db.setJobStatus(jobId, "Processing", null);
        boolean image = job.isImage(), i2v = "i2v".equals(job.kind), scene = "scene".equals(job.kind);
        boolean vid = i2v || scene;                                          // replies that carry {video, ext, duration}
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cmd", i2v ? "generate_i2v" : image ? "generate_image" : "generate");   // job types, same distributed pipeline
        req.put("job_id", jobId); req.put("prompt", job.promptText);
        req.put("style", job.style); req.put("camera", job.camera);
        req.put("aspect", job.aspect); req.put("model", job.model);

        if (i2v) {                                                         // the worker is stateless: send it the picture inside the request
            try {
                req.put("image", Base64.getEncoder().encodeToString(Files.readAllBytes(new File(storageDir, job.sourceFile).toPath())));
                req.put("seconds", job.seconds);
            } catch (Exception e) {
                db.setJobStatus(jobId, "Failed", 0);
                db.refundQuota(job.userId);
                System.out.println("[dispatcher] job " + jobId.substring(0, 8) + " FAILED: uploaded image missing");
                return;
            }
        }

        if (scene) {                                                       // Scene Builder: the clips from the Library travel inside the request
            try {
                req = SceneCommands.requestForJob(job, db);
            } catch (Exception e) {
                db.setJobStatus(jobId, "Failed", 0);
                System.out.println("[dispatcher] job " + jobId.substring(0, 8) + " FAILED: " + e.getMessage());
                return;
            }
        }

        int attempt = 0;
        while (true) {
            try {
                Map<String, Object> r = Scheduler.dispatch(req);          // load-balanced + failover
                if (!Boolean.TRUE.equals(r.get("ok"))) throw new Exception(String.valueOf(r.get("error")));
                String ext = vid ? String.valueOf(r.get("ext")).replaceAll("[^a-z0-9]", "") : image ? "png" : "gif";
                String file = jobId + "." + ext;
                Files.write(new File(storageDir, file).toPath(), Base64.getDecoder().decode((String) r.get(vid ? "video" : image ? "png" : "gif")));
                db.setJobNode(jobId, String.valueOf(r.get("node")));
                if (vid) db.setJobEngine(jobId, String.valueOf(r.get("engine")));
                db.createAsset(job, file, image ? 0.0 : ((Number) r.get("duration")).doubleValue());
                db.setJobStatus(jobId, "Completed", null);
                if (i2v) new File(storageDir, job.sourceFile).delete();
                System.out.println("[dispatcher] job " + jobId.substring(0, 8) + " COMPLETED by " + r.get("node"));
                return;
            } catch (Exception e) {
                attempt++;
                if (attempt > MAX_RETRIES) {
                    db.setJobStatus(jobId, "Failed", attempt - 1);
                    if (i2v) new File(storageDir, job.sourceFile).delete();
                    db.refundQuota(job.userId);                           // fault tolerance: give the credit back
                    System.out.println("[dispatcher] job " + jobId.substring(0, 8) + " FAILED: " + e.getMessage());
                    return;
                }
                long backoff = (long) (BASE_BACKOFF_MS * Math.pow(2, attempt - 1) + Math.random() * 300);
                System.out.println("[dispatcher] job " + jobId.substring(0, 8) + " error (" + e.getMessage()
                        + "); retry " + attempt + "/" + MAX_RETRIES + " in " + backoff + "ms");   // exponential backoff
                db.setJobStatus(jobId, "Waiting", attempt);
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { return; }
                db.setJobStatus(jobId, "Processing", attempt);
            }
        }
    }

    // --------------------------------------------------------- commands --
    static Map<String, Object> handle(Map<String, Object> r) throws Exception {
        Database db = Database.get();
        String cmd = str(r, "cmd");
        if (cmd == null) return err("Missing cmd");
        String tk = str(r, "token");
        String uid = tk == null ? null : sessions.get(tk);
        if (uid == null && !NO_AUTH.contains(cmd)) return err("Not authenticated. Please log in.");

        switch (cmd) {
            case "register": {
                if (blank(str(r, "name")) || blank(str(r, "email")) || blank(str(r, "password")))
                    return err("name, email and password are required");
                if (db.createUser(str(r, "name"), str(r, "email"), str(r, "password")) == null)
                    return err("Email already registered");
                return ok("message", "Registration successful");
            }
            case "login": {
                Models.User u = db.verifyLogin(str(r, "email"), str(r, "password"));
                if (u == null) return err("Invalid email or password");
                String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
                sessions.put(token, u.userId);
                Map<String, Object> m = ok("message", "Welcome back, " + u.name + "!");
                m.put("token", token); m.put("user", u.toPublicMap());
                return m;
            }
            case "logout":
                if (tk != null) sessions.remove(tk);
                return ok("message", "Logged out");

            case "submit": return submit(r, uid, db);
            case "ref_upload": case "ref_list": case "ref_update": case "ref_delete":   // NEW: My Assets (character & asset consistency)
                return RefCommands.handle(cmd, r, uid);
            case "scene_info": return SceneCommands.info();                 // NEW: Scene Builder / Video Timeline
            case "scene_preview": return SceneCommands.preview(r, uid, db);
            case "scene_submit": return SceneCommands.submit(r, uid, db);
            case "enhance": return enhance(r, uid);                  // NEW: AI Prompt Assistant
            case "status": {
                Models.Job j = db.getJob(str(r, "job"));
                if (j == null || !j.userId.equals(uid)) return err("Job not found");
                return ok("job", j.toMap());
            }
            case "history": {
                List<Object> l = new ArrayList<>();
                for (Models.Job j : db.getHistory(uid)) l.add(j.toMap());
                return ok("history", l);
            }
            case "library": return ok("library", assetList(db.getLibrary(uid)));
            case "home": {
                Map<String, Object> m = ok("recent", assetList(first(db.getLibrary(uid), 6)));
                m.put("tv", assetList(first(db.getPublic(), 12)));
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("clips", db.getLibrary(uid).size());
                st.put("playlists", db.getPlaylists(uid).size());
                st.put("jobs", db.getHistory(uid).size());
                m.put("stats", st);
                return m;
            }
            case "search": return ok("results", assetList(db.search(uid, str(r, "q"))));
            case "like": case "publish": case "delete": {
                Models.Asset a = db.getOwnedAsset(uid, str(r, "asset_id"));
                if (a == null) return err("Clip not found");
                if (cmd.equals("like")) db.toggleLike(a);
                else if (cmd.equals("publish")) db.togglePublic(a);
                else new File(storageDir, db.deleteAsset(a)).delete();
                return ok("message", "Done");
            }
            case "pl_list": {
                List<Object> l = new ArrayList<>();
                for (Models.Playlist p : db.getPlaylists(uid)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("playlist_id", p.playlistId); m.put("name", p.name);
                    List<Models.Asset> clips = new ArrayList<>();
                    for (String id : p.assetIds) { Models.Asset a = db.getAsset(id); if (a != null) clips.add(a); }
                    m.put("clips", assetList(clips));
                    l.add(m);
                }
                return ok("playlists", l);
            }
            case "pl_create":
                if (blank(str(r, "name"))) return err("Playlist name required");
                db.createPlaylist(uid, str(r, "name").trim());
                return ok("message", "Playlist created");
            case "pl_add": case "pl_remove": case "pl_delete": {
                Models.Playlist p = db.getOwnedPlaylist(uid, str(r, "playlist_id"));
                if (p == null) return err("Playlist not found");
                if (cmd.equals("pl_delete")) { db.deletePlaylist(p); return ok("message", "Deleted"); }
                Models.Asset a = db.getOwnedAsset(uid, str(r, "asset_id"));
                if (cmd.equals("pl_add")) {
                    if (a == null) return err("Clip not found");
                    db.addToPlaylist(p, a.assetId);
                } else db.removeFromPlaylist(p, str(r, "asset_id"));
                return ok("message", "Done");
            }
            case "quota": {
                Map<String, Object> m = ok("plan_type", db.getUser(uid).planType);
                m.putAll(db.getQuota(uid).toMap());
                return m;
            }
            case "cluster": {
                Map<String, Object> m = ok("nodes", Scheduler.snapshot());
                m.put("queue_length", jobQueue.size());
                m.put("dispatchers", DISPATCHERS);
                return m;
            }
            case "node_toggle": {                       // demo helper: simulate a node crash / recovery
                int port = (int) Double.parseDouble(str(r, "port"));
                FlowWorkerNode n = embedded.get(port);
                if (n == null) return err("Node " + port + " is not embedded in this backend (stop it manually)");
                if (n.isRunning()) n.stop();
                else { FlowWorkerNode fresh = new FlowWorkerNode(port); fresh.start(); embedded.put(port, fresh); }
                return ok("message", "Toggled node-" + port);
            }
            case "media": {
                String f = str(r, "file");
                if (f == null || !f.matches("[A-Za-z0-9._-]+") || f.contains("..") || f.startsWith("src_")) return err("Bad file name");
                File file = new File(storageDir, f);
                if (!file.isFile()) return err("Not found");
                return ok("data", Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath())));
            }
            default: return err("Unknown command: " + cmd);
        }
    }

    // ---------------------------------------------- AI Prompt Assistant (new) --
    private static final Map<String, Long> lastEnhance = new ConcurrentHashMap<>();

    /** Simple idea -> cinematic prompt. Runs on a worker node (load-balanced, with failover) - not counted against the quota. */
    private static Map<String, Object> enhance(Map<String, Object> r, String uid) {
        String idea = str(r, "idea");
        if (blank(idea)) return err("Type a simple idea first, then press Enhance");
        if (idea.length() > 500) return err("Idea is too long (max 500 characters)");
        for (String bad : BLOCKED)
            if (idea.toLowerCase().contains(bad)) return err("Idea rejected by content moderation policy");
        long now = System.currentTimeMillis();
        Long last = lastEnhance.put(uid, now);
        if (last != null && now - last < 1000) return err("Please wait a second before asking again");

        String target = Set.of("video", "image", "i2v").contains(String.valueOf(str(r, "target")).toLowerCase())
                ? str(r, "target").toLowerCase() : "video";
        long seed = r.get("seed") instanceof Number ? ((Number) r.get("seed")).longValue() : System.nanoTime();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cmd", "enhance_prompt"); req.put("idea", idea); req.put("target", target);
        req.put("style", str(r, "style")); req.put("seed", seed);
        String prompt = null, engine = null, node = null;
        try {
            Map<String, Object> w = Scheduler.dispatch(req);
            if (Boolean.TRUE.equals(w.get("ok"))) { prompt = (String) w.get("prompt"); engine = String.valueOf(w.get("engine")); node = String.valueOf(w.get("node")); }
        } catch (IOException e) {
            System.out.println("[assistant] no worker node reachable -> backend uses the built-in template");
        }
        if (prompt == null) {                                          // last line of defence: the button must never fail
            prompt = PromptAssistant.localTemplate(idea, target, str(r, "style"), seed);
            engine = "local-template (not an LLM)"; node = "backend";
        }
        Map<String, Object> m = ok("prompt", prompt);
        m.put("engine", engine); m.put("node", node);
        return m;
    }

    private static Map<String, Object> submit(Map<String, Object> r, String uid, Database db) throws Exception {
        String prompt = str(r, "prompt_text");
        if (blank(prompt) && "i2v".equalsIgnoreCase(str(r, "kind"))) prompt = "subtle natural motion";
        if (blank(prompt)) return err("Prompt text cannot be empty");
        for (String bad : BLOCKED)
            if (prompt.toLowerCase().contains(bad)) return err("Prompt rejected by content moderation policy");

        String extendOf = str(r, "extend_of");
        if (!blank(extendOf)) {                       // "Extend" feature: continue an existing clip
            Models.Asset base = db.getOwnedAsset(uid, extendOf);
            if (base == null) return err("Clip to extend not found");
            if (base.isImage()) return err("Only video clips can be extended");
            prompt = base.prompt + " ... then: " + prompt;
        }
        String kind = "image".equalsIgnoreCase(str(r, "kind")) ? "image" : "i2v".equalsIgnoreCase(str(r, "kind")) ? "i2v" : "video";
        byte[] upload = null;
        if (kind.equals("i2v")) {                                       // NEW: Image -> Video (uploaded picture travels as base64 in the JSON line)
            String b64 = str(r, "image_b64");
            if (blank(b64)) return err("Please upload an image first");
            if (b64.length() > 11_000_000) return err("Image too large (max about 8 MB)");
            try { upload = Base64.getDecoder().decode(b64.replaceAll("\\s", "")); }
            catch (IllegalArgumentException e) { return err("Image data is corrupted"); }
            try { if (javax.imageio.ImageIO.read(new ByteArrayInputStream(upload)) == null) return err("Unsupported image format (use JPG / PNG / GIF)"); }
            catch (IOException e) { return err("Unsupported image format (use JPG / PNG / GIF)"); }
        }
        String style = or(str(r, "style"), "realistic"), camera = or(str(r, "camera"), "static");
        String aspect = or(str(r, "aspect"), "16:9"), model = or(str(r, "model"), "fast");
        if (kind.equals("image") && !Set.of("16:9", "9:16", "1:1").contains(aspect)) aspect = "16:9";
        if (!kind.equals("image") && aspect.equals("1:1")) aspect = "16:9";

        String quotaErr = db.reserveQuota(uid);
        if (quotaErr != null) return err(quotaErr);
        Models.Job job = db.createJob(uid, prompt, style, camera, aspect, model, extendOf, kind);
        if (kind.equals("i2v")) {
            int secs = "6".equals(str(r, "seconds")) || "6.0".equals(str(r, "seconds")) ? 6 : 5;
            String src = "src_" + job.jobId + ".jpg";
            try {
                Files.write(new File(storageDir, src).toPath(), upload);      // kept only until the job is finished (needed for retries)
            } catch (IOException e) {
                db.setJobStatus(job.jobId, "Failed", 0);
                db.refundQuota(uid);
                return err("Could not store the uploaded image: " + e.getMessage());
            }
            db.setI2vInfo(job.jobId, src, secs);
            job = db.getJob(job.jobId);
        }
        jobQueue.put(job.jobId);
        Map<String, Object> m = ok("message", kind.equals("image") ? "Image job queued" : kind.equals("i2v") ? "Image-to-video job queued" : "Job queued");
        m.put("job_id", job.jobId);
        return m;
    }

    // ------------------------------------------------------------- util --
    private static List<Object> assetList(List<Models.Asset> in) {
        List<Object> l = new ArrayList<>();
        for (Models.Asset a : in) l.add(a.toMap());
        return l;
    }
    private static <T> List<T> first(List<T> l, int n) { return l.size() > n ? l.subList(0, n) : l; }
    static Map<String, Object> ok(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("status", "ok"); m.put(k, v); return m;
    }
    static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("status", "error"); m.put("message", msg); return m;
    }
    private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String or(String s, String d) { return blank(s) ? d : s; }

    /**
     * Standalone backend:  java FlowBackend [storageDir] [external]
     *   default  -> 3 worker nodes are started inside this JVM (ports 9101-9103)
     *   external -> expects separately started nodes:  java FlowWorkerNode 9101 9102 9103
     */
    public static void main(String[] args) throws Exception {
        String storage = args.length > 0 ? args[0] : "storage";
        boolean external = args.length > 1 && args[1].equals("external");
        start(PORT, storage, DEFAULT_NODE_PORTS, !external);
        Thread.currentThread().join();
    }
}
