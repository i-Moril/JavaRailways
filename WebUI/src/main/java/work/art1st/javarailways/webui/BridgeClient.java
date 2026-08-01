package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class BridgeClient {
    record Response(int status, JsonNode body) {}
    private final WebUiConfig config;
    private final HttpClient client;
    private final ObjectMapper mapper;

    BridgeClient(WebUiConfig config, ObjectMapper mapper) {
        this(config, HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build(), mapper);
    }
    BridgeClient(WebUiConfig config, HttpClient client, ObjectMapper mapper) { this.config = config; this.client = client; this.mapper = mapper; }

    Response get(String path) throws IOException, InterruptedException { return send("GET", path, null); }
    Response post(String path, JsonNode payload) throws IOException, InterruptedException { return send("POST", path, payload); }
    Response put(String path, JsonNode payload) throws IOException, InterruptedException { return send("PUT", path, payload); }
    Response delete(String path, JsonNode payload) throws IOException, InterruptedException { return send("DELETE", path, payload); }

    private Response send(String method, String path, JsonNode payload) throws IOException, InterruptedException {
        if (config.bridgeToken().isBlank()) throw new IllegalStateException("Bukkit bridge token is not configured");
        URI target = config.bridgeUrl().resolve(path.startsWith("/") ? path.substring(1) : path);
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.bridgeToken()).header("Accept", "application/json");
        if (payload == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)));
        HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(), response.body().length == 0 ? mapper.nullNode() : mapper.readTree(response.body()));
    }
}
