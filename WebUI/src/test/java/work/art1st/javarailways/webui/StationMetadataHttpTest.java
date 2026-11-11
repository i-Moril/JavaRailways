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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class StationMetadataHttpTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void exactObjectRequestRejectsUnknownFieldsAndNonObjects() throws Exception {
        WebUiApplication.requireStationMetadataRequest(mapper.readTree("{\"metadata\":{},\"expectedMetadata\":{}}"));
        for (String text : new String[]{"[]", "null", "{}", "{\"metadata\":[],\"expectedMetadata\":{}}",
            "{\"metadata\":{},\"expectedMetadata\":null}", "{\"metadata\":{},\"expectedMetadata\":{},\"other\":true}"}) {
            assertThrows(IllegalArgumentException.class, () -> WebUiApplication.requireStationMetadataRequest(mapper.readTree(text)));
        }
    }

    @Test void authenticatedProxyRetainsWorldBodyTokenAndDomainStatus() throws Exception {
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicInteger nextStatus = new AtomicInteger(200);
        bridge.createContext("/auth/exchange", exchange -> {
            byte[] body = "{\"playerId\":\"player\",\"playerName\":\"Player\",\"worldId\":\"world\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        bridge.createContext("/worlds/世界/stations", exchange -> {
            calls.incrementAndGet();
            assertEquals("Bearer private-token", exchange.getRequestHeaders().getFirst("Authorization"));
            boolean put = exchange.getRequestMethod().equals("PUT");
            if (put) {
                assertEquals("/worlds/世界/stations/中央/metadata", exchange.getRequestURI().getPath());
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            int status = nextStatus.get();
            String value = status == 409 ? "{\"error\":\"metadata changed\",\"code\":\"CONFLICT\"}" : status == 404 ? "{\"error\":\"station missing\",\"code\":\"NOT_FOUND\"}" :
                put ? "{\"id\":\"中央\",\"displayName\":\"Central\",\"metadata\":{\"notice\":\"换乘\"}}" : "{\"stations\":[{\"id\":\"中央\",\"displayName\":\"Central\",\"metadata\":{}}]}";
            byte[] body = value.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        bridge.start();
        Javalin app = new WebUiApplication(new WebUiConfig(URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"),
            "private-token", directory, "127.0.0.1", false, 0, 8192, Duration.ofSeconds(2))).createApp().start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String root = "http://127.0.0.1:" + app.port();
            URI list = URI.create(root + "/api/v1/worlds/%E4%B8%96%E7%95%8C/stations");
            URI update = URI.create(list + "/%E4%B8%AD%E5%A4%AE/metadata");
            assertEquals(401, client.send(HttpRequest.newBuilder(list).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(update).PUT(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(0, calls.get());
            var login = client.send(HttpRequest.newBuilder(URI.create(root + "/auth/exchange")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"one-time\"}")).build(), HttpResponse.BodyHandlers.ofString());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            var listed = client.send(HttpRequest.newBuilder(list).header("Cookie",cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200,listed.statusCode());assertEquals("中央",mapper.readTree(listed.body()).path("stations").get(0).path("id").asText());
            String submitted = "{\"metadata\":{\"notice\":\"换乘\",\"custom\":[true,null]},\"expectedMetadata\":{\"old\":{\"value\":3}}}";
            for(int status : new int[]{200,409,404}) {
                nextStatus.set(status);
                var response = client.send(HttpRequest.newBuilder(update).header("Cookie",cookie).header("Content-Type","application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(submitted)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(status,response.statusCode());assertEquals(mapper.readTree(submitted),mapper.readTree(requestBody.get()));
                assertFalse(response.body().contains("private-token"));
            }
            int previous = calls.get();
            var invalid = client.send(HttpRequest.newBuilder(update).header("Cookie",cookie).header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"metadata\":[],\"expectedMetadata\":{}}" )).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400,invalid.statusCode());assertEquals(previous,calls.get());
        } finally {app.stop();bridge.stop(0);}
    }
}
