import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes a minimal, valid HTTP/1.1 response directly onto the socket stream. */
public class HttpResponse {

    public static void send(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String statusText = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            default -> "OK";
        };
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("Connection: close\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    public static void sendJson(OutputStream out, int status, Object obj) throws IOException {
        send(out, status, "application/json", Json.stringify(obj).getBytes(StandardCharsets.UTF_8));
    }
}
