package work.art1st.minerails.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Standalone browser-facing service. The Bukkit bearer token never leaves this process. */
public final class WebUiApplication {
    private final WebUiConfig config;
    private final ObjectMapper mapper;
    private final DraftRepository drafts;
    private final BridgeClient bridge;

    WebUiApplication(WebUiConfig config) {
        this(config, new ObjectMapper());
    }

    WebUiApplication(WebUiConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.drafts = new DraftRepository(config.draftDirectory(), mapper);
        this.bridge = new BridgeClient(config, mapper);
    }

    public static void main(String[] args) {
        new WebUiApplication(WebUiConfig.fromEnvironment()).start();
    }

    public void start() {
        createApp().start(config.port());
    }

    Javalin createApp() {
        Javalin app = Javalin.create(settings -> {
            settings.http.maxRequestSize = config.maxRequestBytes();
            settings.staticFiles.add("/public", Location.CLASSPATH);
        });
        app.get("/health", context -> context.json(Map.of("status", "ok")));

        app.get("/api/v1/worlds", context -> proxyGet(context, "worlds"));
        app.get("/api/v1/worlds/{world}/metadata", this::metadata);
        app.get("/api/v1/worlds/{world}/drafts", this::listDrafts);
        app.post("/api/v1/worlds/{world}/drafts", this::createDraft);
        app.get("/api/v1/worlds/{world}/drafts/{draft}", this::getDraft);
        app.put("/api/v1/worlds/{world}/drafts/{draft}", this::putDraft);
        app.delete("/api/v1/worlds/{world}/drafts/{draft}", this::deleteDraft);
        app.post("/api/v1/worlds/{world}/validate", this::validate);
        app.post("/api/v1/worlds/{world}/publish", this::publish);

        app.exception(IllegalArgumentException.class,
            (exception, context) -> error(context, 400, exception.getMessage()));
        app.exception(IllegalStateException.class,
            (exception, context) -> error(context, 503, exception.getMessage()));
        app.exception(IOException.class,
            (exception, context) -> error(context, 502, "bridge or draft storage unavailable"));
        app.exception(InterruptedException.class, (exception, context) -> {
            Thread.currentThread().interrupt();
            error(context, 504, "bridge request interrupted");
        });
        app.get("/{path...}", context -> {
            if (context.path().startsWith("/api/")) {
                error(context, 404, "not found");
                return;
            }
            try (var stream = WebUiApplication.class.getResourceAsStream("/public/index.html")) {
                if (stream == null) {
                    error(context, 404, "frontend is not packaged");
                    return;
                }
                context.contentType("text/html").result(stream.readAllBytes());
            }
        });
        return app;
    }

    private void metadata(Context context) throws IOException, InterruptedException {
        String world = segment(context.pathParam("world"));
        BridgeClient.Response metadata = bridge.get("worlds/" + world + "/metadata");
        if (metadata.status() >= 400) {
            respond(context, metadata);
            return;
        }
        BridgeClient.Response extensions = bridge.get("worlds/" + world + "/extensions");
        if (extensions.status() >= 400) {
            respond(context, extensions);
            return;
        }
        ObjectNode merged = metadata.body().deepCopy();
        merged.set("extensions", extensions.body());
        context.json(merged);
    }

    private void listDrafts(Context context) throws IOException {
        context.json(drafts.list(context.pathParam("world")));
    }

    private void createDraft(Context context) throws IOException {
        String world = context.pathParam("world");
        String id = drafts.create(world, requestJson(context));
        context.status(201).json(Map.of("id", id));
    }

    private void getDraft(Context context) throws IOException {
        drafts.find(context.pathParam("world"), context.pathParam("draft"))
            .ifPresentOrElse(context::json, () -> error(context, 404, "draft not found"));
    }

    private void putDraft(Context context) throws IOException {
        drafts.save(context.pathParam("world"), context.pathParam("draft"), requestJson(context));
        context.status(204);
    }

    private void deleteDraft(Context context) throws IOException {
        if (!drafts.delete(context.pathParam("world"), context.pathParam("draft"))) {
            error(context, 404, "draft not found");
            return;
        }
        context.status(204);
    }

    private void validate(Context context) throws IOException, InterruptedException {
        JsonNode body = requestJson(context);
        String id = requiredTimetableId(body);
        respond(context, bridge.post(timetablePath(context, id) + "/validate", body));
    }

    private void publish(Context context) throws IOException, InterruptedException {
        JsonNode body = requestJson(context);
        String id = requiredTimetableId(body);
        respond(context, bridge.put(timetablePath(context, id) + "/publish", body));
    }

    private String timetablePath(Context context, String id) {
        return "worlds/" + segment(context.pathParam("world")) + "/timetables/" + segment(id);
    }

    private static String requiredTimetableId(JsonNode body) {
        String id = body.path("id").asText("");
        if (id.isBlank()) throw new IllegalArgumentException("timetable id is required");
        return id;
    }

    private void proxyGet(Context context, String path) throws IOException, InterruptedException {
        respond(context, bridge.get(path));
    }

    private JsonNode requestJson(Context context) throws IOException {
        byte[] bytes = context.bodyAsBytes();
        if (bytes.length > config.maxRequestBytes()) {
            throw new IllegalArgumentException("request body exceeds limit");
        }
        JsonNode json = mapper.readTree(bytes);
        if (json == null) throw new IllegalArgumentException("request body must be JSON");
        return json;
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void respond(Context context, BridgeClient.Response response) {
        context.status(response.status()).json(response.body());
    }

    private static void error(Context context, int status, String message) {
        context.status(status).json(Map.of("error", message == null ? "bad request" : message));
    }
}
