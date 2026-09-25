package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.ato.mission.AtoMission
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import kotlin.math.abs

/**
 * One Dispatcher mission adapted to Basic's tail-started admission coordinate.
 *
 * [headDistanceFromRouteStartMeters] is the exact published body length. Mission progress remains
 * head-based, so adding this offset maps it onto the frozen route submitted to Basic.
 */
internal data class FrozenMissionRoute(
    val selection: FrozenRouteSelectionResult.Selected,
    val headDistanceFromRouteStartMeters: Double
)

internal enum class FrozenMissionRouteRejection {
    STALE_OBSERVATION,
    TRAIN_NOT_FOUND,
    INVALID_TRAIN,
    INVALID_MISSION,
    INVALID_BODY_TRAVERSAL
}

internal sealed interface FrozenMissionRouteResult {
    data class Frozen(val route: FrozenMissionRoute) : FrozenMissionRouteResult

    data class Rejected(
        val reason: FrozenMissionRouteRejection,
        val detail: String
    ) : FrozenMissionRouteResult {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Prepends the observed physical train body to the route already selected for the mission.
 *
 * This adapter never searches the topology. The mission resolver owns the head-forward route, the
 * Dispatcher supplies its already-computed monotonic head progress, and Core owns the actual ordered
 * tail-to-head placement. Basic remains responsible for validating the resulting traversal against
 * the immutable topology before it grants any resource.
 */
internal object FrozenMissionRouteAssembler {
    fun assemble(
        topology: TopologyState,
        context: FrozenRouteSelectionContext,
        trainId: String,
        mission: AtoMission,
        missionHeadProgressMeters: Double
    ): FrozenMissionRouteResult {
        val observation = context.observation
        if (observation.stamp.topologyRevision != topology.revision) {
            return rejected(
                FrozenMissionRouteRejection.STALE_OBSERVATION,
                "observation topology revision ${observation.stamp.topologyRevision} does not match ${topology.revision}"
            )
        }
        val train = context.train(trainId)
            ?: return rejected(
                FrozenMissionRouteRejection.TRAIN_NOT_FOUND,
                "train $trainId is absent from observation epoch ${observation.stamp.physicsEpoch}"
            )
        if (train.derailed) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_TRAIN,
                "train $trainId is derailed in observation epoch ${observation.stamp.physicsEpoch}"
            )
        }

        val missionRoute = missionRouteSnapshot(topology, mission)
            ?: return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "mission ${mission.id}@${mission.revision} has no valid physical traversal"
            )
        if (!missionHeadProgressMeters.isFinite() || missionHeadProgressMeters < 0.0 ||
            missionHeadProgressMeters > missionRoute.totalLengthMeters + POSITION_TOLERANCE_METERS
        ) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "train $trainId progress $missionHeadProgressMeters is outside mission ${mission.id}@${mission.revision}"
            )
        }

        val bodySegments = train.bodyTraversal.map { segment ->
            toRouteSegment(topology, segment)
                ?: return rejected(
                    FrozenMissionRouteRejection.INVALID_BODY_TRAVERSAL,
                    "train $trainId body references an ambiguous or missing rail ${segment.railId}"
                )
        }
        if (bodySegments.isEmpty() ||
            !matches(bodySegments.first(), train.tail, atStart = true, topology) ||
            !matches(bodySegments.last(), train.head, atStart = false, topology)
        ) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_BODY_TRAVERSAL,
                "train $trainId body traversal does not run from its published tail to head"
            )
        }

        val bodyLength = bodySegments.sumOf(RouteSegmentSnapshot::lengthMeters)
        val route = RouteSnapshot(
            routeId = mission.id,
            revision = mission.revision,
            topologyRevision = topology.revision,
            segments = mergeAdjacent(bodySegments + trim(missionRoute.segments, missionHeadProgressMeters))
        )
        return FrozenMissionRouteResult.Frozen(
            FrozenMissionRoute(
                FrozenRouteSelectionResult.Selected(train.trainId, observation.stamp, route),
                bodyLength
            )
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
        return RouteSnapshot(mission.id, mission.revision, topology.revision, mergeAdjacent(segments))
    }

    private fun toRouteSegment(
        topology: TopologyState,
        segment: RailTraversalSegmentSnapshot
    ): RouteSegmentSnapshot? {
        val length = railLength(topology, segment.railId) ?: return null
        val start = when (segment.facing) {
            ViewDirection.AB -> segment.startArcLengthInAbMeters
            ViewDirection.BA -> length - segment.startArcLengthInAbMeters
        }
        val end = when (segment.facing) {
            ViewDirection.AB -> segment.endArcLengthInAbMeters
            ViewDirection.BA -> length - segment.endArcLengthInAbMeters
        }
        if (start < -POSITION_TOLERANCE_METERS || start > length + POSITION_TOLERANCE_METERS ||
            end < -POSITION_TOLERANCE_METERS || end > length + POSITION_TOLERANCE_METERS ||
            end + POSITION_TOLERANCE_METERS < start
        ) return null
        val normalizedStart = start.coerceIn(0.0, length)
        val normalizedEnd = end.coerceIn(0.0, length)
        if (normalizedEnd < normalizedStart) return null
        return RouteSegmentSnapshot(
            segment.railId,
            segment.facing,
            normalizedStart,
            normalizedEnd
        )
    }

    private fun matches(
        segment: RouteSegmentSnapshot,
        position: RailPositionSnapshot,
        atStart: Boolean,
        topology: TopologyState
    ): Boolean {
        if (segment.railId != position.railId || segment.facing != position.facing) return false
        val expected = positionInView(topology, position) ?: return false
        val actual = if (atStart) segment.startArcLengthMeters else segment.endArcLengthMeters
        return abs(actual - expected) <= POSITION_TOLERANCE_METERS
    }

    private fun positionInView(
        topology: TopologyState,
        position: RailPositionSnapshot
    ): Double? {
        val length = railLength(topology, position.railId) ?: return null
        return when (position.facing) {
            ViewDirection.AB -> position.arcLengthInAbMeters
            ViewDirection.BA -> length - position.arcLengthInAbMeters
        }
    }

    private fun trim(
        segments: List<RouteSegmentSnapshot>,
        distanceMeters: Double
    ): List<RouteSegmentSnapshot> {
        var segmentStart = 0.0
        val result = ArrayList<RouteSegmentSnapshot>()
        segments.forEach { segment ->
            val segmentEnd = segmentStart + segment.lengthMeters
            when {
                segment.lengthMeters <= EPSILON -> {
                    if (segmentStart + EPSILON >= distanceMeters) result += segment
                }
                segmentEnd <= distanceMeters + EPSILON -> Unit
                distanceMeters > segmentStart + EPSILON -> result += segment.copy(
                    startArcLengthMeters = segment.startArcLengthMeters + (distanceMeters - segmentStart)
                )
                else -> result += segment
            }
            segmentStart = segmentEnd
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

    private fun railLength(topology: TopologyState, railId: java.util.UUID): Double? {
        val projection = topology.railProjection[railId]?.singleOrNull() ?: return null
        return abs(projection.railEndAbMeters - projection.railStartAbMeters)
    }

    private fun rejected(
        reason: FrozenMissionRouteRejection,
        detail: String
    ) = FrozenMissionRouteResult.Rejected(reason, detail)

    private const val EPSILON = 1.0e-6
    private const val POSITION_TOLERANCE_METERS = 0.5
}
