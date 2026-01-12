package work.art1st.mineRails.core.physics.rail

import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.rail.curve.Curve
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurve
import work.art1st.mineRails.core.physics.train.Train
import work.art1st.mineRails.core.physics.train.TrainEnd
import java.util.LinkedList
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class RailView(
    val rail: Rail,
    val direction: ViewDirection,
    val curve: PrimeCurve,
    var nextRails: MutableList<RailView>,
    var activeTrack: Int = 0
) {
    var blocked: Boolean = false
        private set
    private var destinations: MutableMap<String, RailDestination> = HashMap()

    enum class ViewDirection {
        AB,
        BA
    }

    val nextActiveRail: RailView?
        get() {
            if (nextRails.isEmpty()) return null
            return nextRails[activeTrack]
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

    fun asyncFindPath(label: String, then: (List<RailView>?) -> Unit) {
        rail.world.scheduler.execute {
            val path = findPath(label)
            then(path)
        }
    }

    fun save() : SavedRailView {
        return SavedRailView(
            nextRails.map { railView -> rail.id.toString() },
            nextRails[activeTrack].rail.id.toString()
        )
    }

    data class SavedRailView(
        val nextRails: List<String>,
        val activeTrack: String
    )
}
