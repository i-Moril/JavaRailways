package work.art1st.javarailways.bukkit.render.chunk

import org.bukkit.Bukkit
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import work.art1st.javarailways.bukkit.util.PlatformCleanup
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.joml.Vector3dc
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.rail.RailArcVisualSegment
import work.art1st.javarailways.bukkit.extension.rail.renderer
import work.art1st.javarailways.bukkit.extension.train.renderer
import work.art1st.javarailways.bukkit.render.asVector3d
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import work.art1st.javarailways.core.api.util.DestructionSteps
import java.util.concurrent.CompletionStage
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.render.Renderable
import work.art1st.javarailways.core.api.render.Vector3Snapshot
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 保存 renderable 的区块索引，并在玩家移动 / renderable 位置变化时增量触发模型构建。
 */
class RenderChunkManager(
    world: World,
    private val renderableObjects: ConcurrentMap<RenderChunk, MutableSet<Renderable>> = ConcurrentHashMap(),
    private val dispatcher: PlatformDispatcher = BukkitPlugin.INSTANCE.platformDispatcher,
    private val playerLookup: (UUID) -> Player? = Bukkit::getPlayer,
    private val settingsFor: (Player) -> ViewDistanceSettings = { it.railViewDistanceSettings },
    registerListener: (Listener) -> Unit = { Bukkit.getPluginManager().registerEvents(it, BukkitPlugin.INSTANCE) },
    private val unregisterListener: (Listener) -> Unit = HandlerList::unregisterAll,
    initialPlayers: Collection<Player> = Bukkit.getOnlinePlayers().toList()
) : Listener {
    private val worldRef: WeakReference<World> = WeakReference(world)

    /** Owning platform world; fails fast rather than passing an expired reference as non-null. */
    val world: World
        get() = checkNotNull(worldRef.get()) { "RenderChunkManager world is no longer live" }
    private val visibleByPlayer: ConcurrentMap<UUID, MutableSet<Renderable>> = ConcurrentHashMap()
    private val refreshStateByPlayer: ConcurrentMap<UUID, PlayerRefreshState> = ConcurrentHashMap()
    private val audienceIndex = RenderAudienceIndex<Renderable>()
    private val renderablePositions: ConcurrentMap<Renderable, Vector3Snapshot> = ConcurrentHashMap()
    private val pendingAudienceUpdates: ConcurrentMap<UUID, PendingAudienceUpdates> = ConcurrentHashMap()
    private val candidateUpdates = AtomicLong()
    private val coalescedUpdates = AtomicLong()
    private val scheduledDrains = AtomicLong()
    private val processedUpdates = AtomicLong()
    private val authoritativePassengerRefreshRequests = AtomicLong()
    private val authoritativePassengerRefreshes = AtomicLong()
    @Volatile private var closed = false

    init {
        try {
            registerListener(this)
            // Seed once per world load; later joins/moves and model changes use the existing reverse index.
            initialPlayers.forEach { player ->
                dispatcher.runForEntity(player, action = { refreshPlayer(player, force = true) })
            }
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        // Models without viewers also own native entities and behavior runtimes.
        val renderables = renderableObjects.values.flatMap { it.toList() }.toSet() + renderablePositions.keys
        renderables.forEach { renderable ->
            try {
                when (renderable) {
                    is Car -> PlatformCleanup.awaitCompletion(renderable.renderer.destroy())
                    is RailArcVisualSegment -> PlatformCleanup.awaitCompletion(renderable.renderer.destroy())
                }
            } catch (cleanupFailure: Throwable) {
                failure = accumulateFailure(failure, cleanupFailure)
            }
        }
        visibleByPlayer.keys.toList().forEach { playerId ->
            try {
                val player = playerLookup(playerId)
                if (player == null) {
                    visibleByPlayer.remove(playerId)
                } else {
                    dispatcher.runForEntity(player, action = { clearPlayer(player) })
                }
            } catch (cleanupFailure: Throwable) {
                failure = accumulateFailure(failure, cleanupFailure)
            }
        }
        pendingAudienceUpdates.clear()
        renderableObjects.clear()
        visibleByPlayer.clear()
        refreshStateByPlayer.clear()
        renderablePositions.clear()
        audienceIndex.clear()
        try {
            unregisterListener(this)
        } catch (cleanupFailure: Throwable) {
            failure = accumulateFailure(failure, cleanupFailure)
        }
        failure?.let { throw it }
    }

    fun getRenderablesAround(position: Vector3dc, radius: Double): Collection<Renderable> {
        val radiusSq = radius * radius
        val chunks = RenderChunk.getChunksAround(position, radius)
        val selected = LinkedHashSet<Renderable>()
        for (chunk in chunks) {
            renderableObjects[chunk]?.forEach { renderable ->
                val renderablePosition = renderablePositions[renderable]
                    ?: Vector3Snapshot.capture(renderable.position)
                if (distanceSquared(renderablePosition, position) <= radiusSq) {
                    selected.add(renderable)
                }
            }
        }
        return selected
    }

    @EventHandler
    fun onPlayerChunkLoad(event: PlayerChunkLoadEvent) {
        if (event.world == world) refreshChunkAudience(event.player, RenderChunk(event.chunk.x, event.chunk.z))
    }

    @EventHandler
    fun onPlayerChunkUnload(event: PlayerChunkUnloadEvent) {
        if (event.world == world) refreshChunkAudience(event.player, RenderChunk(event.chunk.x, event.chunk.z))
    }

    private fun refreshChunkAudience(player: Player, chunk: RenderChunk) {
        if (closed) return
        // Bogies can occupy a sent chunk even while the car centre is in a neighbouring chunk.
        val affected = LinkedHashSet<Renderable>()
        renderableObjects[chunk]?.let(affected::addAll)
        visibleByPlayer[player.uniqueId]?.let(affected::addAll)
        affected.forEach { renderable ->
            renderablePositions[renderable]?.let { queueAudienceUpdate(player.uniqueId, renderable, it) }
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        BukkitThreadOwnership.ensureEntity(event.player, "RenderChunkManager.onPlayerJoin")
        if (event.player.world == world) {
            refreshPlayer(event.player, force = true)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        BukkitThreadOwnership.ensureEntity(event.player, "RenderChunkManager.onPlayerQuit")
        clearPlayer(event.player)
        BukkitPlugin.INSTANCE.viewDistanceManager.clear(event.player.uniqueId)
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        BukkitThreadOwnership.ensureEntity(event.player, "RenderChunkManager.onPlayerMove")
        val to = event.to
        if (event.player.world != world) return
        if (event.from.x == to.x && event.from.y == to.y && event.from.z == to.z) return
        refreshPlayer(event.player)
    }

    @EventHandler
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        BukkitThreadOwnership.ensureEntity(event.player, "RenderChunkManager.onPlayerTeleport")
        if (event.from.world != world && event.to.world != world) return
        dispatcher.runForEntityLater(event.player, 1L, action = {
            if (event.player.world == world) refreshPlayer(event.player, force = true) else clearPlayer(event.player)
        })
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        BukkitThreadOwnership.ensureEntity(event.player, "RenderChunkManager.onPlayerChangedWorld")
        if (event.player.world == world) {
            refreshPlayer(event.player, force = true)
        } else if (event.from == world) {
            clearPlayer(event.player)
        }
    }

    /** Refreshes packet-render audiences after server-side passenger positioning, which emits no PlayerMoveEvent. */
    internal fun refreshAuthoritativePassenger(player: Player) {
        BukkitThreadOwnership.ensureEntity(player, "RenderChunkManager.refreshAuthoritativePassenger")
        authoritativePassengerRefreshRequests.incrementAndGet()
        if (refreshPlayer(player)) authoritativePassengerRefreshes.incrementAndGet()
    }

    private fun refreshPlayer(player: Player, force: Boolean = false): Boolean {
        if (player.world != world) {
            clearPlayer(player)
            return false
        }
        val location = player.location
        val position = location.asVector3d()
        val settings = settingsFor(player)
        val chunk = RenderChunk(position)
        val previous = refreshStateByPlayer[player.uniqueId]
        if (!force && previous != null && previous.chunk == chunk &&
            previous.viewDistance == settings.viewDistance && previous.lodThreshold == settings.lodThreshold
        ) {
            if (!PlayerAudienceRefreshPolicy.movedEnough(
                    previous.x,
                    previous.y,
                    previous.z,
                    location.x,
                    location.y,
                    location.z
                )
            ) return false
        }

        val watchedChunks = RenderChunk.getChunksAround(position, settings.viewDistance).toSet()
        audienceIndex.replaceWatchedChunks(player.uniqueId, watchedChunks)
        val newVisible = getRenderablesAround(position, settings.viewDistance).toSet()
        reconcilePlayerVisibility(player, newVisible)
        refreshStateByPlayer[player.uniqueId] = PlayerRefreshState(
            chunk = chunk,
            x = location.x,
            y = location.y,
            z = location.z,
            viewDistance = settings.viewDistance,
            lodThreshold = settings.lodThreshold
        )
        return true
    }

    private fun reconcilePlayerVisibility(player: Player, newVisible: Set<Renderable>) {
        val oldVisible = visibleByPlayer[player.uniqueId]?.toSet().orEmpty()
        val touched = LinkedHashSet<Renderable>()
        touched.addAll(oldVisible)
        touched.addAll(newVisible)
        touched.forEach { renderable ->
            setVisible(player, renderable, renderable in newVisible, refreshExisting = true)
        }
    }

    private fun setVisible(player: Player, renderable: Renderable, visible: Boolean, refreshExisting: Boolean = false) {
        if (closed && visible) return
        if (visible) {
            val renderables = visibleByPlayer.computeIfAbsent(player.uniqueId) { ConcurrentHashMap.newKeySet() }
            audienceIndex.markVisible(player.uniqueId, renderable)
            val added = renderables.add(renderable)
            // A drain may have captured its update immediately before remove. Register first, then
            // recheck publication: either we undo here, or a later withdraw sees this installed entry.
            try {
                if (renderable is OrdinaryModelRenderable && !renderable.isPublished) return
                if (added) {
                    dispatchSendTo(renderable, player)
                } else if (refreshExisting) {
                    dispatchRefreshFor(renderable, player)
                }
            } finally {
                if (renderable is OrdinaryModelRenderable && !renderable.isPublished) {
                    setVisible(player, renderable, visible = false)
                }
            }
            return
        }

        audienceIndex.markHidden(player.uniqueId, renderable)
        val renderables = visibleByPlayer[player.uniqueId] ?: return
        if (renderables.remove(renderable)) {
            dispatchHideFrom(renderable, player)
            if (renderables.isEmpty()) {
                visibleByPlayer.remove(player.uniqueId)
            }
        }
    }

    private fun clearPlayer(player: Player) {
        val playerId = player.uniqueId
        refreshStateByPlayer.remove(playerId)
        pendingAudienceUpdates.remove(playerId)?.updates?.clear()
        audienceIndex.removePlayer(playerId)
        visibleByPlayer.remove(playerId)?.forEach { renderable ->
            audienceIndex.markHidden(playerId, renderable)
            dispatchHideFrom(renderable, player)
        }
    }

    private fun dispatchSendTo(renderable: Renderable, player: Player) {
        when (renderable) {
            is OrdinaryModelRenderable -> renderable.showTo(player)
            is Car -> renderable.renderer.sendTo(player)
            is RailArcVisualSegment -> renderable.renderer.sendTo(player)
        }
    }

    private fun dispatchHideFrom(renderable: Renderable, player: Player) {
        when (renderable) {
            is OrdinaryModelRenderable -> renderable.hideFrom(player)
            is Car -> renderable.renderer.hideFrom(player)
            is RailArcVisualSegment -> renderable.renderer.hideFrom(player)
        }
    }

    private fun dispatchRefreshFor(renderable: Renderable, player: Player) {
        when (renderable) {
            is Car -> renderable.renderer.refreshFor(player)
            is RailArcVisualSegment -> Unit
        }
    }

    private fun remove(renderable: Renderable, chunk: RenderChunk) {
        renderableObjects[chunk]?.let {
            it.remove(renderable)
            if (it.isEmpty()) {
                renderableObjects.remove(chunk, it)
            }
        }
    }

    internal fun remove(renderable: Renderable, position: Vector3dc = renderable.position): CompletionStage<Void> {
        val steps = DestructionSteps()
        remove(renderable, RenderChunk(position))
        renderablePositions.remove(renderable)
        pendingAudienceUpdates.values.forEach { it.updates.remove(renderable) }
        val playerIds = audienceIndex.removeRenderable(renderable)
        playerIds.forEach { playerId ->
            val player = playerLookup(playerId)
            if (player != null) {
                steps.await {
                    dispatcher.runForEntityCompletion(player) {
                        setVisible(player, renderable, visible = false)
                    }
                }
            } else {
                visibleByPlayer[playerId]?.let { renderables ->
                    renderables.remove(renderable)
                    if (renderables.isEmpty()) visibleByPlayer.remove(playerId, renderables)
                }
            }
        }
        return steps.finish()
    }

    internal fun update(renderable: Renderable, oldPosition: Vector3dc?, newPosition: Vector3dc) {
        if (closed) return
        val newChunk = RenderChunk(newPosition)
        val oldChunk = oldPosition?.let(::RenderChunk)
        val positionSnapshot = Vector3Snapshot.capture(newPosition)
        renderablePositions[renderable] = positionSnapshot

        // The chunk map is an in-memory spatial index, not a reason to force-load Bukkit chunks.
        // Only mutate it when a renderable is first registered or actually crosses a chunk border.
        val alreadyIndexed = renderableObjects[newChunk]?.contains(renderable) == true
        if (!alreadyIndexed || oldChunk == null || oldChunk != newChunk) {
            renderableObjects.computeIfAbsent(newChunk) { ConcurrentHashMap.newKeySet() }.add(renderable)
            if (oldChunk != null && oldChunk != newChunk) remove(renderable, oldChunk)
        }

        updateAudience(renderable, newChunk, positionSnapshot)
    }

    private fun updateAudience(
        renderable: Renderable,
        newChunk: RenderChunk,
        position: Vector3Snapshot
    ) {
        audienceIndex.candidates(renderable, newChunk).forEach { playerId ->
            candidateUpdates.incrementAndGet()
            queueAudienceUpdate(playerId, renderable, position)
        }
    }

    private fun queueAudienceUpdate(
        playerId: UUID,
        renderable: Renderable,
        position: Vector3Snapshot
    ) {
        val pending = pendingAudienceUpdates.computeIfAbsent(playerId) { PendingAudienceUpdates() }
        if (pending.updates.put(renderable, position) != null) coalescedUpdates.incrementAndGet()
        if (pending.scheduled.compareAndSet(false, true)) {
            scheduleAudienceDrain(playerId, pending)
        }
    }

    private fun scheduleAudienceDrain(playerId: UUID, pending: PendingAudienceUpdates) {
        val player = playerLookup(playerId)
        if (player == null) {
            discardPendingAudienceUpdates(playerId, pending)
            return
        }
        scheduledDrains.incrementAndGet()
        val task = dispatcher.runForEntityLater(
            entity = player,
            delayTicks = 1L,
            action = { drainAudienceUpdates(player, pending) },
            retired = { discardPendingAudienceUpdates(playerId, pending) }
        )
        if (task == null) discardPendingAudienceUpdates(playerId, pending)
    }

    private fun drainAudienceUpdates(player: Player, pending: PendingAudienceUpdates) {
        if (player.world != world) {
            discardPendingAudienceUpdates(player.uniqueId, pending)
            clearPlayer(player)
            return
        }
        val playerPosition = player.location.asVector3d()
        val radius = settingsFor(player).viewDistance
        val radiusSq = radius * radius
        pending.updates.forEach { (renderable, position) ->
            if (pending.updates.remove(renderable, position)) {
                processedUpdates.incrementAndGet()
                setVisible(
                    player,
                    renderable,
                    distanceSquared(position, playerPosition) <= radiusSq,
                    refreshExisting = true
                )
            }
        }

        pending.scheduled.set(false)
        if (pending.updates.isNotEmpty() && pending.scheduled.compareAndSet(false, true)) {
            scheduleAudienceDrain(player.uniqueId, pending)
        }
    }

    private fun discardPendingAudienceUpdates(playerId: UUID, pending: PendingAudienceUpdates) {
        pending.updates.clear()
        pending.scheduled.set(false)
        pendingAudienceUpdates.remove(playerId, pending)
    }

    private fun distanceSquared(a: Vector3Snapshot, b: Vector3dc): Double {
        val dx = a.x - b.x()
        val dy = a.y - b.y()
        val dz = a.z - b.z()
        return dx * dx + dy * dy + dz * dz
    }

    private fun accumulateFailure(primary: Throwable?, next: Throwable): Throwable {
        if (primary == null) return next
        if (next !== primary) primary.addSuppressed(next)
        return primary
    }

    private fun distanceSquared(a: Vector3dc, b: Vector3dc): Double {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        val dz = a.z() - b.z()
        return dx * dx + dy * dy + dz * dz
    }

    fun audienceMetrics(): AudienceMetrics = AudienceMetrics(
        candidateUpdates = candidateUpdates.get(),
        coalescedUpdates = coalescedUpdates.get(),
        scheduledDrains = scheduledDrains.get(),
        processedUpdates = processedUpdates.get(),
        authoritativePassengerRefreshRequests = authoritativePassengerRefreshRequests.get(),
        authoritativePassengerRefreshes = authoritativePassengerRefreshes.get(),
        visibleEntries = visibleByPlayer.values.sumOf { it.size },
        pendingPlayers = pendingAudienceUpdates.count { (_, pending) ->
            pending.scheduled.get() || pending.updates.isNotEmpty()
        }
    )

    data class AudienceMetrics(
        val candidateUpdates: Long,
        val coalescedUpdates: Long,
        val scheduledDrains: Long,
        val processedUpdates: Long,
        val authoritativePassengerRefreshRequests: Long,
        val authoritativePassengerRefreshes: Long,
        val visibleEntries: Int,
        val pendingPlayers: Int
    )

    private class PendingAudienceUpdates {
        val updates: ConcurrentMap<Renderable, Vector3Snapshot> = ConcurrentHashMap()
        val scheduled = AtomicBoolean(false)
    }

    private data class PlayerRefreshState(
        val chunk: RenderChunk,
        val x: Double,
        val y: Double,
        val z: Double,
        val viewDistance: Double,
        val lodThreshold: Double
    )

}

internal object PlayerAudienceRefreshPolicy {
    private const val REFRESH_DISTANCE_SQUARED = 1.0

    fun movedEnough(
        previousX: Double,
        previousY: Double,
        previousZ: Double,
        currentX: Double,
        currentY: Double,
        currentZ: Double
    ): Boolean {
        val dx = currentX - previousX
        val dy = currentY - previousY
        val dz = currentZ - previousZ
        return dx * dx + dy * dy + dz * dz >= REFRESH_DISTANCE_SQUARED
    }
}
