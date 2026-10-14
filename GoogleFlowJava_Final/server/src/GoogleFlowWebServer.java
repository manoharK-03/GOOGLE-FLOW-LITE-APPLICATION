import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * MODE 1 - Google Flow Web Server (HTML/CSS/JS front end)
 * ========================================================
 * A hand-written HTTP/1.1 server on a raw java.net.ServerSocket (port 8080).
 * It serves the website and acts as an API GATEWAY: every /api/<cmd> request
 * is turned into a JSON line and forwarded over a TCP socket to FlowBackend.
 * (If no backend is running, it starts one inside this JVM.)
 *
 *   java GoogleFlowWebServer [publicDir] [storageDir]
 */
public class GoogleFlowWebServer {

    public static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        File publicDir = new File(args.length > 0 ? args[0] : "public").getCanonicalFile();
        String storage = args.length > 1 ? args[1] : "storage";

        boolean started = FlowBackend.ensureRunning(storage);
        System.out.println(started ? "[web] no backend found -> started one in this JVM"
                                   : "[web] connected to existing backend on port " + FlowBackend.PORT);

        ServerSocket server = new ServerSocket(PORT);
        System.out.println("=========================================================");
        System.out.println(" GOOGLE FLOW (web)  ->  http://localhost:" + PORT);
        System.out.println("=========================================================");
        while (true) {
            Socket client = server.accept();
            new Thread(new ClientHandler(client, publicDir, "localhost", FlowBackend.PORT)).start(); // thread per connection
        }
    }
}
