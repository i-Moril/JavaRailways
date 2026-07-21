package work.art1st.minerails.webui;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebUiApplicationTest {
    @TempDir Path drafts;

    @Test void healthIsLocalAndDoesNotRequireBridgeAuthentication() throws Exception {
        WebUiConfig config = new WebUiConfig(URI.create("http://127.0.0.1:1/"), "", drafts, "127.0.0.1", 0, 4096, Duration.ofSeconds(1));
        Javalin app = new WebUiApplication(config).createApp().start(0);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("ok"));
        } finally { app.stop(); }
    }
}
