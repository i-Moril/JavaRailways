package work.art1st.mineRails.bukkit.render.chunk

import org.bukkit.World
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.BukkitRenderable
import work.art1st.mineRails.bukkit.extension.chunk
import work.art1st.mineRails.bukkit.render.asVector3d
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.util.WeakReferenceWrapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * 线程安全：使用ConcurrentHashMap；仅保存渲染信息，没有时序依赖性
 */
class RenderChunkManager(
    world: World,
    private val renderableObjects: ConcurrentMap<RenderChunk, MutableSet<Renderable>> = ConcurrentHashMap()
) {
    val world: World by WeakReferenceWrapper(world)

    fun getRenderablesAround(position: Vector3dc, radius: Double): Collection<Renderable> {
        val chunks = RenderChunk.getChunksAround(position, radius)
        val selected = LinkedHashSet<Renderable>()
        for (chunk in chunks) {
            renderableObjects[chunk]?.forEach { renderable ->
                if (renderable.position.distance(position) <= radius) {
                    selected.add(renderable)
                }
            }
        }
        return selected
    }

    fun render() {
        world.players.forEach { player ->
            getRenderablesAround(
                player.location.asVector3d(),
                player.railViewDistanceSettings.viewDistance
            ).forEach {
                if (it is BukkitRenderable<*>) {
                    it.sendTo(player)
                }
            }
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
    }

    internal fun update(renderable: Renderable, oldPosition: Vector3dc?, newPosition: Vector3dc) {
        if (oldPosition == null) {
            val chunk = RenderChunk(newPosition)
            renderableObjects.getOrPut(chunk) { HashSet() }.add(renderable)
            addChunk(chunk)
        } else {
            val oldChunk = RenderChunk(oldPosition)
            val newChunk = RenderChunk(newPosition)
            if (oldChunk != newChunk) {
                renderableObjects.getOrPut(RenderChunk(newPosition)) { HashSet() }.add(renderable)
                remove(renderable, oldChunk)
            }
        }
    }

    /**
     * 令区块加载，允许区块内的实体被发送给玩家
     */
    private fun addChunk(chunk: RenderChunk) {
        val bukkitChunk = world.getChunkAt(chunk.x, chunk.z)
        bukkitChunk.addPluginChunkTicket(BukkitPlugin)
    }

    /**
     * 卸载区块
     */
    private fun removeChunk(chunk: RenderChunk) {
        val bukkitChunk = world.getChunkAt(chunk.x, chunk.z)
        bukkitChunk.removePluginChunkTicket(BukkitPlugin)
    }
}