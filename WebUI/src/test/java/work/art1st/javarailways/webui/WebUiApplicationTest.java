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

    @Test void bridgeTimeoutReturnsTypedErrorAndCorrelatedLogInsteadOfStorageFailure() throws Exception {
        var mapper = new ObjectMapper();
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/worlds", exchange -> {
            try { Thread.sleep(350); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        bridge.start();
        Javalin app = new WebUiApplication(new WebUiConfig(
            URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"), "private-token",
            drafts.resolve("drafts"), "127.0.0.1", false, 0, 4096, Duration.ofMillis(150))).createApp().start(0);
        String requestId;
        try {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + app.port() + "/api/v1/worlds")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(504, response.statusCode());
            var error = mapper.readTree(response.body());
            assertEquals("BRIDGE_TIMEOUT", error.path("code").asText());
            requestId = error.path("requestId").asText();
            java.util.UUID.fromString(requestId);
            assertTrue(!response.body().contains("storage") && !response.body().contains("private-token"));
        } finally { app.stop(); bridge.stop(0); }
        String log = java.nio.file.Files.readString(drafts.resolve("logs/errors.log"));
        assertTrue(log.contains("requestId=" + requestId));
        assertTrue(log.contains("java.net.http.HttpTimeoutException"));
        assertTrue(!log.contains("private-token"));
    }

    @Test void rejectsEditorDocumentsThatCannotBeSafelyLoadedByTheFrontend() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String valid = """
            {"formatVersion":1,"id":"t","periodDays":1,
             "runs":[],"duties":[],"diagramLines":[],"initialPositions":[],"arrivalOverrides":[]}
            """;
        WebUiApplication.requireEditorDocument(mapper.readTree(valid));
        WebUiApplication.requireEditorDocument(mapper.readTree("""
            {"formatVersion":1,"id":"t","formalScriptName":"weekday.js","periodDays":1,
             "runs":[],"duties":[],"diagramLines":[],"initialPositions":[],"arrivalOverrides":[]}
            """));
        assertThrows(IllegalArgumentException.class, () -> WebUiApplication.requireEditorDocument(mapper.readTree("""
            {"formatVersion":1,"id":"t","formalScriptName":"../weekday.js","periodDays":1,
             "runs":[],"duties":[],"diagramLines":[],"initialPositions":[],"arrivalOverrides":[]}
            """)));
        WebUiApplication.requireEditorDocument(mapper.readTree("""
            {"formatVersion":1,"id":"draft","periodDays":1,
             "runs":[{"id":"run-1","type":"PASSENGER","lineDirection":"UP","calls":[{"id":"c1","stationId":"","status":"ORIGIN","targetId":""}]}],
             "duties":[{"id":"duty-1","selector":{"type":"TRAIN_NAME","name":""},"elements":[]}],
             "diagramLines":[],"initialPositions":[],"arrivalOverrides":[]}
            """));

        assertThrows(IllegalArgumentException.class, () -> WebUiApplication.requireEditorDocument(
            mapper.readTree("""
                {"formatVersion":1,"id":"t","periodDays":1,
                 "runs":[],"duties":[],"initialPositions":[],"arrivalOverrides":[]}
                """)));
        assertThrows(IllegalArgumentException.class, () -> WebUiApplication.requireEditorDocument(
            mapper.readTree("""
                {"formatVersion":1,"id":"t","periodDays":1,
                 "runs":[],"duties":[{"id":"d","selector":{"type":"TRAIN_NAME"},"elements":[]}],
                 "diagramLines":[],"initialPositions":[],"arrivalOverrides":[]}
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

    @Test void standaloneProgramSimulationRequiresSessionValidatesBodyAndPreservesBridgeStatus() throws Exception {
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/auth/exchange", exchange -> {
            byte[] body = "{\"playerId\":\"player\",\"playerName\":\"Player\",\"worldId\":\"world\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        var received = new java.util.concurrent.atomic.AtomicReference<String>();
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        bridge.createContext("/worlds/world/programs/simulate", exchange -> {
            assertEquals("Bearer bridge-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("POST", exchange.getRequestMethod());
            received.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] body = (status.get() == 200 ? "{\"status\":\"COMPLETED\",\"durationTicks\":1300}" : status.get() == 504
                ? "{\"error\":\"program simulation timed out\",\"code\":\"SIMULATION_TIMEOUT\"}"
                : "{\"error\":\"simulation unavailable\"}").getBytes();
            exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        bridge.start();
        Javalin app = new WebUiApplication(new WebUiConfig(
            URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"), "bridge-token", drafts,
            "127.0.0.1", false, 0, 8192, Duration.ofSeconds(2))).createApp().start(0);
        try {
            HttpClient client = HttpClient.newHttpClient(); String root = "http://127.0.0.1:" + app.port();
            URI endpoint = URI.create(root + "/api/v1/worlds/world/programs/simulate");
            String body = """
                {"programId":"p","definition":{"parameters":[],"steps":[{"id":"w","type":"WAIT_DURATION","durationTicks":{"value":1300}}]},
                 "arguments":{},"startTick":100,"executionTimeoutMillis":60000,"maximumDurationTicks":2000,
                 "profile":{"type":"WORLD_TRAIN","id":"reference"},"initialPosition":{"wayPointId":"point","reverseTrain":true}}
                """;
            assertEquals(401, client.send(HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(null, received.get());
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(root + "/auth/exchange"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"one-time-token\"}")).build(), HttpResponse.BodyHandlers.ofString());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            HttpResponse<String> invalid = client.send(HttpRequest.newBuilder(endpoint).header("Cookie", cookie)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.replace(",\"reverseTrain\":true", ""))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode()); assertEquals(null, received.get());
            for (int code : new int[]{200, 422, 429, 504}) {
                status.set(code);
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint).header("Cookie", cookie)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(code, response.statusCode());
                assertEquals(new ObjectMapper().readTree(body), new ObjectMapper().readTree(received.get()));
                assertTrue(!response.body().contains("bridge-token") && !response.body().contains("one-time-token"));
                if (code == 504) {
                    var timeout = new ObjectMapper().readTree(response.body());
                    assertEquals("SIMULATION_TIMEOUT", timeout.path("code").asText());
                    assertEquals("program simulation timed out", timeout.path("error").asText());
                    java.util.UUID.fromString(timeout.path("requestId").asText());
                }
            }
            // Local storage failure must never be mislabeled as a bridge outage.
            java.nio.file.Files.createDirectories(drafts.resolve("world"));
            java.nio.file.Files.writeString(drafts.resolve("world/broken.json"), "not JSON");
            var failedRead = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/world/drafts/broken"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(502, failedRead.statusCode());
            assertEquals("STORAGE_UNAVAILABLE", new ObjectMapper().readTree(failedRead.body()).path("code").asText());
        } finally { app.stop(); bridge.stop(0); }
    }

    @Test void forwardsExactLineMeasurementAndPreservesDwellRejection() throws Exception {
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/auth/exchange", exchange -> {
            byte[] body = "{\"playerId\":\"player\",\"playerName\":\"Player\",\"worldId\":\"world\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        var received = new java.util.concurrent.atomic.AtomicReference<String>();
        bridge.createContext("/worlds/world/lines/line/simulate", exchange -> {
            assertEquals("Bearer bridge-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("POST", exchange.getRequestMethod());
            String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            received.set(request);
            var input = new ObjectMapper().readTree(request);
            boolean invalid = input.get("maximumSpeedMetersPerSecond").asDouble() <= 0;
            boolean insufficient = input.get("fixedDwellTicks").asLong() < input.get("minimumDwellTicks").asLong();
            byte[] body = (invalid ? "{\"error\":\"maximumSpeedMetersPerSecond must be a finite positive number\"}"
                : insufficient ? "{\"code\":\"LINE_DWELL_INSUFFICIENT\",\"error\":\"station b: requested 10 ticks, minimum 20 ticks\"}"
                : "{\"lineRevision\":7,\"legs\":[],\"totalTravelSeconds\":12.35,\"calls\":[{\"callId\":\"origin\",\"kind\":\"ORIGIN\",\"departureTick\":100},{\"callId\":\"middle\",\"kind\":\"STOP\",\"arrivalTick\":247,\"departureTick\":267},{\"callId\":\"end\",\"kind\":\"TERMINAL\",\"arrivalTick\":367}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(invalid ? 400 : insufficient ? 422 : 200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        bridge.start();
        Javalin app = new WebUiApplication(new WebUiConfig(
            URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"),
            "bridge-token", drafts, "127.0.0.1", false, 0, 4096, Duration.ofSeconds(2)
        )).createApp().start(0);
        try {
            HttpClient client = HttpClient.newHttpClient(); String root = "http://127.0.0.1:" + app.port();
            URI reset = URI.create(root + "/api/v1/worlds/world/lines/line/simulate");
            assertEquals(401, client.send(HttpRequest.newBuilder(reset).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(null, received.get());
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(root + "/auth/exchange"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"one-time-token\"}")).build(), HttpResponse.BodyHandlers.ofString());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            for (int scenario : new int[]{0, 1, 2}) {
                int speed = scenario == 1 ? 0 : 5;
                int dwell = scenario == 2 ? 10 : 20;
                String body = """
                    {"executionTimeoutMillis":1234,"profileType":"TRAIN_TEMPLATE","profileId":"train",
                     "maximumSpeedMetersPerSecond":%d,"timetableId":"editor","measurementSource":"function main(t) { /* exact unsaved source */ }",
                     "firstDepartureTick":100,"fixedDwellTicks":%d,"minimumDwellTicks":20,
                     "calls":[{"callId":"origin","stationId":"a","destinationId":"a@1","kind":"ORIGIN","viaIds":[]},
                              {"callId":"middle","stationId":"b","destinationId":"b@1","kind":"STOP","viaIds":["junction"],"stoppingOffsetMetersBefore":1.5},
                              {"callId":"end","stationId":"a","destinationId":"a@2","kind":"TERMINAL","viaIds":[]}]}
                    """.formatted(speed, dwell);
                HttpResponse<String> result = client.send(HttpRequest.newBuilder(reset).header("Cookie", cookie)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(new ObjectMapper().readTree(body), new ObjectMapper().readTree(received.get()));
                assertEquals(scenario == 1 ? 400 : scenario == 2 ? 422 : 200, result.statusCode());
                if (speed == 0) assertTrue(result.body().contains("finite positive number"));
                var response = new ObjectMapper().readTree(result.body());
                if (scenario == 0) {
                    assertEquals(7, response.path("lineRevision").asInt());
                    assertEquals("middle", response.path("calls").get(1).path("callId").asText());
                    assertEquals(247, response.path("calls").get(1).path("arrivalTick").asLong());
                    assertEquals(267, response.path("calls").get(1).path("departureTick").asLong());
                } else if (scenario == 2) {
                    assertEquals("LINE_DWELL_INSUFFICIENT", response.path("code").asText());
                    assertEquals("station b: requested 10 ticks, minimum 20 ticks", response.path("error").asText());
                    assertTrue(!response.has("calls"));
                }
                assertTrue(!result.body().contains("bridge-token") && !result.body().contains("one-time-token"));
            }
        } finally { app.stop(); bridge.stop(0); }
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

    @Test void issuesRequireSessionAndProxyOnlyTheSelectedWorld() throws Exception {
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/auth/exchange", exchange -> {
            byte[] body = "{\"playerId\":\"player\",\"playerName\":\"Player\",\"worldId\":\"world\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        bridge.createContext("/worlds/world/issues", exchange -> {
            calls.incrementAndGet();
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = "[{\"source\":\"models\",\"kind\":\"TRAIN\",\"objectId\":\"t1\",\"code\":\"missing\",\"message\":\"Missing snapshot\",\"details\":null,\"firstSeen\":\"2026-10-17T00:00:00Z\",\"lastSeen\":\"2026-10-17T00:01:00Z\"}]".getBytes();
            exchange.sendResponseHeaders("Bearer bridge-token".equals(token) ? 200 : 401, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        bridge.createContext("/worlds/other/issues", exchange -> {
            byte[] body = "[]".getBytes(); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        bridge.start();
        Javalin app = new WebUiApplication(new WebUiConfig(
            URI.create("http://127.0.0.1:" + bridge.getAddress().getPort() + "/"),
            "bridge-token", drafts, "127.0.0.1", false, 0, 4096, Duration.ofSeconds(2)
        )).createApp().start(0);
        try {
            HttpClient client = HttpClient.newHttpClient(); String root = "http://127.0.0.1:" + app.port();
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/world/issues")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(0, calls.get());
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(root + "/auth/exchange"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"one-time-token\"}")).build(), HttpResponse.BodyHandlers.ofString());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            HttpResponse<String> result = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/world/issues")).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, result.statusCode()); assertEquals(1, calls.get());
            assertTrue(result.body().contains("Missing snapshot")); assertTrue(!result.body().contains("bridge-token"));
            HttpResponse<String> other = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/worlds/other/issues")).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, other.statusCode()); assertEquals("[]", other.body());
        } finally { app.stop(); bridge.stop(0); }
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
