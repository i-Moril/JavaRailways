package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.junit.jupiter.api.Assertions.*;

class TimetableProgramHttpTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void timetableProgramsImportAndExclusiveEditingLocks() throws Exception {
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/auth/exchange", exchange -> {
            byte[] response = "{\"playerId\":\"player\",\"playerName\":\"Player\",\"worldId\":\"world\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        bridge.start();
        Javalin app = new WebUiApplication(new WebUiConfig(URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"), "secret", directory, "127.0.0.1", false, 0, 100000, Duration.ofSeconds(2))).createApp().start(0);
        HttpClient client = HttpClient.newHttpClient();
        String root = "http://127.0.0.1:" + app.port();
        try {
            String firstCookie = login(client, root);
            String secondCookie = login(client, root);
            String empty = "{\"formatVersion\":1,\"id\":\"weekday\",\"periodDays\":1,\"runs\":[],\"duties\":[],\"initialPositions\":[],\"diagramLines\":[],\"arrivalOverrides\":[]}";
            String documents = "/api/v1/worlds/world/editor-documents";
            HttpResponse<String> created = send(client, root, documents, "POST", empty, firstCookie);
            assertEquals(201, created.statusCode());
            String firstId = mapper.readTree(created.body()).path("id").asText();
            String secondId = mapper.readTree(send(client, root, documents, "POST", empty.replace("weekday", "weekend"), secondCookie).body()).path("id").asText();
            String programs = documents + "/" + firstId + "/programs";
            String definition = "{\"parameters\":[],\"steps\":[{\"id\":\"reverse\",\"type\":\"REVERSE\"}]}";
            String create = "{\"id\":\"turnback\",\"name\":\"Turnback\",\"estimatedDurationTicks\":80,\"definition\":" + definition + "}";
            assertEquals(400, send(client, root, programs, "POST", create.replace("Turnback", "Turnback\",\"extra\":true,\"name\":\"x"), firstCookie).statusCode());
            HttpResponse<String> createdProgram = send(client, root, programs, "POST", create, firstCookie);
            assertEquals(201, createdProgram.statusCode());
            JsonNode program = mapper.readTree(createdProgram.body());
            assertEquals("turnback", program.path("id").asText());
            assertEquals(80, program.path("estimatedDurationTicks").asInt());
            assertFalse(program.has("revision"));
            assertFalse(program.has("contentIdentity"));
            assertEquals(1, mapper.readTree(send(client, root, programs, "GET", null, firstCookie).body()).size());
            assertEquals(409, send(client, root, documents + "/" + firstId, "PUT", empty.replace("weekday", "changed"), secondCookie).statusCode());
            assertEquals(409, send(client, root, programs + "/turnback", "PUT", "{\"name\":\"Other\",\"definition\":" + definition + "}", secondCookie).statusCode());
            var updatedProgram = send(client, root, programs + "/turnback", "PUT", "{\"name\":\"Updated\",\"estimatedDurationTicks\":140,\"definition\":" + definition + "}", firstCookie);
            assertEquals(200, updatedProgram.statusCode(), updatedProgram.body());
            assertEquals(140, mapper.readTree(send(client, root, programs, "GET", null, firstCookie).body()).get(0).path("estimatedDurationTicks").asInt());
            JsonNode imported = mapper.readTree(send(client, root, documents + "/" + secondId + "/programs/import", "POST",
                "{\"sourceDocumentId\":\"" + firstId + "\"}", secondCookie).body());
            assertEquals(1, imported.size());
            assertEquals("turnback", imported.get(0).path("id").asText());
            assertEquals(140, imported.get(0).path("estimatedDurationTicks").asInt());
            assertEquals(200, send(client, root, programs + "/turnback", "DELETE", null, firstCookie).statusCode());
            assertFalse(java.nio.file.Files.exists(directory.resolve("drafts/timetable-programs/world/" + firstId + "/turnback.json")));
            assertEquals(204, send(client, root, documents + "/" + firstId, "DELETE", null, firstCookie).statusCode());
            assertFalse(java.nio.file.Files.exists(directory.resolve("drafts/timetable-programs/world/" + firstId)));
            assertEquals(401, send(client, root, programs, "GET", null, null).statusCode());
        } finally { app.stop(); bridge.stop(0); }
    }

    private String login(HttpClient client, String root) throws Exception {
        HttpResponse<String> login = send(client, root, "/auth/exchange", "POST", "{\"token\":\"one-time\"}", null);
        return login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> send(HttpClient client, String root, String path, String method, String body, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(root + path)).header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
