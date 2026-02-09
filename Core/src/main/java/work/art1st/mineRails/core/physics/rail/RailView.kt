package work.art1st.mineRails.core.physics.rail

import kotlinx.serialization.Serializable
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.api.physics.exception.InvalidActiveRailException
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurve
import work.art1st.mineRails.core.physics.train.TrainEnd
import work.art1st.mineRails.core.util.DeserializationMissingDependencyException
import work.art1st.mineRails.core.util.PersistentFactoryWithDependency
import work.art1st.mineRails.core.util.PersistentObject
import java.util.LinkedList
import java.util.PriorityQueue
import java.util.UUID
import java.util.stream.Collectors

class RailView(
    val rail: Rail,
    val direction: ViewDirection,
    val curve: PrimeCurve,
    var nextRails: MutableList<RailView>,
    nextActiveRail: RailView? = if (nextRails.isEmpty()) null else nextRails[0]
) : PersistentObject<RailView.SavedRailViewV1> {
    init {
        if (!nextRails.contains(nextActiveRail)) {
            throw InvalidActiveRailException(nextActiveRail)
        }
    }

//    var blocked: Boolean = false
//        private set
    private var destinations: MutableMap<String, RailDestination> = HashMap()

    var nextActiveRail: RailView? = nextActiveRail
        set(value) {
            if (!nextRails.contains(value)) {
                throw InvalidActiveRailException(value)
            }
            nextActiveRail = value
        }

    enum class ViewDirection {
        AB,
        BA
    }

    val startPoint: Curve.Point
        get() = curve.getPointAt(0)!!

    val endPoint: Curve.Point
        get() = curve.getPointAt(curve.totalPoints - 1)!!

    val staticRailObjects: Collection<Signal>
        get() = when (direction) {
            ViewDirection.AB -> rail.signals
            ViewDirection.BA -> rail.signals.reversed()
        }

    val dynamicRailObjects: Collection<TrainEnd>
        get() = rail.trains

    val reversed
        get() = when (direction) {
            ViewDirection.AB -> rail.viewBA
            ViewDirection.BA -> rail.viewAB
        }

    fun addDestination(destination: RailDestination) {
        destinations[destination.label] = destination
    }

    fun removeDestination(label: String) {
        destinations.remove(label)
    }

    fun hasDestination(label: String): Boolean {
        return destinations.contains(label)
    }

    fun findPath(label: String): List<RailView>? {
        val path = LinkedList<RailView>()
        val pending = PriorityQueue<Triple<RailView, Double, RailView?>> {
            a, b -> if (a.second < b.second) -1 else if (a.second == b.second) 0 else 1
        }
        val visited = HashMap<RailView, RailView?>()
        pending.add(Triple(this, 0.0, null))
        while (pending.isNotEmpty()) {
            val current = pending.poll()
            visited[current.first] = current.third
            if (current.first.hasDestination(label)) {
                var node: RailView? = current.first
                while (node != null) {
                    path.add(node)
                    node = visited[node]
                }
                return path
            }
            val distance = current.second + current.first.curve.totalArcLength
            current.first.nextRails.forEach { rail -> if (!visited.contains(rail)) pending.add(Triple(rail, distance, current.first)) }
            while (visited.contains(pending.first().first)) {
                pending.poll()
            }
        }
        return null
    }

    /* TODO: 异步关系 */
    fun asyncFindPath(label: String, then: (List<RailView>?) -> Unit) {
        rail.world.scheduler.execute {
            val path = findPath(label)
            then(path)
        }
    }

    @Serializable
    data class SavedRailViewV1(
        val rail: String,
        val direction: ViewDirection,
        val nextRails: List<String>,
        val nextActiveRail: Int
    )

    override fun save() : SavedRailViewV1 {
        return SavedRailViewV1(
            rail.id.toString(),
            direction,
            nextRails.map { railView -> rail.id.toString() },
            nextRails.indexOf(nextActiveRail),
        )
    }

    companion object Deserializer : PersistentFactoryWithDependency<RailView, SavedRailViewV1, World> {
        override fun load(
            saved: SavedRailViewV1,
            dependencyProvider: World
        ): RailView {
            val rail = dependencyProvider.getRail(UUID.fromString(saved.rail))
                            ?: throw DeserializationMissingDependencyException(Rail.javaClass, saved.rail, dependencyProvider)
            val nextRails = saved.nextRails.stream().map {
                id -> dependencyProvider.getRail(UUID.fromString(id))
                    ?: throw DeserializationMissingDependencyException(Rail.javaClass, id, dependencyProvider)
            }.collect(Collectors.toList())
            val nextActiveRail = nextRails[saved.nextActiveRail]
            return RailView(
                rail,
                saved.direction,
                nextRails,
                nextActiveRail
            )
        }
    }
}
