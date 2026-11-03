package work.art1st.javarailways.webui;

import work.art1st.javarailways.diagnostics.DiskLogReader;
import work.art1st.javarailways.diagnostics.PluginErrorLog;
import java.util.logging.Logger;
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
    private static final System.Logger LOGGER = System.getLogger(WebUiApplication.class.getName());
    private final java.util.Set<String> failedTrainPolls = ConcurrentHashMap.newKeySet();
    private final DiskLogReader logReader;
    private PluginErrorLog errorLog;
    private final WebUiConfig config;
    private final ObjectMapper mapper;
    private final DraftRepository drafts;
    private final DraftRepository editorDocuments;
    private final TimetableProgramRepository timetablePrograms;
    private final TimetableLockManager documentLocks;
    private final BridgeClient bridge;
    private final WebUiSessions sessions;
    private final ConcurrentHashMap<String, Queue<TrainSubscriber>> trainStreamClients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService trainStreamExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "JavaRailways-webui-train-stream");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean trainStreamStarted = new AtomicBoolean();
    private final ConcurrentHashMap<String, AtomicBoolean> trainPollsInFlight = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor trainStreamWorkers = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "JavaRailways-webui-train-poll");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy()
    );
    private final ThreadPoolExecutor trainDeliveryWorkers = new ThreadPoolExecutor(
        0, 64, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "JavaRailways-webui-train-delivery");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy()
    );
    private static final int MAX_STREAM_CLIENTS = 64;
    private static final int MAX_STREAM_CLIENTS_PER_WORLD = 16;
    private record TrainEvent(String name, String payload) {}
    private static final class TrainSubscriber {
        final SseClient client;
        final String sessionId;
        final AtomicReference<TrainEvent> latest = new AtomicReference<>();
        final AtomicBoolean delivering = new AtomicBoolean();
        TrainSubscriber(SseClient client, String sessionId) { this.client = client; this.sessionId = sessionId; }
    }

    WebUiApplication(WebUiConfig config) {
        this(config, new ObjectMapper());
    }

    WebUiApplication(WebUiConfig config, ObjectMapper mapper) {
        this(config, mapper, new WebUiSessions(Duration.ofMinutes(30)));
    }

    WebUiApplication(WebUiConfig config, ObjectMapper mapper, WebUiSessions sessions) {
        this.config = config;
        this.sessions = sessions;
        this.logReader = new DiskLogReader(Map.of("webui-errors", new DiskLogReader.Location(
            "WebUI errors", config.draftDirectory().toAbsolutePath().getParent().resolve("logs/errors.log"))));
        this.mapper = mapper;
        this.drafts = new DraftRepository(config.draftDirectory(), mapper);
        this.editorDocuments = new DraftRepository(config.draftDirectory().resolve("editor-documents"), mapper);
        this.timetablePrograms = new TimetableProgramRepository(config.draftDirectory().resolve("timetable-programs"), mapper);
        this.documentLocks = new TimetableLockManager();
        this.bridge = new BridgeClient(config, mapper);
    }

    public static void main(String[] args) {
        new WebUiApplication(WebUiConfig.fromEnvironment()).start();
    }

    public void start() {
        try {
            createApp().start(config.bindAddress(), config.port());
        } catch (RuntimeException | Error failure) {
            if (errorLog != null) {
                errorLog.reportFailure("WebUI startup failed", failure);
                errorLog.close();
            }
            throw failure;
        }
    }

    Javalin createApp() {
        errorLog = new PluginErrorLog(config.draftDirectory().toAbsolutePath().getParent().resolve("logs/errors.log"),
            Logger.getLogger("work.art1st.javarailways.webui"), "WebUI");
        Javalin app = Javalin.create(settings -> {
            settings.http.maxRequestSize = config.maxRequestBytes();
            settings.staticFiles.add("/public", Location.CLASSPATH);
        });
        app.before(context -> {
            context.header("Referrer-Policy", "no-referrer");
            context.attribute("requestId", java.util.UUID.randomUUID().toString());
        });
        app.get("/health", context -> context.json(Map.of("status", "ok")));
        app.get("/auth/session", this::sessionStatus);
        app.post("/auth/session", this::renewSession);
        app.post("/auth/exchange", this::exchangeLoginToken);
        app.before(context -> {
            if ((context.path().equals("/api/v1") || context.path().startsWith("/api/v1/"))
                && !isPublicMapRequest(context)) {
                requireSession(context);
            }
        });

        app.get("/api/v1/logs/sources", context -> {
            var sources = mapper.createArrayNode();
            logReader.sources().forEach(source -> sources.add(mapper.valueToTree(source)));
            String warning = null;
            try {
                BridgeClient.Response response = bridge.get("logs/sources");
                if (response.status() == 200 && response.body().path("sources").isArray()) response.body().path("sources").forEach(sources::add);
                else warning = "Host log sources unavailable";
            } catch (IOException | IllegalStateException unavailable) {
                warning = "Host log sources unavailable";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                warning = "Host log sources request interrupted";
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("sources", sources);
            if (warning != null) result.put("warning", warning);
            context.json(result);
        });
        app.get("/api/v1/logs", context -> {
            String source = context.queryParam("source");
            if (source == null || !source.matches("[a-z-]{1,64}")) throw new IllegalArgumentException("Invalid log source");
            String query = context.queryParam("query"), cursor = context.queryParam("cursor");
            if (query != null && query.length() > 200 || cursor != null && cursor.length() > 512) throw new IllegalArgumentException("Invalid log query");
            if (source.equals("webui-errors")) context.json(logReader.read(source, query, cursor));
            else proxyGet(context, "logs?source=" + source + "&query=" + encodeQuery(query) + "&cursor=" + encodeQuery(cursor));
        });
        app.get("/api/v1/worlds", context -> proxyGet(context, "worlds"));
        app.get("/api/v1/worlds/{world}/metadata", this::metadata);
        app.get("/api/v1/worlds/{world}/issues", context -> proxyGet(context, worldPath(context) + "/issues"));
        app.get("/api/v1/worlds/{world}/rail-map", context -> proxyGet(context, worldPath(context) + "/rail-map"));
        app.sse("/api/v1/worlds/{world}/rail-map/trains", this::trainStream);
        app.get("/api/v1/worlds/{world}/scripts", this::scripts);
        app.post("/api/v1/worlds/{world}/scripts/compile", this::compileSource);
        app.post("/api/v1/worlds/{world}/timetables/simulate", this::simulateTimetable);
        app.post("/api/v1/worlds/{world}/programs/simulate", this::simulateProgram);
        app.get("/api/v1/worlds/{world}/scripts/{script}", this::renderScript);
        app.get("/api/v1/worlds/{world}/lines", this::lines);
        app.post("/api/v1/worlds/{world}/lines/{line}/simulate", this::simulateLine);
        app.put("/api/v1/worlds/{world}/lines/{line}", this::putLine);
        app.delete("/api/v1/worlds/{world}/lines/{line}", this::deleteLine);
        app.get("/api/v1/worlds/{world}/drafts", this::listDrafts);
        app.post("/api/v1/worlds/{world}/drafts", this::createDraft);
        app.get("/api/v1/worlds/{world}/drafts/{draft}", this::getDraft);
        app.put("/api/v1/worlds/{world}/drafts/{draft}", this::putDraft);
        app.delete("/api/v1/worlds/{world}/drafts/{draft}", this::deleteDraft);
        app.get("/api/v1/worlds/{world}/editor-documents/{document}/programs", this::listTimetablePrograms);
        app.post("/api/v1/worlds/{world}/editor-documents/{document}/programs", this::createTimetableProgram);
        app.post("/api/v1/worlds/{world}/editor-documents/{document}/programs/import", this::importTimetablePrograms);
        app.put("/api/v1/worlds/{world}/editor-documents/{document}/programs/{program}", this::updateTimetableProgram);
        app.delete("/api/v1/worlds/{world}/editor-documents/{document}/programs/{program}", this::deleteTimetableProgram);
        app.post("/api/v1/worlds/{world}/editor-documents/{document}/lock", this::acquireEditorLock);
        app.post("/api/v1/worlds/{world}/editor-documents/{document}/lock/heartbeat", this::heartbeatEditorLock);
        app.delete("/api/v1/worlds/{world}/editor-documents/{document}/lock", this::releaseEditorLock);
        app.get("/api/v1/worlds/{world}/editor-documents", this::listEditorDocuments);
        app.post("/api/v1/worlds/{world}/editor-documents", this::createEditorDocument);
        app.get("/api/v1/worlds/{world}/editor-documents/{document}", this::getEditorDocument);
        app.put("/api/v1/worlds/{world}/editor-documents/{document}", this::putEditorDocument);
        app.delete("/api/v1/worlds/{world}/editor-documents/{document}", this::deleteEditorDocument);
        app.put("/api/v1/worlds/{world}/editor-documents/{document}/formal-script", this::saveFormalScript);
        app.post("/api/v1/worlds/{world}/editor-documents/{document}/formal-script/rename", this::renameFormalScript);
        app.delete("/api/v1/worlds/{world}/editor-documents/{document}/formal-script", this::deleteFormalScript);
        app.post("/api/v1/worlds/{world}/validate", this::validate);
        app.post("/api/v1/worlds/{world}/publish", this::publish);

        app.events(events -> {
            events.serverStopping(() -> {
                trainStreamExecutor.shutdownNow();
                trainStreamWorkers.shutdownNow();
                trainDeliveryWorkers.shutdownNow();
            });
            events.serverStopped(() -> errorLog.close());
        });
        app.exception(UnauthorizedResponse.class,
            (exception, context) -> error(context, 401, "login required or session expired"));
        app.exception(TimetableLockManager.Locked.class,
            (exception, context) -> error(context, 409, "DOCUMENT_LOCKED", exception.getMessage()));
        app.exception(IllegalArgumentException.class,
            (exception, context) -> error(context, 400, exception.getMessage()));
        app.exception(IllegalStateException.class,
            (exception, context) -> {
                logRequestFailure(context, exception);
                error(context, 503, exception.getMessage());
            });
        app.exception(BridgeClient.Failure.class, (exception, context) -> {
            logRequestFailure(context, exception);
            error(context, exception.status, exception.code, exception.getMessage());
        });
        app.exception(IOException.class,
            (exception, context) -> {
                logRequestFailure(context, exception);
                error(context, 502, "STORAGE_UNAVAILABLE", "Draft storage is unavailable");
            });
        app.exception(Exception.class, (exception, context) -> {
            logRequestFailure(context, exception);
            error(context, 500, "internal error");
        });
        app.exception(InterruptedException.class, (exception, context) -> {
            logRequestFailure(context, exception);
            Thread.currentThread().interrupt();
            error(context, 504, "REQUEST_INTERRUPTED", "Bridge request interrupted");
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

    private static String encodeQuery(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }

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
        setSessionCookie(context, created.id());
        context.status(204);
    }

    private void setSessionCookie(Context context, String id) {
        String secure = config.secureCookies() ? "; Secure" : "";
        context.header(
            "Set-Cookie",
            WebUiSessions.COOKIE_NAME + "=" + id + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + sessions.cookieMaxAgeSeconds() + secure
        );
    }

    private void renewSession(Context context) {
        String id = context.cookie(WebUiSessions.COOKIE_NAME);
        WebUiSessions.Session session = sessions.renew(id).orElseThrow(UnauthorizedResponse::new);
        setSessionCookie(context, id);
        authenticatedSessionStatus(context, session);
    }

    private void sessionStatus(Context context) {
        sessions.find(context.cookie(WebUiSessions.COOKIE_NAME)).ifPresentOrElse(
            session -> authenticatedSessionStatus(context, session),
            () -> context.json(Map.of("authenticated", false))
        );
    }

    private void authenticatedSessionStatus(Context context, WebUiSessions.Session session) {
        context.json(Map.of(
            "authenticated", true,
            "playerName", session.playerName(),
            "worldId", session.worldId(),
            "expiresAtEpochMillis", session.expiresAtEpochMillis()
        ));
    }

    private boolean isPublicMapRequest(Context context) {
        if (!context.method().name().equals("GET")) return false;
        String[] parts = context.path().substring(1).split("/");
        if (parts.length == 3) {
            return parts[0].equals("api") && parts[1].equals("v1") && parts[2].equals("worlds");
        }
        if (parts.length == 5 || parts.length == 6) {
            return parts[0].equals("api") && parts[1].equals("v1") && parts[2].equals("worlds")
                && parts[4].equals("rail-map") && (parts.length == 5 || parts[5].equals("trains"));
        }
        return false;
    }

    private void requireSession(Context context) {
        String id = context.cookie(WebUiSessions.COOKIE_NAME);
        WebUiSessions.Session session = sessions.find(id).orElseThrow(UnauthorizedResponse::new);
        context.attribute("webUiSession", session);
    }

    private void trainStream(SseClient client) {
        String world = segment(client.ctx().pathParam("world"));
        Queue<TrainSubscriber> clients = trainStreamClients.computeIfAbsent(world, ignored -> new ConcurrentLinkedQueue<>());
        synchronized (trainStreamClients) {
            int totalClients = trainStreamClients.values().stream().mapToInt(Queue::size).sum();
            if (clients.size() >= MAX_STREAM_CLIENTS_PER_WORLD || totalClients >= MAX_STREAM_CLIENTS) {
                client.close();
                return;
            }
            client.keepAlive();
            String cookieSessionId = client.ctx().cookie(WebUiSessions.COOKIE_NAME);
            String sessionId = sessions.find(cookieSessionId).isPresent() ? cookieSessionId : null;
            TrainSubscriber subscriber = new TrainSubscriber(client, sessionId);
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
            if (response.status() >= 400) {
                reportTrainPollFailure(world, "HTTP " + response.status(), null);
                return;
            }
            if (!response.body().isArray()) {
                reportTrainPollFailure(world, "invalid train frame", null);
                return;
            }
            failedTrainPolls.remove(world);
            String payload = response.body().toString();
            clients.removeIf(subscriber -> {
                SseClient client = subscriber.client;
                if (client.terminated() || (subscriber.sessionId != null && sessions.find(subscriber.sessionId).isEmpty())) {
                    if (!client.terminated()) client.close();
                    return true;
                }
                subscriber.latest.set(new TrainEvent("trains", payload)); // one latest-only slot per client
                scheduleTrainDelivery(clients, subscriber);
                return false;
            });
        } catch (IOException failure) {
            reportTrainPollFailure(world, "transport failure", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            reportTrainPollFailure(world, "poll failure", failure);
        } finally {
            inFlight.set(false);
            if (clients.isEmpty()) {
                trainStreamClients.remove(world, clients);
                trainPollsInFlight.remove(world, inFlight);
                failedTrainPolls.remove(world);
            }
        }
    }

    private void reportTrainPollFailure(String world, String reason, Throwable failure) {
        Queue<TrainSubscriber> clients = trainStreamClients.get(world);
        if (clients != null) {
            String status = reason.equals("invalid train frame") ? "invalid-frame" : "bridge-unavailable";
            for (TrainSubscriber subscriber : clients) {
                subscriber.latest.set(new TrainEvent("stream-error", status));
                scheduleTrainDelivery(clients, subscriber);
            }
        }
        // Status events never renew the age of the last train frame.
        // Log the first failure of an outage; a successful frame re-arms reporting.
        if (failedTrainPolls.add(world)) {
            LOGGER.log(System.Logger.Level.WARNING, "Train position stream unavailable for world " + world + ": " + reason, failure);
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
                TrainEvent event = subscriber.latest.getAndSet(null);
                if (event == null) break;
                subscriber.client.sendEvent(event.name(), event.payload());
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

    private void compileSource(Context context) throws IOException, InterruptedException {
        respond(context, bridge.post(worldPath(context) + "/scripts/compile", requestJson(context)));
    }

    private void simulateProgram(Context context) throws IOException, InterruptedException {
        JsonNode request = requestJson(context);
        ProgramSimulationValidator.validate(request);
        respond(context, bridge.simulate(worldPath(context) + "/programs/simulate", request));
    }

    private void simulateTimetable(Context context) throws IOException, InterruptedException {
        respond(context, bridge.simulate(worldPath(context) + "/timetables/simulate", requestJson(context)));
    }

    private String worldPath(Context context) {
        return "worlds/" + segment(context.pathParam("world"));
    }

    private void lines(Context context) throws IOException, InterruptedException {
        respond(context, bridge.get(lineCollectionPath(context)));
    }

    private void simulateLine(Context context) throws IOException, InterruptedException {
        respond(context, bridge.simulate(
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

    private void listTimetablePrograms(Context context) throws IOException {
        context.json(timetablePrograms.list(context.pathParam("world"), context.pathParam("document")));
    }

    private void createTimetableProgram(Context context) throws IOException {
        String world = context.pathParam("world"), document = context.pathParam("document");
        requireEditorLock(context, world, document);
        JsonNode request = requestJson(context);
        StationProgramValidator.fields(request, java.util.Set.of("id", "name", "definition"), java.util.Set.of("estimatedDurationTicks"), "create timetable program request");
        context.status(201).json(timetablePrograms.create(world, document,
            StationProgramValidator.text(request.path("id"), "program id"),
            StationProgramValidator.text(request.path("name"), "program name"),
            request.path("definition"), request.get("estimatedDurationTicks")));
    }

    private void updateTimetableProgram(Context context) throws IOException {
        String world = context.pathParam("world"), document = context.pathParam("document");
        requireEditorLock(context, world, document);
        JsonNode request = requestJson(context);
        StationProgramValidator.fields(request, java.util.Set.of("name", "definition"), java.util.Set.of("estimatedDurationTicks"), "update timetable program request");
        timetablePrograms.save(world, document, context.pathParam("program"),
            StationProgramValidator.text(request.path("name"), "program name"),
            request.path("definition"), request.get("estimatedDurationTicks"))
            .ifPresentOrElse(context::json, () -> error(context, 404, "program not found"));
    }

    private void deleteTimetableProgram(Context context) throws IOException {
        String world = context.pathParam("world"), document = context.pathParam("document"), program = context.pathParam("program");
        requireEditorLock(context, world, document);
        if (programInUse(world, document, program)) {
            error(context, 409, "PROGRAM_IN_USE", "program is still referenced by this timetable");
            return;
        }
        if (!timetablePrograms.delete(world, document, program)) {
            error(context, 404, "program not found");
            return;
        }
        context.json(Map.of("removed", true));
    }

    private void importTimetablePrograms(Context context) throws IOException {
        String world = context.pathParam("world"), document = context.pathParam("document");
        requireEditorLock(context, world, document);
        JsonNode request = requestJson(context);
        StationProgramValidator.fields(request, java.util.Set.of("sourceDocumentId"), java.util.Set.of("programIds"), "import timetable programs request");
        String source = StationProgramValidator.text(request.path("sourceDocumentId"), "source timetable id");
        java.util.List<String> ids = null;
        if (request.has("programIds")) {
            if (!request.path("programIds").isArray()) throw new IllegalArgumentException("programIds must be an array");
            ids = new java.util.ArrayList<>();
            for (JsonNode id : request.path("programIds")) ids.add(StationProgramValidator.text(id, "program id"));
        }
        context.json(timetablePrograms.importPrograms(world, document, source, ids));
    }

    private void acquireEditorLock(Context context) throws IOException {
        String world = context.pathParam("world"), document = context.pathParam("document");
        if (editorDocuments.find(world, document).isEmpty()) {
            error(context, 404, "editor document not found");
            return;
        }
        if (!documentLocks.acquire(world, document, lockOwner(context))) throw new TimetableLockManager.Locked();
        context.status(204);
    }

    private void heartbeatEditorLock(Context context) {
        if (!documentLocks.heartbeat(context.pathParam("world"), context.pathParam("document"), lockOwner(context))) {
            throw new TimetableLockManager.Locked();
        }
        context.status(204);
    }

    private void releaseEditorLock(Context context) {
        documentLocks.release(context.pathParam("world"), context.pathParam("document"), lockOwner(context));
        context.status(204);
    }

    private String lockOwner(Context context) {
        String owner = context.cookie(WebUiSessions.COOKIE_NAME);
        if (owner == null || owner.isBlank()) throw new UnauthorizedResponse();
        return owner;
    }

    private void requireEditorLock(Context context, String world, String document) {
        if (!documentLocks.heartbeat(world, document, lockOwner(context))) throw new TimetableLockManager.Locked();
    }

    private boolean programInUse(String world, String document, String program) throws IOException {
        var found = editorDocuments.find(world, document);
        if (found.isEmpty()) return false;
        JsonNode value = found.get();
        for (JsonNode event : value.path("events")) {
            if (program.equals(event.path("program").path("programId").asText())) return true;
        }
        for (JsonNode duty : value.path("duties")) {
            for (JsonNode element : duty.path("elements")) {
                if (program.equals(element.path("program").path("programId").asText())) return true;
            }
        }
        return false;
    }

    private java.util.Map<String, JsonNode> programsFor(String world, String document) throws IOException {
        java.util.Map<String, JsonNode> programs = new java.util.HashMap<>();
        for (JsonNode program : timetablePrograms.list(world, document)) programs.put(program.path("id").asText(), program);
        return programs;
    }

    private void listEditorDocuments(Context context) throws IOException {
        context.json(editorDocuments.list(context.pathParam("world")));
    }

    private void createEditorDocument(Context context) throws IOException {
        JsonNode document = requestJson(context);
        requireEditorDocument(document);
        String world = context.pathParam("world");
        String id = editorDocuments.create(world, document);
        if (!documentLocks.acquire(world, id, lockOwner(context))) {
            editorDocuments.delete(world, id);
            throw new TimetableLockManager.Locked();
        }
        context.status(201).json(Map.of("id", id));
    }

    private void getEditorDocument(Context context) throws IOException {
        editorDocuments.find(context.pathParam("world"), context.pathParam("document"))
            .ifPresentOrElse(context::json, () -> error(context, 404, "editor document not found"));
    }

    private void putEditorDocument(Context context) throws IOException {
        JsonNode document = requestJson(context);
        String world = context.pathParam("world"), id = context.pathParam("document");
        requireEditorLock(context, world, id);
        EditorDocumentValidator.validate(document, programsFor(world, id));
        editorDocuments.save(world, id, document);
        context.status(204);
    }

    private void deleteEditorDocument(Context context) throws IOException {
        String world = context.pathParam("world"), id = context.pathParam("document");
        requireEditorLock(context, world, id);
        if (!editorDocuments.delete(world, id)) {
            error(context, 404, "editor document not found");
            return;
        }
        timetablePrograms.deleteAll(world, id);
        documentLocks.release(world, id, lockOwner(context));
        context.status(204);
    }

    private void saveFormalScript(Context context) throws IOException, InterruptedException {
        JsonNode request = requestJson(context);
        JsonNode document = request.path("document");
        String lockWorld = context.pathParam("world"), lockDocument = context.pathParam("document");
        requireEditorLock(context, lockWorld, lockDocument);
        EditorDocumentValidator.validate(document, programsFor(lockWorld, lockDocument));
        String fileName = requiredScriptName(request.path("fileName").asText(""));
        if (!fileName.equals(document.path("formalScriptName").asText())) {
            throw new IllegalArgumentException("document formalScriptName must match fileName");
        }
        String world = context.pathParam("world");
        String documentId = context.pathParam("document");
        var previous = editorDocuments.find(world, documentId);
        if (previous.map(value -> value.path("formalScriptName").asText(""))
            .filter(value -> !value.isBlank() && !value.equals(fileName)).isPresent()) {
            throw new IllegalArgumentException("rename the existing formal script before saving a different file name");
        }
        editorDocuments.save(world, documentId, document);
        BridgeClient.Response response;
        try {
            response = bridge.put(
                worldPath(context) + "/scripts/" + segment(fileName),
                mapper.createObjectNode()
                    .put("id", document.path("id").asText())
                    .put("source", request.path("source").asText())
                    .put("overwrite", request.path("overwrite").asBoolean(false))
            );
        } catch (IOException | InterruptedException failure) {
            restoreEditorDocument(world, documentId, previous);
            throw failure;
        }
        if (response.status() >= 400) restoreEditorDocument(world, documentId, previous);
        respond(context, response);
    }

    private void renameFormalScript(Context context) throws IOException, InterruptedException {
        JsonNode request = requestJson(context);
        JsonNode document = request.path("document");
        String lockWorld = context.pathParam("world"), lockDocument = context.pathParam("document");
        requireEditorLock(context, lockWorld, lockDocument);
        EditorDocumentValidator.validate(document, programsFor(lockWorld, lockDocument));
        String sourceName = requiredScriptName(request.path("sourceName").asText(""));
        String targetName = requiredScriptName(request.path("targetName").asText(""));
        if (!targetName.equals(document.path("formalScriptName").asText())) {
            throw new IllegalArgumentException("document formalScriptName must match targetName");
        }
        String world = context.pathParam("world");
        String documentId = context.pathParam("document");
        var previous = editorDocuments.find(world, documentId);
        if (previous.isEmpty() || !sourceName.equals(previous.get().path("formalScriptName").asText())) {
            throw new IllegalArgumentException("sourceName does not match the editor document formal script");
        }
        editorDocuments.save(world, documentId, document);
        BridgeClient.Response response;
        try {
            response = bridge.post(
                worldPath(context) + "/scripts/" + segment(sourceName) + "/rename",
                mapper.createObjectNode().put("targetName", targetName)
            );
        } catch (IOException | InterruptedException failure) {
            restoreEditorDocument(world, documentId, previous);
            throw failure;
        }
        if (response.status() >= 400) restoreEditorDocument(world, documentId, previous);
        respond(context, response);
    }

    private void deleteFormalScript(Context context) throws IOException, InterruptedException {
        JsonNode request = requestJson(context);
        String fileName = requiredScriptName(request.path("fileName").asText(""));
        String world = context.pathParam("world");
        String documentId = context.pathParam("document");
        requireEditorLock(context, world, documentId);
        var previous = editorDocuments.find(world, documentId);
        if (previous.isEmpty()) {
            error(context, 404, "editor document not found");
            return;
        }
        if (!fileName.equals(previous.get().path("formalScriptName").asText())) {
            throw new IllegalArgumentException("fileName does not match the editor document formal script");
        }
        editorDocuments.delete(world, documentId);
        timetablePrograms.deleteAll(world, documentId);
        documentLocks.release(world, documentId, lockOwner(context));
        BridgeClient.Response response;
        try {
            response = bridge.delete(
                worldPath(context) + "/scripts/" + segment(fileName), mapper.createObjectNode()
            );
        } catch (IOException | InterruptedException failure) {
            restoreEditorDocument(world, documentId, previous);
            throw failure;
        }
        if (response.status() >= 400) restoreEditorDocument(world, documentId, previous);
        respond(context, response);
    }

    private void restoreEditorDocument(String world, String id, java.util.Optional<JsonNode> previous) throws IOException {
        if (previous.isPresent()) editorDocuments.save(world, id, previous.get());
        else editorDocuments.delete(world, id);
    }

    private static String requiredScriptName(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,123}\\.js")) {
            throw new IllegalArgumentException("invalid script file name");
        }
        return value;
    }

    static void requireEditorDocument(JsonNode document) {
        EditorDocumentValidator.validate(document);
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
        JsonNode json;
        try {
            json = mapper.readTree(bytes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidInput) {
            // Input syntax is a client rejection, not a runtime failure. Do not retain the
            // parser exception: its message can contain user-supplied authentication data.
            throw new IllegalArgumentException("request body must be JSON");
        }
        if (json == null) throw new IllegalArgumentException("request body must be JSON");
        return json;
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void respond(Context context, BridgeClient.Response response) {
        if (response.status() >= 400) {
            ObjectNode body = response.body().isObject() ? ((ObjectNode) response.body()).deepCopy() : new ObjectMapper().createObjectNode();
            if (!body.path("error").isTextual()) body.put("error", "Server request failed");
            if (!body.path("code").isTextual()) body.put("code",
                response.status() == 422 && context.path().endsWith("/simulate") ? "SIMULATION_REJECTED" : errorCode(response.status()));
            body.put("requestId", (String) context.attribute("requestId"));
            LOGGER.log(System.Logger.Level.WARNING, "WebUI bridge request failed requestId=" + context.attribute("requestId")
                + " method=" + context.method() + " path=" + context.path()
                + " status=" + response.status() + " code=" + body.path("code").asText());
            context.status(response.status()).json(body);
        } else context.status(response.status()).json(response.body());
    }

    private static void error(Context context, int status, String message) {
        error(context, status, errorCode(status), message);
    }

    private static String errorCode(int status) {
        return switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "AUTH_REQUIRED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 422 -> "REQUEST_REJECTED";
            case 429 -> "TOO_MANY_REQUESTS";
            case 502 -> "BRIDGE_UNAVAILABLE";
            case 503 -> "SERVICE_UNAVAILABLE";
            case 504 -> "BRIDGE_TIMEOUT";
            default -> "INTERNAL_ERROR";
        };
    }

    private static void error(Context context, int status, String code, String message) {
        context.status(status).json(Map.of("error", message == null ? "Request failed" : message,
            "code", code, "requestId", (String) context.attribute("requestId")));
    }

    private void logRequestFailure(Context context, Exception exception) {
        errorLog.reportFailure("WebUI request failed requestId=" + context.attribute("requestId")
            + " method=" + context.method() + " path=" + context.path(), exception);
    }
}
