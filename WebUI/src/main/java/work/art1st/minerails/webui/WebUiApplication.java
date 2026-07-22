package work.art1st.minerails.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.sse.SseClient;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone browser-facing service. The Bukkit bearer token never leaves this process. */
public final class WebUiApplication {
    private final WebUiConfig config;
    private final ObjectMapper mapper;
    private final DraftRepository drafts;
    private final BridgeClient bridge;
    private final WebUiSessions sessions = new WebUiSessions(Duration.ofMinutes(30));
    private final ConcurrentHashMap<String, Queue<TrainSubscriber>> trainStreamClients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService trainStreamExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MineRails-webui-train-stream");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean trainStreamStarted = new AtomicBoolean();
    private final ConcurrentHashMap<String, AtomicBoolean> trainPollsInFlight = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor trainStreamWorkers = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "MineRails-webui-train-poll");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy()
    );
    private final ThreadPoolExecutor trainDeliveryWorkers = new ThreadPoolExecutor(
        0, 64, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "MineRails-webui-train-delivery");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy()
    );
    private static final int MAX_STREAM_CLIENTS = 64;
    private static final int MAX_STREAM_CLIENTS_PER_WORLD = 16;
    private static final class TrainSubscriber {
        final SseClient client;
        final String sessionId;
        final AtomicReference<String> latest = new AtomicReference<>();
        final AtomicBoolean delivering = new AtomicBoolean();
        TrainSubscriber(SseClient client, String sessionId) { this.client = client; this.sessionId = sessionId; }
    }

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
        app.get("/api/v1/worlds/{world}/rail-map", context -> proxyGet(context, worldPath(context) + "/rail-map"));
        app.sse("/api/v1/worlds/{world}/rail-map/trains", this::trainStream);
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

        app.events(events -> events.serverStopping(() -> {
            trainStreamExecutor.shutdownNow();
            trainStreamWorkers.shutdownNow();
            trainDeliveryWorkers.shutdownNow();
        }));
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

    private void trainStream(SseClient client) {
        requireSession(client.ctx());
        String world = segment(client.ctx().pathParam("world"));
        Queue<TrainSubscriber> clients = trainStreamClients.computeIfAbsent(world, ignored -> new ConcurrentLinkedQueue<>());
        synchronized (trainStreamClients) {
            int totalClients = trainStreamClients.values().stream().mapToInt(Queue::size).sum();
            if (clients.size() >= MAX_STREAM_CLIENTS_PER_WORLD || totalClients >= MAX_STREAM_CLIENTS) {
                client.close();
                return;
            }
            client.keepAlive();
            TrainSubscriber subscriber = new TrainSubscriber(client, client.ctx().cookie(WebUiSessions.COOKIE_NAME));
            clients.add(subscriber);
            client.onClose(() -> clients.remove(subscriber));
        }
        if (trainStreamStarted.compareAndSet(false, true)) {
            trainStreamExecutor.scheduleWithFixedDelay(this::broadcastTrainFrames, 0L, 500L, TimeUnit.MILLISECONDS);
        }
    }

    private void broadcastTrainFrames() {
        trainStreamClients.forEach((world, clients) -> {
            if (clients.isEmpty()) return;
            AtomicBoolean inFlight = trainPollsInFlight.computeIfAbsent(world, ignored -> new AtomicBoolean());
            if (!inFlight.compareAndSet(false, true)) return; // latest-only: skip instead of queueing stale polls
            try {
                trainStreamWorkers.execute(() -> pollAndBroadcastWorld(world, clients, inFlight));
            } catch (RejectedExecutionException rejected) {
                inFlight.set(false); // bounded pool is busy; the next interval retries
            }
        });
    }

    private void pollAndBroadcastWorld(String world, Queue<TrainSubscriber> clients, AtomicBoolean inFlight) {
        try {
            BridgeClient.Response response = bridge.get("worlds/" + world + "/rail-map/trains");
            if (response.status() >= 400) return;
            String payload = response.body().toString();
            clients.removeIf(subscriber -> {
                SseClient client = subscriber.client;
                if (client.terminated() || sessions.find(subscriber.sessionId).isEmpty()) {
                    if (!client.terminated()) client.close();
                    return true;
                }
                subscriber.latest.set(payload); // one latest-only slot per client
                scheduleTrainDelivery(clients, subscriber);
                return false;
            });
        } catch (IOException ignored) {
            // The next bounded interval retries; browser clients keep their existing positions.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // Keep the shared stream workers alive across transient bridge/configuration failures.
        } finally {
            inFlight.set(false);
            if (clients.isEmpty()) {
                trainStreamClients.remove(world, clients);
                trainPollsInFlight.remove(world, inFlight);
            }
        }
    }

    private void scheduleTrainDelivery(Queue<TrainSubscriber> clients, TrainSubscriber subscriber) {
        if (!subscriber.delivering.compareAndSet(false, true)) return;
        try {
            trainDeliveryWorkers.execute(() -> deliverLatestTrainFrame(clients, subscriber));
        } catch (RejectedExecutionException rejected) {
            subscriber.delivering.set(false);
            subscriber.latest.set(null);
            clients.remove(subscriber);
            subscriber.client.close();
        }
    }

    private void deliverLatestTrainFrame(Queue<TrainSubscriber> clients, TrainSubscriber subscriber) {
        try {
            while (!subscriber.client.terminated()) {
                String payload = subscriber.latest.getAndSet(null);
                if (payload == null) break;
                subscriber.client.sendEvent("trains", payload);
            }
        } catch (RuntimeException failure) {
            clients.remove(subscriber);
            if (!subscriber.client.terminated()) subscriber.client.close();
        } finally {
            subscriber.delivering.set(false);
            if (subscriber.latest.get() != null && clients.contains(subscriber)) {
                scheduleTrainDelivery(clients, subscriber);
            }
        }
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
