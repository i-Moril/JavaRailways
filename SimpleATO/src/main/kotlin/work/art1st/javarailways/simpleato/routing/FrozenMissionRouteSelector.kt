package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.ato.mission.AtoMission
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import kotlin.math.abs

/**
 * One Dispatcher mission frozen from the authoritative observed tail.
 *
 * [headDistanceFromRouteStartMeters] maps the mission's head-based progress axis onto the frozen
 * tail-started route used by Basic admission.
 */
internal data class FrozenMissionRoute(
    val selection: FrozenRouteSelectionResult.Selected,
    val headDistanceFromRouteStartMeters: Double
)

internal enum class FrozenMissionRouteRejection {
    INVALID_MISSION,
    ROUTE_SELECTION_REJECTED,
    HEAD_NOT_ON_SELECTED_ROUTE,
    SELECTED_ROUTE_DIFFERS_FROM_MISSION
}

internal sealed interface FrozenMissionRouteResult {
    data class Frozen(val route: FrozenMissionRoute) : FrozenMissionRouteResult

    data class Rejected(
        val reason: FrozenMissionRouteRejection,
        val detail: String,
        val selectionRejection: FrozenRouteSelectionResult.Rejected? = null
    ) : FrozenMissionRouteResult {
        init {
            require(detail.isNotBlank())
            require((reason == FrozenMissionRouteRejection.ROUTE_SELECTION_REJECTED) ==
                (selectionRejection != null))
        }
    }
}

/**
 * Joins the existing Dispatcher mission with the topology planner without allowing two routes.
 *
 * The topology planner is needed because Basic authority begins at the physical train tail, which
 * may be behind the mission origin at the head. Its result is accepted only when the traversal from
 * the observed head to the target is exactly the already-selected mission traversal.
 */
internal object FrozenMissionRouteSelector {
    fun select(
        topology: TopologyState,
        context: FrozenRouteSelectionContext,
        operatingDirections: OperatingDirectionOverlay,
        trainId: String,
        mission: AtoMission
    ): FrozenMissionRouteResult {
        val missionRoute = missionRouteSnapshot(topology, mission)
            ?: return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "mission ${mission.id}@${mission.revision} has no valid physical target"
            )
        val targetSegment = missionRoute.segments.lastOrNull { it.lengthMeters > EPSILON }
            ?: return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "mission ${mission.id}@${mission.revision} has no positive-length traversal"
            )
        val target = OperationalRouteLocation(
            targetSegment.railId,
            canonicalAbPosition(topology, targetSegment, targetSegment.endArcLengthMeters)
                ?: return rejected(
                    FrozenMissionRouteRejection.INVALID_MISSION,
                    "mission target rail ${targetSegment.railId} has no unambiguous topology projection"
                )
        )
        val selected = when (val result = FrozenRouteSelector.select(
            FrozenRouteSelectionInput(
                topology = topology,
                context = context,
                operatingDirections = operatingDirections,
                trainId = trainId,
                target = target,
                routeId = mission.id,
                routeRevision = mission.revision
            )
        )) {
            is FrozenRouteSelectionResult.Selected -> result
            is FrozenRouteSelectionResult.Rejected -> return rejected(
                FrozenMissionRouteRejection.ROUTE_SELECTION_REJECTED,
                result.detail,
                result
            )
        }
        val train = checkNotNull(context.train(trainId)) {
            "a successful frozen-route selection lost train $trainId in its immutable observation"
        }

        val selectedHeadProgress = project(selected.route, topology, train.head)
            ?: return rejected(
                FrozenMissionRouteRejection.HEAD_NOT_ON_SELECTED_ROUTE,
                "train $trainId head is not on the tail-started route selected for mission ${mission.id}"
            )
        val missionHeadProgress = project(missionRoute, topology, train.head)
            ?: return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "train $trainId head is not on mission ${mission.id}@${mission.revision}"
            )
        val selectedRemainder = trim(selected.route.segments, selectedHeadProgress)
        val missionRemainder = trim(missionRoute.segments, missionHeadProgress)
        if (!sameTraversal(selectedRemainder, missionRemainder)) {
            return rejected(
                FrozenMissionRouteRejection.SELECTED_ROUTE_DIFFERS_FROM_MISSION,
                "tail-started topology route differs from mission ${mission.id}@${mission.revision} after the train head"
            )
        }
        return FrozenMissionRouteResult.Frozen(
            FrozenMissionRoute(selected, selectedHeadProgress)
        )
    }

    private fun missionRouteSnapshot(topology: TopologyState, mission: AtoMission): RouteSnapshot? {
        val segments = mission.legs.flatMap { leg ->
            leg.plannedRoute.expandedRoute.segments.map { segment ->
                if (topology.railProjection[segment.railViewRef.railId]?.size != 1) return null
                RouteSegmentSnapshot(
                    railId = segment.railViewRef.railId,
                    facing = segment.railViewRef.direction,
                    startArcLengthMeters = segment.startArcLength,
                    endArcLengthMeters = segment.endArcLength
                )
            }
        }
        if (segments.isEmpty()) return null
        return RouteSnapshot(mission.id, mission.revision, topology.revision, segments)
    }

    private fun project(
        route: RouteSnapshot,
        topology: TopologyState,
        position: RailPositionSnapshot
    ): Double? {
        var routeStart = 0.0
        route.segments.forEach { segment ->
            if (segment.railId == position.railId && segment.facing == position.facing) {
                val railLength = railLength(topology, segment.railId) ?: return null
                val positionInView = when (segment.facing) {
                    ViewDirection.AB -> position.arcLengthInAbMeters
                    ViewDirection.BA -> railLength - position.arcLengthInAbMeters
                }
                if (positionInView >= segment.startArcLengthMeters - POSITION_TOLERANCE_METERS &&
                    positionInView <= segment.endArcLengthMeters + POSITION_TOLERANCE_METERS
                ) {
                    return routeStart +
                        (positionInView.coerceIn(
                            segment.startArcLengthMeters,
                            segment.endArcLengthMeters
                        ) - segment.startArcLengthMeters)
                }
            }
            routeStart += segment.lengthMeters
        }
        return null
    }

    private fun trim(
        segments: List<RouteSegmentSnapshot>,
        distanceMeters: Double
    ): List<RouteSegmentSnapshot> {
        var remaining = distanceMeters.coerceAtLeast(0.0)
        val result = ArrayList<RouteSegmentSnapshot>()
        segments.forEach { segment ->
            if (remaining >= segment.lengthMeters - EPSILON) {
                remaining = (remaining - segment.lengthMeters).coerceAtLeast(0.0)
                return@forEach
            }
            val start = segment.startArcLengthMeters + remaining
            remaining = 0.0
            if (segment.endArcLengthMeters - start > EPSILON) {
                result += segment.copy(startArcLengthMeters = start)
            }
        }
        return mergeAdjacent(result)
    }

    private fun mergeAdjacent(source: List<RouteSegmentSnapshot>): List<RouteSegmentSnapshot> {
        val merged = ArrayList<RouteSegmentSnapshot>()
        source.forEach { segment ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.railId == segment.railId &&
                previous.facing == segment.facing &&
                abs(previous.endArcLengthMeters - segment.startArcLengthMeters) <= EPSILON
            ) {
                merged[merged.lastIndex] = previous.copy(endArcLengthMeters = segment.endArcLengthMeters)
            } else {
                merged += segment
            }
        }
        return merged
    }

    private fun sameTraversal(
        first: List<RouteSegmentSnapshot>,
        second: List<RouteSegmentSnapshot>
    ): Boolean = first.size == second.size && first.indices.all { index ->
        val a = first[index]
        val b = second[index]
        a.railId == b.railId && a.facing == b.facing &&
            abs(a.startArcLengthMeters - b.startArcLengthMeters) <= EPSILON &&
            abs(a.endArcLengthMeters - b.endArcLengthMeters) <= EPSILON
    }

    private fun canonicalAbPosition(
        topology: TopologyState,
        segment: RouteSegmentSnapshot,
        positionInViewMeters: Double
    ): Double? {
        val length = railLength(topology, segment.railId) ?: return null
        return when (segment.facing) {
            ViewDirection.AB -> positionInViewMeters
            ViewDirection.BA -> length - positionInViewMeters
        }
    }

    private fun railLength(topology: TopologyState, railId: java.util.UUID): Double? {
        val projection = topology.railProjection[railId]?.singleOrNull() ?: return null
        return maxOf(projection.railStartAbMeters, projection.railEndAbMeters)
    }

    private fun rejected(
        reason: FrozenMissionRouteRejection,
        detail: String,
        selectionRejection: FrozenRouteSelectionResult.Rejected? = null
    ) = FrozenMissionRouteResult.Rejected(reason, detail, selectionRejection)

    private const val EPSILON = 1.0e-6
    private const val POSITION_TOLERANCE_METERS = 0.5
}
