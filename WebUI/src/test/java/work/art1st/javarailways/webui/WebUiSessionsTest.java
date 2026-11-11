package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WebUiSessionsTest {
    @Test void readsNeverRenewAndExpiryIncludesTheBoundary() throws Exception {
        SessionTestClock clock = new SessionTestClock();
        WebUiSessions sessions = new WebUiSessions(Duration.ofMinutes(30), clock);
        var created = sessions.create(new ObjectMapper().readTree("""
            {"playerId":"player-1","playerName":"Alice","worldId":"world"}
            """));
        clock.advance(Duration.ofMinutes(29));
        assertEquals(created.session(), sessions.find(created.id()).orElseThrow());
        clock.advance(Duration.ofMinutes(1));
        assertTrue(sessions.find(created.id()).isEmpty());
        assertTrue(sessions.renew(created.id()).isEmpty());
    }

    @Test void activityExtendsOnlyAnUnexpiredSession() throws Exception {
        SessionTestClock clock = new SessionTestClock();
        WebUiSessions sessions = new WebUiSessions(Duration.ofMinutes(30), clock);
        var created = sessions.create(new ObjectMapper().readTree("""
            {"playerId":"player-1","playerName":"Alice","worldId":"world"}
            """));
        clock.advance(Duration.ofMinutes(29));
        var renewed = sessions.renew(created.id()).orElseThrow();
        assertEquals(clock.millis() + Duration.ofMinutes(30).toMillis(), renewed.expiresAtEpochMillis());
        assertEquals(created.session().playerId(), renewed.playerId());
        assertEquals(created.session().worldId(), renewed.worldId());
        clock.advance(Duration.ofMinutes(1));
        assertEquals(renewed, sessions.find(created.id()).orElseThrow());
        clock.advance(Duration.ofMinutes(29));
        // Expired entries cannot be resurrected even before any lookup removes them.
        assertTrue(sessions.renew(created.id()).isEmpty());
        assertTrue(sessions.find(created.id()).isEmpty());
        assertTrue(sessions.renew(null).isEmpty());
        assertTrue(sessions.renew("missing").isEmpty());
        assertTrue(sessions.renew("x".repeat(129)).isEmpty());
    }

    @Test void createsOpaqueAuthenticatedSession() throws Exception {
        WebUiSessions sessions = new WebUiSessions(Duration.ofMinutes(5));
        var grant = new ObjectMapper().readTree("""
            {"playerId":"player-1","playerName":"Alice","worldId":"world"}
            """);

        var created = sessions.create(grant);

        assertTrue(created.id().length() >= 32);
        assertEquals("world", sessions.find(created.id()).orElseThrow().worldId());
        assertTrue(sessions.find("missing").isEmpty());
    }
}
