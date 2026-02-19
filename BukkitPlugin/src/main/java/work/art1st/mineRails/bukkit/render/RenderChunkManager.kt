package work.art1st.mineRails.bukkit.render

import org.joml.Vector3dc
import work.art1st.mineRails.core.api.render.Renderable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

class RenderChunkManager(
    private val renderableObjects: ConcurrentMap<RenderChunk, HashSet<Renderable>> = ConcurrentHashMap()
) {
    fun getRenderableObjects(position: Vector3dc, radius: Double): Collection<Renderable> {
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

    internal fun removeAsset(renderable: Renderable) {
        renderableObjects[renderable.chunk]?.remove(renderable)
    }

    internal fun updateRenderablePosition(renderable: Renderable, oldPosition: Vector3dc?, newPosition: Vector3dc) {
        if (oldPosition == null) {
            renderableObjects.getOrPut(RenderChunk(newPosition)) { HashSet() }.add(renderable)
        } else {
            val oldChunk = RenderChunk(oldPosition)
            val newChunk = RenderChunk(newPosition)
            if (oldChunk != newChunk) {
                renderableObjects.getOrPut(RenderChunk(newPosition)) { HashSet() }.add(renderable)
                renderableObjects[RenderChunk(oldPosition)]?.remove(renderable)
            }
        }
    }
}
