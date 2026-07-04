package work.art1st.mineRails.basic.route

import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailView
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import java.util.LinkedList
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
    val length: Double
        get() = endArcLength - startArcLength
}

/** 从一个路径点到另一个路径点的展开经路。 */
data class ExpandedRoute(
    val fromWayPointId: String,
    val toWayPointId: String,
    val segments: List<RouteSegment>
) {
    val totalLength: Double
        get() = segments.sumOf { it.length }
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
     * 路径点本身不带方向。这里会尝试起点/终点所在 rail 的 AB、BA 两种视图组合，
     * 并选择可达路线中总长度最短的一条。方向限制后续由独立的运营 metadata 处理。
     */
    fun buildRoute(from: WayPoint, to: WayPoint): RouteBuildResult {
        val startCandidates = resolveViews(from)
        if (startCandidates.isEmpty()) return RouteBuildResult.Failure("起点轨道不存在: ${from.railId}")
        val goalCandidates = resolveViews(to)
        if (goalCandidates.isEmpty()) return RouteBuildResult.Failure("终点轨道不存在: ${to.railId}")

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
        )
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

        val pending: ArrayDeque<RailView> = ArrayDeque()
        val predecessor = HashMap<RailView, RailView?>()
        pending.addLast(start)
        predecessor[start] = null

        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            for (next in current.nextRails) {
                if (predecessor.containsKey(next)) continue
                predecessor[next] = current
                if (next == goal) return rebuildPath(predecessor, next)
                pending.addLast(next)
            }
        }
        return null
    }

    private fun rebuildPath(predecessor: Map<RailView, RailView?>, goal: RailView): List<RailView> {
        val path = LinkedList<RailView>()
        var node: RailView? = goal
        while (node != null) {
            path.addFirst(node)
            node = predecessor[node]
        }
        return path
    }

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
