package work.art1st.minerails.webui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebUiConfigTest {
    @AfterEach void clearProperties() {
        for (String key : new String[]{"minerails.bridge.url", "minerails.web.bind", "minerails.web.port", "minerails.max.request.bytes", "minerails.request.timeout.ms"}) System.clearProperty(key);
    }
    @Test void systemPropertiesOverrideDefaults() {
        System.setProperty("minerails.bridge.url", "https://bridge.example/api");
        System.setProperty("minerails.web.port", "8181");
        WebUiConfig config = WebUiConfig.fromEnvironment();
        assertEquals("https://bridge.example/api/", config.bridgeUrl().toString());
        assertEquals(8181, config.port());
    }
    @Test void rejectsNonLoopbackBind() {
        System.setProperty("minerails.web.bind", "0.0.0.0");
        assertThrows(IllegalArgumentException.class, WebUiConfig::fromEnvironment);
    }
    @Test void rejectsInvalidPort() {
        System.setProperty("minerails.web.port", "0");
        assertThrows(IllegalArgumentException.class, WebUiConfig::fromEnvironment);
    }
}
