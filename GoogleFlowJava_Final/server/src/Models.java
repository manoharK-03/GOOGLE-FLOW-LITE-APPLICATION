import java.io.Serializable;
import java.util.*;

/** Data classes (users, quotas, jobs, assets, playlists). */
public class Models {

    public static class User implements Serializable {
        public String userId, name, email, passwordHash, planType, createdAt;
        public Map<String, Object> toPublicMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name); m.put("email", email); m.put("plan_type", planType);
            return m;
        }
    }

    public static class Quota implements Serializable {
        public String quotaId, userId, month;
        public int generationsUsed, generationsAllowed;
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", month); m.put("generations_used", generationsUsed);
            m.put("generations_allowed", generationsAllowed);
            return m;
        }
    }

    /** One text-to-video / text-to-image request (the unit of work that is distributed to worker nodes). */
    public static class Job implements Serializable {
        private static final long serialVersionUID = -6461803921434479397L;   // pinned: old googleflow.dat files still load
        public String jobId, userId, promptText, style, camera, aspect, model, extendOf;
        public String kind;                       // "video", "image", "i2v" (image->video) or "scene" (Scene Builder movie)  (null in old data == video)
        public String sourceFile, engine;         // i2v: uploaded picture kept in storage while the job runs / AI engine that made the clip
        public int seconds;                       // i2v: requested clip length
        public String sceneSpec;                  // scene: JSON of the timeline (scenes, aspect, format)
        public int sceneCount;                    // scene: number of scenes on the timeline
        public boolean isImage() { return "image".equals(kind); }
        public String status, createdAt, completedAt, node;
        public int retries;
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("job_id", jobId); m.put("kind", kind == null ? "video" : kind);
            m.put("engine", engine); m.put("seconds", seconds); m.put("scene_count", sceneCount);
            m.put("prompt_text", promptText); m.put("style", style);
            m.put("camera", camera); m.put("aspect", aspect); m.put("model", model);
            m.put("status", status); m.put("retries", retries); m.put("node", node);
            m.put("created_at", createdAt); m.put("completed_at", completedAt);
            return m;
        }
    }

    /** A generated clip (animated GIF) or still image (PNG). */
    public static class Asset implements Serializable {
        private static final long serialVersionUID = -313043027468514680L;    // pinned: old googleflow.dat files still load
        public String assetId, jobId, userId, author, fileName, prompt, style, createdAt;
        public String kind;                       // "video" (animated GIF) or "image" (PNG)  (null in old data == video)
        public boolean isImage() { return "image".equals(kind); }
        public double duration;
        public boolean isPublic, liked;
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("asset_id", assetId); m.put("kind", isImage() ? "image" : "video");
            m.put("prompt", prompt); m.put("style", style);
            m.put("author", author); m.put("file_name", fileName);
            m.put("media_url", "/media/" + fileName); m.put("duration", duration);
            m.put("public", isPublic); m.put("liked", liked); m.put("created_at", createdAt);
            return m;
        }
    }

    /** Playlist == a Flow "Scenebuilder" project: an ordered list of clips. */
    public static class Playlist implements Serializable {
        public String playlistId, userId, name;
        public List<String> assetIds = new ArrayList<>();
    }
}
