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

import static org.junit.jupiter.api.Assertions.*;

class WebUiSessionHttpTest {
    @TempDir Path drafts;

    @Test void explicitActivityRenewsWhileStatusAndApiReadsDoNot() throws Exception {
        verifySessionContract(false);
    }

    @Test void secureModeKeepsSecureFlagWhenRenewing() throws Exception {
        verifySessionContract(true);
    }

    private void verifySessionContract(boolean secureCookies) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionTestClock clock = new SessionTestClock();
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/auth/exchange", exchange -> {
            byte[] body = "{\"playerId\":\"player\",\"playerName\":\"Player\",\"worldId\":\"world\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bridge.createContext("/worlds/world/metadata", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bridge.createContext("/worlds", exchange -> {
            byte[] body = "[]".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bridge.start();
        WebUiConfig config = new WebUiConfig(
            URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"), "bridge-token", drafts,
            "127.0.0.1", secureCookies, 0, 4096, Duration.ofSeconds(2));
        Javalin app = new WebUiApplication(config, mapper,
            new WebUiSessions(Duration.ofMinutes(30), clock)).createApp().start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String root = "http://127.0.0.1:" + app.port();
            HttpResponse<String> missing = send(client, root, "/auth/session", "POST", null);
            assertEquals(401, missing.statusCode());
            assertTrue(missing.headers().firstValue("Set-Cookie").isEmpty());
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(root + "/auth/exchange"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"one-time-token\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, login.statusCode());
            String initialCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
            String cookie = initialCookie.split(";", 2)[0];
            assertCookie(initialCookie, secureCookies);
            long initialExpiry = mapper.readTree(send(client, root, "/auth/session", "GET", cookie).body()).path("expiresAtEpochMillis").asLong();
            clock.advance(Duration.ofMinutes(29));
            HttpResponse<String> read = send(client, root, "/auth/session", "GET", cookie);
            assertEquals(initialExpiry, mapper.readTree(read.body()).path("expiresAtEpochMillis").asLong());
            assertTrue(read.headers().firstValue("Set-Cookie").isEmpty());
            assertEquals(200, send(client, root, "/api/v1/worlds/world/metadata", "GET", cookie).statusCode());
            HttpResponse<String> renewed = send(client, root, "/auth/session", "POST", cookie);
            assertEquals(200, renewed.statusCode());
            assertEquals(cookie, renewed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0]);
            assertCookie(renewed.headers().firstValue("Set-Cookie").orElseThrow(), secureCookies);
            var status = mapper.readTree(renewed.body());
            assertTrue(status.path("authenticated").asBoolean());
            assertEquals("Player", status.path("playerName").asText());
            assertEquals("world", status.path("worldId").asText());
            assertEquals(4, status.size());
            long renewedExpiry = clock.millis() + Duration.ofMinutes(30).toMillis();
            assertEquals(renewedExpiry, status.path("expiresAtEpochMillis").asLong());
            assertFalse(renewed.body().contains(cookie.substring(cookie.indexOf('=') + 1)));
            assertFalse(renewed.body().contains("bridge-token"));
            clock.advance(Duration.ofMinutes(29));
            assertEquals(200, send(client, root, "/api/v1/worlds/world/metadata", "GET", cookie).statusCode());
            assertEquals(renewedExpiry, mapper.readTree(send(client, root, "/auth/session", "GET", cookie).body()).path("expiresAtEpochMillis").asLong());
            clock.advance(Duration.ofMinutes(1));
            HttpResponse<String> expired = send(client, root, "/auth/session", "POST", cookie);
            assertEquals(401, expired.statusCode());
            assertTrue(expired.headers().firstValue("Set-Cookie").isEmpty());
            assertEquals(401, send(client, root, "/api/v1/worlds/world/metadata", "GET", cookie).statusCode());
            assertFalse(mapper.readTree(send(client, root, "/auth/session", "GET", cookie).body()).path("authenticated").asBoolean());
            assertEquals(200, send(client, root, "/api/v1/worlds", "GET", null).statusCode());
        } finally {
            app.stop();
            bridge.stop(0);
        }
    }

    private static HttpResponse<String> send(HttpClient client, String root, String path, String method, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(root + path));
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertCookie(String cookie, boolean secure) {
        assertTrue(cookie.contains("; Path=/; HttpOnly; SameSite=Strict; Max-Age=1800"));
        assertEquals(secure, cookie.contains("; Secure"));
    }
}
