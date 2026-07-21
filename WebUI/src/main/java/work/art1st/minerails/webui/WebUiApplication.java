package work.art1st.minerails.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.util.Map;

public final class WebUiApplication {
    private final WebUiConfig config;
    private final ObjectMapper mapper;
    private final DraftRepository drafts;
    private final BridgeClient bridge;

    WebUiApplication(WebUiConfig config) {
        this(config, new ObjectMapper());
    }
    WebUiApplication(WebUiConfig config, ObjectMapper mapper) {
        this.config = config; this.mapper = mapper;
        this.drafts = new DraftRepository(config.draftDirectory(), mapper);
        this.bridge = new BridgeClient(config, mapper);
    }

    public static void main(String[] args) { new WebUiApplication(WebUiConfig.fromEnvironment()).start(); }
    public void start() { createApp().start(config.port()); }

    Javalin createApp() {
        Javalin app = Javalin.create(settings -> {
            settings.http.maxRequestSize = config.maxRequestBytes();
            settings.staticFiles.add("/public", Location.CLASSPATH);
        });
        app.get("/health", context -> context.json(Map.of("status", "ok")));
        app.get("/api/drafts/{id}", this::getDraft);
        app.put("/api/drafts/{id}", this::putDraft);
        app.get("/api/worlds", context -> proxyGet(context, "api/worlds"));
        app.get("/api/metadata", context -> proxyGet(context, "api/metadata"));
        app.get("/api/timetables", context -> proxyGet(context, "api/timetables"));
        app.post("/api/timetables/validate", context -> proxyPost(context, "api/timetables/validate"));
        app.post("/api/timetables/publish", context -> proxyPost(context, "api/timetables/publish"));
        app.exception(IllegalArgumentException.class, (exception, context) -> error(context, 400, exception.getMessage()));
        app.exception(IllegalStateException.class, (exception, context) -> error(context, 503, exception.getMessage()));
        app.exception(IOException.class, (exception, context) -> error(context, 502, "bridge or draft storage unavailable"));
        app.exception(InterruptedException.class, (exception, context) -> { Thread.currentThread().interrupt(); error(context, 504, "bridge request interrupted"); });
        app.get("/{path...}", context -> {
            if (context.path().startsWith("/api/")) { error(context, 404, "not found"); return; }
            try (var stream = WebUiApplication.class.getResourceAsStream("/public/index.html")) {
                if (stream == null) { error(context, 404, "frontend is not packaged"); return; }
                context.contentType("text/html").result(stream.readAllBytes());
            }
        });
        return app;
    }

    private void getDraft(Context context) throws IOException {
        drafts.find(context.pathParam("id")).ifPresentOrElse(context::json, () -> error(context, 404, "draft not found"));
    }
    private void putDraft(Context context) throws IOException {
        JsonNode body = requestJson(context);
        drafts.save(context.pathParam("id"), body);
        context.status(204);
    }
    private void proxyGet(Context context, String path) throws IOException, InterruptedException { respond(context, bridge.get(path)); }
    private void proxyPost(Context context, String path) throws IOException, InterruptedException { respond(context, bridge.post(path, requestJson(context))); }
    private JsonNode requestJson(Context context) throws IOException {
        byte[] bytes = context.bodyAsBytes();
        if (bytes.length > config.maxRequestBytes()) throw new IllegalArgumentException("request body exceeds limit");
        JsonNode json = mapper.readTree(bytes);
        if (json == null) throw new IllegalArgumentException("request body must be JSON");
        return json;
    }
    private static void respond(Context context, BridgeClient.Response response) { context.status(response.status()).json(response.body()); }
    private static void error(Context context, int status, String message) { context.status(status).json(Map.of("error", message == null ? "bad request" : message)); }
}
