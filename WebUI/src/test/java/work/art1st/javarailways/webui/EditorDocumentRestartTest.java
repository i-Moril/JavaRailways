package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorDocumentRestartTest {
    @TempDir Path storage;

    @Test void savedEditorDocumentSurvivesApplicationRestartAndFreshAuthentication() throws Exception {
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/auth/exchange", exchange -> {
            byte[] body = "{\"playerId\":\"player\",\"playerName\":\"Player\",\"worldId\":\"world\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bridge.start();
        WebUiConfig config = new WebUiConfig(URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"),
            "bridge-token", storage, "127.0.0.1", false, 0, 4096, Duration.ofSeconds(2));
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        String document = """
            {"formatVersion":1,"id":"weekday","formalScriptName":"weekday.js","periodDays":2,
             "runs":[],"duties":[],"diagramLines":[],"arrivalOverrides":[]}
            """;
        Javalin app = null;
        try {
            app = new WebUiApplication(config).createApp().start(0);
            String root = "http://127.0.0.1:" + app.port();
            String cookie = login(client, root);
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/world/editor-documents"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(document)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode());
            String id = mapper.readTree(created.body()).path("id").asText();
            String updated = document.replace("\"periodDays\":2", "\"periodDays\":3");
            assertEquals(204, client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/world/editor-documents/" + id))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(updated)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            app.stop();
            app = new WebUiApplication(config).createApp().start(0);
            root = "http://127.0.0.1:" + app.port();
            URI endpoint = URI.create(root + "/api/v1/worlds/world/editor-documents/" + id);
            assertEquals(401, client.send(HttpRequest.newBuilder(endpoint).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            cookie = login(client, root);
            HttpResponse<String> listed = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/world/editor-documents"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listed.statusCode());
            assertEquals(id, mapper.readTree(listed.body()).get(0).path("id").asText());
            HttpResponse<String> loaded = client.send(HttpRequest.newBuilder(endpoint).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, loaded.statusCode());
            assertEquals(mapper.readTree(updated), mapper.readTree(loaded.body()));
            HttpResponse<String> otherWorld = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/other/editor-documents"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, otherWorld.statusCode());
            assertEquals(0, mapper.readTree(otherWorld.body()).size());
        } finally {
            if (app != null) app.stop();
            bridge.stop(0);
        }
    }

    private static String login(HttpClient client, String root) throws Exception {
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(root + "/auth/exchange"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"one-time-token\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, login.statusCode());
        return login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
