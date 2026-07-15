package work.art1st.mineRails.basic.route

import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailView
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import java.util.Collections
import java.util.LinkedList
import java.util.PriorityQueue
import java.util.UUID

/** 持久化友好的 RailView 引用。 */
data class RailViewRef(
    val railId: UUID,
    val direction: ViewDirection
) {
    companion object {
        fun from(view: RailView): RailViewRef = RailViewRef(view.rail.id, view.viewDirection)
    }
}

/** 展开经路中的一段弧长区间。 */
data class RouteSegment(
    val railViewRef: RailViewRef,
    val startArcLength: Double,
    val endArcLength: Double
) {
    init {
        require(startArcLength.isFinite() && startArcLength >= 0.0)
        require(endArcLength.isFinite() && endArcLength >= startArcLength)
    }

    val length: Double
        get() = endArcLength - startArcLength
}

/** 从一个路径点到另一个路径点的不可变展开经路。 */
@ConsistentCopyVisibility
data class ExpandedRoute private constructor(
    val fromWayPointId: String,
    val toWayPointId: String,
    val segments: List<RouteSegment>
) {
    constructor(
        fromWayPointId: String,
        toWayPointId: String,
        segments: Collection<RouteSegment>
    ) : this(fromWayPointId, toWayPointId, immutableCopy(segments))

    init {
        require(fromWayPointId.isNotBlank() && toWayPointId.isNotBlank())
        require(segments.isNotEmpty()) { "ExpandedRoute must contain at least one segment" }
        require(segments.sumOf(RouteSegment::length).let { it.isFinite() && it > 0.0 }) {
            "ExpandedRoute total length must be finite and positive"
        }
    }

    val totalLength: Double
        get() = segments.sumOf(RouteSegment::length)

    private companion object {
        fun immutableCopy(source: Collection<RouteSegment>): List<RouteSegment> =
            Collections.unmodifiableList(ArrayList(source))
    }
}

sealed class RouteBuildResult {
    data class Success(val route: ExpandedRoute) : RouteBuildResult()
    data class Failure(val reason: String) : RouteBuildResult()
}

class RoutePlanner(private val world: World) {
    fun buildRoute(fromWayPointId: String, toWayPointId: String): RouteBuildResult {
        val store = world.basicWorldData.wayPoints
        val from = store[fromWayPointId] ?: return RouteBuildResult.Failure("路径点不存在: $fromWayPointId")
        val to = store[toWayPointId] ?: return RouteBuildResult.Failure("路径点不存在: $toWayPointId")
        return buildRoute(from, to)
    }

    /**
     * 路径点本身不带方向。这里会尝试方向限制允许的 RailView 组合，
     * 并选择可达路线中总长度最短的一条。
     */
    fun buildRoute(
        from: WayPoint,
        to: WayPoint,
        requiredGoalDirection: ViewDirection? = null
    ): RouteBuildResult {
        val startCandidates = resolveViews(from)
        if (startCandidates.isEmpty()) return RouteBuildResult.Failure("起点轨道不存在: ${from.railId}")
        val goalCandidates = resolveViews(to).filter { candidate ->
            requiredGoalDirection == null || candidate.viewDirection == requiredGoalDirection
        }
        if (goalCandidates.isEmpty()) {
            return RouteBuildResult.Failure(
                "终点轨道或要求方向不存在: ${to.railId}/${requiredGoalDirection ?: "any"}"
            )
        }

        val routes = startCandidates.flatMap { start ->
            goalCandidates.mapNotNull { goal -> buildRouteForViews(from, to, start, goal) }
        }
        val best = routes.minByOrNull { it.totalLength }
            ?: return RouteBuildResult.Failure("路径不可达: ${from.id} -> ${to.id}")
        return RouteBuildResult.Success(best)
    }

    private fun buildRouteForViews(
        from: WayPoint,
        to: WayPoint,
        start: RailView,
        goal: RailView
    ): ExpandedRoute? {
        val startArc = arcLengthInView(from, start.viewDirection, start.curve.totalArcLength)
        val goalArc = arcLengthInView(to, goal.viewDirection, goal.curve.totalArcLength)
        val path = findPath(start, goal, startArc, goalArc) ?: return null
        val segments = buildSegments(path, startArc, goalArc)
        if (segments.any { it.length < -1e-6 }) return null
        val usefulSegments = segments.filter { it.length > 1e-6 }
        if (usefulSegments.isEmpty()) return null
        return ExpandedRoute(from.id, to.id, usefulSegments)
    }

    private fun resolveViews(wayPoint: WayPoint): List<RailView> {
        val rail = world.getRail(wayPoint.railId) ?: return emptyList()
        return listOf(
            rail.getView(ViewDirection.AB),
            rail.getView(ViewDirection.BA)
        ).filter(::allowsDirection)
    }

    private fun arcLengthInView(wayPoint: WayPoint, direction: ViewDirection, totalLength: Double): Double {
        return when (direction) {
            ViewDirection.AB -> wayPoint.arcLength
            ViewDirection.BA -> totalLength - wayPoint.arcLength
        }
    }

    private fun findPath(
        start: RailView,
        goal: RailView,
        startArcLength: Double,
        goalArcLength: Double
    ): List<RailView>? {
        if (start == goal && goalArcLength >= startArcLength) return listOf(start)

        val initial = SearchState(start, departedStart = false)
        val distances = HashMap<SearchState, Double>()
        val predecessor = HashMap<SearchState, SearchState?>()
        var sequence = 0L
        val pending = PriorityQueue<QueueEntry>(compareBy(QueueEntry::distance, QueueEntry::sequence))
        distances[initial] = 0.0
        predecessor[initial] = null
        pending += QueueEntry(initial, 0.0, sequence++)

        while (pending.isNotEmpty()) {
            val entry = pending.remove()
            val bestKnown = distances[entry.state] ?: continue
            if (entry.distance > bestKnown) continue
            val current = entry.state
            if (current.departedStart && current.view == goal) {
                return rebuildPath(predecessor, current)
            }

            val traversalLength = if (current.departedStart) {
                current.view.curve.totalArcLength
            } else {
                current.view.curve.totalArcLength - startArcLength
            }
            for (next in current.view.nextRails) {
                if (!allowsDirection(next)) continue
                val nextState = SearchState(next, departedStart = true)
                val candidate = bestKnown + traversalLength
                if (candidate >= (distances[nextState] ?: Double.POSITIVE_INFINITY)) continue
                distances[nextState] = candidate
                predecessor[nextState] = current
                pending += QueueEntry(nextState, candidate, sequence++)
            }
        }
        return null
    }

    private fun allowsDirection(view: RailView): Boolean =
        view.rail.metadata.allowedDirection?.let { it == view.viewDirection } ?: true

    private fun rebuildPath(
        predecessor: Map<SearchState, SearchState?>,
        goal: SearchState
    ): List<RailView> {
        val path = LinkedList<RailView>()
        var node: SearchState? = goal
        while (node != null) {
            path.addFirst(node.view)
            node = predecessor[node]
        }
        return path
    }

    private data class SearchState(val view: RailView, val departedStart: Boolean)

    private data class QueueEntry(
        val state: SearchState,
        val distance: Double,
        val sequence: Long
    )

    private fun buildSegments(
        path: List<RailView>,
        startArcLength: Double,
        goalArcLength: Double
    ): List<RouteSegment> {
        if (path.isEmpty()) return emptyList()
        if (path.size == 1) {
            val view = path.single()
            return listOf(RouteSegment(RailViewRef.from(view), startArcLength, goalArcLength))
        }
        return path.mapIndexed { index, view ->
            val start = if (index == 0) startArcLength else 0.0
            val end = if (index == path.lastIndex) goalArcLength else view.curve.totalArcLength
            RouteSegment(RailViewRef.from(view), start, end)
        }
    }
}
