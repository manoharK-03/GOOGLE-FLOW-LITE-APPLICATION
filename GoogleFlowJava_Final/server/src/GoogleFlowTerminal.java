import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * MODE 2 - Google Flow Terminal App
 * ===================================
 * A text-menu client. It talks to the SAME FlowBackend as the website, using
 * the same JSON-over-TCP protocol (see Net.java). If no backend is running,
 * it starts one inside this JVM.
 *
 *   java GoogleFlowTerminal [storageDir]
 */
public class GoogleFlowTerminal {

    static final Scanner in = new Scanner(System.in);
    static String token;

    @SuppressWarnings("unchecked")
    static Map<String, Object> call(String cmd, String... kv) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cmd", cmd);
        if (token != null) req.put("token", token);
        for (int i = 0; i + 1 < kv.length; i += 2) req.put(kv[i], kv[i + 1]);
        try {
            return Net.call("localhost", FlowBackend.PORT, req, 30000);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "error"); m.put("message", "Backend unreachable: " + e.getMessage());
            return m;
        }
    }

    static boolean ok(Map<String, Object> r) {
        if ("ok".equals(r.get("status"))) return true;
        System.out.println("  ! " + r.get("message"));
        return false;
    }

    static String ask(String label) { System.out.print(label); return in.nextLine().trim(); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) { return o == null ? new ArrayList<>() : (List<Map<String, Object>>) o; }
    static int num(Object o) { return o == null ? 0 : ((Number) o).intValue(); }

    static String pick(String label, String... options) {
        StringBuilder sb = new StringBuilder(label);
        for (int i = 0; i < options.length; i++) sb.append("  ").append(i + 1).append(")").append(options[i]);
        String s = ask(sb + " [1]: ");
        try { return options[Integer.parseInt(s) - 1]; } catch (Exception e) { return options[0]; }
    }

    public static void main(String[] args) throws Exception {
        boolean started = FlowBackend.ensureRunning(args.length > 0 ? args[0] : "storage");
        System.out.println("==========================================");
        System.out.println("   GOOGLE FLOW  -  terminal mode");
        System.out.println("   backend: " + (started ? "started in this JVM" : "connected to existing backend")
                + " (TCP " + FlowBackend.PORT + ")");
        System.out.println("==========================================");
        while (token == null) {
            String c = ask("\n1) Log in   2) Sign up   0) Exit > ");
            if (c.equals("0")) System.exit(0);
            if (c.equals("2")) {
                Map<String, Object> r = call("register", "name", ask("Name: "), "email", ask("Email: "), "password", ask("Password: "));
                if (ok(r)) System.out.println("  Registered. Now log in.");
            } else if (c.equals("1")) {
                Map<String, Object> r = call("login", "email", ask("Email: "), "password", ask("Password: "));
                if (ok(r)) { token = (String) r.get("token"); System.out.println("  " + r.get("message")); }
            }
        }
        mainMenu();
    }

    static void mainMenu() {
        while (true) {
            System.out.println("\n--- MAIN MENU ---");
            System.out.println(" 1) Home          2) Text -> Video   3) Search");
            System.out.println(" 4) Library       5) Playlists       6) History");
            System.out.println(" 7) Cluster       8) Account/Quota   9) Text -> Image");
            System.out.println("10) Image -> Video   11) AI Prompt Assistant   12) Scene Builder");
            System.out.println(" 0) Log out");
            switch (ask("> ")) {
                case "1": home(); break;
                case "2": generate(null, "video"); break;
                case "9": generate(null, "image"); break;
                case "10": imageToVideo(); break;
                case "11": promptAssistant(); break;
                case "12": sceneBuilder(); break;
                case "3": search(); break;
                case "4": library(); break;
                case "5": playlists(); break;
                case "6": history(); break;
                case "7": cluster(); break;
                case "8": account(); break;
                case "0": call("logout"); token = null; System.out.println("Bye!"); System.exit(0);
                default: System.out.println("  ?");
            }
        }
    }

    // ----------------------------------------------------------- views --
    static void printClips(List<Map<String, Object>> clips) {
        if (clips.isEmpty()) System.out.println("   (nothing here yet)");
        int i = 1;
        for (Map<String, Object> a : clips) {
            String p = String.valueOf(a.get("prompt"));
            if (p.length() > 50) p = p.substring(0, 50) + "...";
            System.out.printf("  %2d. %-4s %-5s %-5s %-9s \"%s\" by %s%n", i++,
                    Boolean.TRUE.equals(a.get("liked")) ? "<3" : "", Boolean.TRUE.equals(a.get("public")) ? "[TV]" : "",
                    "image".equals(a.get("kind")) ? "[IMG]" : "[VID]", a.get("style"), p, a.get("author"));
        }
    }

    static void home() {
        Map<String, Object> r = call("home");
        if (!ok(r)) return;
        @SuppressWarnings("unchecked") Map<String, Object> st = (Map<String, Object>) r.get("stats");
        System.out.println("\n=== HOME ===  clips: " + num(st.get("clips")) + " | playlists: " + num(st.get("playlists"))
                + " | jobs: " + num(st.get("jobs")));
        System.out.println("Recently created:");
        printClips(list(r.get("recent")));
        System.out.println("Flow TV (published by everyone):");
        printClips(list(r.get("tv")));
    }

    static void generate(String extendOf, String kind) {
        boolean image = kind.equals("image");
        System.out.println("\n=== TEXT -> " + (image ? "IMAGE" : "VIDEO") + (extendOf != null ? " (extend clip)" : "") + " ===");
        String prompt = ask(image ? "Describe your image: " : "Describe your scene: ");
        if (prompt.isEmpty()) return;
        prompt = assist(prompt, image ? "image" : "video", true);
        String style, camera, aspect, model;
        if (image) {
            style = pick("Style ", "realistic", "cartoon", "cinematic", "anime", "painting", "3d");
            camera = "static";
            aspect = pick("Aspect", "16:9", "9:16", "1:1");
        } else {
            style = pick("Style ", "realistic", "cartoon", "cinematic");
            camera = pick("Camera", "static", "pan", "tilt", "zoom");
            aspect = pick("Aspect", "16:9", "9:16");
        }
        model = pick("Model ", "fast", "quality");
        Map<String, Object> r = call("submit", "kind", kind, "prompt_text", prompt, "style", style, "camera", camera,
                "aspect", aspect, "model", model, "extend_of", extendOf == null ? "" : extendOf);
        if (!ok(r)) return;
        String jobId = (String) r.get("job_id"), last = "";
        System.out.println("  queued job " + jobId.substring(0, 8) + " ...");
        while (true) {
            Map<String, Object> s = call("status", "job", jobId);
            if (!ok(s)) return;
            @SuppressWarnings("unchecked") Map<String, Object> job = (Map<String, Object>) s.get("job");
            String st = job.get("status") + (num(job.get("retries")) > 0 ? " (retry " + job.get("retries") + ")" : "");
            if (!st.equals(last)) { System.out.println("  status: " + st); last = st; }
            if (job.get("status").equals("Completed")) {
                System.out.println("  Done! Rendered by " + job.get("node") + ". Open it in Library to save the "
                        + (image ? "PNG." : "GIF."));
                return;
            }
            if (job.get("status").equals("Failed")) { System.out.println("  Failed after retries - quota refunded."); return; }
            try { Thread.sleep(700); } catch (InterruptedException e) { return; }
        }
    }

    /** Image -> Video: upload a picture from disk (as base64 inside the JSON line) + a prompt -> 5-6 s clip. */
    static void imageToVideo() {
        System.out.println("\n=== IMAGE -> VIDEO ===");
        String path = ask("Path of your image file (jpg/png/gif): ").replaceAll("^\"|\"$", "");
        if (path.isEmpty()) return;
        byte[] img;
        try { img = Files.readAllBytes(new File(path).toPath()); }
        catch (Exception e) { System.out.println("  ! cannot read file: " + e.getMessage()); return; }
        String prompt = ask("What should happen? (e.g. the dog jumps out of the swimming pool): ");
        prompt = assist(prompt, "i2v", true);
        String secs = pick("Length ", "5", "6");
        Map<String, Object> r = call("submit", "kind", "i2v", "prompt_text", prompt, "seconds", secs,
                "image_b64", Base64.getEncoder().encodeToString(img));
        if (!ok(r)) return;
        String jobId = (String) r.get("job_id"), last = "";
        System.out.println("  queued job " + jobId.substring(0, 8) + " ... (AI video models can take 1-3 minutes)");
        while (true) {
            Map<String, Object> s = call("status", "job", jobId);
            if (!ok(s)) return;
            @SuppressWarnings("unchecked") Map<String, Object> job = (Map<String, Object>) s.get("job");
            String st = job.get("status") + (num(job.get("retries")) > 0 ? " (retry " + job.get("retries") + ")" : "");
            if (!st.equals(last)) { System.out.println("  status: " + st); last = st; }
            if (job.get("status").equals("Completed")) {
                System.out.println("  Done! Rendered by " + job.get("node") + " using: " + job.get("engine"));
                System.out.println("  Open it in Library and press 's' to save the video file.");
                return;
            }
            if (job.get("status").equals("Failed")) { System.out.println("  Failed after retries - quota refunded."); return; }
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * AI Prompt Assistant: simple idea -> cinematic prompt (the backend hands the job to a worker node).
     * Returns the prompt the user decided to keep (the AI one or their own).
     */
    static String assist(String idea, String target, boolean askFirst) {
        if (idea.isEmpty()) return idea;
        if (askFirst && !ask("Improve it with the AI Prompt Assistant? (y/N): ").toLowerCase().startsWith("y")) return idea;
        boolean again = false;
        while (true) {
            if (again) { try { Thread.sleep(1100); } catch (InterruptedException ignored) { } }   // server allows 1 request / second
            again = true;
            Map<String, Object> r = call("enhance", "idea", idea, "target", target);
            if (!ok(r)) return idea;
            System.out.println("\n  AI prompt  [" + r.get("engine") + ", via " + r.get("node") + "]:");
            System.out.println("  " + r.get("prompt") + "\n");
            String a = ask("Use it? [Y]es / [r]egenerate / [n]o, keep mine: ").toLowerCase();
            if (a.startsWith("r")) continue;
            return a.startsWith("n") ? idea : (String) r.get("prompt");
        }
    }

    static void promptAssistant() {
        System.out.println("\n=== AI PROMPT ASSISTANT ===");
        String idea = ask("Your simple idea (e.g. a dog jumping into a pool): ");
        if (idea.isEmpty()) return;
        String target = pick("For    ", "video", "image", "i2v");
        String result = assist(idea, target, false);
        System.out.println("  Final prompt (copy it into Text -> Video / Image / Image -> Video):\n  " + result);
    }

    // ------------------------------------------- Scene Builder / Video Timeline (NEW) --
    /** Sends a command whose arguments include a list (the timeline), which the String-only call() cannot carry. */
    static Map<String, Object> callMap(String cmd, Map<String, Object> args) {
        Map<String, Object> req = new LinkedHashMap<>(args);
        req.put("cmd", cmd);
        if (token != null) req.put("token", token);
        try { return Net.call("localhost", FlowBackend.PORT, req, 30000); }
        catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "error"); m.put("message", "Backend unreachable: " + e.getMessage());
            return m;
        }
    }

    static double dbl(String s, double def) { try { return s.isBlank() ? def : Double.parseDouble(s); } catch (NumberFormatException e) { return def; } }

    /** Combine Library clips/images into one movie: add, remove, move, trim, preview (saved as a low-res GIF), generate. */
    static void sceneBuilder() {
        System.out.println("\n=== SCENE BUILDER (video timeline) ===");
        Map<String, Object> r = call("library");
        if (!ok(r)) return;
        List<Map<String, Object>> lib = list(r.get("library"));
        if (lib.isEmpty()) { System.out.println("  Your Library is empty - generate some clips or images first."); return; }
        Map<String, Object> caps = call("scene_info");
        boolean mp4 = Boolean.TRUE.equals(caps.get("mp4"));
        List<Map<String, Object>> tl = new ArrayList<>();           // each entry: clip (library map), start, end, hold
        String aspect = "auto", format = "gif", title = "";
        while (true) {
            System.out.println("\nLibrary:");
            int i = 1;
            for (Map<String, Object> a : lib) {
                String p = String.valueOf(a.get("prompt")); if (p.length() > 45) p = p.substring(0, 45) + "...";
                boolean img = "image".equals(a.get("kind"));
                System.out.printf("  %2d. %-6s %-7s \"%s\"%n", i++, img ? "[IMG]" : "[VID]", img ? "" : a.get("duration") + " s", p);
            }
            System.out.println("\nTimeline  (aspect " + aspect + ", output " + format + (title.isEmpty() ? "" : ", title \"" + title + "\"") + "):");
            double total = 0; i = 1;
            if (tl.isEmpty()) System.out.println("  (empty)");
            for (Map<String, Object> s : tl) {
                @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) s.get("clip");
                boolean img = "image".equals(a.get("kind"));
                double d = img ? (Double) s.get("hold") : ((Double) s.get("end") > 0 ? (Double) s.get("end") : ((Number) a.get("duration")).doubleValue()) - (Double) s.get("start");
                total += d;
                String p = String.valueOf(a.get("prompt")); if (p.length() > 35) p = p.substring(0, 35) + "...";
                System.out.printf("  %2d. %-5s %-35s %s%n", i++, img ? "[IMG]" : "[VID]", "\"" + p + "\"",
                        img ? "shown " + s.get("hold") + " s" : "trim " + s.get("start") + " -> " + ((Double) s.get("end") > 0 ? s.get("end") : "end") + "  (" + String.format("%.2f", d) + " s)");
            }
            if (!tl.isEmpty()) System.out.printf("  total about %.2f s%n", total);
            String cmd = ask("a N=add clip  r N=remove  m N M=move scene N to position M  t N=re-trim  o=options  p=preview  g=generate  b=back > ");
            if (cmd.equals("b") || cmd.isEmpty()) return;
            String[] p = cmd.split("\\s+");
            try {
                switch (p[0]) {
                    case "a": {
                        Map<String, Object> clip = lib.get(Integer.parseInt(p[1]) - 1);
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("clip", clip); s.put("start", 0.0); s.put("end", 0.0); s.put("hold", 3.0);
                        trimPrompt(s, clip);
                        tl.add(s);
                        break;
                    }
                    case "r": tl.remove(Integer.parseInt(p[1]) - 1); break;
                    case "m": { Map<String, Object> s = tl.remove(Integer.parseInt(p[1]) - 1); tl.add(Math.max(0, Math.min(tl.size(), Integer.parseInt(p[2]) - 1)), s); break; }
                    case "t": { Map<String, Object> s = tl.get(Integer.parseInt(p[1]) - 1); @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) s.get("clip"); trimPrompt(s, c); break; }
                    case "o":
                        title = ask("Movie title (blank = automatic): ");
                        aspect = pick("Size", "auto", "16:9", "9:16", "1:1");
                        format = mp4 ? pick("Output", "gif", "mp4") : "gif";
                        if (!mp4) System.out.println("  (MP4 output needs ffmpeg installed; using GIF)");
                        break;
                    case "p": case "g": {
                        if (tl.isEmpty()) { System.out.println("  ! The timeline is empty."); break; }
                        Map<String, Object> args = new LinkedHashMap<>();
                        List<Object> scenes = new ArrayList<>();
                        for (Map<String, Object> s : tl) {
                            @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) s.get("clip");
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("asset_id", a.get("asset_id")); m.put("start", s.get("start")); m.put("end", s.get("end")); m.put("hold", s.get("hold"));
                            scenes.add(m);
                        }
                        args.put("scenes", scenes); args.put("aspect", aspect); args.put("format", format); args.put("title", title);
                        if (p[0].equals("p")) {
                            System.out.println("  rendering preview ...");
                            Map<String, Object> pr = callMap("scene_preview", args);
                            if (ok(pr)) { System.out.println("  Preview: " + pr.get("duration") + " s, rendered by " + pr.get("node")); save(String.valueOf(pr.get("media_url")).substring("/media/".length())); }
                        } else {
                            Map<String, Object> sr = callMap("scene_submit", args);
                            if (ok(sr)) { waitForScene((String) sr.get("job_id")); return; }
                        }
                        break;
                    }
                    default: System.out.println("  ?");
                }
            } catch (Exception e) { System.out.println("  ? check the numbers (" + e.getClass().getSimpleName() + ")"); }
        }
    }

    static void trimPrompt(Map<String, Object> s, Map<String, Object> clip) {
        if ("image".equals(clip.get("kind"))) {
            s.put("hold", Math.max(0.5, Math.min(10, dbl(ask("  Show this picture for how many seconds? [3]: "), 3))));
        } else {
            System.out.println("  Clip length: " + clip.get("duration") + " s");
            s.put("start", Math.max(0, dbl(ask("  Trim start in seconds [0]: "), 0)));
            s.put("end", Math.max(0, dbl(ask("  Trim end in seconds [blank = until the end]: "), 0)));
        }
    }

    static void waitForScene(String jobId) {
        System.out.println("  queued job " + jobId.substring(0, 8) + " ...");
        String last = "";
        while (true) {
            Map<String, Object> s = call("status", "job", jobId);
            if (!ok(s)) return;
            @SuppressWarnings("unchecked") Map<String, Object> job = (Map<String, Object>) s.get("job");
            String st = job.get("status") + (num(job.get("retries")) > 0 ? " (retry " + job.get("retries") + ")" : "");
            if (!st.equals(last)) { System.out.println("  status: " + st); last = st; }
            if (job.get("status").equals("Completed")) {
                System.out.println("  Done! Rendered by " + job.get("node") + ". Open it in Library and press 's' to save the movie.");
                return;
            }
            if (job.get("status").equals("Failed")) { System.out.println("  Failed after retries (a clip may have been deleted) - see the server log."); return; }
            try { Thread.sleep(700); } catch (InterruptedException e) { return; }
        }
    }

    static void search() {
        String q = ask("\nSearch my clips + Flow TV: ");
        Map<String, Object> r = call("search", "q", q);
        if (ok(r)) printClips(list(r.get("results")));
    }

    static void library() {
        while (true) {
            Map<String, Object> r = call("library");
            if (!ok(r)) return;
            List<Map<String, Object>> clips = list(r.get("library"));
            System.out.println("\n=== LIBRARY ===");
            printClips(clips);
            String cmd = ask("Action: l=like p=publish d=delete e=extend a=add-to-playlist s=save-gif  (e.g. 'l 2'), b=back > ");
            if (cmd.equals("b") || cmd.isEmpty()) return;
            String[] p = cmd.split("\\s+");
            int idx;
            try { idx = Integer.parseInt(p[1]) - 1; clips.get(idx); } catch (Exception e) { System.out.println("  ? use e.g. 'l 2'"); continue; }
            Map<String, Object> clip = clips.get(idx);
            String id = (String) clip.get("asset_id");
            switch (p[0]) {
                case "l": ok(call("like", "asset_id", id)); break;
                case "p": ok(call("publish", "asset_id", id)); break;
                case "d": ok(call("delete", "asset_id", id)); break;
                case "e":
                    if ("image".equals(clip.get("kind"))) System.out.println("  Only videos can be extended.");
                    else generate(id, "video");
                    break;
                case "a": addToPlaylist(id); break;
                case "s": save((String) clip.get("file_name")); break;
                default: System.out.println("  ?");
            }
        }
    }

    static void addToPlaylist(String assetId) {
        List<Map<String, Object>> pls = list(call("pl_list").get("playlists"));
        if (pls.isEmpty()) { System.out.println("  No playlists yet - create one in Playlists."); return; }
        int i = 1;
        for (Map<String, Object> p : pls) System.out.println("  " + i++ + ") " + p.get("name"));
        try {
            Map<String, Object> pl = pls.get(Integer.parseInt(ask("Playlist #: ")) - 1);
            if (ok(call("pl_add", "playlist_id", (String) pl.get("playlist_id"), "asset_id", assetId))) System.out.println("  Added.");
        } catch (Exception e) { System.out.println("  cancelled"); }
    }

    static void save(String fileName) {
        Map<String, Object> r = call("media", "file", fileName);
        if (!ok(r)) return;
        try {
            File dir = new File("downloads"); dir.mkdirs();
            File f = new File(dir, fileName);
            Files.write(f.toPath(), Base64.getDecoder().decode((String) r.get("data")));
            System.out.println("  Saved -> " + f.getPath() + "  (open it with any image viewer / video player / browser)");
        } catch (Exception e) { System.out.println("  save failed: " + e); }
    }

    static void playlists() {
        while (true) {
            List<Map<String, Object>> pls = list(call("pl_list").get("playlists"));
            System.out.println("\n=== PLAYLISTS (Scenebuilder projects) ===");
            int i = 1;
            for (Map<String, Object> p : pls) System.out.println("  " + i++ + ") " + p.get("name") + "  (" + list(p.get("clips")).size() + " clips)");
            if (pls.isEmpty()) System.out.println("   (none)");
            String c = ask("n=new, <number>=open, d <number>=delete, b=back > ");
            if (c.equals("b") || c.isEmpty()) return;
            if (c.equals("n")) { ok(call("pl_create", "name", ask("Name: "))); continue; }
            try {
                boolean del = c.startsWith("d ");
                Map<String, Object> pl = pls.get(Integer.parseInt(del ? c.substring(2).trim() : c) - 1);
                if (del) ok(call("pl_delete", "playlist_id", (String) pl.get("playlist_id")));
                else openPlaylist(pl);
            } catch (Exception e) { System.out.println("  ?"); }
        }
    }

    static void openPlaylist(Map<String, Object> pl) {
        String pid = (String) pl.get("playlist_id");
        while (true) {
            for (Map<String, Object> p : list(call("pl_list").get("playlists")))
                if (pid.equals(p.get("playlist_id"))) pl = p;
            List<Map<String, Object>> clips = list(pl.get("clips"));
            System.out.println("\n--- " + pl.get("name") + " ---");
            printClips(clips);
            String c = ask("r <number>=remove clip, b=back > ");
            if (c.equals("b") || c.isEmpty()) return;
            try {
                int idx = Integer.parseInt(c.split("\\s+")[1]) - 1;
                ok(call("pl_remove", "playlist_id", pid, "asset_id", (String) clips.get(idx).get("asset_id")));
            } catch (Exception e) { System.out.println("  ?"); }
        }
    }

    static void history() {
        Map<String, Object> r = call("history");
        if (!ok(r)) return;
        System.out.println("\n=== HISTORY ===");
        for (Map<String, Object> j : list(r.get("history"))) {
            String p = String.valueOf(j.get("prompt_text"));
            if (p.length() > 40) p = p.substring(0, 40) + "...";
            System.out.printf("  %-10s %-6s %-9s %-8s retries=%s %-10s \"%s\"%n", j.get("status"),
                    "image".equals(j.get("kind")) ? "IMAGE" : "i2v".equals(j.get("kind")) ? "IMG>V" : "VIDEO", j.get("style"),
                    j.get("model"), j.get("retries"), j.get("node") == null ? "-" : j.get("node"), p);
        }
    }

    static void cluster() {
        while (true) {
            Map<String, Object> r = call("cluster");
            if (!ok(r)) return;
            System.out.println("\n=== DISTRIBUTED CLUSTER ===  queue=" + num(r.get("queue_length")) + " dispatchers=" + num(r.get("dispatchers")));
            System.out.printf("  %-11s %-6s %-9s %-6s %-6s%n", "NODE", "STATE", "IN-FLIGHT", "DONE", "FAILED");
            for (Map<String, Object> n : list(r.get("nodes")))
                System.out.printf("  %-11s %-6s %-9d %-6d %-6d%n", n.get("name"), Boolean.TRUE.equals(n.get("up")) ? "UP" : "DOWN",
                        num(n.get("active")), num(n.get("done")), num(n.get("failed")));
            String c = ask("t <port>=crash/revive a node (demo), r=refresh, b=back > ");
            if (c.equals("b") || c.isEmpty()) return;
            if (c.startsWith("t ")) { ok(call("node_toggle", "port", c.substring(2).trim())); try { Thread.sleep(3500); } catch (Exception ignored) {} }
        }
    }

    static void account() {
        Map<String, Object> r = call("quota");
        if (ok(r)) System.out.println("\n=== ACCOUNT ===  plan=" + r.get("plan_type") + " month=" + r.get("month")
                + " used=" + num(r.get("generations_used")) + "/" + num(r.get("generations_allowed")));
    }
}
