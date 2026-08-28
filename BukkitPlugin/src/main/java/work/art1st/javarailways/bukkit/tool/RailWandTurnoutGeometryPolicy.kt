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
    if (oldLanes.size != replacementLanes.size) return "turnout.lane_count_changed"
    oldLanes.indices.forEach { laneIndex ->
        val oldLane = oldLanes[laneIndex]
        val replacementLane = replacementLanes[laneIndex]
        if (oldLane.isEmpty() || replacementLane.isEmpty()) return "turnout.incomplete_lane"
        if (TurnoutTopology.isTurnoutEndpoint(oldLane.first().viewBA)) {
            frameDifference(
                oldLane.first().viewAB.startPoint,
                replacementLane.first().getPointAt(0.0)
            )?.let { difference ->
                return RailWandI18n.reference("turnout.boundary_$difference", "A")
            }
        }
        if (TurnoutTopology.isTurnoutEndpoint(oldLane.last().viewAB)) {
            val replacement = replacementLane.last()
            frameDifference(
                oldLane.last().viewAB.endPoint,
                replacement.getPointAt(replacement.totalArcLength)
            )?.let { difference ->
                return RailWandI18n.reference("turnout.boundary_$difference", "B")
            }
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
    fun resolve(ref: RailViewRef): RailView = world.getRail(ref.railId)?.getView(ref.direction)
        ?: throw RailWandLocalizedException("failure.turnout_view_missing", ref)

    snapshots.forEach { snapshot ->
        val mappedView = remap(snapshot.view)
        val view = resolve(mappedView)
        val expectedRefs = snapshot.next.map(::remap).distinct()
        val actualRefs = view.nextRails.map(RailViewRef::from)
        if (actualRefs.size != expectedRefs.size || actualRefs.toSet() != expectedRefs.toSet()) {
            throw RailWandLocalizedException("failure.turnout_topology_mismatch", mappedView)
        }
        val expectedNext = expectedRefs.map(::resolve)
        val expectedActive = snapshot.active?.let(::remap)?.let(::resolve)
        if (expectedActive != null && expectedActive !in expectedNext) {
            throw RailWandLocalizedException("failure.turnout_active_branch_missing", mappedView)
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
        return RailWandI18n.reference(
            "turnout.occupied",
            occupyingTrainIds.sorted().joinToString()
        )
    }
    val dispatcher = world.basicWorldDataOrNull?.dispatcher ?: return null
    if (dispatcher.hasRouteAllocationOn(affectedIds)) return "turnout.route_locked"
    if (dispatcher.hasLiveRouteOn(affectedIds)) return "turnout.live_route"

    return null
}

private fun frameDifference(original: Curve.Point, replacement: Curve.Point): String? {
    if (original.position.distance(replacement.position) > Curve.TOLERANCE) return "position"
    if (!sameDirection(original.tangent, replacement.tangent)) return "tangent"
    if (!sameDirection(original.normal, replacement.normal)) return "roll"
    return null
}

private fun sameDirection(first: Vector3dc, second: Vector3dc): Boolean {
    val a = Vector3d(first)
    val b = Vector3d(second)
    if (a.lengthSquared() < 1.0e-12 || b.lengthSquared() < 1.0e-12) return false
    return a.normalize().dot(b.normalize()) >= TURNOUT_FRAME_DIRECTION_DOT
}
