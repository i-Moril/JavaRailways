package work.art1st.minerails.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Standalone browser-facing service. The Bukkit bearer token never leaves this process. */
public final class WebUiApplication {
    private final WebUiConfig config;
    private final ObjectMapper mapper;
    private final DraftRepository drafts;
    private final BridgeClient bridge;
    private final WebUiSessions sessions = new WebUiSessions(Duration.ofMinutes(30));

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
        createApp().start(config.bindAddress(), config.port());
    }

    Javalin createApp() {
        Javalin app = Javalin.create(settings -> {
            settings.http.maxRequestSize = config.maxRequestBytes();
            settings.staticFiles.add("/public", Location.CLASSPATH);
        });
        app.before(context -> context.header("Referrer-Policy", "no-referrer"));
        app.get("/health", context -> context.json(Map.of("status", "ok")));
        app.post("/auth/exchange", this::exchangeLoginToken);
        app.before("/api/v1/{path...}", this::requireSession);

        app.get("/api/v1/worlds", context -> proxyGet(context, "worlds"));
        app.get("/api/v1/worlds/{world}/metadata", this::metadata);
        app.get("/api/v1/worlds/{world}/scripts", this::scripts);
        app.get("/api/v1/worlds/{world}/scripts/{script}", this::renderScript);
        app.get("/api/v1/worlds/{world}/lines", this::lines);
        app.post("/api/v1/worlds/{world}/lines/preview", this::previewLine);
        app.post("/api/v1/worlds/{world}/lines/{line}/simulate", this::simulateLine);
        app.put("/api/v1/worlds/{world}/lines/{line}", this::putLine);
        app.delete("/api/v1/worlds/{world}/lines/{line}", this::deleteLine);
        app.get("/api/v1/worlds/{world}/drafts", this::listDrafts);
        app.post("/api/v1/worlds/{world}/drafts", this::createDraft);
        app.get("/api/v1/worlds/{world}/drafts/{draft}", this::getDraft);
        app.put("/api/v1/worlds/{world}/drafts/{draft}", this::putDraft);
        app.delete("/api/v1/worlds/{world}/drafts/{draft}", this::deleteDraft);
        app.post("/api/v1/worlds/{world}/validate", this::validate);
        app.post("/api/v1/worlds/{world}/publish", this::publish);

        app.exception(UnauthorizedResponse.class,
            (exception, context) -> error(context, 401, "login required or session expired"));
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

    private void exchangeLoginToken(Context context) throws IOException, InterruptedException {
        JsonNode loginRequest = requestJson(context);
        String token = loginRequest.path("token").asText();
        if (token.isBlank() || token.length() > 256) {
            error(context, 400, "login token is required");
            return;
        }
        ObjectNode request = mapper.createObjectNode().put("token", token);
        BridgeClient.Response response = bridge.post("auth/exchange", request);
        if (response.status() != 200) {
            respond(context, response);
            return;
        }
        WebUiSessions.CreatedSession created = sessions.create(response.body());
        long maxAge = Math.max(1L,
            (created.session().expiresAtEpochMillis() - System.currentTimeMillis()) / 1000L);
        String secure = config.secureCookies() ? "; Secure" : "";
        context.header(
            "Set-Cookie",
            WebUiSessions.COOKIE_NAME + "=" + created.id() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge + secure
        );
        context.status(204);
    }

    private void requireSession(Context context) {
        String id = context.cookie(WebUiSessions.COOKIE_NAME);
        WebUiSessions.Session session = sessions.find(id).orElseThrow(UnauthorizedResponse::new);
        context.attribute("webUiSession", session);
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

    private void scripts(Context context) throws IOException, InterruptedException {
        respond(context, bridge.get(worldPath(context) + "/scripts"));
    }

    private void renderScript(Context context) throws IOException, InterruptedException {
        String path = worldPath(context) + "/scripts/" + segment(context.pathParam("script"));
        String id = context.queryParam("id");
        if (id != null && !id.isBlank()) path += "?id=" + segment(id);
        respond(context, bridge.get(path));
    }

    private String worldPath(Context context) {
        return "worlds/" + segment(context.pathParam("world"));
    }

    private void lines(Context context) throws IOException, InterruptedException {
        respond(context, bridge.get(lineCollectionPath(context)));
    }

    private void previewLine(Context context) throws IOException, InterruptedException {
        respond(context, bridge.post(lineCollectionPath(context) + "/preview", requestJson(context)));
    }

    private void simulateLine(Context context) throws IOException, InterruptedException {
        respond(context, bridge.post(
            lineCollectionPath(context) + "/" + segment(context.pathParam("line")) + "/simulate",
            requestJson(context)
        ));
    }

    private void putLine(Context context) throws IOException, InterruptedException {
        respond(context, bridge.put(
            lineCollectionPath(context) + "/" + segment(context.pathParam("line")),
            requestJson(context)
        ));
    }

    private void deleteLine(Context context) throws IOException, InterruptedException {
        respond(context, bridge.delete(
            lineCollectionPath(context) + "/" + segment(context.pathParam("line")),
            requestJson(context)
        ));
    }

    private String lineCollectionPath(Context context) {
        return worldPath(context) + "/lines";
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
