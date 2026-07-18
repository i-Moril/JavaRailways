package work.art1st.mineRails.bukkit.render.chunk

import org.bukkit.Bukkit
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
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.rail.RailArcVisualSegment
import work.art1st.mineRails.bukkit.extension.rail.renderer
import work.art1st.mineRails.bukkit.extension.train.renderer
import work.art1st.mineRails.bukkit.render.asVector3d
import work.art1st.mineRails.bukkit.util.BukkitThreadOwnership
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.util.WeakReferenceWrapper
import work.art1st.mineRails.core.util.ThreadGuard
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * 保存 renderable 的区块索引，并在玩家移动 / renderable 位置变化时增量触发模型构建。
 */
class RenderChunkManager(
    world: World,
    private val renderableObjects: ConcurrentMap<RenderChunk, MutableSet<Renderable>> = ConcurrentHashMap()
) : Listener {
    val world: World by WeakReferenceWrapper(world)
    private val visibleByPlayer: ConcurrentMap<UUID, MutableSet<Renderable>> = ConcurrentHashMap()
    private val refreshStateByPlayer: ConcurrentMap<UUID, PlayerRefreshState> = ConcurrentHashMap()

    init {
        Bukkit.getPluginManager().registerEvents(this, BukkitPlugin.INSTANCE)
    }

    fun close() {
        ThreadGuard.ensureMainThread("RenderChunkManager.close")
        world.players.forEach(::clearPlayer)
        HandlerList.unregisterAll(this)
    }

    fun getRenderablesAround(position: Vector3dc, radius: Double): Collection<Renderable> {
        val radiusSq = radius * radius
        val chunks = RenderChunk.getChunksAround(position, radius)
        val selected = LinkedHashSet<Renderable>()
        for (chunk in chunks) {
            renderableObjects[chunk]?.forEach { renderable ->
                if (distanceSquared(renderable.position, position) <= radiusSq) {
                    selected.add(renderable)
                }
            }
        }
        return selected
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
        BukkitPlugin.INSTANCE.platformDispatcher.runForEntityLater(event.player, 1L, action = {
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

    private fun refreshPlayer(player: Player, force: Boolean = false) {
        if (player.world != world) {
            clearPlayer(player)
            return
        }
        val location = player.location
        val position = location.asVector3d()
        val settings = player.railViewDistanceSettings
        val chunk = RenderChunk(position)
        val previous = refreshStateByPlayer[player.uniqueId]
        if (!force && previous != null && previous.chunk == chunk &&
            previous.viewDistance == settings.viewDistance && previous.lodThreshold == settings.lodThreshold
        ) {
            val dx = location.x - previous.x
            val dy = location.y - previous.y
            val dz = location.z - previous.z
            if (dx * dx + dy * dy + dz * dz < PLAYER_REFRESH_DISTANCE_SQ) return
        }

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
        if (visible) {
            val renderables = visibleByPlayer.computeIfAbsent(player.uniqueId) { ConcurrentHashMap.newKeySet() }
            if (renderables.add(renderable)) {
                dispatchSendTo(renderable, player)
            } else if (refreshExisting) {
                dispatchRefreshFor(renderable, player)
            }
            return
        }

        val renderables = visibleByPlayer[player.uniqueId] ?: return
        if (renderables.remove(renderable)) {
            dispatchHideFrom(renderable, player)
            if (renderables.isEmpty()) {
                visibleByPlayer.remove(player.uniqueId)
            }
        }
    }

    private fun clearPlayer(player: Player) {
        refreshStateByPlayer.remove(player.uniqueId)
        visibleByPlayer.remove(player.uniqueId)?.forEach { dispatchHideFrom(it, player) }
    }

    private fun dispatchSendTo(renderable: Renderable, player: Player) {
        when (renderable) {
            is Car -> renderable.renderer.sendTo(player)
            is RailArcVisualSegment -> renderable.renderer.sendTo(player)
        }
    }

    private fun dispatchHideFrom(renderable: Renderable, player: Player) {
        when (renderable) {
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

    internal fun remove(renderable: Renderable, position: Vector3dc = renderable.position) {
        ThreadGuard.ensureMainThread("RenderChunkManager.remove")
        remove(renderable, RenderChunk(position))
        val playerIds = visibleByPlayer.entries
            .filter { (_, renderables) -> renderable in renderables }
            .map { (playerId, _) -> playerId }
        val playersById = world.players.associateBy { it.uniqueId }
        playerIds.forEach { playerId ->
            val player = playersById[playerId]
            if (player != null) {
                setVisible(player, renderable, visible = false)
            } else {
                visibleByPlayer[playerId]?.let { renderables ->
                    renderables.remove(renderable)
                    if (renderables.isEmpty()) visibleByPlayer.remove(playerId)
                }
            }
        }
    }

    internal fun update(renderable: Renderable, oldPosition: Vector3dc?, newPosition: Vector3dc) {
        ThreadGuard.ensureMainThread("RenderChunkManager.update")
        val newChunk = RenderChunk(newPosition)
        val oldChunk = oldPosition?.let(::RenderChunk)

        // The chunk map is an in-memory spatial index, not a reason to force-load Bukkit chunks.
        // Only mutate it when a renderable is first registered or actually crosses a chunk border.
        val alreadyIndexed = renderableObjects[newChunk]?.contains(renderable) == true
        if (!alreadyIndexed || oldChunk == null || oldChunk != newChunk) {
            renderableObjects.computeIfAbsent(newChunk) { ConcurrentHashMap.newKeySet() }.add(renderable)
            if (oldChunk != null && oldChunk != newChunk) remove(renderable, oldChunk)
        }

        updateAudience(renderable, newPosition)
    }

    private fun updateAudience(renderable: Renderable, position: Vector3dc) {
        val positionSnapshot = org.joml.Vector3d(position)
        world.players.forEach { player ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(player, action = playerAction@{
                if (player.world != world) {
                    clearPlayer(player)
                    return@playerAction
                }
                val radius = player.railViewDistanceSettings.viewDistance
                val visible = distanceSquared(positionSnapshot, player.location.asVector3d()) <= radius * radius
                setVisible(player, renderable, visible)
            })
        }
    }

    private fun distanceSquared(a: Vector3dc, b: Vector3dc): Double {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        val dz = a.z() - b.z()
        return dx * dx + dy * dy + dz * dz
    }

    private data class PlayerRefreshState(
        val chunk: RenderChunk,
        val x: Double,
        val y: Double,
        val z: Double,
        val viewDistance: Double,
        val lodThreshold: Double
    )

    companion object {
        private const val PLAYER_REFRESH_DISTANCE_SQ = 1.0
    }
}
