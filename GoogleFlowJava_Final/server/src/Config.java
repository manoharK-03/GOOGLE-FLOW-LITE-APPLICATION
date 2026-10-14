import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Google Flow - tiny configuration helper.
 * Looks a setting up in this order:
 *   1. environment variable   (export HF_TOKEN=...   /   set HF_TOKEN=... on Windows)
 *   2. server/config.properties  (the scripts always start in the server/ folder)
 *   3. the default given in code
 */
public class Config {
    private static final Properties FILE = new Properties();
    static {
        try (InputStream in = new FileInputStream("config.properties")) {
            FILE.load(in);
        } catch (IOException ignored) { /* file is optional */ }
    }

    public static String get(String key, String def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) v = FILE.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    public static int getInt(String key, int def) {
        try { return Integer.parseInt(get(key, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    public static double getDouble(String key, double def) {
        try { return Double.parseDouble(get(key, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    public static boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }
}
