package work.art1st.javarailways.webui;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory single-editor lease for a timetable document and its programs. */
final class TimetableLockManager {
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlMillis;

    TimetableLockManager() {
        this(Duration.ofMinutes(2), Clock.systemUTC());
    }

    TimetableLockManager(Duration ttl, Clock clock) {
        this.ttlMillis = ttl.toMillis();
        this.clock = java.util.Objects.requireNonNull(clock);
        if (ttlMillis < 1_000L) throw new IllegalArgumentException("lock lifetime is too short");
    }

    synchronized boolean acquire(String world, String document, String owner) {
        requireOwner(owner);
        long now = clock.millis();
        String key = key(world, document);
        Lease current = leases.get(key);
        if (current != null && current.expiresAtMillis() > now && !current.owner().equals(owner)) return false;
        leases.put(key, new Lease(owner, Math.addExact(now, ttlMillis)));
        return true;
    }

    synchronized boolean heartbeat(String world, String document, String owner) {
        requireOwner(owner);
        long now = clock.millis();
        String key = key(world, document);
        Lease current = leases.get(key);
        if (current == null) return false;
        if (current.expiresAtMillis() <= now) {
            leases.remove(key, current);
            return false;
        }
        if (!current.owner().equals(owner)) return false;
        leases.put(key, new Lease(owner, Math.addExact(now, ttlMillis)));
        return true;
    }

    synchronized void release(String world, String document, String owner) {
        String key = key(world, document);
        Lease current = leases.get(key);
        if (current != null && current.owner().equals(owner)) leases.remove(key, current);
    }

    synchronized boolean owns(String world, String document, String owner) {
        Lease current = leases.get(key(world, document));
        return current != null && current.expiresAtMillis() > clock.millis() && current.owner().equals(owner);
    }

    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128) throw new IllegalArgumentException("invalid editor lock owner");
    }

    private static String key(String world, String document) {
        return world + "\n" + document;
    }

    private record Lease(String owner, long expiresAtMillis) {}

    static final class Locked extends RuntimeException {
        Locked() { super("timetable is locked by another editor"); }
    }
}
