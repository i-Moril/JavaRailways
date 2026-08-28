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
            {"formatVersion":1,"id":"t","formalScriptName":"weekday.js","periodDays":1,
             "runs":[],"duties":[],"diagramLines":[],"arrivalOverrides":[]}
            """));
        assertThrows(IllegalArgumentException.class, () -> WebUiApplication.requireEditorDocument(mapper.readTree("""
            {"formatVersion":1,"id":"t","formalScriptName":"../weekday.js","periodDays":1,
             "runs":[],"duties":[],"diagramLines":[],"arrivalOverrides":[]}
            """)));
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
            HttpResponse<String> session = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/auth/session"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, session.statusCode());
            assertTrue(session.body().contains("false"));
            HttpResponse<String> unauthorized = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/v1/worlds/world/metadata"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(401, unauthorized.statusCode());
        } finally { app.stop(); }
    }

    @Test void authenticatedClientsCannotRequestStationOnlyRouteValidation() throws Exception {
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/auth/exchange", exchange -> {
            byte[] body = "{\"playerId\":\"player\",\"playerName\":\"Player\",\"worldId\":\"world\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bridge.start();
        WebUiConfig config = new WebUiConfig(
            URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"),
            "bridge-token", drafts, "127.0.0.1", false, 0, 4096, Duration.ofSeconds(2)
        );
        Javalin app = new WebUiApplication(config).createApp().start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String root = "http://127.0.0.1:" + app.port();
            HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create(root + "/auth/exchange"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"one-time-token\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(204, login.statusCode());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

            HttpResponse<String> preview = client.send(
                HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/world/lines/preview"))
                    .header("Cookie", cookie)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(404, preview.statusCode());
        } finally {
            app.stop();
            bridge.stop(0);
        }
    }

    @Test void guestsCanReadOnlyTheWorldListAndRailMapApis() throws Exception {
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/worlds", exchange -> {
            byte[] body = "[{\"id\":\"world\",\"name\":\"World\"}]".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bridge.createContext("/worlds/world/rail-map", exchange -> {
            byte[] body = "{\"formatVersion\":1,\"world\":\"world\",\"runtimeId\":\"r\",\"generatedAtEpochMillis\":0,\"rails\":[],\"markers\":[]}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bridge.start();
        WebUiConfig config = new WebUiConfig(
            URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"),
            "bridge-token", drafts, "127.0.0.1", false, 0, 4096, Duration.ofSeconds(2)
        );
        Javalin app = new WebUiApplication(config).createApp().start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String root = "http://127.0.0.1:" + app.port();
            assertEquals(200, client.send(
                HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            ).statusCode());
            assertEquals(200, client.send(
                HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/world/rail-map")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            ).statusCode());
            assertEquals(401, client.send(
                HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/world/lines")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            ).statusCode());
        } finally {
            app.stop();
            bridge.stop(0);
        }
    }
}
