import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Google Flow - Worker Node (the "AI render server")
 * ====================================================
 * A tiny TCP server. The backend's scheduler connects to it, sends one
 * JSON line {"cmd":"generate", ...} (text->video) or {"cmd":"generate_image", ...}
 * (text->image), and gets back one JSON line with the result (base64 GIF / PNG). Several nodes run on different ports (or machines):
 *     java FlowWorkerNode 9101 9102 9103
 * Nodes share NOTHING with the backend except messages - a classic
 * distributed design (no shared memory / disk).
 */
public class FlowWorkerNode {

    static final double FAIL_PROBABILITY = Config.getDouble("FLOW_FAIL_PROB", 0.0);   // set e.g. 0.15 to demo retries

    private final int port;
    private ServerSocket ss;
    private volatile boolean running;

    public FlowWorkerNode(int port) { this.port = port; }

    public void start() throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(this::acceptLoop, "node-" + port);
        t.setDaemon(true);
        t.start();
        System.out.println("[node-" + port + "] online");
    }

    public void stop() {
        running = false;
        try { ss.close(); } catch (IOException ignored) {}
        System.out.println("[node-" + port + "] OFFLINE (simulated crash)");
    }

    public boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = ss.accept();
                Thread h = new Thread(() -> handle(s));
                h.setDaemon(true);
                h.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) {
        try (s) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            String line = in.readLine();
            if (line == null) return;
            Map<String, Object> req = Json.parseObject(line);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("node", "node-" + port);
            String cmd = String.valueOf(req.get("cmd"));
            if (cmd.equals("ping")) {
                resp.put("ok", true);
            } else if (cmd.equals("generate")) {
                System.out.println("[node-" + port + "] rendering: " + req.get("prompt"));
                try {
                    Object[] r = AiPipeline.generateClip(str(req, "prompt"), str(req, "style"), str(req, "camera"),
                            str(req, "aspect"), str(req, "model"), FAIL_PROBABILITY);
                    resp.put("ok", true);
                    resp.put("gif", Base64.getEncoder().encodeToString((byte[]) r[0]));
                    resp.put("duration", r[1]);
                } catch (AiPipeline.GenerationException e) {
                    resp.put("ok", false);
                    resp.put("error", e.getMessage());
                }
            } else if (cmd.equals("generate_image")) {                 // NEW: Text -> Image
                System.out.println("[node-" + port + "] painting image: " + req.get("prompt"));
                try {
                    Object[] r = AiPipeline.generateImage(str(req, "prompt"), str(req, "style"),
                            str(req, "aspect"), str(req, "model"), FAIL_PROBABILITY);
                    resp.put("ok", true);
                    resp.put("png", Base64.getEncoder().encodeToString((byte[]) r[0]));
                    resp.put("width", r[1]);
                    resp.put("height", r[2]);
                } catch (AiPipeline.GenerationException e) {
                    resp.put("ok", false);
                    resp.put("error", e.getMessage());
                }
            } else if (cmd.equals("enhance_prompt")) {                 // NEW: AI Prompt Assistant (idea -> cinematic prompt)
                System.out.println("[node-" + port + "] enhancing prompt: " + req.get("idea"));
                long seed = req.get("seed") instanceof Number ? ((Number) req.get("seed")).longValue() : System.nanoTime();
                Object[] r = PromptAssistant.expand(str(req, "idea"), str(req, "target"), str(req, "style"), seed);
                resp.put("ok", true);
                resp.put("prompt", r[0]);
                resp.put("engine", r[1]);
            } else if (cmd.equals("generate_i2v")) {                   // NEW: Image -> Video
                System.out.println("[node-" + port + "] animating uploaded image: " + req.get("prompt"));
                try {
                    byte[] img = Base64.getDecoder().decode(str(req, "image"));
                    double secs = req.get("seconds") instanceof Number ? ((Number) req.get("seconds")).doubleValue() : 5;
                    Object[] r = I2VPipeline.generate(img, str(req, "prompt"), secs, FAIL_PROBABILITY);
                    resp.put("ok", true);
                    resp.put("video", Base64.getEncoder().encodeToString((byte[]) r[0]));
                    resp.put("ext", r[1]);
                    resp.put("duration", r[2]);
                    resp.put("engine", r[3]);
                } catch (AiPipeline.GenerationException e) {
                    resp.put("ok", false);
                    resp.put("error", e.getMessage());
                }
            } else if (cmd.equals("stitch")) {                         // NEW: Scene Builder (timeline of clips -> one movie)
                boolean preview = Boolean.TRUE.equals(req.get("preview"));
                System.out.println("[node-" + port + "] " + (preview ? "previewing" : "building") + " a scene movie");
                try {
                    if (!preview && FAIL_PROBABILITY > 0 && Math.random() < FAIL_PROBABILITY)
                        throw new AiPipeline.GenerationException("Simulated transient failure (FLOW_FAIL_PROB)");
                    List<SceneBuilder.Scene> scenes = new ArrayList<>();
                    for (Object o : (List<?>) req.get("scenes")) {
                        Map<?, ?> m = (Map<?, ?>) o;
                        SceneBuilder.Scene sc = new SceneBuilder.Scene();
                        sc.data = Base64.getDecoder().decode(String.valueOf(m.get("data")));
                        sc.name = String.valueOf(m.get("name"));
                        sc.start = m.get("start") instanceof Number ? ((Number) m.get("start")).doubleValue() : 0;
                        sc.end = m.get("end") instanceof Number ? ((Number) m.get("end")).doubleValue() : 0;
                        sc.hold = m.get("hold") instanceof Number ? ((Number) m.get("hold")).doubleValue() : 3;
                        scenes.add(sc);
                    }
                    Object[] r = SceneBuilder.render(scenes, str(req, "aspect"), str(req, "format"), preview);
                    resp.put("ok", true);
                    resp.put("video", Base64.getEncoder().encodeToString((byte[]) r[0]));
                    resp.put("ext", r[1]); resp.put("duration", r[2]);
                    resp.put("width", r[3]); resp.put("height", r[4]); resp.put("engine", r[5]);
                } catch (Exception | OutOfMemoryError e) {              // always answer, so the scheduler does not mistake it for a dead node
                    resp.put("ok", false);
                    resp.put("error", e instanceof OutOfMemoryError ? "Not enough memory to build this movie (use fewer / shorter scenes)" : e.getMessage());
                }
            } else {
                resp.put("ok", false);
                resp.put("error", "unknown command");
            }
            out.println(Json.stringify(resp));
        } catch (Exception e) {
            System.err.println("[node-" + port + "] " + e);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    /** Standalone: java FlowWorkerNode 9101 9102 ... */
    public static void main(String[] args) throws Exception {
        int[] ports = args.length == 0 ? new int[]{9101} : Arrays.stream(args).mapToInt(Integer::parseInt).toArray();
        for (int p : ports) new FlowWorkerNode(p).start();
        Thread.currentThread().join();
    }
}
