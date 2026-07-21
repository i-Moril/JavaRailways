package work.art1st.mineRails.bukkit.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.World as BukkitWorld
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.api.timetable.TimetableConditionProvider
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.persistence.SavedTimetableV1
import work.art1st.mineRails.basic.persistence.TimetableSnapshotCodec
import work.art1st.mineRails.basic.timetable.TimetableValidator
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.util.BukkitThreadGuardBridge
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class TimetableHttpBridge(private val plugin: BukkitPlugin) {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = true }
    private var server: HttpServer? = null
    private val executor = Executors.newCachedThreadPool { Thread(it, "MineRails-timetable-http").apply { isDaemon = true } }

    fun start(managedToken: String? = null) {
        val config = plugin.mineRailsConfig.timetableHttpBridge
        if (!config.enabled) return
        val token = managedToken ?: System.getenv(config.bearerTokenEnvironmentVariable)
            ?: throw IllegalStateException("Timetable HTTP bridge token environment variable is not set: ${config.bearerTokenEnvironmentVariable}")
        require(token.isNotEmpty()) { "Timetable HTTP bridge bearer token must not be empty" }
        server = HttpServer.create(InetSocketAddress(config.bindAddress, config.port), 0).also { http ->
            http.executor = executor
            http.createContext("/", Handler(config.bodyLimitBytes, config.timeoutMillis, token))
            http.start()
        }
        plugin.logger.info("Timetable HTTP bridge listening on ${config.bindAddress}:${config.port}")
    }

    fun stop() {
        server?.stop(0)
        server = null
        executor.shutdownNow()
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
                exchange.requestMethod == "GET" && parts.size == 2 && parts[1] == "metadata" -> exchange.respond(200, await { metadataJson(worldName) })
                exchange.requestMethod == "GET" && parts.size == 2 && parts[1] == "extensions" -> exchange.respond(200, await { extensionsJson(worldName) })
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

        private fun await(action: () -> String): String {
            val future = CompletableFuture<String>()
            val state = AtomicReference(RequestState.PENDING)
            plugin.platformDispatcher.runGlobal {
                if (!state.compareAndSet(RequestState.PENDING, RequestState.RUNNING)) return@runGlobal
                try { future.complete(BukkitThreadGuardBridge.runInSimulationContext(action)) }
                catch (t: Throwable) { future.completeExceptionally(t) }
                finally { state.set(RequestState.COMPLETED) }
            }
            return try {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (state.compareAndSet(RequestState.PENDING, RequestState.CANCELLED)) {
                    future.cancel(false)
                    throw HttpFailure(504, "request expired before entering the simulation context")
                }
                // Once mutation has entered the serialized simulation context, return its real
                // outcome rather than reporting a timeout and allowing an apparently failed write.
                future.get()
            }
        }

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
            val destinations = data.destinations.all()
            val depotIds = buildSet {
                destinations.mapNotNullTo(this) {
                    (it.role as? DestinationRole.DepotStablingPointRole)?.depotId
                }
                data.depots.allRollingStock().mapTo(this) { it.record.homeDepotId.value }
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
                        put("destinationId", destination.id)
                    }
                }))
                put("depots", JsonArray(depotIds.map(::JsonPrimitive)))
                put("trains", JsonArray(world.allTrains.map { JsonPrimitive(it.id) }.sortedBy { it.content }))
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
                    world.basicWorldData.timetableExtensions.conditionProviders().map(::conditionDescriptorJson)
                ))
                put("actions", JsonArray(
                    world.basicWorldData.timetableExtensions.actionProviders().map { provider ->
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
        private fun timetablesJson(worldName: String) = withWorld(worldName) { _, world ->
            json.encodeToString(JsonArray(world.basicWorldData.timetables.all().map { JsonPrimitive(it.id) }))
        }
        private fun timetableJson(worldName: String, id: String) = withWorld(worldName) { _, world ->
            val tt = world.basicWorldData.timetables[id] ?: throw IllegalArgumentException("timetable not found: $id")
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
            val current = data.timetables[id]
            val currentEtag = current?.let { etag(TimetableSnapshotCodec.encode(SavedTimetableV1.capture(it))) }
            require(ifMatch == null || ifMatch == currentEtag) { "If-Match does not match current timetable" }
            val checkpoint = data.dispatcher.saveCheckpoint()
            require(checkpoint.clocks.none { it.timetableId == id } && checkpoint.runs.none { it.timetableId == id }) {
                "timetable owns an active or future dispatcher schedule: $id"
            }
            val validation = TimetableValidator(world).validate(tt)
            require(validation.isSuccess) { validation.errors.joinToString("; ") }
            data.timetables.add(tt)
            json.encodeToString(buildJsonObject { put("published", true); put("etag", etag(TimetableSnapshotCodec.encode(saved))) })
        }

        private fun withWorld(name: String, action: (BukkitWorld, work.art1st.mineRails.core.api.physics.World) -> String): String {
            val entry = plugin.worldManager.railWorlds.entries.firstOrNull { it.key.name == name }
                ?: throw IllegalArgumentException("world not found: $name")
            return action(entry.key, entry.value)
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

    private enum class RequestState { PENDING, RUNNING, COMPLETED, CANCELLED }
    private class HttpFailure(val status: Int, message: String) : RuntimeException(message)

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
