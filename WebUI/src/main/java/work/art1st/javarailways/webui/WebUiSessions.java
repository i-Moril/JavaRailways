package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.SecureRandom;
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

    WebUiSessions(Duration lifetime) {
        this.lifetimeMillis = lifetime.toMillis();
        if (lifetimeMillis < 60_000L) throw new IllegalArgumentException("session lifetime is too short");
    }

    CreatedSession create(JsonNode grant) {
        long now = System.currentTimeMillis();
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
        if (session.expiresAtEpochMillis() <= System.currentTimeMillis()) {
            sessions.remove(id, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    record Session(String playerId, String playerName, String worldId, long expiresAtEpochMillis) {}
    record CreatedSession(String id, Session session) {}
}
