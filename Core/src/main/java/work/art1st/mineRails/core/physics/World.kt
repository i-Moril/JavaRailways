package work.art1st.mineRails.core.physics

import work.art1st.mineRails.core.render.RenderChunk
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.train.Train
import work.art1st.mineRails.core.physics.rail.Rail
import work.art1st.mineRails.core.api.math.Vector3D
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ScheduledExecutorService

class World(
    private val rails: MutableMap<UUID, Rail>,
    private val trains: MutableMap<String, Train>,
    private val signals: MutableSet<Signal>,
    private val scheduler: ScheduledExecutorService
) {
    internal val renderableObjects: ConcurrentMap<RenderChunk, HashSet<Renderable>> = ConcurrentHashMap()

    fun addTrain(train: Train) {
        trains[train.id] = train
    }

    fun getTrain(name: String): Train? = trains[name]

    fun removeTrain(train: Train) = trains.remove(train.id)

    fun addRail(rail: Rail) {
        rails[rail.id] = rail
    }

    fun getRail(id: UUID): Rail? = rails[id]

    fun removeRail(rail: Rail) = rails.remove(rail.id)

//    fun getNearestRailLocation(position: Vector3D): RailLocation {
//
//    }

    fun getRenderableObjects(position: Vector3D, radius: Double): Collection<Renderable> {
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

    internal fun updateRenderablePosition(renderable: Renderable, oldPosition: Vector3D?, newPosition: Vector3D) {
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