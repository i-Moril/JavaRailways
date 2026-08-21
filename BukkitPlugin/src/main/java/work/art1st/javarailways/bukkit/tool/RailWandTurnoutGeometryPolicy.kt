package work.art1st.javarailways.bukkit.tool

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.basic.pathFinding.basicWorldDataOrNull
import work.art1st.javarailways.basic.rail.TurnoutTopology
import work.art1st.javarailways.basic.route.RailViewRef
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import java.util.UUID

private const val TURNOUT_FRAME_DIRECTION_DOT = 1.0 - 1.0e-10

internal data class TurnoutViewSnapshot(
    val view: RailViewRef,
    val next: List<RailViewRef>,
    val active: RailViewRef?
)

/** Returns null when rebuilding [oldLanes] keeps every touched turnout node frame unchanged. */
internal fun turnoutBoundaryFrameError(
    oldLanes: List<List<CoreRail>>,
    replacementLanes: List<List<PrimeCurve>>
): String? {
    if (oldLanes.size != replacementLanes.size) return "道岔相邻线路数量发生变化"
    oldLanes.indices.forEach { laneIndex ->
        val oldLane = oldLanes[laneIndex]
        val replacementLane = replacementLanes[laneIndex]
        if (oldLane.isEmpty() || replacementLane.isEmpty()) return "道岔相邻逻辑线路信息不完整"
        if (TurnoutTopology.isTurnoutEndpoint(oldLane.first().viewBA)) {
            frameDifference(
                oldLane.first().viewAB.startPoint,
                replacementLane.first().getPointAt(0.0)
            )?.let { return "道岔 A 端${it}发生变化" }
        }
        if (TurnoutTopology.isTurnoutEndpoint(oldLane.last().viewAB)) {
            val replacement = replacementLane.last()
            frameDifference(
                oldLane.last().viewAB.endPoint,
                replacement.getPointAt(replacement.totalArcLength)
            )?.let { return "道岔 B 端${it}发生变化" }
        }
    }
    return null
}

/** Captures only the directional views that form turnout nodes touched by [rails]. */
internal fun captureTurnoutViewSnapshots(rails: Collection<CoreRail>): List<TurnoutViewSnapshot> =
    TurnoutTopology.turnoutRailsTouching(rails).asSequence()
        .flatMap { rail -> sequenceOf(rail.viewBA, rail.viewAB) }
        .filter(TurnoutTopology::isTurnoutEndpoint)
        .map { view ->
            TurnoutViewSnapshot(
                view = RailViewRef.from(view),
                next = view.nextRails.map(RailViewRef::from),
                active = view.activeRail?.let(RailViewRef::from)
            )
        }
        .distinctBy(TurnoutViewSnapshot::view)
        .sortedWith(compareBy<TurnoutViewSnapshot> { it.view.railId.toString() }.thenBy { it.view.direction })
        .toList()

/** Restores exact turnout branch ordering and selection after ordinary replacement/reconnection. */
internal fun restoreMappedTurnoutViewSnapshots(
    world: CoreWorld,
    snapshots: Collection<TurnoutViewSnapshot>,
    oldToNew: Map<UUID, UUID>
) {
    fun remap(ref: RailViewRef): RailViewRef = ref.copy(railId = oldToNew[ref.railId] ?: ref.railId)
    fun resolve(ref: RailViewRef): RailView = requireNotNull(
        world.getRail(ref.railId)?.getView(ref.direction)
    ) { "道岔轨道视图已不存在: $ref" }

    snapshots.forEach { snapshot ->
        val mappedView = remap(snapshot.view)
        val view = resolve(mappedView)
        val expectedRefs = snapshot.next.map(::remap).distinct()
        val actualRefs = view.nextRails.map(RailViewRef::from)
        require(actualRefs.size == expectedRefs.size && actualRefs.toSet() == expectedRefs.toSet()) {
            "道岔重建后的连接集合与原拓扑不一致: $mappedView"
        }
        val expectedNext = expectedRefs.map(::resolve)
        val expectedActive = snapshot.active?.let(::remap)?.let(::resolve)
        require(expectedActive == null || expectedActive in expectedNext) {
            "道岔重建后的活动股道不在连接集合中: $mappedView"
        }
        view.activeRail = null
        view.mutableNextRails.clear()
        view.mutableNextRails.addAll(expectedNext)
        view.activeRail = expectedActive
    }
}

/** Full maintenance preflight for a geometry replacement that touches a turnout node. */
internal fun turnoutGeometryMaintenanceError(
    world: CoreWorld,
    logicalRails: Collection<CoreRail>
): String? {
    val turnoutRails = TurnoutTopology.turnoutRailsTouching(logicalRails)
    if (turnoutRails.isEmpty()) return null
    val affectedIds = logicalRails.mapTo(linkedSetOf(), CoreRail::id).apply {
        addAll(turnoutRails.map(CoreRail::id))
    }
    val occupyingTrainIds = world.occupyingTrainIds(affectedIds)
    if (occupyingTrainIds.isNotEmpty()) {
        return "不能修改列车占用的道岔相关轨道：${occupyingTrainIds.sorted().joinToString()}"
    }
    val dispatcher = world.basicWorldDataOrNull?.dispatcher ?: return null
    if (dispatcher.hasRouteAllocationOn(affectedIds)) return "不能修改有活动进路锁的道岔"
    if (dispatcher.hasLiveRouteOn(affectedIds)) return "不能修改运行中任务正在引用的道岔线路"
    return null
}

private fun frameDifference(original: Curve.Point, replacement: Curve.Point): String? {
    if (original.position.distance(replacement.position) > Curve.TOLERANCE) return "位置"
    if (!sameDirection(original.tangent, replacement.tangent)) return "切线或坡度"
    if (!sameDirection(original.normal, replacement.normal)) return "横滚"
    return null
}

private fun sameDirection(first: Vector3dc, second: Vector3dc): Boolean {
    val a = Vector3d(first)
    val b = Vector3d(second)
    if (a.lengthSquared() < 1.0e-12 || b.lengthSquared() < 1.0e-12) return false
    return a.normalize().dot(b.normalize()) >= TURNOUT_FRAME_DIRECTION_DOT
}
