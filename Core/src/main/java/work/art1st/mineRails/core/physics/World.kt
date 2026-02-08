package work.art1st.mineRails.core.physics

import work.art1st.mineRails.core.asset.RenderChunk
import work.art1st.mineRails.core.asset.Renderable
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.train.Train
import work.art1st.mineRails.core.physics.rail.Rail
import work.art1st.mineRails.core.physics.rail.RailLocation
import work.art1st.mineRails.core.util.Vector3D
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ScheduledExecutorService

class World(
    val rails: MutableCollection<Rail>,
    val trains: MutableMap<String, Train>,
    val signals: MutableSet<Signal>,
    val scheduler: ScheduledExecutorService
) {
    internal val renderableObjects: ConcurrentMap<RenderChunk, HashSet<Renderable>> = ConcurrentHashMap()

    fun addTrain(train: Train) {

    }

    fun linkTrain(train1: Train, train2: Train) {

    }

    fun splitTrain(train: Train, splitFrom: Int) {

    }

    fun removeTrain(train: Train) {

    }

    fun addRail(rail: Rail) {

    }

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

    fun getTrain(name: String): Train? = trains[name]

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