import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Tiny socket client used by EVERY tier:
 *   web gateway -> backend, terminal -> backend, backend -> worker node.
 * Wire protocol: one JSON object per line over a plain TCP socket
 * (request line in, reply line out).
 */
public class Net {

    public static Map<String, Object> call(String host, int port, Map<String, Object> req, int timeoutMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            s.setSoTimeout(timeoutMs);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println(Json.stringify(req));
            String line = in.readLine();
            if (line == null) throw new IOException("connection closed without reply");
            return Json.parseObject(line);
        }
    }

    public static boolean isUp(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
