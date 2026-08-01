package work.art1st.javarailways.webui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebUiConfigTest {
    @AfterEach void clearProperties() {
        for (String key : new String[]{"javarailways.bridge.url", "javarailways.web.bind", "javarailways.web.secure-cookie", "javarailways.web.port", "javarailways.max.request.bytes", "javarailways.request.timeout.ms"}) System.clearProperty(key);
    }
    @Test void systemPropertiesOverrideDefaults() {
        System.setProperty("javarailways.bridge.url", "https://bridge.example/api");
        System.setProperty("javarailways.web.port", "8181");
        WebUiConfig config = WebUiConfig.fromEnvironment();
        assertEquals("https://bridge.example/api/", config.bridgeUrl().toString());
        assertEquals(8181, config.port());
        assertFalse(config.secureCookies());
    }
    @Test void acceptsNonLoopbackBind() {
        System.setProperty("javarailways.web.bind", "0.0.0.0");
        assertEquals("0.0.0.0", WebUiConfig.fromEnvironment().bindAddress());
    }
    @Test void rejectsInvalidPort() {
        System.setProperty("javarailways.web.port", "0");
        assertThrows(IllegalArgumentException.class, WebUiConfig::fromEnvironment);
    }
}
