import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Flow - CHARACTER & ASSET CONSISTENCY ("My Assets")
 * ==========================================================
 * A user can upload a picture of a person, an object or a location, give it a name (and an optional text
 * description) and re-use it later as a REFERENCE when generating:
 *   - Image -> Video : the asset's picture itself becomes the start picture (a true image reference)
 *   - Text -> Image / Text -> Video : the asset's description is added to the prompt, so the same character /
 *     object / place is described the same way every time (consistency through text)
 *
 * Commands (login needed): ref_upload, ref_list, ref_update, ref_delete.
 * Pictures are stored in storage/ as ref_<id>.jpg (re-encoded, max 1024 px) and served by the normal /media/ route.
 * The records live in their own file refassets.dat, so googleflow.dat and the Library are not touched.
 */
public class RefCommands {
    static class Ref implements Serializable {
        private static final long serialVersionUID = 1L;
        String refId, userId, name, type, description, fileName, createdAt;
    }

    static final int MAX_PER_USER = 30;
    static final Set<String> TYPES = Set.of("character", "object", "location");
    static final File DATA = new File("refassets.dat");
    static final Map<String, Ref> refs = new ConcurrentHashMap<>();

    static {
        if (DATA.isFile()) {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(DATA))) {
                @SuppressWarnings("unchecked") Map<String, Ref> m = (Map<String, Ref>) in.readObject();
                refs.putAll(m);
                System.out.println("[assets] loaded " + refs.size() + " uploaded asset(s)");
            } catch (Exception e) { System.out.println("[assets] could not read refassets.dat: " + e); }
        }
    }

    static synchronized void persist() {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(DATA))) { out.writeObject(new HashMap<>(refs)); }
        catch (IOException e) { System.out.println("[assets] could not save: " + e); }
    }

    static Map<String, Object> handle(String cmd, Map<String, Object> r, String uid) {
        switch (cmd) {
            case "ref_upload": return upload(r, uid);
            case "ref_update": return update(r, uid);
            case "ref_delete": return delete(r, uid);
            default: return list(uid);
        }
    }

    private static String s(Map<String, Object> r, String k) { Object o = r.get(k); return o == null ? "" : o.toString().trim(); }

    private static String moderate(String... texts) {
        for (String t : texts) for (String bad : FlowBackend.BLOCKED)
            if (t.toLowerCase().contains(bad)) return "Rejected by content moderation policy";
        return null;
    }

    static Map<String, Object> toMap(Ref a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref_id", a.refId); m.put("name", a.name); m.put("type", a.type); m.put("description", a.description);
        m.put("media_url", "/media/" + a.fileName); m.put("created_at", a.createdAt);
        return m;
    }

    static Map<String, Object> list(String uid) {
        List<Object> out = new ArrayList<>();
        refs.values().stream().filter(a -> a.userId.equals(uid)).sorted((x, y) -> y.createdAt.compareTo(x.createdAt)).forEach(a -> out.add(toMap(a)));
        return FlowBackend.ok("assets", out);
    }

    static Map<String, Object> upload(Map<String, Object> r, String uid) {
        String name = s(r, "name"), desc = s(r, "description"), type = s(r, "type").toLowerCase(), b64 = s(r, "image_b64");
        if (name.isEmpty()) return FlowBackend.err("Give the asset a name");
        if (name.length() > 40) name = name.substring(0, 40);
        if (desc.length() > 300) desc = desc.substring(0, 300);
        if (!TYPES.contains(type)) type = "character";
        String bad = moderate(name, desc);
        if (bad != null) return FlowBackend.err(bad);
        if (b64.isEmpty()) return FlowBackend.err("Choose an image to upload");
        if (b64.length() > 12_000_000) return FlowBackend.err("Image is too large (max about 8 MB)");
        long mine = refs.values().stream().filter(a -> a.userId.equals(uid)).count();
        if (mine >= MAX_PER_USER) return FlowBackend.err("You can keep up to " + MAX_PER_USER + " assets - delete one first");
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
            if (src == null) return FlowBackend.err("That file is not a readable image (use PNG, JPG or GIF)");
            double k = Math.min(1.0, 1024.0 / Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(1, (int) Math.round(src.getWidth() * k)), h = Math.max(1, (int) Math.round(src.getHeight() * k));
            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, w, h, null); g.dispose();
            Ref a = new Ref();
            a.refId = UUID.randomUUID().toString(); a.userId = uid; a.name = name; a.type = type; a.description = desc;
            a.fileName = "ref_" + a.refId + ".jpg"; a.createdAt = Instant.now().toString();
            ImageIO.write(rgb, "jpg", new File(FlowBackend.storageDir, a.fileName));
            refs.put(a.refId, a);
            persist();
            Map<String, Object> m = FlowBackend.ok("message", "Asset saved");
            m.put("asset", toMap(a));
            return m;
        } catch (IllegalArgumentException | IOException e) {
            return FlowBackend.err("Could not read that image");
        }
    }

    private static Ref owned(Map<String, Object> r, String uid) {
        Ref a = refs.get(s(r, "ref_id"));
        return a != null && a.userId.equals(uid) ? a : null;
    }

    static Map<String, Object> update(Map<String, Object> r, String uid) {
        Ref a = owned(r, uid);
        if (a == null) return FlowBackend.err("Asset not found");
        String name = s(r, "name"), desc = s(r, "description");
        if (name.isEmpty()) name = a.name;
        if (name.length() > 40) name = name.substring(0, 40);
        if (desc.length() > 300) desc = desc.substring(0, 300);
        String bad = moderate(name, desc);
        if (bad != null) return FlowBackend.err(bad);
        a.name = name; a.description = desc;
        persist();
        return FlowBackend.ok("asset", toMap(a));
    }

    static Map<String, Object> delete(Map<String, Object> r, String uid) {
        Ref a = owned(r, uid);
        if (a == null) return FlowBackend.err("Asset not found");
        refs.remove(a.refId);
        new File(FlowBackend.storageDir, a.fileName).delete();
        persist();
        return FlowBackend.ok("message", "Asset deleted");
    }
}
