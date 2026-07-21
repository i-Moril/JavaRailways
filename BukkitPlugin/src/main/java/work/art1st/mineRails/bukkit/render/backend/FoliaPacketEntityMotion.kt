package work.art1st.mineRails.bukkit.render.backend

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.util.Vector3d
import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.bukkit.util.PlatformDispatcher
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.DoubleAccumulator
import kotlin.math.floor

/**
 * Temporary Folia compatibility layer. It owns client movement packets for selected native entities
 * while coalescing their authoritative teleportAsync calls to at most one in-flight operation.
 */
class FoliaPacketEntityMotion(
    private val plugin: Plugin,
    private val dispatcher: PlatformDispatcher,
    private val bundles: TrainPacketBundleCoordinator
) : AutoCloseable {
    private val entities = ConcurrentHashMap<Int, ManagedEntity>()
    private val entitiesByIdentity = Collections.synchronizedMap(IdentityHashMap<Entity, ManagedEntity>())
    private val seatCorrectionGate = SeatTeleportCorrectionGate()
    private val packetTrace = PacketMotionTrace()
    private val seatTrace = PacketMotionTrace(SEAT_TRACE_CAPACITY)
    private val suppressedPlayerCorrections = AtomicLong()
    private val seatAuthorityMoveUpdates = AtomicLong()
    private val seatAuthorityCorrections = AtomicLong()
    private val maxSeatAuthorityError = DoubleAccumulator({ current, value -> maxOf(current, value) }, 0.0)
    private val listener = MovementPacketFilter()
    private var registeredListener: PacketListenerCommon? = null

    fun start() {
        check(registeredListener == null) { "Folia packet entity motion is already started" }
        registeredListener = PacketEvents.getAPI().eventManager.registerListener(listener)
    }

    fun sync(entity: Entity, target: Location, viewers: Collection<Player>) {
        if (!entity.isValid) return
        val entityId = entity.entityId
        val entityUuid = entity.uniqueId
        val managed = entities.compute(entityId) { _, current ->
            if (current == null || current.entityUuid != entityUuid) {
                current?.let {
                    entitiesByIdentity.remove(it.entity)
                    it.retire()
                }
                ManagedEntity(
                    entity = entity,
                    entityId = entityId,
                    entityUuid = entityUuid,
                    initialAnchor = AuthoritativeAnchor.from(entity.location),
                    virtualSeat = null
                )
            } else {
                current
            }
        } ?: return
        entitiesByIdentity[entity] = managed
        if (!managed.active.get()) return

        val viewerIds = viewers.asSequence().filter { it.isOnline }.map { it.uniqueId }.toSet()
        managed.viewers.replace(viewerIds)
        bundles.enqueue(viewerIds) {
            WrapperPlayServerEntityPositionSync(
                managed.entityId,
                EntityPositionData(
                    Vector3d(target.x, target.y, target.z),
                    Vector3d.zero(),
                    target.yaw,
                    target.pitch
                ),
                false
            )
        }

        val targetAnchor = AuthoritativeAnchor.from(target)
        if (AuthoritativeAnchorPolicy.shouldSync(managed.comparisonAnchor.get(), targetAnchor)) {
            managed.gate.offer(target.clone())?.let { launchTeleport(managed, it) }
        }
    }

    /** Registers a real server authority carrier and its stable client-only seat mount. */
    fun registerSeatCarrier(entity: Entity, target: Location, viewers: Collection<Player>) {
        check(entity.isValid) { "Cannot register an invalid Seat authority carrier" }
        val entityId = entity.entityId
        val entityUuid = entity.uniqueId
        val managed = entities.compute(entityId) { _, current ->
            if (current == null || current.entityUuid != entityUuid || current.virtualSeat == null) {
                current?.let {
                    entitiesByIdentity.remove(it.entity)
                    it.retire()
                }
                ManagedEntity(
                    entity = entity,
                    entityId = entityId,
                    entityUuid = entityUuid,
                    initialAnchor = AuthoritativeAnchor.from(entity.location),
                    virtualSeat = FoliaVirtualSeat(entityId, bundles)
                )
            } else {
                current
            }
        } ?: return
        entitiesByIdentity[entity] = managed
        seatTrace.record(
            "entity=$entityId SEAT_REGISTER virtual=${managed.virtualSeat?.entityId} " +
                "target=${target.x.formatTrace()},${target.y.formatTrace()},${target.z.formatTrace()}"
        )
        renderSeatCarrier(entity, target, viewers)
    }

    /** Queues the packet Seat immediately in the current train Bundle; does not access Bukkit entity state. */
    fun renderSeatCarrier(entity: Entity, target: Location, viewers: Collection<Player>) {
        val managed = entitiesByIdentity[entity] ?: return
        val virtualSeat = managed.virtualSeat ?: return
        val viewerEntityIds = viewers.asSequence()
            .filter { it.isOnline }
            .associate { it.uniqueId to it.entityId }
        managed.viewers.replace(viewerEntityIds.keys)
        virtualSeat.sync(target, viewerEntityIds)
    }

    /** Moves the hidden server authority exactly on its owning Folia tick thread. */
    fun syncSeatAuthority(entity: Entity, target: Location) {
        val managed = entitiesByIdentity[entity] ?: return
        if (!managed.active.get() || managed.virtualSeat == null || !entity.isValid) return

        val result = try {
            FoliaSeatAuthorityMover.move(entity, target)
        } catch (failure: Exception) {
            seatTrace.record(
                "entity=${managed.entityId} AUTHORITY_MOVE_FAILED " +
                    "type=${failure.javaClass.simpleName} message=${failure.message}"
            )
            plugin.logger.severe(
                "Folia Seat authority move failed for entity ${managed.entityId}: ${failure.message}"
            )
            managed.gate.offer(target.clone())?.let { launchTeleport(managed, it) }
            return
        }

        refreshAuthoritativePassengerAudiences(entity)
        seatAuthorityMoveUpdates.incrementAndGet()
        maxSeatAuthorityError.accumulate(result.residualError)
        val worldId = target.world?.uid?.toString() ?: "null"
        val actualAnchor = AuthoritativeAnchor(
            worldId,
            result.actual.x,
            result.actual.y,
            result.actual.z
        )
        managed.comparisonAnchor.set(actualAnchor)
        val targetAnchor = AuthoritativeAnchor.from(target)
        val moveSequence = managed.seatMoveSequence.incrementAndGet()
        if (moveSequence == 1L || moveSequence % SEAT_TRACE_SAMPLE_INTERVAL == 0L) {
            seatTrace.record(
                "entity=${managed.entityId} AUTHORITY_MOVE seq=$moveSequence " +
                    "start=${result.start.x.formatTrace()},${result.start.y.formatTrace()},${result.start.z.formatTrace()} " +
                    "target=${result.target.x.formatTrace()},${result.target.y.formatTrace()},${result.target.z.formatTrace()} " +
                    "actual=${result.actual.x.formatTrace()},${result.actual.y.formatTrace()},${result.actual.z.formatTrace()} " +
                    "residual=${result.residualError.formatTrace()} passengers=${result.passengerEntityIds}"
            )
        }
        if (SeatAuthorityAnchorPolicy.shouldSync(actualAnchor, targetAnchor)) {
            seatAuthorityCorrections.incrementAndGet()
            seatTrace.record(
                "entity=${managed.entityId} SEAT_CORRECTION residual=${result.residualError.formatTrace()}"
            )
            managed.gate.offer(target.clone())?.let { launchTeleport(managed, it) }
        }
    }

    private fun refreshAuthoritativePassengerAudiences(carrier: Entity) {
        carrier.passengers.filterIsInstance<Player>().forEach { passenger ->
            dispatcher.runForEntity(passenger, action = passengerTask@{
                if (!passenger.isOnline) return@passengerTask
                val railWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[passenger.world]
                    ?: return@passengerTask
                railWorld.renderChunkManager.refreshAuthoritativePassenger(passenger)
            })
        }
    }

    fun releaseViewer(entity: Entity, playerId: UUID) {
        val managed = entitiesByIdentity[entity] ?: return
        managed.viewers.remove(playerId)
        managed.virtualSeat?.removeViewer(playerId)
    }

    fun release(entity: Entity) {
        val managed = entitiesByIdentity.remove(entity) ?: return
        packetTrace.record("A${managed.traceSequence.get()} entity=${managed.entityId} RELEASE")
        if (managed.virtualSeat != null) {
            seatTrace.record("entity=${managed.entityId} SEAT_RELEASE")
        }
        if (entities.remove(managed.entityId, managed)) managed.retire()
    }

    override fun close() {
        registeredListener?.let { PacketEvents.getAPI().eventManager.unregisterListener(it) }
        registeredListener = null
        entities.values.forEach(ManagedEntity::retire)
        entities.clear()
        entitiesByIdentity.clear()
        seatCorrectionGate.clearAll()
        packetTrace.clear()
        seatTrace.clear()
    }

    private fun launchTeleport(managed: ManagedEntity, target: Location) {
        if (!managed.active.get()) return
        val entity = managed.entity
        managed.comparisonAnchor.set(AuthoritativeAnchor.from(target))
        dispatcher.runForEntity(
            entity,
            action = entityTask@{
                if (!managed.active.get() || !entity.isValid) {
                    retire(managed)
                    return@entityTask
                }
                val passengers = entity.passengers.filterIsInstance<Player>()
                val traceSequence = managed.traceSequence.incrementAndGet()
                managed.traceUntilNanos.set(System.nanoTime() + TRACE_WINDOW_NANOS)
                packetTrace.record(
                    "A$traceSequence entity=${managed.entityId} ANCHOR_START " +
                        "passengers=${passengers.map { it.entityId }} " +
                        "target=${target.x.formatTrace()},${target.y.formatTrace()},${target.z.formatTrace()}"
                )
                val passengerMarkers = if (managed.virtualSeat == null) {
                    passengers.map { passenger ->
                        seatCorrectionGate.mark(
                            passenger.uniqueId,
                            PacketPosition(target.x, target.y, target.z)
                        )
                    }
                } else {
                    emptyList()
                }
                entity.teleportAsync(
                    target,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS
                ).whenComplete { teleported, error ->
                    packetTrace.record(
                        "A$traceSequence entity=${managed.entityId} ANCHOR_DONE " +
                            "success=${teleported == true} error=${error?.javaClass?.simpleName ?: "none"}"
                    )
                    if (teleported != true) passengerMarkers.forEach(seatCorrectionGate::clear)
                    if (error != null && managed.active.get()) {
                        plugin.logger.warning(
                            "Folia authoritative teleport failed for entity ${managed.entityId}: ${error.message}"
                        )
                    }
                    dispatcher.runForEntityLater(
                        entity = entity,
                        delayTicks = SERVER_SYNC_INTERVAL_TICKS,
                        action = completionTask@{
                            if (!managed.active.get() || !entity.isValid) {
                                retire(managed)
                                return@completionTask
                            }
                            if (teleported != true) {
                                managed.comparisonAnchor.set(AuthoritativeAnchor.from(entity.location))
                            }
                            managed.gate.complete { next ->
                                val nextAnchor = AuthoritativeAnchor.from(next)
                                if (managed.virtualSeat == null) {
                                    AuthoritativeAnchorPolicy.shouldSync(managed.comparisonAnchor.get(), nextAnchor)
                                } else {
                                    SeatAuthorityAnchorPolicy.shouldSync(managed.comparisonAnchor.get(), nextAnchor)
                                }
                            }?.let { next -> launchTeleport(managed, next) }
                        },
                        retired = { retire(managed) }
                    )
                }
            },
            retired = { retire(managed) }
        )
    }

    private fun retire(managed: ManagedEntity) {
        entities.remove(managed.entityId, managed)
        entitiesByIdentity.remove(managed.entity)
        packetTrace.record("A${managed.traceSequence.get()} entity=${managed.entityId} RETIRE")
        managed.retire()
    }

    fun metrics(): FoliaPacketEntityMotionMetrics = FoliaPacketEntityMotionMetrics(
        managedEntities = entities.size,
        virtualSeats = entities.values.count { it.virtualSeat != null },
        suppressedPlayerCorrections = suppressedPlayerCorrections.get(),
        seatAuthorityMoveUpdates = seatAuthorityMoveUpdates.get(),
        seatAuthorityCorrections = seatAuthorityCorrections.get(),
        maxSeatAuthorityError = maxSeatAuthorityError.get()
    )

    fun recentPacketTrace(): List<String> = packetTrace.snapshot()

    fun recentSeatTrace(): List<String> = seatTrace.snapshot()

    private inner class MovementPacketFilter : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.packetType == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
                val packet = WrapperPlayServerPlayerPositionAndLook(event)
                if (
                    seatCorrectionGate.consumeIfMatches(
                        event.user.uuid,
                        PacketPosition(packet.x, packet.y, packet.z)
                    )
                ) {
                    packetTrace.record(
                        "viewer=${event.user.uuid.shortTrace()} PLAYER_POSITION canceled " +
                            "teleportId=${packet.teleportId} target=${packet.x.formatTrace()}," +
                            "${packet.y.formatTrace()},${packet.z.formatTrace()}"
                    )
                    event.isCancelled = true
                    event.user.receivePacketSilently(
                        WrapperPlayClientTeleportConfirm(packet.teleportId)
                    )
                    suppressedPlayerCorrections.incrementAndGet()
                    return
                }
            }

            if (event.packetType == PacketType.Play.Server.DESTROY_ENTITIES) {
                WrapperPlayServerDestroyEntities(event).entityIds.forEach { entityId ->
                    val managed = entities[entityId] ?: return@forEach
                    if (managed.viewers.contains(event.user.uuid)) {
                        managed.trace("REMOVE_ENTITIES", event.user.uuid)
                    }
                }
                return
            }

            if (event.packetType == PacketType.Play.Server.SPAWN_ENTITY) {
                val packet = WrapperPlayServerSpawnEntity(event)
                val managed = entities[packet.entityId] ?: return
                if (managed.viewers.contains(event.user.uuid)) {
                    val cancel = managed.virtualSeat != null
                    managed.trace("SPAWN_ENTITY${if (cancel) " canceled" else ""}", event.user.uuid)
                    if (cancel) event.isCancelled = true
                }
                return
            }

            if (event.packetType == PacketType.Play.Server.SET_PASSENGERS) {
                val packet = WrapperPlayServerSetPassengers(event)
                val managed = entities[packet.entityId] ?: return
                if (managed.viewers.contains(event.user.uuid)) {
                    val virtualSeat = managed.virtualSeat
                    if (virtualSeat == null) {
                        managed.trace("SET_PASSENGERS passengers=${packet.passengers.toList()} pass", event.user.uuid)
                    } else {
                        event.isCancelled = true
                        virtualSeat.redirectPassengers(event.user.uuid, packet.passengers)
                        val message = "SET_PASSENGERS passengers=${packet.passengers.toList()} redirected"
                        managed.trace(message, event.user.uuid)
                        seatTrace.record(
                            "entity=${managed.entityId} viewer=${event.user.uuid.shortTrace()} $message"
                        )
                    }
                }
                return
            }

            val entityId = movementEntityId(event) ?: return
            val managed = entities[entityId] ?: return
            if (managed.viewers.contains(event.user.uuid)) {
                if (managed.traceUntilNanos.get() >= System.nanoTime()) {
                    managed.trace("${movementPacketName(event)} canceled", event.user.uuid)
                }
                event.isCancelled = true
            }
        }
    }

    private class ManagedEntity(
        val entity: Entity,
        val entityId: Int,
        val entityUuid: UUID,
        initialAnchor: AuthoritativeAnchor,
        val virtualSeat: FoliaVirtualSeat?
    ) {
        val active = AtomicBoolean(true)
        val gate = LatestTargetGate<Location>()
        val viewers = ManagedMovementViewers()
        val comparisonAnchor = AtomicReference(initialAnchor)
        val traceSequence = AtomicLong()
        val traceUntilNanos = AtomicLong()
        val seatMoveSequence = AtomicLong()

        fun retire() {
            if (!active.compareAndSet(true, false)) return
            gate.cancel()
            viewers.clear()
            virtualSeat?.destroy()
        }
    }

    private fun ManagedEntity.trace(message: String, viewerId: UUID) {
        packetTrace.record(
            "A${traceSequence.get()} entity=$entityId viewer=${viewerId.shortTrace()} $message"
        )
    }

    private companion object {
        const val SERVER_SYNC_INTERVAL_TICKS = 2L
        const val TRACE_WINDOW_NANOS = 3_000_000_000L
        const val SEAT_TRACE_CAPACITY = 200
        const val SEAT_TRACE_SAMPLE_INTERVAL = 20L
    }
}

data class FoliaPacketEntityMotionMetrics(
    val managedEntities: Int,
    val virtualSeats: Int,
    val suppressedPlayerCorrections: Long,
    val seatAuthorityMoveUpdates: Long,
    val seatAuthorityCorrections: Long,
    val maxSeatAuthorityError: Double
)

internal data class AuthoritativeAnchor(
    val worldId: String,
    val x: Double,
    val y: Double,
    val z: Double
) {
    val chunkX: Int get() = floor(x).toInt() shr 4
    val chunkZ: Int get() = floor(z).toInt() shr 4

    companion object {
        fun from(location: Location): AuthoritativeAnchor = AuthoritativeAnchor(
            worldId = location.world?.uid?.toString() ?: "null",
            x = location.x,
            y = location.y,
            z = location.z
        )
    }
}

internal object AuthoritativeAnchorPolicy {
    private const val MAX_ERROR_SQUARED = 12.0 * 12.0

    fun shouldSync(anchor: AuthoritativeAnchor, target: AuthoritativeAnchor): Boolean {
        if (anchor.worldId != target.worldId) return true
        if (anchor.chunkX != target.chunkX || anchor.chunkZ != target.chunkZ) return true
        val dx = target.x - anchor.x
        val dy = target.y - anchor.y
        val dz = target.z - anchor.z
        return dx * dx + dy * dy + dz * dz >= MAX_ERROR_SQUARED
    }
}

/** Native velocity is allowed to transfer a Seat authority carrier across chunks without teleporting it. */
internal object SeatAuthorityAnchorPolicy {
    private const val MAX_ERROR_SQUARED = 12.0 * 12.0

    fun shouldSync(anchor: AuthoritativeAnchor, target: AuthoritativeAnchor): Boolean {
        if (anchor.worldId != target.worldId) return true
        val dx = target.x - anchor.x
        val dy = target.y - anchor.y
        val dz = target.z - anchor.z
        return dx * dx + dy * dy + dz * dz >= MAX_ERROR_SQUARED
    }
}

internal class LatestTargetGate<T> {
    private var active = true
    private var inFlight = false
    private var latest: T? = null

    @Synchronized
    fun offer(target: T): T? {
        if (!active) return null
        latest = target
        if (inFlight) return null
        inFlight = true
        return latest.also { latest = null }
    }

    fun complete(): T? = complete { true }

    @Synchronized
    fun complete(shouldLaunch: (T) -> Boolean): T? {
        if (!active) return null
        val next = latest
        latest = null
        if (next == null || !shouldLaunch(next)) {
            inFlight = false
            return null
        }
        return next
    }

    @Synchronized
    fun cancel() {
        active = false
        inFlight = false
        latest = null
    }
}

internal class ManagedMovementViewers {
    private val current = AtomicReference<Set<UUID>>(emptySet())

    fun replace(viewers: Set<UUID>) {
        current.set(viewers.toSet())
    }

    fun contains(viewer: UUID): Boolean = viewer in current.get()

    fun remove(viewer: UUID) {
        current.updateAndGet { viewers -> viewers - viewer }
    }

    fun clear() {
        current.set(emptySet())
    }
}

private fun UUID.shortTrace(): String = toString().take(8)

private fun Double.formatTrace(): String = String.format(Locale.ROOT, "%.2f", this)

private fun movementPacketName(event: PacketSendEvent): String = when (event.packetType) {
    PacketType.Play.Server.ENTITY_TELEPORT -> "ENTITY_TELEPORT"
    PacketType.Play.Server.ENTITY_POSITION_SYNC -> "ENTITY_POSITION_SYNC"
    PacketType.Play.Server.ENTITY_RELATIVE_MOVE -> "ENTITY_RELATIVE_MOVE"
    PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION -> "ENTITY_RELATIVE_MOVE_ROTATION"
    PacketType.Play.Server.ENTITY_ROTATION -> "ENTITY_ROTATION"
    PacketType.Play.Server.ENTITY_VELOCITY -> "ENTITY_VELOCITY"
    PacketType.Play.Server.ENTITY_HEAD_LOOK -> "ENTITY_HEAD_LOOK"
    else -> event.packetType.toString()
}

private fun movementEntityId(event: PacketSendEvent): Int? = when (event.packetType) {
    PacketType.Play.Server.ENTITY_TELEPORT -> WrapperPlayServerEntityTeleport(event).entityId
    PacketType.Play.Server.ENTITY_POSITION_SYNC -> WrapperPlayServerEntityPositionSync(event).id
    PacketType.Play.Server.ENTITY_RELATIVE_MOVE -> WrapperPlayServerEntityRelativeMove(event).entityId
    PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION ->
        WrapperPlayServerEntityRelativeMoveAndRotation(event).entityId
    PacketType.Play.Server.ENTITY_ROTATION -> WrapperPlayServerEntityRotation(event).entityId
    PacketType.Play.Server.ENTITY_VELOCITY -> WrapperPlayServerEntityVelocity(event).entityId
    PacketType.Play.Server.ENTITY_HEAD_LOOK -> WrapperPlayServerEntityHeadLook(event).entityId
    else -> null
}
