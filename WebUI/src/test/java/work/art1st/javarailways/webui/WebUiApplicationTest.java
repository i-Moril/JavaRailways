package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebUiApplicationTest {
    @TempDir Path drafts;

    @Test void rejectsEditorDocumentsThatCannotBeSafelyLoadedByTheFrontend() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String valid = """
            {"formatVersion":1,"id":"t","periodDays":1,
             "runs":[],"duties":[],"diagramLines":[],"arrivalOverrides":[]}
            """;
        WebUiApplication.requireEditorDocument(mapper.readTree(valid));
        WebUiApplication.requireEditorDocument(mapper.readTree("""
            {"formatVersion":1,"id":"draft","periodDays":1,
             "runs":[{"id":"run-1","type":"PASSENGER","lineDirection":"UP","calls":[{"id":"c1","stationId":"","status":"ORIGIN","targetId":""}]}],
             "duties":[{"id":"duty-1","selector":{"type":"TRAIN_NAME","name":""},"elements":[]}],
             "diagramLines":[],"arrivalOverrides":[]}
            """));

        assertThrows(IllegalArgumentException.class, () -> WebUiApplication.requireEditorDocument(
            mapper.readTree("""
                {"formatVersion":1,"id":"t","periodDays":1,
                 "runs":[],"duties":[],"arrivalOverrides":[]}
                """)));
        assertThrows(IllegalArgumentException.class, () -> WebUiApplication.requireEditorDocument(
            mapper.readTree("""
                {"formatVersion":1,"id":"t","periodDays":1,
                 "runs":[],"duties":[{"id":"d","selector":{"type":"TRAIN_NAME"},"elements":[]}],
                 "diagramLines":[],"arrivalOverrides":[]}
                """)));
    }

    @Test void healthIsLocalAndDoesNotRequireBridgeAuthentication() throws Exception {
        WebUiConfig config = new WebUiConfig(URI.create("http://127.0.0.1:1/"), "", drafts, "127.0.0.1", false, 0, 4096, Duration.ofSeconds(1));
        Javalin app = new WebUiApplication(config).createApp().start(0);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("ok"));
            HttpResponse<String> unauthorized = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/v1/worlds"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(401, unauthorized.statusCode());
        } finally { app.stop(); }
    }
}
