import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Google Flow - Distributed Scheduler (the simple distributed algorithm)
 * =========================================================================
 *  1. LEAST-LOADED LOAD BALANCING : each job goes to the UP worker node with
 *     the fewest in-flight jobs (tie -> the node that finished fewer jobs).
 *  2. HEARTBEAT FAILURE DETECTION : every 3 s each node is pinged; no reply
 *     => marked DOWN, reply again => marked UP (auto-recovery).
 *  3. FAILOVER : if a node refuses/drops the connection mid-dispatch, it is
 *     marked DOWN at once and the SAME job is re-sent to another node.
 */
public class Scheduler {

    public static class Node {
        final String host; final int port;
        volatile boolean up = true;
        final AtomicInteger active = new AtomicInteger(), done = new AtomicInteger(), failed = new AtomicInteger();
        Node(String host, int port) { this.host = host; this.port = port; }
        String name() { return "node-" + port; }
    }

    static final List<Node> nodes = new CopyOnWriteArrayList<>();
    /** Real AI calls on a free tier can queue for a while, so allow a long wait before declaring a node dead. */
    static final int WORKER_TIMEOUT_MS = 300_000;

    public static void addNode(String host, int port) { nodes.add(new Node(host, port)); }

    /** Algorithm step 1: pick the least-loaded live node not already tried for this job. */
    private static synchronized Node pick(Set<Node> tried) {
        Node best = null;
        for (Node n : nodes) {
            if (!n.up || tried.contains(n)) continue;
            if (best == null || n.active.get() < best.active.get()
                    || (n.active.get() == best.active.get() && n.done.get() < best.done.get())) best = n;
        }
        if (best != null) best.active.incrementAndGet();
        return best;
    }

    /** Sends a generate request; returns the worker's reply (ok true/false). Throws IOException if no node is reachable. */
    public static Map<String, Object> dispatch(Map<String, Object> req) throws IOException {
        Set<Node> tried = new HashSet<>();
        while (true) {
            Node n = pick(tried);
            if (n == null) throw new IOException("No worker node available");
            tried.add(n);
            System.out.println("[scheduler] job -> " + n.name() + " (in-flight=" + n.active.get() + ")");
            try {
                Map<String, Object> resp = Net.call(n.host, n.port, req, WORKER_TIMEOUT_MS);
                if (Boolean.TRUE.equals(resp.get("ok"))) n.done.incrementAndGet(); else n.failed.incrementAndGet();
                return resp;
            } catch (IOException e) {                         // algorithm step 3: failover
                n.up = false;
                n.failed.incrementAndGet();
                System.out.println("[scheduler] " + n.name() + " unreachable -> marked DOWN, failing over");
            } finally {
                n.active.decrementAndGet();
            }
        }
    }

    /** Algorithm step 2: heartbeat thread. */
    public static void startHeartbeat() {
        Thread t = new Thread(() -> {
            while (true) {
                for (Node n : nodes) {
                    boolean alive;
                    try {
                        Map<String, Object> ping = new HashMap<>();
                        ping.put("cmd", "ping");
                        alive = Boolean.TRUE.equals(Net.call(n.host, n.port, ping, 1500).get("ok"));
                    } catch (IOException e) { alive = false; }
                    if (alive != n.up) System.out.println("[heartbeat] " + n.name() + (alive ? " is back UP" : " is DOWN"));
                    n.up = alive;
                }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "heartbeat");
        t.setDaemon(true);
        t.start();
    }

    public static List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Node n : nodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n.name()); m.put("host", n.host); m.put("port", n.port);
            m.put("up", n.up); m.put("active", n.active.get());
            m.put("done", n.done.get()); m.put("failed", n.failed.get());
            list.add(m);
        }
        return list;
    }
}
