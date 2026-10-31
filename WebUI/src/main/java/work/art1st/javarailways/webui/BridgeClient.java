package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

final class BridgeClient {
    record Response(int status, JsonNode body) {}
    static final class Failure extends IOException {
        final int status;
        final String code;
        Failure(int status, String code, String message, IOException cause) {
            super(message, cause); this.status = status; this.code = code;
        }
    }
    private final WebUiConfig config;
    private final HttpClient client;
    private final ObjectMapper mapper;

    BridgeClient(WebUiConfig config, ObjectMapper mapper) {
        this(config, HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build(), mapper);
    }
    BridgeClient(WebUiConfig config, HttpClient client, ObjectMapper mapper) { this.config = config; this.client = client; this.mapper = mapper; }

    Response get(String path) throws IOException, InterruptedException { return send("GET", path, null); }
    Response post(String path, JsonNode payload) throws IOException, InterruptedException { return send("POST", path, payload); }
    Response simulate(String path, JsonNode payload) throws IOException, InterruptedException {
        return send("POST", path, payload, Duration.ofMillis(simulationExecutionTimeoutMillis(payload)).plus(config.requestTimeout()));
    }
    static long simulationExecutionTimeoutMillis(JsonNode payload) {
        JsonNode value = payload == null ? null : payload.get("executionTimeoutMillis");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1 || value.longValue() > 3_600_000) {
            throw new IllegalArgumentException("executionTimeoutMillis must be an integer in 1..3600000");
        }
        return value.longValue();
    }
    Response put(String path, JsonNode payload) throws IOException, InterruptedException { return send("PUT", path, payload); }
    Response delete(String path, JsonNode payload) throws IOException, InterruptedException { return send("DELETE", path, payload); }

    private Response send(String method, String path, JsonNode payload) throws IOException, InterruptedException {
        return send(method, path, payload, config.requestTimeout());
    }

    private Response send(String method, String path, JsonNode payload, Duration timeout) throws IOException, InterruptedException {
        if (config.bridgeToken().isBlank()) throw new IllegalStateException("Bukkit bridge token is not configured");
        URI target = config.bridgeUrl().resolve(path.startsWith("/") ? path.substring(1) : path);
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(timeout)
                .header("Authorization", "Bearer " + config.bridgeToken()).header("Accept", "application/json");
        if (payload == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)));
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException failure) {
            throw new Failure(504, "BRIDGE_TIMEOUT", "Server bridge request timed out", failure);
        } catch (IOException failure) {
            throw new Failure(502, "BRIDGE_UNAVAILABLE", "Cannot reach the server bridge", failure);
        }
        try {
            return new Response(response.statusCode(), response.body().length == 0 ? mapper.nullNode() : mapper.readTree(response.body()));
        } catch (IOException failure) {
            throw new Failure(502, "BRIDGE_INVALID_RESPONSE", "Server bridge returned invalid JSON", failure);
        }
    }
}
