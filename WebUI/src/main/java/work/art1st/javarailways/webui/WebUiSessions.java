package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory HttpOnly browser sessions created from one-time Bukkit grants. */
final class WebUiSessions {
    static final String COOKIE_NAME = "MR_WEB_SESSION";
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long lifetimeMillis;
    private final Clock clock;

    WebUiSessions(Duration lifetime) {
        this(lifetime, Clock.systemUTC());
    }

    WebUiSessions(Duration lifetime, Clock clock) {
        this.lifetimeMillis = lifetime.toMillis();
        this.clock = java.util.Objects.requireNonNull(clock);
        if (lifetimeMillis < 60_000L) throw new IllegalArgumentException("session lifetime is too short");
    }

    CreatedSession create(JsonNode grant) {
        long now = clock.millis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochMillis() <= now);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session = new Session(
            grant.path("playerId").asText(),
            grant.path("playerName").asText(),
            grant.path("worldId").asText(),
            Math.addExact(now, lifetimeMillis)
        );
        if (session.playerId().isBlank() || session.worldId().isBlank()) {
            throw new IllegalArgumentException("bridge returned an incomplete login grant");
        }
        sessions.put(id, session);
        return new CreatedSession(id, session);
    }

    Optional<Session> find(String id) {
        if (id == null || id.length() > 128) return Optional.empty();
        Session session = sessions.get(id);
        if (session == null) return Optional.empty();
        if (session.expiresAtEpochMillis() <= clock.millis()) {
            sessions.remove(id, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** Only explicit browser activity renews a session; lookups and stream polls remain read-only. */
    Optional<Session> renew(String id) {
        if (id == null || id.length() > 128) return Optional.empty();
        return Optional.ofNullable(sessions.computeIfPresent(id, (key, session) -> {
            long now = clock.millis();
            if (session.expiresAtEpochMillis() <= now) return null;
            return new Session(session.playerId(), session.playerName(), session.worldId(),
                Math.addExact(now, lifetimeMillis));
        }));
    }

    long cookieMaxAgeSeconds() { return lifetimeMillis / 1000L; }

    record Session(String playerId, String playerName, String worldId, long expiresAtEpochMillis) {}
    record CreatedSession(String id, Session session) {}
}
