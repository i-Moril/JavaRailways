package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class BridgeClientTest {
    @TempDir Path drafts;

    @Test void requiresBoundedIntegerRequestBudget() throws Exception {
        var mapper = new ObjectMapper();
        for (String value : new String[]{"null", "0", "-1", "3600001", "9223372036854775808", "1.5", "\"60000\"", "true"}) {
            var payload = mapper.readTree("{\"executionTimeoutMillis\":" + value + "}");
            assertThrows(IllegalArgumentException.class, () -> BridgeClient.simulationExecutionTimeoutMillis(payload));
        }
        assertThrows(IllegalArgumentException.class, () -> BridgeClient.simulationExecutionTimeoutMillis(mapper.createObjectNode()));
        assertEquals(1L, BridgeClient.simulationExecutionTimeoutMillis(mapper.createObjectNode().put("executionTimeoutMillis", 1)));
        assertEquals(3600000L, BridgeClient.simulationExecutionTimeoutMillis(mapper.createObjectNode().put("executionTimeoutMillis", 3600000)));
    }

    @Test void simulationCanOutlastOrdinaryDeadlineAndTransportFailuresRemainDistinct() throws Exception {
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(350); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"status\":\"COMPLETED\"}".getBytes();
            try {
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.createContext("/invalid", exchange -> {
            byte[] body = "not JSON".getBytes();
            exchange.sendResponseHeaders(500, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var config = new WebUiConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            "private-token", drafts, "127.0.0.1", false, 0, 4096, Duration.ofMillis(150));
        var bridge = new BridgeClient(config, mapper);
        try {
            var timeout = assertThrows(BridgeClient.Failure.class, () -> bridge.post("slow", mapper.createObjectNode()));
            assertEquals(504, timeout.status); assertEquals("BRIDGE_TIMEOUT", timeout.code);
            assertInstanceOf(java.net.http.HttpTimeoutException.class, timeout.getCause());
            assertEquals("COMPLETED", bridge.simulate("slow", mapper.createObjectNode().put("executionTimeoutMillis", 1000)).body().path("status").asText());
            // A nondefault simulation budget is enforced, rather than a separate hardcoded minimum.
            assertEquals("BRIDGE_TIMEOUT", assertThrows(BridgeClient.Failure.class,
                () -> bridge.simulate("slow", mapper.createObjectNode().put("executionTimeoutMillis", 50))).code);
            // Ordinary requests still use their original deadline after a simulation.
            assertEquals("BRIDGE_TIMEOUT", assertThrows(BridgeClient.Failure.class, () -> bridge.get("slow")).code);
            assertEquals("BRIDGE_INVALID_RESPONSE", assertThrows(BridgeClient.Failure.class, () -> bridge.get("invalid")).code);
            server.stop(0);
            var unavailable = assertThrows(BridgeClient.Failure.class, () -> bridge.get("missing"));
            assertEquals(502, unavailable.status); assertEquals("BRIDGE_UNAVAILABLE", unavailable.code);
            assertFalse(unavailable.getMessage().contains("private-token"));
        } finally { server.stop(0); workers.shutdownNow(); }
    }
}
