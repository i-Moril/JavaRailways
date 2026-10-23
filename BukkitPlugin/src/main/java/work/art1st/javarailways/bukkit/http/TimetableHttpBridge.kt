package work.art1st.javarailways.bukkit.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineCompileResult
import work.art1st.javarailways.simpleato.api.line.LineDirection
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.api.line.LinePlanner
import work.art1st.javarailways.simpleato.api.line.LineRevisionConflict
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionProvider
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.persistence.SavedTimetableV1
import work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec
import work.art1st.javarailways.simpleato.timetable.TimetableValidator
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationRequest
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationResult
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal class HttpFailure(val status: Int, message: String) : RuntimeException(message)

/** Resolve either the current authoring cap or an authoritative stored service cap. */
internal fun resolveLineSimulationMaximumSpeed(
    request: JsonObject,
    resolveStored: (timetableId: String, serviceId: String, runId: String?) -> Double
): Double? {
    val timetableId = request["timetableId"]?.jsonPrimitive?.content
    val serviceId = request["serviceId"]?.jsonPrimitive?.content
    if ((timetableId == null) != (serviceId == null)) {
        throw HttpFailure(400, "timetableId and serviceId must be provided together")
    }
    val runId = request["runId"]?.jsonPrimitive?.content
    if (runId != null && (runId.isBlank() || timetableId == null)) throw HttpFailure(400, "runId requires timetableId and serviceId")
    val inlineMaximum = parseLineMaximumSpeed(request["maximumSpeedMetersPerSecond"])
    if (timetableId != null && serviceId != null) {
        if (inlineMaximum != null) {
            throw HttpFailure(400, "maximumSpeedMetersPerSecond cannot be combined with timetableId and serviceId")
        }
        return resolveStored(timetableId, serviceId, runId)
    }
    return inlineMaximum
}

internal fun parseLineMaximumSpeed(element: JsonElement?): Double? {
    if (element == null || element == JsonNull) return null
    val value = (element as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    if (value == null || !value.isFinite() || value <= 0.0) {
        throw HttpFailure(400, "maximumSpeedMetersPerSecond must be a finite positive number")
    }
    return value
}

internal fun resolveInfrastructureLineMaximumSpeed(configured: Double?, requested: Double?): Double {
    val maximum = configured ?: throw HttpFailure(422, "No maximum speed is configured for infrastructure line")
    if (requested != null && requested != maximum) throw HttpFailure(400, "Set maximum speed in line settings")
    return maximum
}

internal fun unitDisplayJson(display: work.art1st.javarailways.simpleato.timetable.TimetableUnitDisplaySnapshot): JsonObject = buildJsonObject {
    put("unitId", display.unitId)
    put("operationalTripId", display.operationalTripId)
    put("cycleIndex", display.cycleIndex)
    put("active", display.active)
    put("values", display.values)
    fun stops(values: List<work.art1st.javarailways.simpleato.timetable.TimetableUnitStopSnapshot>) = JsonArray(values.map { stop -> buildJsonObject {
        put("serviceId", stop.serviceId); put("runId", stop.runId); put("completed", stop.completed)
        put("call", buildJsonObject {
            put("id", stop.call.id); put("stationId", stop.call.stationId); put("destinationId", stop.call.destinationId)
            put("status", stop.call.status.name); put("arrivalTick", stop.call.arrivalTick); put("departureTick", stop.call.departureTick)
        })
    } })
    put("plannedStops", stops(display.plannedStops))
    put("completedStops", stops(display.completedStops))
    put("remainingStops", stops(display.remainingStops))
    put("unresolvedRuns", JsonArray(display.unresolvedRuns.map { JsonPrimitive(it) }))
}

private data class LineSimulationSnapshot(
    val revision: Long,
    val profile: SimulationControlProfile,
    val gravityMetersPerSecondSquared: Double,
    val legs: List<SimulationLeg>,
    val lineMaximumSpeedMetersPerSecond: Double?
)

class TimetableHttpBridge(private val plugin: BukkitPlugin) : Listener {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }
    private var server: HttpServer? = null
    private val executor = Executors.newCachedThreadPool { Thread(it, "JavaRailways-timetable-http").apply { isDaemon = true } }
    private val railMapPreview = RailMapPreviewService(java.io.File(plugin.dataFolder, "webui-maps"))
    private val simulationExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8),
        { task -> Thread(task, "JavaRailways-webui-simulation").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun start(managedToken: String? = null) {
        val config = plugin.javaRailwaysConfig.timetableHttpBridge
        if (!config.enabled) return
        val token = managedToken ?: System.getenv(config.bearerTokenEnvironmentVariable)
            ?: throw IllegalStateException("Timetable HTTP bridge token environment variable is not set: ${config.bearerTokenEnvironmentVariable}")
        require(token.isNotEmpty()) { "Timetable HTTP bridge bearer token must not be empty" }
        server = HttpServer.create(InetSocketAddress(config.bindAddress, config.port), 0).also { http ->
            http.executor = executor
            http.createContext("/", Handler(config.bodyLimitBytes, config.timeoutMillis, token))
            http.start()
        }
        plugin.server.pluginManager.registerEvents(this,plugin)
        plugin.logger.info("Timetable HTTP bridge listening on ${config.bindAddress}:${config.port}")
    }

    @EventHandler
    fun onWorldUnload(event:WorldUnloadEvent)=railMapPreview.invalidate(event.world.name)

    fun stop() {
        HandlerList.unregisterAll(this)
        server?.stop(0)
        server = null
        executor.shutdownNow()
        simulationExecutor.shutdownNow()
        railMapPreview.close()
    }

    private inner class Handler(
        private val bodyLimitBytes: Int,
        private val timeoutMillis: Long,
        private val token: String
    ) : com.sun.net.httpserver.HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                if (exchange.requestURI.path != "/health" && !authorized(exchange)) {
                    exchange.respond(401, "{\"error\":\"unauthorized\"}")
                    return
                }
                route(exchange)
            } catch (e: HttpFailure) {
                exchange.respond(e.status, error(e.message ?: "request failed"))
            } catch (e: IllegalArgumentException) {
                exchange.respond(400, error(e.message ?: "bad request"))
            } catch (e: Exception) {
                plugin.logger.warning("Timetable HTTP bridge request failed: ${e.message}")
                exchange.respond(500, error("internal error"))
            }
        }

        private fun route(exchange: HttpExchange) {
            val parts = exchange.requestURI.path.trim('/').split('/').filter { it.isNotEmpty() }
            when {
                exchange.requestMethod == "GET" && parts == listOf("health") -> exchange.respond(200, "{\"ok\":true}")
                exchange.requestMethod == "POST" && parts == listOf("auth", "exchange") -> {
                    exchange.respond(200, exchangeLoginToken(readBody(exchange)))
                }
                exchange.requestMethod == "GET" && parts == listOf("worlds") -> exchange.respond(200, await { worldsJson() })
                parts.size >= 2 && parts[0] == "worlds" -> routeWorld(exchange, parts.drop(1))
                else -> exchange.respond(404, error("not found"))
            }
        }

        private fun routeWorld(exchange: HttpExchange, parts: List<String>) {
            val worldName = decode(parts[0])
            when {
                exchange.requestMethod == "GET" && parts.size == 2 && parts[1] == "issues" -> exchange.respond(200, await {
                    withWorld(worldName) { bukkit, _ ->
                        json.encodeToString(buildJsonArray {
                            plugin.worldIssues.snapshot(bukkit.uid).forEach { issue ->
                                add(buildJsonObject {
                                    put("source", issue.source)
                                    put("kind", issue.kind.name)
                                    put("objectId", issue.objectId)
                                    put("code", issue.code)
                                    put("message", issue.message)
                                    put("details", issue.details?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                                    put("firstSeen", issue.firstSeen.toString())
                                    put("lastSeen", issue.lastSeen.toString())
                                })
                            }
                        })
                    }
                })
                exchange.requestMethod == "GET" && parts.size == 2 && parts[1] == "metadata" -> exchange.respond(200, await { metadataJson(worldName) })
                exchange.requestMethod == "GET" && parts.size == 2 && parts[1] == "extensions" -> exchange.respond(200, await { extensionsJson(worldName) })
                exchange.requestMethod == "GET" && parts.size == 2 && parts[1] == "rail-map" -> {
                    val snapshot = await { withWorld(worldName) { _, world ->
                        railMapPreview.capture(worldName, world as work.art1st.javarailways.core.physics.CoreWorld)
                    } }
                    exchange.respond(200, railMapPreview.render(snapshot))
                }
                exchange.requestMethod == "GET" && parts.size == 3 && parts[1] == "rail-map" && parts[2] == "trains" ->
                    exchange.respond(200, railMapPreview.trains(worldName))
                exchange.requestMethod == "GET" && parts.size == 2 && parts[1] == "scripts" ->
                    exchange.respond(200, scriptsJson())
                exchange.requestMethod == "POST" && parts.size == 3 && parts[1] == "scripts" && parts[2] == "compile" -> {
                    exchange.respond(200, compileSourceJson(worldName, readBody(exchange)))
                }
                exchange.requestMethod == "PUT" && parts.size == 3 && parts[1] == "scripts" -> {
                    exchange.respond(200, saveScriptJson(worldName, decode(parts[2]), readBody(exchange)))
                }
                exchange.requestMethod == "POST" && parts.size == 4 && parts[1] == "scripts" && parts[3] == "rename" -> {
                    exchange.respond(200, renameScriptJson(decode(parts[2]), readBody(exchange)))
                }
                exchange.requestMethod == "DELETE" && parts.size == 3 && parts[1] == "scripts" -> {
                    exchange.respond(200, deleteScriptJson(decode(parts[2])))
                }
                exchange.requestMethod == "GET" && parts.size == 3 && parts[1] == "scripts" -> {
                    val scriptName = decode(parts[2])
                    val timetableId = exchange.requestURI.rawQuery?.split('&')?.mapNotNull { pair ->
                        val pieces = pair.split('=', limit = 2)
                        if (pieces.firstOrNull() == "id") decode(pieces.getOrElse(1) { "" }) else null
                    }?.firstOrNull()?.takeIf { it.isNotBlank() } ?: scriptName.removeSuffix(".js")
                    exchange.respond(200, renderScriptJson(worldName, scriptName, timetableId))
                }
                exchange.requestMethod == "GET" && parts.size == 2 && parts[1] == "lines" -> exchange.respond(200, await { linesJson(worldName) })
                exchange.requestMethod == "POST" && parts.size == 4 && parts[1] == "lines" && parts[3] == "simulate" -> {
                    val body = readBody(exchange)
                    exchange.respond(200, simulateLineJson(worldName, decode(parts[2]), body))
                }
                exchange.requestMethod == "PUT" && parts.size == 3 && parts[1] == "lines" -> {
                    val body = readBody(exchange)
                    exchange.respond(200, await { putLineJson(worldName, decode(parts[2]), body) })
                }
                exchange.requestMethod == "DELETE" && parts.size == 3 && parts[1] == "lines" -> {
                    val body = readBody(exchange)
                    exchange.respond(200, await { deleteLineJson(worldName, decode(parts[2]), body) })
                }
                exchange.requestMethod == "POST" && parts.size == 3 && parts[1] == "timetables" && parts[2] == "simulate" -> {
                    exchange.respond(200, simulateTimetableJson(worldName, readBody(exchange)))
                }
                exchange.requestMethod == "GET" && parts.size == 2 && parts[1] == "timetables" -> exchange.respond(200, await { timetablesJson(worldName) })
                exchange.requestMethod == "GET" && parts.size == 3 && parts[1] == "timetables" -> exchange.respond(200, await { timetableJson(worldName, decode(parts[2])) })
                exchange.requestMethod == "POST" && parts.size == 4 && parts[1] == "timetables" && parts[3] == "validate" -> {
                    val body = readBody(exchange)
                    exchange.respond(200, await { validateJson(worldName, decode(parts[2]), body) })
                }
                exchange.requestMethod == "PUT" && parts.size == 4 && parts[1] == "timetables" && parts[3] == "publish" -> {
                    val body = readBody(exchange)
                    exchange.respond(200, await { publishJson(worldName, decode(parts[2]), body, exchange.requestHeaders.getFirst("If-Match")) })
                }
                else -> exchange.respond(404, error("not found"))
            }
        }

        private fun <T> await(action: () -> T): T = awaitDomainHttpRequest(
            plugin.platformDispatcher, timeoutMillis,
            expired = { HttpFailure(504, "request expired before entering the simulation context") },
            action = action
        )

        private fun exchangeLoginToken(body: String): String {
            val token = json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.content
                ?: throw HttpFailure(400, "token is required")
            val grant = plugin.webUiLoginTokens.consume(token)
                ?: throw HttpFailure(401, "login token is invalid, expired, or already used")
            return json.encodeToString(buildJsonObject {
                put("playerId", grant.playerId.toString())
                put("playerName", grant.playerName)
                put("worldId", grant.worldName)
                put("expiresAtEpochMillis", grant.expiresAtEpochMillis)
            })
        }

        private fun worldsJson() = json.encodeToString(JsonArray(
            plugin.worldManager.railWorlds.keys.sortedBy { it.name }.map { world ->
                buildJsonObject {
                    put("id", world.name)
                    put("name", world.name)
                    put("uuid", world.uid.toString())
                }
            }
        ))

        private fun metadataJson(worldName: String) = withWorld(worldName) { bukkit, world ->
            val data = world.basicWorldData
            val operationalData = world.simpleAtoWorldData
            val destinations = operationalData.destinations.all()
            val depotIds = buildSet {
                destinations.mapNotNullTo(this) {
                    (it.role as? DestinationRole.DepotStablingPointRole)?.depotId
                }
                operationalData.depots.allDepots().mapTo(this) { it.id.value }
                operationalData.depots.allRollingStock().mapTo(this) { it.record.homeDepotId.value }
            }.sorted()
            json.encodeToString(buildJsonObject {
                put("id", bukkit.name)
                put("name", bukkit.name)
                put("uuid", bukkit.uid.toString())
                put("wayPoints", JsonArray(data.wayPoints.all().map { JsonPrimitive(it.id) }))
                put("stations", JsonArray(destinations.mapNotNull { destination ->
                    val role = destination.role as? DestinationRole.StationBerthRole
                        ?: return@mapNotNull null
                    buildJsonObject {
                        put("stationId", role.stationId.value)
                        put("stationDisplayName", role.stationDisplayName)
                        put("berthId", role.berthId)
                        put("stoppingAreaId", role.stoppingAreaId)
                        role.minimumTrainLengthMeters?.let { put("minimumTrainLengthMeters", it) }
                        role.maximumTrainLengthMeters?.let { put("maximumTrainLengthMeters", it) }
                        role.usableLengthMeters?.let { put("usableLengthMeters", it) }
                        put("destinationId", destination.id)
                    }
                }))
                put("depots", JsonArray(depotIds.map(::JsonPrimitive)))
                put("depotDetails", JsonArray(operationalData.depots.allDepots().map { depot -> buildJsonObject {
                    put("id", depot.id.value)
                    put("displayName", depot.displayName)
                    put("stablingPoints", JsonArray(operationalData.depots.stablingPoints(depot.id).map { point -> buildJsonObject {
                        put("id", point.point.ref.pointId)
                        put("destinationId", point.point.destinationId)
                    } }))
                } }))
                put("trains", JsonArray(world.allTrains.map { JsonPrimitive(it.name) }.sortedBy { it.content }))
                put("simulationProfiles", JsonArray(
                    SimulationControlProfileCatalog(plugin).descriptors(world).map { profile -> buildJsonObject {
                        put("type", profile.type.name)
                        put("id", profile.id)
                        put("displayName", profile.displayName)
                        put("carCount", profile.carCount)
                    } }
                ))
                put("destinations", JsonArray(destinations.map { destination ->
                    buildJsonObject {
                        put("id", destination.id)
                        put("displayName", destination.displayName)
                        put("railId", destination.railId.toString())
                        put("arcLengthMeters", destination.arcLengthInAbMeters)
                        put("approachDirection", destination.approachDirection?.name ?: "ANY")
                        put("role", destination.role::class.simpleName ?: "Unknown")
                    }
                }))
            })
        }

        private fun extensionsJson(worldName: String) = withWorld(worldName) { _, world ->
            json.encodeToString(buildJsonObject {
                put("conditions", JsonArray(
                    world.simpleAtoWorldData.timetableExtensions.conditionProviders().map(::conditionDescriptorJson)
                ))
                put("actions", JsonArray(
                    world.simpleAtoWorldData.timetableExtensions.actionProviders().map { provider ->
                        descriptorJson(
                            provider.typeId,
                            provider.version,
                            provider.descriptor.title,
                            provider.descriptor.description,
                            provider.descriptor.parameterSchema
                        )
                    }
                ))
            })
        }
        private fun scriptsJson(): String = json.encodeToString(JsonArray(
            plugin.timetableScriptSandbox.listScripts().map { script -> buildJsonObject {
                put("name", script.name)
                put("sizeBytes", script.sizeBytes)
                put("modifiedAtEpochMillis", script.modifiedAtEpochMillis)
            } }
        ))

        private fun renderScriptJson(worldName: String, scriptName: String, timetableId: String): String {
            val source = plugin.timetableScriptSandbox.readSource(scriptName)
            val compiled = compileSource(worldName, source, timetableId)
            return json.encodeToString(buildJsonObject {
                put("name", scriptName)
                put("source", source)
                put("timetable", json.parseToJsonElement(TimetableSnapshotCodec.encode(compiled.first)))
                put("validation", validationJson(compiled.second))
            })
        }

        private fun saveScriptJson(worldName: String, scriptName: String, body: String): String {
            val root = json.parseToJsonElement(body).jsonObject
            val source = root["source"]?.jsonPrimitive?.content
                ?: throw HttpFailure(400, "source is required")
            val timetableId = root["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: throw HttpFailure(400, "id is required")
            val overwrite = root["overwrite"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val compiled = compileSource(worldName, source, timetableId)
            if (!compiled.second.isSuccess) throw HttpFailure(422, compiled.second.errors.joinToString("; "))
            val replaced = try {
                plugin.timetableScriptSandbox.saveSource(scriptName, source, overwrite)
            } catch (conflict: TimetableScriptConflict) {
                throw HttpFailure(409, conflict.message ?: "script already exists")
            }
            return json.encodeToString(buildJsonObject {
                put("name", scriptName)
                put("replaced", replaced)
                put("timetable", json.parseToJsonElement(TimetableSnapshotCodec.encode(compiled.first)))
            })
        }

        private fun renameScriptJson(scriptName: String, body: String): String {
            val targetName = json.parseToJsonElement(body).jsonObject["targetName"]?.jsonPrimitive?.content
                ?: throw HttpFailure(400, "targetName is required")
            try {
                plugin.timetableScriptSandbox.renameSource(scriptName, targetName)
            } catch (conflict: TimetableScriptConflict) {
                throw HttpFailure(409, conflict.message ?: "target script already exists")
            }
            return json.encodeToString(buildJsonObject { put("name", targetName) })
        }

        private fun deleteScriptJson(scriptName: String): String {
            val removed = plugin.timetableScriptSandbox.deleteSource(scriptName)
            if (!removed) throw HttpFailure(404, "script not found: $scriptName")
            return json.encodeToString(buildJsonObject { put("removed", true) })
        }

        private fun compileSourceJson(worldName: String, body: String): String {
            val root = json.parseToJsonElement(body).jsonObject
            val source = root["source"]?.jsonPrimitive?.content
                ?: throw HttpFailure(400, "source is required")
            val timetableId = root["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: throw HttpFailure(400, "id is required")
            val expectedHash = root["documentHash"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val compiled = compileSource(worldName, source, timetableId)
            return json.encodeToString(buildJsonObject {
                put("timetable", json.parseToJsonElement(TimetableSnapshotCodec.encode(compiled.first)))
                put("validation", validationJson(compiled.second))
                expectedHash?.let { put("documentHash", it) }
            })
        }

        private fun compileSource(
            worldName: String,
            source: String,
            timetableId: String
        ): Pair<SavedTimetableV1, work.art1st.javarailways.simpleato.timetable.TimetableValidationResult> {
            val workerSaved = plugin.timetableScriptSandbox.compileSource(source, timetableId)
            return await {
                withWorld(worldName) { _, world ->
                    val normalized = TimetableScriptNormalizer.normalize(
                        workerSaved,
                        world.simpleAtoWorldData.destinations
                    )
                    val timetable = runCatching(normalized::restore).getOrElse {
                        throw HttpFailure(422, "script produced an invalid timetable: ${it.message}")
                    }
                    normalized to TimetableValidator(world).validate(timetable)
                }
            }
        }

        private fun validationJson(validation: work.art1st.javarailways.simpleato.timetable.TimetableValidationResult) =
            buildJsonObject {
                put("valid", validation.isSuccess)
                put("errors", JsonArray(validation.errors.map(::JsonPrimitive)))
                validation.circulation?.let { summary ->
                    put("circulation", buildJsonObject {
                        put("inventory", summary.inventory.name)
                        put("timing", summary.timing.name)
                        put("capacity", summary.capacity.name)
                        summary.periodicInventoryCycles?.let { put("periodicInventoryCycles", it) }
                        put("findings", JsonArray(summary.findings.map { finding -> buildJsonObject {
                            put("code", finding.code)
                            put("detail", finding.detail)
                            put("blocking", finding.blocking)
                            finding.dutyId?.let { put("dutyId", it) }
                            finding.stepIndex?.let { put("stepIndex", it) }
                        } }))
                    })
                }
            }

        private fun simulateTimetableJson(worldName: String, body: String): String {
            val root = json.parseToJsonElement(body).jsonObject
            val savedElement = root["timetable"]
                ?: throw HttpFailure(400, "timetable is required")
            val saved = runCatching { TimetableSnapshotCodec.decode(savedElement.toString()) }
                .getOrElse { throw HttpFailure(400, "invalid timetable: ${it.message}") }
            val timetable = runCatching(saved::restore)
                .getOrElse { throw HttpFailure(422, "invalid timetable: ${it.message}") }
            val delayThreshold = root["delayThresholdTicks"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: 1_200L
            if (delayThreshold <= 0L) throw HttpFailure(400, "delayThresholdTicks must be positive")
            val selections = root["profileAssignments"]?.let { element ->
                runCatching {
                    (element as JsonArray).map { assignmentElement ->
                        val assignment = assignmentElement.jsonObject
                        val serviceId = assignment["serviceId"]?.jsonPrimitive?.content
                            ?.takeIf(String::isNotBlank)
                            ?: throw IllegalArgumentException("profile assignment serviceId is required")
                        val profileType = assignment["profileType"]?.jsonPrimitive?.content?.let {
                            SimulationProfileType.valueOf(it)
                        } ?: throw IllegalArgumentException("profile assignment profileType is required")
                        val profileId = assignment["profileId"]?.jsonPrimitive?.content
                            ?.takeIf(String::isNotBlank)
                            ?: throw IllegalArgumentException("profile assignment profileId is required")
                        TimetableProfileSelection(serviceId, profileType, profileId)
                    }
                }.getOrElse { throw HttpFailure(400, "invalid profileAssignments: ${it.message}") }
            } ?: throw HttpFailure(400, "profileAssignments is required")
            val factory = IsolatedTimetableSimulationFactory(
                SimulationControlProfileCatalog(plugin),
                plugin.physicsConfig.track.gravityMetersPerSecondSquared,
                plugin.javaRailwaysConfig.atoSafetyDistanceMeters,
                speedSafety = plugin.physicsConfig.overspeed.let {
                    work.art1st.javarailways.basic.safety.SpeedSafetyMonitor.Config(
                        it.derailEnabled, it.toleranceKmH / 3.6, it.graceTicks)
                },
                requiredDomainFeatures = plugin::requiredSimulationDomainFeatures,
                prepareDomainFeatures = plugin::prepareSimulationDomainFeatures
            )
            val snapshot = await {
                withWorld(worldName) { _, world ->
                    val coreWorld = world as? work.art1st.javarailways.core.physics.CoreWorld
                        ?: throw HttpFailure(422, "world does not support isolated simulation")
                    val validation = TimetableValidator(coreWorld).validate(timetable)
                    if (!validation.isSuccess) throw HttpFailure(422, validation.errors.joinToString("; "))
                    runCatching { factory.capture(coreWorld, timetable, selections) }
                        .getOrElse { throw HttpFailure(422, it.message ?: "could not capture simulation world") }
                }
            }
            val task = try {
                simulationExecutor.submit<IsolatedTimetableSimulationResult> {
                    factory.create(
                            snapshot,
                            workerThreads = plugin.physicsConfig.workerThreads.coerceIn(1, 4)
                        ).use { simulation ->
                            simulation.simulate(IsolatedTimetableSimulationRequest(
                                timetable,
                                delayThresholdTicks = delayThreshold
                            ))
                        }
                }
            } catch (_: RejectedExecutionException) {
                throw HttpFailure(429, "timetable simulation queue is full; retry later")
            }
            val result = try {
                task.get(60, TimeUnit.SECONDS)
            } catch (failure: ExecutionException) {
                val cause = failure.cause ?: failure
                if (cause is HttpFailure) throw cause
                if (cause is IllegalArgumentException || cause is IllegalStateException) {
                    throw HttpFailure(422, cause.message ?: "simulation setup failed")
                }
                throw cause
            } catch (_: TimeoutException) {
                task.cancel(true)
                throw HttpFailure(504, "timetable simulation timed out")
            } catch (interrupted: InterruptedException) {
                task.cancel(true)
                Thread.currentThread().interrupt()
                throw interrupted
            }
            return simulationResultJson(result)
        }

        private fun branchPathJson(path: List<work.art1st.javarailways.simpleato.timetable.TimetableBranchEntry>) =
            JsonArray(path.map { entry -> buildJsonObject { put("arm", entry.arm.name); put("index", entry.index) } })

        private fun simulationResultJson(result: IsolatedTimetableSimulationResult): String =
            json.encodeToString(buildJsonObject {
                put("status", when (result) {
                    is IsolatedTimetableSimulationResult.Completed -> "COMPLETED"
                    is IsolatedTimetableSimulationResult.DelayExceeded -> "DELAY_EXCEEDED"
                    is IsolatedTimetableSimulationResult.Failed -> "FAILED"
                    is IsolatedTimetableSimulationResult.PeriodEnded -> "PERIOD_ENDED"
                })
                put("simulatedTicks", result.simulatedTicks)
                if (result is IsolatedTimetableSimulationResult.DelayExceeded) {
                    put("thresholdTicks", result.thresholdTicks)
                    put("observedDelayTicks", result.observedDelayTicks)
                    put("delayedServiceId", result.delayedRun.id.serviceId)
                    put("delayedStepIndex", result.delayedRun.activeStepIndex ?: result.delayedRun.nextStepIndex)
                    put("delayedStepBranchPath", branchPathJson(if (result.delayedRun.activeStepIndex != null) result.delayedRun.activeStepBranchPath else result.delayedRun.nextStepBranchPath))
                }
                if (result is IsolatedTimetableSimulationResult.Failed) put("reason", result.reason)
                put("runs", JsonArray(result.runs.map { run -> buildJsonObject {
                    put("timetableId", run.id.timetableId)
                    put("serviceId", run.id.serviceId)
                    put("trainId", run.trainId)
                    put("state", run.state.name)
                    put("timetableElapsedTicks", run.timetableElapsedTicks)
                    put("scheduledActionTick", run.scheduledActionTick)
                    put("nextScheduledTick", run.nextScheduledTick)
                    put("lastLatenessTicks", run.lastLatenessTicks)
                    put("currentWayPointId", run.currentWayPointId)
                    put("currentRunId", run.currentRunId)
                    put("currentLineId", run.currentLineId?.value)
                    put("unitDisplays", JsonArray(run.unitDisplays.map(::unitDisplayJson)))
                    put("nextStepIndex", run.nextStepIndex)
                    put("nextStepBranchPath", branchPathJson(run.nextStepBranchPath))
                    put("activeStepIndex", run.activeStepIndex)
                    put("activeStepBranchPath", branchPathJson(run.activeStepBranchPath))
                    run.selectedBerthRef?.let { berth -> put("selectedBerthRef", buildJsonObject {
                        put("stationId", berth.stationId.value)
                        put("berthId", berth.berthId)
                    }) }
                    put("selectedBerthCommitted", run.selectedBerthCommitted)
                    put("trainHeadRailId", run.trainHeadRailId)
                    put("trainHeadArcLengthInAbMeters", run.trainHeadArcLengthInAbMeters)
                    put("headProgressMeters", run.headProgressMeters)
                    put("message", run.message)
                }}))
            })

        private fun linesJson(worldName: String) = withWorld(worldName) { _, world ->
            val snapshot = world.simpleAtoWorldData.lines.snapshot()
            json.encodeToString(buildJsonObject {
                put("revision", snapshot.revision)
                put("lines", JsonArray(snapshot.lines.map(::lineJson)))
            })
        }

        private fun putLineJson(worldName: String, pathId: String, body: String) = withWorld(worldName) { _, world ->
            val root = json.parseToJsonElement(body).jsonObject
            val line = parseLine(root["line"]?.jsonObject ?: root)
            require(line.id.value == pathId) { "path line id does not match body id" }
            val expected = root["expectedRevision"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw HttpFailure(400, "expectedRevision is required")
            val revision = try {
                world.simpleAtoWorldData.lines.put(line, expected)
            } catch (conflict: LineRevisionConflict) {
                throw HttpFailure(409, conflict.message ?: "line revision conflict")
            }
            json.encodeToString(buildJsonObject {
                put("revision", revision)
                put("line", lineJson(line))
            })
        }

        private fun simulateLineJson(worldName: String, lineId: String, body: String): String {
            val request = json.parseToJsonElement(body).jsonObject
            val snapshot = await {
                withWorld(worldName) { _, world ->
                    val data = world.basicWorldData
                    val line = world.simpleAtoWorldData.lines[LineId(lineId)]
                        ?: throw HttpFailure(404, "line not found: $lineId")
                    val requestedMaximumSpeed = resolveLineSimulationMaximumSpeed(request) { requestedTimetableId, serviceId, runId ->
                        val timetable = world.simpleAtoWorldData.timetables[requestedTimetableId]
                            ?: throw HttpFailure(404, "timetable not found: $requestedTimetableId")
                        val service = timetable.services.firstOrNull { it.id == serviceId }
                            ?: throw HttpFailure(404, "service not found: $requestedTimetableId/$serviceId")
                        val selectedLineId = if (service.runSegments.isEmpty()) {
                            if (runId != null) throw HttpFailure(400, "service has no run segments")
                            service.lineId
                        } else {
                            if (runId == null) throw HttpFailure(400, "runId is required for a segmented service")
                            service.runSegments.singleOrNull { it.runId == runId }?.lineId
                                ?: throw HttpFailure(404, "run segment not found: $requestedTimetableId/$serviceId/$runId")
                        }
                        if (selectedLineId != line.id) throw HttpFailure(400, "run infrastructure line does not match requested line: $lineId")
                        timetable.lineSpeedLimits.singleOrNull { it.lineId == selectedLineId }?.maximumSpeedMetersPerSecond
                            ?: throw HttpFailure(422, "run has no configured line maximum speed: $requestedTimetableId/$serviceId/$runId")
                    }
                    val lineMaximumSpeed = if (request["timetableId"] == null) {
                        resolveInfrastructureLineMaximumSpeed(line.maximumSpeedMetersPerSecond, requestedMaximumSpeed)
                    } else requestedMaximumSpeed
                    val direction = request["direction"]?.jsonPrimitive?.content?.let {
                        runCatching { LineDirection.valueOf(it.uppercase()) }.getOrElse { throw HttpFailure(400, "direction must be UP or DOWN") }
                    } ?: LineDirection.UP
                    val originStationId = request["originStationId"]?.jsonPrimitive?.content
                    val terminalStationId = request["terminalStationId"]?.jsonPrimitive?.content
                    if ((originStationId == null) != (terminalStationId == null)) {
                        throw HttpFailure(400, "originStationId and terminalStationId must be provided together")
                    }
                    val stationPattern = line.stations(direction)
                    val stationIds = stationPattern.map { it.value }
                    val originIndex = originStationId?.let(stationIds::indexOf) ?: 0
                    val terminalIndex = terminalStationId?.let(stationIds::indexOf) ?: stationIds.lastIndex
                    if (originIndex < 0 || terminalIndex < 0 || terminalIndex <= originIndex) {
                        throw HttpFailure(400, "route endpoints must follow line direction and contain at least two stations")
                    }
                    val selectedStations = stationPattern.subList(originIndex, terminalIndex + 1)
                    val selectedStationIds = selectedStations.map { it.value }
                    val selectedDestinationIds = request["destinationIds"]?.jsonArray?.map { it.jsonPrimitive.content }
                    if (selectedDestinationIds != null && selectedDestinationIds.size != selectedStations.size) {
                        throw HttpFailure(400, "destinationIds must match the selected route station count")
                    }
                    val compilation = if (selectedDestinationIds == null) {
                        when (val compiled = LinePlanner(world).compileStations(line, selectedStations)) {
                            is LineCompileResult.Success -> compiled
                            is LineCompileResult.Failure -> throw HttpFailure(422, compiled.reason)
                        }
                    } else {
                        val originMode = request["originDepartureMode"]?.jsonPrimitive?.content?.uppercase() ?: "AUTO"
                        val originApproachDirection = world.simpleAtoWorldData
                            .destinations[selectedDestinationIds.first()]?.approachDirection
                        val originDirections = when (originMode) {
                            "AUTO" -> ViewDirection.entries
                            "CONTINUE" -> listOf(originApproachDirection
                                ?: throw HttpFailure(400, "origin berth has no configured approach direction; use AUTO"))
                            "REVERSE" -> listOf(when (originApproachDirection
                                ?: throw HttpFailure(400, "origin berth has no configured approach direction; use AUTO")) {
                                ViewDirection.AB -> ViewDirection.BA
                                ViewDirection.BA -> ViewDirection.AB
                            })
                            else -> throw HttpFailure(400, "originDepartureMode must be AUTO, CONTINUE, or REVERSE")
                        }
                        val candidates = runCatching {
                            LinePlanner(world).compileResetCandidates(
                                line,
                                selectedStations,
                                selectedDestinationIds,
                                originDirections
                            )
                        }.getOrElse { throw HttpFailure(422, it.message ?: "could not compile selected berth route") }
                        if (candidates.isEmpty()) {
                            throw HttpFailure(422, "No continuous route from the selected origin berth in the requested departure mode")
                        }
                        if (originMode == "AUTO" && candidates.size > 1) {
                            throw HttpFailure(
                                409,
                                "Both origin departure directions can complete this run; choose normal or turnback departure in Reset"
                            )
                        }
                        candidates.minBy { it.compilation.totalLengthMeters }.compilation
                    }
                    val requestedStopStationIds = request["stopStationIds"]?.jsonArray
                        ?.map { it.jsonPrimitive.content }
                        ?.toSet()
                    if (requestedStopStationIds != null && !selectedStationIds.containsAll(requestedStopStationIds)) {
                        throw HttpFailure(400, "stopStationIds must belong to the selected route section")
                    }
                    val stopStationIds = requestedStopStationIds ?: selectedStationIds.drop(1).toSet()
                    val selectedLegs = compilation.legs
                    val profileType = request["profileType"]?.jsonPrimitive?.content?.let { value ->
                        runCatching { SimulationProfileType.valueOf(value) }.getOrElse {
                            throw HttpFailure(400, "unknown simulation profileType: $value")
                        }
                    } ?: throw HttpFailure(400, "profileType is required")
                    val profileId = request["profileId"]?.jsonPrimitive?.content
                        ?: throw HttpFailure(400, "profileId is required")
                    val profile = runCatching {
                        SimulationControlProfileCatalog(plugin).resolve(world, profileType, profileId)
                    }.getOrElse { throw HttpFailure(422, it.message ?: "simulation profile is unavailable") }
                    LineSimulationSnapshot(
                        revision = world.simpleAtoWorldData.lines.snapshot().revision,
                        profile = profile,
                        gravityMetersPerSecondSquared = plugin.physicsConfig.track.gravityMetersPerSecondSquared,
                        legs = selectedLegs.mapIndexed { index, leg ->
                            val weightedGrade = leg.route.segments.sumOf { segment ->
                                val rail = world.getRail(segment.railViewRef.railId)
                                    ?: throw HttpFailure(409, "route rail disappeared: ${segment.railViewRef.railId}")
                                val view = rail.getView(segment.railViewRef.direction)
                                val midpoint = (segment.startArcLength + segment.endArcLength) * 0.5
                                view.curve.getPointAt(midpoint).tangent.y() * segment.length
                            } / leg.route.totalLength
                            SimulationLeg(
                                leg.fromStationId,
                                leg.toStationId,
                                leg.route.totalLength,
                                weightedGrade,
                                stopAtEnd = leg.toStationId in stopStationIds || index == selectedLegs.lastIndex
                            )
                        },
                        lineMaximumSpeedMetersPerSecond = lineMaximumSpeed
                    )
                }
            }
            val task = try {
                simulationExecutor.submit<IsolatedLineSimulationResult> {
                    IsolatedLineSimulator.simulate(
                        snapshot.legs,
                        snapshot.profile,
                        snapshot.gravityMetersPerSecondSquared,
                        snapshot.lineMaximumSpeedMetersPerSecond
                    )
                }
            } catch (_: RejectedExecutionException) {
                throw HttpFailure(429, "line simulation queue is full; retry later")
            }
            val result = try {
                task.get(60, TimeUnit.SECONDS)
            } catch (failure: ExecutionException) {
                val cause = failure.cause ?: failure
                if (cause is IllegalArgumentException) throw HttpFailure(400, cause.message ?: "invalid simulation profile")
                if (cause is SimulationComputationLimitException) throw HttpFailure(422, cause.message ?: "simulation limit reached")
                throw cause
            } catch (_: TimeoutException) {
                task.cancel(true)
                throw HttpFailure(504, "line simulation timed out")
            } catch (interrupted: InterruptedException) {
                task.cancel(true)
                Thread.currentThread().interrupt()
                throw interrupted
            }
            val currentRevision = await {
                withWorld(worldName) { _, world -> world.simpleAtoWorldData.lines.snapshot().revision }
            }
            if (currentRevision != snapshot.revision) {
                throw HttpFailure(409, "lines changed while simulation was running; retry")
            }
            return json.encodeToString(buildJsonObject {
                put("lineRevision", currentRevision)
                put("totalTravelSeconds", result.totalTravelSeconds)
                put("legs", JsonArray(result.legs.map { leg -> buildJsonObject {
                    put("fromStationId", leg.fromStationId)
                    put("toStationId", leg.toStationId)
                    put("lengthMeters", leg.lengthMeters)
                    put("travelSeconds", leg.travelSeconds)
                    put("peakSpeedKmh", leg.peakSpeedMetersPerSecond * 3.6)
                    put("stoppingErrorMeters", leg.stoppingErrorMeters)
                } }))
            })
        }

        private fun deleteLineJson(worldName: String, pathId: String, body: String) = withWorld(worldName) { _, world ->
            val root = json.parseToJsonElement(body).jsonObject
            val expected = root["expectedRevision"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw HttpFailure(400, "expectedRevision is required")
            val result = try {
                world.simpleAtoWorldData.lines.remove(LineId(pathId), expected)
            } catch (conflict: LineRevisionConflict) {
                throw HttpFailure(409, conflict.message ?: "line revision conflict")
            }
            json.encodeToString(buildJsonObject {
                put("revision", result.second)
                put("removed", result.first != null)
            })
        }

        private fun parseLine(value: JsonObject): Line {
            val id = value["id"]?.jsonPrimitive?.content ?: throw HttpFailure(400, "line id is required")
            val displayName = value["displayName"]?.jsonPrimitive?.content
                ?: throw HttpFailure(400, "line displayName is required")
            val up = value["upStationIds"] as? JsonArray
                ?: throw HttpFailure(400, "line upStationIds is required")
            val down = value["downStationIds"] as? JsonArray
                ?: throw HttpFailure(400, "line downStationIds is required")
            val upIds = up.map { StationId(it.jsonPrimitive.content) }
            val downIds = down.map { StationId(it.jsonPrimitive.content) }
            val color = value["color"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val maximumSpeed = parseLineMaximumSpeed(value["maximumSpeedMetersPerSecond"])
            return Line(LineId(id), displayName, upIds, downIds, color, maximumSpeed)
        }

        private fun lineJson(line: Line) = buildJsonObject {
            put("id", line.id.value)
            put("displayName", line.displayName)
            put("upStationIds", JsonArray(line.upStationIds.map { JsonPrimitive(it.value) }))
            put("downStationIds", JsonArray(line.downStationIds.map { JsonPrimitive(it.value) }))
            line.color?.let { put("color", it) }
            line.maximumSpeedMetersPerSecond?.let { put("maximumSpeedMetersPerSecond", it) }
        }

        private fun timetablesJson(worldName: String) = withWorld(worldName) { _, world ->
            json.encodeToString(JsonArray(world.simpleAtoWorldData.timetables.all().map { JsonPrimitive(it.id) }))
        }
        private fun timetableJson(worldName: String, id: String) = withWorld(worldName) { _, world ->
            val tt = world.simpleAtoWorldData.timetables[id] ?: throw IllegalArgumentException("timetable not found: $id")
            TimetableSnapshotCodec.encode(SavedTimetableV1.capture(tt))
        }
        private fun validateJson(worldName: String, id: String, body: String) = withWorld(worldName) { _, world ->
            val saved = TimetableSnapshotCodec.decode(body); val tt = saved.restore()
            require(tt.id == id && saved.id == id) { "path timetable id does not match body id" }
            val result = TimetableValidator(world).validate(tt)
            json.encodeToString(buildJsonObject {
                put("valid", result.isSuccess)
                put("errors", JsonArray(result.errors.map { message ->
                    buildJsonObject {
                        put("message", message)
                        put("severity", "error")
                    }
                }))
            })
        }
        private fun publishJson(worldName: String, id: String, body: String, ifMatch: String?) = withWorld(worldName) { _, world ->
            val data = world.basicWorldData
            val saved = TimetableSnapshotCodec.decode(body); val tt = saved.restore()
            require(tt.id == id && saved.id == id) { "path timetable id does not match body id" }
            val current = world.simpleAtoWorldData.timetables[id]
            val currentEtag = current?.let { etag(TimetableSnapshotCodec.encode(SavedTimetableV1.capture(it))) }
            require(ifMatch == null || ifMatch == currentEtag) { "If-Match does not match current timetable" }
            val checkpoint = world.simpleAtoWorldData.dispatcher.saveCheckpoint()
            require(checkpoint.clocks.none { it.timetableId == id } && checkpoint.runs.none { it.timetableId == id }) {
                "timetable owns an active or future dispatcher schedule: $id"
            }
            val validation = TimetableValidator(world).validate(tt)
            require(validation.isSuccess) { validation.errors.joinToString("; ") }
            world.simpleAtoWorldData.timetables.add(tt)
            json.encodeToString(buildJsonObject { put("published", true); put("etag", etag(TimetableSnapshotCodec.encode(saved))) })
        }

        private fun <T> withWorld(name: String, action: (BukkitWorld, work.art1st.javarailways.core.api.physics.World) -> T): T {
            val entry = plugin.worldManager.railWorlds.entries.firstOrNull { it.key.name == name }
                ?: throw IllegalArgumentException("world not found: $name")
            return BukkitThreadGuardBridge.runInSimulationContext(entry.value) {
                val coreWorld = entry.value as? work.art1st.javarailways.core.physics.CoreWorld
                val apply = {
                    check(plugin.worldManager.railWorlds[entry.key] === entry.value) { "world changed: $name" }
                    action(entry.key, entry.value)
                }
                if (coreWorld == null) apply() else coreWorld.physicsCoordinator.withStructuralMutation(apply)
            }
        }
        private fun descriptorJson(
            typeId: String,
            version: Int,
            title: String,
            description: String,
            parameterSchema: JsonObject
        ) = buildJsonObject {
            put("typeId", typeId)
            put("version", version)
            put("title", title)
            put("description", description)
            put("parameterSchema", parameterSchema)
        }

        private fun conditionDescriptorJson(provider: TimetableConditionProvider) = buildJsonObject {
            val descriptor = provider.descriptor
            put("typeId", provider.typeId)
            put("version", provider.version)
            put("title", descriptor.title)
            put("description", descriptor.description)
            put("parameterSchema", descriptor.parameterSchema)
            put("supportedScopes", JsonArray(provider.supportedScopes.map { JsonPrimitive(it.name) }))
        }
        private fun authorized(exchange: HttpExchange): Boolean {
            val value = exchange.requestHeaders.getFirst("Authorization") ?: return false
            val prefix = "Bearer "
            if (!value.startsWith(prefix)) return false
            return MessageDigest.isEqual(value.removePrefix(prefix).toByteArray(StandardCharsets.UTF_8), token.toByteArray(StandardCharsets.UTF_8))
        }
        private fun readBody(exchange: HttpExchange): String {
            val bytes = exchange.requestBody.readNBytes(bodyLimitBytes + 1)
            require(bytes.size <= bodyLimitBytes) { "request body exceeds limit" }
            return bytes.toString(StandardCharsets.UTF_8)
        }
    }


    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
    private fun error(message: String) = json.encodeToString(buildJsonObject { put("error", message) })
    private fun decode(value: String) = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)
    private fun etag(body: String): String = '"' + MessageDigest.getInstance("SHA-256").digest(body.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) } + '"'
}

private enum class RequestState { PENDING, RUNNING, COMPLETED, CANCELLED }

/** Domain waits run off the global scheduler so one busy World cannot stop other wakeups. */
internal fun <T> awaitDomainHttpRequest(
    dispatcher: work.art1st.javarailways.bukkit.util.PlatformDispatcher,
    timeoutMillis: Long,
    expired: () -> RuntimeException,
    action: () -> T
): T {
    val future = CompletableFuture<T>()
    val state = AtomicReference(RequestState.PENDING)
    dispatcher.runAsync {
        if (!state.compareAndSet(RequestState.PENDING, RequestState.RUNNING)) return@runAsync
        try { future.complete(BukkitThreadGuardBridge.runInSimulationContext(action)) }
        catch (t: Throwable) { future.completeExceptionally(t) }
        finally { state.set(RequestState.COMPLETED) }
    }
    return try {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (failure: ExecutionException) {
        val cause = failure.cause ?: failure
        when (cause) {
            is RuntimeException -> throw cause
            is Error -> throw cause
            else -> throw IllegalStateException(cause)
        }
    } catch (_: TimeoutException) {
        if (state.compareAndSet(RequestState.PENDING, RequestState.CANCELLED)) {
            future.cancel(false)
            throw expired()
        }
        // Once mutation has entered the serialized simulation context, return its real
        // outcome rather than reporting a timeout and allowing an apparently failed write.
        future.get()
    }
}
