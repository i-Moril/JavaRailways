package work.art1st.minerails.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WebUiSessionsTest {
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
