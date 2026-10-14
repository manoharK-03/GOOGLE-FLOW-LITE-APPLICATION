import java.util.LinkedHashMap;
import java.util.Map;

/** A minimal parsed HTTP request (method, path, query params, headers, body). */
public class HttpRequest {
    public String method;
    public String path;          // path only, no query string
    public Map<String, String> query = new LinkedHashMap<>();
    public Map<String, String> headers = new LinkedHashMap<>();
    public String body = "";

    public static Map<String, String> parseQuery(String qs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (qs == null || qs.isEmpty()) return map;
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            map.put(urlDecode(k), urlDecode(v));
        }
        return map;
    }

    public static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
