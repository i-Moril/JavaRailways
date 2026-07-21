package work.art1st.minerails.webui;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** Configuration is deliberately server-only: never expose bridgeToken to browser responses. */
public record WebUiConfig(URI bridgeUrl, String bridgeToken, Path draftDirectory,
                          String bindAddress, boolean secureCookies, int port,
                          int maxRequestBytes, Duration requestTimeout) {
    public static WebUiConfig fromEnvironment() {
        String bridge = value("MINERAILS_BRIDGE_URL", "minerails.bridge.url", "http://127.0.0.1:8765/");
        String token = value("MINERAILS_BRIDGE_TOKEN", "minerails.bridge.token", "");
        String drafts = value("MINERAILS_DRAFT_DIR", "minerails.draft.dir", "data/drafts");
        String bindAddress = value("MINERAILS_WEB_BIND", "minerails.web.bind", "127.0.0.1");
        if (bindAddress.isBlank() || bindAddress.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("WebUI bind address must be a non-blank host or IP address");
        }
        boolean secureCookies = bool(
            "MINERAILS_WEB_SECURE_COOKIE",
            "minerails.web.secure-cookie",
            false
        );
        int port = integer("MINERAILS_WEB_PORT", "minerails.web.port", 8080, 1, 65535);
        int max = integer("MINERAILS_MAX_REQUEST_BYTES", "minerails.max.request.bytes", 1_048_576, 1, 50_000_000);
        int timeoutMillis = integer("MINERAILS_REQUEST_TIMEOUT_MS", "minerails.request.timeout.ms", 10_000, 1, 120_000);
        URI uri = URI.create(bridge.endsWith("/") ? bridge : bridge + "/");
        if (!uri.isAbsolute() || uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("bridge URL must be an absolute http(s) URL");
        }
        return new WebUiConfig(
            uri,
            token,
            Path.of(drafts),
            bindAddress,
            secureCookies,
            port,
            max,
            Duration.ofMillis(timeoutMillis)
        );
    }

    private static String value(String environment, String property, String fallback) {
        String system = System.getProperty(property);
        return system != null && !system.isBlank() ? system : System.getenv().getOrDefault(environment, fallback);
    }

    private static boolean bool(String environment, String property, boolean fallback) {
        String raw = value(environment, property, Boolean.toString(fallback));
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("invalid " + property);
    }

    private static int integer(String environment, String property, int fallback, int minimum, int maximum) {
        int value;
        try { value = Integer.parseInt(value(environment, property, Integer.toString(fallback))); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid " + property, exception); }
        if (value < minimum || value > maximum) throw new IllegalArgumentException(property + " is out of range");
        return value;
    }
}
