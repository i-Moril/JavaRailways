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
import work.art1st.mineRails.bukkit.extension.chunk
import work.art1st.mineRails.bukkit.extension.rail.RailArcVisualSegment
import work.art1st.mineRails.bukkit.extension.rail.renderer
import work.art1st.mineRails.bukkit.extension.train.renderer
import work.art1st.mineRails.bukkit.render.asVector3d
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.util.WeakReferenceWrapper
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
    private val visibleByPlayer: MutableMap<UUID, MutableSet<Renderable>> = HashMap()

    init {
        Bukkit.getPluginManager().registerEvents(this, BukkitPlugin.INSTANCE)
    }

    fun close() {
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
        if (event.player.world == world) {
            refreshPlayer(event.player)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player)
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val to = event.to
        if (event.player.world != world) return
        if (event.from.x == to.x && event.from.y == to.y && event.from.z == to.z) return
        refreshPlayer(event.player)
    }

    @EventHandler
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        if (event.from.world != world && event.to.world != world) return
        Bukkit.getScheduler().runTask(BukkitPlugin.INSTANCE, Runnable {
            if (event.player.world == world) refreshPlayer(event.player) else clearPlayer(event.player)
        })
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        if (event.player.world == world) {
            refreshPlayer(event.player)
        } else if (event.from == world) {
            clearPlayer(event.player)
        }
    }

    private fun refreshPlayer(player: Player) {
        if (player.world != world) {
            clearPlayer(player)
            return
        }
        val newVisible = getRenderablesAround(
            player.location.asVector3d(),
            player.railViewDistanceSettings.viewDistance
        ).toMutableSet()
        val oldVisible = visibleByPlayer[player.uniqueId] ?: emptySet()

        oldVisible.filterNot { it in newVisible }.forEach { dispatchHideFrom(it, player) }
        newVisible.filterNot { it in oldVisible }.forEach { dispatchSendTo(it, player) }

        if (newVisible.isEmpty()) {
            visibleByPlayer.remove(player.uniqueId)
        } else {
            visibleByPlayer[player.uniqueId] = newVisible
        }
    }

    private fun clearPlayer(player: Player) {
        visibleByPlayer.remove(player.uniqueId)?.forEach { dispatchHideFrom(it, player) }
    }

    private fun dispatchSendTo(renderable: Renderable, player: Player) {
        when (renderable) {
            is Car -> renderable.renderer.sendTo(player)
            is Bogie -> renderable.renderer.sendTo(player)
            is RailArcVisualSegment -> renderable.renderer.sendTo(player)
        }
    }

    private fun dispatchHideFrom(renderable: Renderable, player: Player) {
        when (renderable) {
            is Car -> renderable.renderer.hideFrom(player)
            is Bogie -> renderable.renderer.hideFrom(player)
            is RailArcVisualSegment -> Unit
        }
    }

    private fun remove(renderable: Renderable, chunk: RenderChunk) {
        renderableObjects[chunk]?.let {
            it.remove(renderable)
            if (it.isEmpty()) {
                removeChunk(chunk)
            }
        }
    }

    internal fun remove(renderable: Renderable) {
        remove(renderable, renderable.chunk)
        visibleByPlayer.entries.removeIf { (_, renderables) ->
            renderables.remove(renderable)
            renderables.isEmpty()
        }
        world.players.forEach { player -> dispatchHideFrom(renderable, player) }
    }

    internal fun update(renderable: Renderable, oldPosition: Vector3dc?, newPosition: Vector3dc) {
        val newChunk = RenderChunk(newPosition)
        renderableObjects.getOrPut(newChunk) { HashSet() }.add(renderable)
        addChunk(newChunk)

        if (oldPosition != null) {
            val oldChunk = RenderChunk(oldPosition)
            if (oldChunk != newChunk) {
                remove(renderable, oldChunk)
            }
        }

        updateAudience(renderable)
    }

    private fun updateAudience(renderable: Renderable) {
        world.players.forEach { player ->
            if (player.world != world) return@forEach
            val renderables = visibleByPlayer.getOrPut(player.uniqueId) { LinkedHashSet() }
            val radius = player.railViewDistanceSettings.viewDistance
            if (distanceSquared(renderable.position, player.location.asVector3d()) <= radius * radius) {
                if (renderables.add(renderable)) {
                    dispatchSendTo(renderable, player)
                }
            } else if (renderables.remove(renderable)) {
                dispatchHideFrom(renderable, player)
                if (renderables.isEmpty()) {
                    visibleByPlayer.remove(player.uniqueId)
                }
            }
        }
    }

    private fun distanceSquared(a: Vector3dc, b: Vector3dc): Double {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        val dz = a.z() - b.z()
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * 令区块加载，允许区块内的实体被发送给玩家
     */
    private fun addChunk(chunk: RenderChunk) {
        val bukkitChunk = world.getChunkAt(chunk.x, chunk.z)
        bukkitChunk.addPluginChunkTicket(BukkitPlugin.INSTANCE)
    }

    /**
     * 卸载区块
     */
    private fun removeChunk(chunk: RenderChunk) {
        val bukkitChunk = world.getChunkAt(chunk.x, chunk.z)
        bukkitChunk.removePluginChunkTicket(BukkitPlugin.INSTANCE)
    }
}
