import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Google Flow - HTTP connection handler / API gateway
 * =======================================
 * One instance runs per accepted TCP connection (thread-per-connection
 * model, spawned from FlowLiteServer's accept loop). It hand-parses a
 * plain HTTP/1.1 request straight off the socket's InputStream - this
 * is the "build the web server yourself with java.net.Socket /
 * ServerSocket" approach, so the browser gets a normal website while
 * the transport underneath is exactly the required Java Socket
 * Programming (no servlet container, no Spring).
 *
 * Every response is sent with "Connection: close" to keep the parsing
 * logic simple (no keep-alive / pipelining support needed) - perfectly
 * fine for a small teaching/demo site.
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final File publicDir;
    private final String backendHost;
    private final int backendPort;

    public ClientHandler(Socket socket, File publicDir, String backendHost, int backendPort) {
        this.socket = socket;
        this.publicDir = publicDir;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
    }

    @Override
    public void run() {
        try (Socket s = socket) {
            InputStream rawIn = s.getInputStream();
            HttpRequest req = parseRequest(rawIn);
            if (req == null) return;
            OutputStream out = s.getOutputStream();

            if (req.path.startsWith("/api/")) {
                gateway(req, out);
            } else if (req.path.startsWith("/media/")) {
                serveMedia(req, out);
            } else {
                serveStatic(req, out);
            }
        } catch (Exception e) {
            System.err.println("[client-handler] " + e);
        }
    }

    // ---------------------------------------------------------- parsing --
    private HttpRequest parseRequest(InputStream in) throws IOException {
        BufferedInputStream bin = new BufferedInputStream(in);
        String requestLine = readLine(bin);
        if (requestLine == null || requestLine.isBlank()) return null;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;

        HttpRequest req = new HttpRequest();
        req.method = parts[0];
        String rawPath = parts[1];
        int qIdx = rawPath.indexOf('?');
        if (qIdx >= 0) {
            req.path = rawPath.substring(0, qIdx);
            req.query = HttpRequest.parseQuery(rawPath.substring(qIdx + 1));
        } else {
            req.path = rawPath;
        }

        String line;
        while ((line = readLine(bin)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) req.headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
        }

        int len = 0;
        try { len = Integer.parseInt(req.headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) {}
        if (len > 0) {
            byte[] body = new byte[len];
            int read = 0;
            while (read < len) {
                int n = bin.read(body, read, len - read);
                if (n < 0) break;
                read += n;
            }
            req.body = new String(body, 0, read, StandardCharsets.UTF_8);
        }
        return req;
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\r') continue;
            if (c == '\n') break;
            buf.write(c);
        }
        if (!any) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------ static files --
    private void serveStatic(HttpRequest req, OutputStream out) throws IOException {
        String rel = req.path.equals("/") ? "/index.html" : req.path;
        File file = new File(publicDir, rel).getCanonicalFile();
        if (!file.getPath().startsWith(publicDir.getCanonicalPath()) || !file.isFile()) {
            HttpResponse.send(out, 404, "text/plain", "404 Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] data = Files.readAllBytes(file.toPath());
        HttpResponse.send(out, 200, mimeType(file.getName()), data);
    }

    /** /api/<cmd>  ->  JSON line over TCP to the backend  ->  JSON response. */
    private void gateway(HttpRequest req, OutputStream out) throws IOException {
        Map<String, Object> msg = new LinkedHashMap<>(req.query);          // GET params
        if (req.body != null && req.body.trim().startsWith("{")) msg.putAll(Json.parseObject(req.body));
        msg.put("cmd", req.path.substring("/api/".length()));
        try {
            Map<String, Object> resp = Net.call(backendHost, backendPort, msg, 30000);
            int code = "ok".equals(resp.get("status")) ? 200
                    : String.valueOf(resp.get("message")).startsWith("Not authenticated") ? 401 : 400;
            HttpResponse.sendJson(out, code, resp);
        } catch (IOException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "error"); m.put("message", "Backend unreachable: " + e.getMessage());
            HttpResponse.sendJson(out, 502, m);
        }
    }

    /** /media/<file>  ->  asks the backend for the clip bytes (no shared disk needed). */
    private void serveMedia(HttpRequest req, OutputStream out) throws IOException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("cmd", "media");
        msg.put("file", req.path.substring("/media/".length()));
        try {
            Map<String, Object> resp = Net.call(backendHost, backendPort, msg, 30000);
            if (!"ok".equals(resp.get("status"))) {
                HttpResponse.send(out, 404, "text/plain", "404 Not Found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String fn = String.valueOf(msg.get("file")).toLowerCase();
            String mime = fn.endsWith(".png") ? "image/png" : fn.endsWith(".jpg") || fn.endsWith(".jpeg") ? "image/jpeg"
                    : fn.endsWith(".mp4") ? "video/mp4" : fn.endsWith(".webm") ? "video/webm" : "image/gif";
            HttpResponse.send(out, 200, mime, Base64.getDecoder().decode((String) resp.get("data")));
        } catch (IOException e) {
            HttpResponse.send(out, 502, "text/plain", "Backend unreachable".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String mimeType(String fileName) {
        if (fileName.endsWith(".html")) return "text/html; charset=utf-8";
        if (fileName.endsWith(".css")) return "text/css; charset=utf-8";
        if (fileName.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".gif")) return "image/gif";
        if (fileName.endsWith(".mp4")) return "video/mp4";
        if (fileName.endsWith(".svg")) return "image/svg+xml";
        if (fileName.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
