package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WebUiLogsTest {
    @TempDir Path root;
    @Test void authenticatedDiskLogsRemainAvailableWithoutWorldsAndDuringHostOutage() throws Exception {
        var mapper = new ObjectMapper();
        var forwarded = new AtomicReference<String>();
        var bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/", exchange -> {
            forwarded.set(exchange.getRequestURI().toString());
            assertEquals("Bearer secret", exchange.getRequestHeaders().getFirst("Authorization"));
            String body = switch (exchange.getRequestURI().getPath()) {
                case "/auth/exchange" -> "{\"playerId\":\"p\",\"playerName\":\"P\",\"worldId\":\"missing\"}";
                case "/logs/sources" -> "{\"sources\":[{\"id\":\"host-errors\",\"label\":\"Host\",\"available\":true}]}";
                case "/logs" -> "{\"source\":\"host-errors\",\"entries\":[],\"reset\":false,\"scannedBytes\":0,\"nextCursor\":null,\"retention\":\"current\"}";
                default -> "{}";
            };
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        bridge.start();
        var config = new WebUiConfig(URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"), "secret", root.resolve("drafts"), "127.0.0.1", false, 0, 4096, Duration.ofSeconds(1));
        Javalin app = new WebUiApplication(config).createApp().start(0);
        String base = "http://127.0.0.1:" + app.port();
        var client = HttpClient.newHttpClient();
        try {
            assertEquals(401, get(client, base + "/api/v1/logs?source=webui-errors", "").statusCode());
            assertEquals(401, get(client, base + "/api/v1/logs/sources", "").statusCode());
            var malformed = client.send(HttpRequest.newBuilder(URI.create(base + "/auth/exchange"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{token:secret-user-input}" )).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformed.statusCode());
            assertFalse(malformed.body().contains("secret-user-input"));
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/auth/exchange")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"one-time\"}")).build(), HttpResponse.BodyHandlers.ofString());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var failure = new IllegalStateException("failure", new IllegalArgumentException("original"));
            failure.addSuppressed(new IllegalStateException("cleanup"));
            System.getLogger(WebUiApplication.class.getName()).log(System.Logger.Level.ERROR, "test sidecar failure", failure);
            Path disk = root.resolve("logs/errors.log");
            for (int attempt = 0; attempt < 100 && (!Files.exists(disk) || !Files.readString(disk).contains("cleanup")); attempt++) Thread.sleep(20);
            var page = get(client, base + "/api/v1/logs?source=webui-errors", cookie);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("module=WebUI"));
            assertTrue(page.body().contains("Caused by: java.lang.IllegalArgumentException: original"));
            assertTrue(page.body().contains("Suppressed: java.lang.IllegalStateException: cleanup"));
            assertFalse(page.body().contains("secret"));
            assertFalse(Files.readString(disk).contains("secret-user-input"));
            var sources = get(client, base + "/api/v1/logs/sources", cookie);
            assertEquals(2, mapper.readTree(sources.body()).path("sources").size());
            assertEquals(200, get(client, base + "/api/v1/logs?source=host-errors&query=a%26b", cookie).statusCode());
            assertEquals("/logs?source=host-errors&query=a%26b&cursor=", forwarded.get());
            assertEquals(400, get(client, base + "/api/v1/logs?source=..%2Fsecret", cookie).statusCode());
            bridge.stop(0);
            var offline = mapper.readTree(get(client, base + "/api/v1/logs/sources", cookie).body());
            assertEquals(1, offline.path("sources").size());
            assertTrue(offline.has("warning"));
            assertEquals(200, get(client, base + "/api/v1/logs?source=webui-errors", cookie).statusCode());
        } finally { app.stop(); bridge.stop(0); }
    }
    private HttpResponse<String> get(HttpClient client, String url, String cookie) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
