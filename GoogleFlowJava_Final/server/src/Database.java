import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Flow - Database Layer
 * ===========================
 * Implements the logical schema from the SRS (§3.4): users, quotas,
 * generation_jobs, video_assets.
 *
 * A real deployment would swap this for a proper relational database
 * (as the SRS specifies: "polyglot persistence" - relational DB for
 * structured data + object storage for media). To keep this a pure-JDK,
 * zero-dependency, single-file-compile project (no JDBC driver, no
 * Maven), records are kept in thread-safe in-memory maps and snapshotted
 * to a single file (googleflow.dat) via Java serialization on every write
 * and reloaded on startup, so data survives a server restart.
 */
public class Database {

    public static final int DEFAULT_MONTHLY_QUOTA = 5;
    private static final String DATA_FILE = "googleflow.dat";
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

    private final Map<String, Models.User> usersById = new ConcurrentHashMap<>();
    private final Map<String, String> emailToUserId = new ConcurrentHashMap<>();
    private final Map<String, Models.Quota> quotas = new ConcurrentHashMap<>(); // key: userId|month
    private final Map<String, Models.Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Models.Asset> assets = new ConcurrentHashMap<>();
    private final Map<String, Models.Playlist> playlists = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private static final Database INSTANCE = new Database();
    public static Database get() { return INSTANCE; }

    private Database() { load(); }

    // ------------------------------------------------------------ util --
    private static String now() { return Instant.now().toString(); }
    private static String monthKey() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString().substring(0, 7);
    }
    private static String uuid() { return UUID.randomUUID().toString(); }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("googleflow_salt" + s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ----------------------------------------------------------- users --
    public synchronized Models.User createUser(String name, String email, String password) {
        if (emailToUserId.containsKey(email)) return null;
        Models.User u = new Models.User();
        u.userId = uuid();
        u.name = name;
        u.email = email;
        u.passwordHash = sha256(password);
        u.planType = "free";
        u.createdAt = now();
        usersById.put(u.userId, u);
        emailToUserId.put(email, u.userId);
        ensureQuota(u.userId);
        persist();
        return u;
    }

    public Models.User verifyLogin(String email, String password) {
        String uid = emailToUserId.get(email);
        if (uid == null) return null;
        Models.User u = usersById.get(uid);
        if (u == null || !u.passwordHash.equals(sha256(password))) return null;
        return u;
    }

    public Models.User getUser(String userId) { return usersById.get(userId); }

    // ---------------------------------------------------------- quotas --
    private synchronized Models.Quota ensureQuota(String userId) {
        String key = userId + "|" + monthKey();
        return quotas.computeIfAbsent(key, k -> {
            Models.Quota q = new Models.Quota();
            q.quotaId = uuid();
            q.userId = userId;
            q.month = monthKey();
            q.generationsUsed = 0;
            q.generationsAllowed = DEFAULT_MONTHLY_QUOTA;
            return q;
        });
    }

    public Models.Quota getQuota(String userId) { return ensureQuota(userId); }

    public synchronized String reserveQuota(String userId) {
        Models.Quota q = ensureQuota(userId);
        if (q.generationsUsed >= q.generationsAllowed) {
            return "Monthly quota exhausted. Upgrade your plan or wait for next month.";
        }
        q.generationsUsed++;
        persist();
        return null; // null == success
    }

    public synchronized void refundQuota(String userId) {
        Models.Quota q = ensureQuota(userId);
        if (q.generationsUsed > 0) q.generationsUsed--;
        persist();
    }

    // ------------------------------------------------------------ jobs --
    public synchronized Models.Job createJob(String userId, String promptText, String style,
                                             String camera, String aspect, String model, String extendOf, String kind) {
        Models.Job j = new Models.Job();
        j.kind = kind;
        j.jobId = uuid();
        j.userId = userId;
        j.promptText = promptText;
        j.style = style;
        j.camera = camera;
        j.aspect = aspect;
        j.model = model;
        j.extendOf = extendOf;
        j.status = "Waiting";
        j.retries = 0;
        j.createdAt = now();
        jobs.put(j.jobId, j);
        persist();
        return j;
    }

    public synchronized void setI2vInfo(String jobId, String sourceFile, int seconds) {
        Models.Job j = jobs.get(jobId);
        if (j != null) { j.sourceFile = sourceFile; j.seconds = seconds; persist(); }
    }

    public synchronized void setSceneInfo(String jobId, String spec, int count) {
        Models.Job j = jobs.get(jobId);
        if (j != null) { j.sceneSpec = spec; j.sceneCount = count; persist(); }
    }

    public synchronized void setJobEngine(String jobId, String engine) {
        Models.Job j = jobs.get(jobId);
        if (j != null) { j.engine = engine; persist(); }
    }

    public synchronized void setJobNode(String jobId, String node) {
        Models.Job j = jobs.get(jobId);
        if (j != null) { j.node = node; persist(); }
    }

    public synchronized void setJobStatus(String jobId, String status, Integer retries) {
        Models.Job j = jobs.get(jobId);
        if (j == null) return;
        j.status = status;
        if (retries != null) j.retries = retries;
        if (status.equals("Completed") || status.equals("Failed")) j.completedAt = now();
        persist();
    }

    public Models.Job getJob(String jobId) { return jobs.get(jobId); }

    public List<Models.Job> getHistory(String userId) {
        List<Models.Job> list = new ArrayList<>();
        for (Models.Job j : jobs.values()) if (j.userId.equals(userId)) list.add(j);
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }

    // ---------------------------------------------------------- assets --
    public synchronized Models.Asset createAsset(Models.Job job, String fileName, double duration) {
        Models.Asset a = new Models.Asset();
        a.assetId = uuid();
        a.jobId = job.jobId;
        a.userId = job.userId;
        Models.User u = usersById.get(job.userId);
        a.author = u == null ? "unknown" : u.name;
        a.fileName = fileName;
        a.kind = job.isImage() ? "image" : "video";          // image->video clips are videos too
        a.prompt = job.promptText;
        a.style = job.style;
        a.duration = duration;
        a.createdAt = now();
        assets.put(a.assetId, a);
        persist();
        return a;
    }

    public List<Models.Asset> getLibrary(String userId) {
        List<Models.Asset> list = new ArrayList<>();
        for (Models.Asset a : assets.values()) if (a.userId.equals(userId)) list.add(a);
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }

    /** "Flow TV": clips that owners chose to publish. */
    public List<Models.Asset> getPublic() {
        List<Models.Asset> list = new ArrayList<>();
        for (Models.Asset a : assets.values()) if (a.isPublic) list.add(a);
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }

    /** Search my clips + public clips by prompt / style / author text. */
    public List<Models.Asset> search(String userId, String q) {
        String needle = q == null ? "" : q.toLowerCase().trim();
        List<Models.Asset> list = new ArrayList<>();
        for (Models.Asset a : assets.values()) {
            if (!(a.userId.equals(userId) || a.isPublic)) continue;
            String hay = (a.prompt + " " + a.style + " " + a.author + " " + (a.isImage() ? "image" : "video")).toLowerCase();
            if (hay.contains(needle)) list.add(a);
        }
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }

    public Models.Asset getAsset(String assetId) { return assetId == null ? null : assets.get(assetId); }

    /** Returns the asset only if it belongs to the user (else null). */
    public Models.Asset getOwnedAsset(String userId, String assetId) {
        Models.Asset a = getAsset(assetId);
        return (a != null && a.userId.equals(userId)) ? a : null;
    }

    public synchronized void toggleLike(Models.Asset a) { a.liked = !a.liked; persist(); }
    public synchronized void togglePublic(Models.Asset a) { a.isPublic = !a.isPublic; persist(); }

    public synchronized String deleteAsset(Models.Asset a) {
        assets.remove(a.assetId);
        for (Models.Playlist p : playlists.values()) p.assetIds.remove(a.assetId);
        persist();
        return a.fileName;
    }

    // ------------------------------------------------------- playlists --
    public synchronized Models.Playlist createPlaylist(String userId, String name) {
        Models.Playlist p = new Models.Playlist();
        p.playlistId = uuid();
        p.userId = userId;
        p.name = name;
        playlists.put(p.playlistId, p);
        persist();
        return p;
    }

    public List<Models.Playlist> getPlaylists(String userId) {
        List<Models.Playlist> list = new ArrayList<>();
        for (Models.Playlist p : playlists.values()) if (p.userId.equals(userId)) list.add(p);
        list.sort(Comparator.comparing(p -> p.name.toLowerCase()));
        return list;
    }

    public Models.Playlist getOwnedPlaylist(String userId, String id) {
        Models.Playlist p = id == null ? null : playlists.get(id);
        return (p != null && p.userId.equals(userId)) ? p : null;
    }

    public synchronized void addToPlaylist(Models.Playlist p, String assetId) {
        if (!p.assetIds.contains(assetId)) p.assetIds.add(assetId);
        persist();
    }

    public synchronized void removeFromPlaylist(Models.Playlist p, String assetId) {
        p.assetIds.remove(assetId);
        persist();
    }

    public synchronized void deletePlaylist(Models.Playlist p) {
        playlists.remove(p.playlistId);
        persist();
    }

    // ------------------------------------------------------- persistence --
    @SuppressWarnings("unchecked")
    private void load() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return;
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
            usersById.putAll((Map<String, Models.User>) in.readObject());
            emailToUserId.putAll((Map<String, String>) in.readObject());
            quotas.putAll((Map<String, Models.Quota>) in.readObject());
            jobs.putAll((Map<String, Models.Job>) in.readObject());
            assets.putAll((Map<String, Models.Asset>) in.readObject());
            playlists.putAll((Map<String, Models.Playlist>) in.readObject());
            System.out.println("[db] loaded " + usersById.size() + " user(s), " + jobs.size() + " job(s) from " + DATA_FILE);
        } catch (Exception e) {
            System.out.println("[db] no valid existing data file, starting fresh (" + e + ")");
        }
    }

    private void persist() {
        synchronized (writeLock) {
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(DATA_FILE))) {
                out.writeObject(new HashMap<>(usersById));
                out.writeObject(new HashMap<>(emailToUserId));
                out.writeObject(new HashMap<>(quotas));
                out.writeObject(new HashMap<>(jobs));
                out.writeObject(new HashMap<>(assets));
                out.writeObject(new HashMap<>(playlists));
            } catch (IOException e) {
                System.err.println("[db] persist failed: " + e);
            }
        }
    }
}
