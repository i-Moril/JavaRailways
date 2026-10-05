package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.simpleato.api.mission.AtoMission
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import kotlin.math.abs

private const val POSITION_TOLERANCE_METERS = 0.5

/**
 * One Dispatcher mission adapted to Basic's tail-started admission coordinate.
 *
 * [headDistanceFromRouteStartMeters] is the exact published body length. The selected route starts
 * at the observed tail and contains only the part of the mission after
 * [missionHeadProgressAtSelectionMeters], so [routeProgressMeters] maps later mission progress onto
 * the same immutable route coordinate without projecting the train again.
 */
internal data class FrozenMissionRoute(
    val selection: FrozenRouteSelectionResult.Selected,
    val headDistanceFromRouteStartMeters: Double,
    val missionHeadProgressAtSelectionMeters: Double
) {
    fun routeProgressMeters(missionHeadProgressMeters: Double): Double {
        require(missionHeadProgressMeters.isFinite()) { "mission progress must be finite" }
        require(missionHeadProgressMeters + POSITION_TOLERANCE_METERS >=
            missionHeadProgressAtSelectionMeters
        ) { "mission progress moved behind the frozen-route selection point" }
        return (headDistanceFromRouteStartMeters +
            missionHeadProgressMeters - missionHeadProgressAtSelectionMeters)
            .coerceIn(0.0, selection.route.totalLengthMeters)
    }

}

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
        if (bodySegments.isEmpty()) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_BODY_TRAVERSAL,
                "train $trainId has no published body traversal"
            )
        }

        val remainingMission = trim(missionRoute.segments, missionHeadProgressMeters)
        if (remainingMission.isNotEmpty() && !joins(bodySegments.last(), remainingMission.first(), topology)) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "mission ${mission.id}@${mission.revision} does not continue from train $trainId physical body endpoint"
            )
        }

        val bodyLength = bodySegments.sumOf(RouteSegmentSnapshot::lengthMeters)
        val route = RouteSnapshot(
            routeId = mission.id,
            revision = mission.revision,
            topologyRevision = topology.revision,
            segments = mergeAdjacent(bodySegments + remainingMission)
        )
        return FrozenMissionRouteResult.Frozen(
            FrozenMissionRoute(
                FrozenRouteSelectionResult.Selected(train.trainId, observation.stamp, route),
                bodyLength,
                missionHeadProgressMeters
            )
        )
    }

    /**
     * Rebuilds an admitted route from the reversed train body without selecting a new mission.
     *
     * The operating-route identity is retained and only its revision advances. This gives Basic
     * one tail-to-head coordinate in which it can reposition the retained grant's release cursor;
     * a later reverse-direction mission is appended through the ordinary continuation path.
     */
    fun rebaseBodyAfterReversal(
        topology: TopologyState,
        context: FrozenRouteSelectionContext,
        existing: FrozenMissionRoute
    ): FrozenMissionRouteResult {
        val observation = context.observation
        val committed = existing.selection
        if (observation.stamp.worldId != committed.observationStamp.worldId ||
            observation.stamp.topologyRevision != topology.revision
        ) {
            return rejected(
                FrozenMissionRouteRejection.STALE_OBSERVATION,
                "reversal observation must keep the committed world and topology ${topology.revision}"
            )
        }
        val train = context.train(committed.trainId)
            ?: return rejected(
                FrozenMissionRouteRejection.TRAIN_NOT_FOUND,
                "train ${committed.trainId} is absent from observation epoch ${observation.stamp.physicsEpoch}"
            )
        if (train.derailed) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_TRAIN,
                "train ${committed.trainId} is derailed in observation epoch ${observation.stamp.physicsEpoch}"
            )
        }
        if (committed.route.revision == Long.MAX_VALUE) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "operating route revision overflow"
            )
        }
        val bodySegments = train.bodyTraversal.map { segment ->
            toRouteSegment(topology, segment)
                ?: return rejected(
                    FrozenMissionRouteRejection.INVALID_BODY_TRAVERSAL,
                    "train ${committed.trainId} body references an ambiguous or missing rail ${segment.railId}"
                )
        }
        if (bodySegments.isEmpty()) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_BODY_TRAVERSAL,
                "train ${committed.trainId} has no published body traversal"
            )
        }

        val mergedBody = mergeAdjacent(bodySegments)
        val bodyLength = mergedBody.sumOf(RouteSegmentSnapshot::lengthMeters)
        val route = RouteSnapshot(
            routeId = committed.route.routeId,
            revision = committed.route.revision + 1L,
            topologyRevision = topology.revision,
            segments = mergedBody
        )
        return FrozenMissionRouteResult.Frozen(
            FrozenMissionRoute(
                FrozenRouteSelectionResult.Selected(train.trainId, observation.stamp, route),
                bodyLength,
                0.0
            )
        )
    }

    /**
     * Appends the next forward mission to an already admitted operating route.
     *
     * The committed segment list is copied verbatim. An ordinary mission whose complete route
     * starts at the prior route end is appended unchanged. Otherwise, the suffix at
     * [missionHeadProgressMeters] must start there; this is the reversal case where the prefix from
     * the operational origin to the new head is already represented by the body-only route.
     */
    fun append(
        topology: TopologyState,
        context: FrozenRouteSelectionContext,
        existing: FrozenMissionRoute,
        mission: AtoMission,
        missionHeadProgressMeters: Double
    ): FrozenMissionRouteResult {
        val observation = context.observation
        val committed = existing.selection
        if (observation.stamp.worldId != committed.observationStamp.worldId ||
            observation.stamp.topologyRevision != topology.revision
        ) {
            return rejected(
                FrozenMissionRouteRejection.STALE_OBSERVATION,
                "continuation observation must keep the committed world and topology ${topology.revision}"
            )
        }
        val train = context.train(committed.trainId)
            ?: return rejected(
                FrozenMissionRouteRejection.TRAIN_NOT_FOUND,
                "train ${committed.trainId} is absent from observation epoch ${observation.stamp.physicsEpoch}"
            )
        if (train.derailed) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_TRAIN,
                "train ${committed.trainId} is derailed in observation epoch ${observation.stamp.physicsEpoch}"
            )
        }
        if (committed.route.revision == Long.MAX_VALUE) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "operating route revision overflow"
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
                "train ${committed.trainId} progress $missionHeadProgressMeters is outside mission ${mission.id}@${mission.revision}"
            )
        }
        val committedEnd = committed.route.segments.lastOrNull()
        if (committedEnd == null || missionRoute.segments.isEmpty()) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "mission ${mission.id}@${mission.revision} does not continue forward from the committed route end"
            )
        }
        val continuationStartProgress = if (joins(
                committedEnd,
                missionRoute.segments.first(),
                topology
            )) {
            0.0
        } else {
            missionHeadProgressMeters
        }
        val continuation = trim(missionRoute.segments, continuationStartProgress)
        if (continuation.isEmpty() || !joins(committedEnd, continuation.first(), topology)) {
            return rejected(
                FrozenMissionRouteRejection.INVALID_MISSION,
                "mission ${mission.id}@${mission.revision} does not continue forward from the committed route end"
            )
        }

        val committedLength = committed.route.totalLengthMeters
        val appended = RouteSnapshot(
            routeId = committed.route.routeId,
            revision = committed.route.revision + 1L,
            topologyRevision = topology.revision,
            segments = committed.route.segments + continuation
        )
        return FrozenMissionRouteResult.Frozen(
            FrozenMissionRoute(
                FrozenRouteSelectionResult.Selected(train.trainId, observation.stamp, appended),
                committedLength,
                continuationStartProgress
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

    private fun joins(
        bodyEnd: RouteSegmentSnapshot,
        missionStart: RouteSegmentSnapshot,
        topology: TopologyState,
    ): Boolean {
        if (bodyEnd.railId == missionStart.railId) {
            return bodyEnd.facing == missionStart.facing &&
                abs(bodyEnd.endArcLengthMeters - missionStart.startArcLengthMeters) <= POSITION_TOLERANCE_METERS
        }
        val bodyRailLength = railLength(topology, bodyEnd.railId) ?: return false
        return abs(bodyEnd.endArcLengthMeters - bodyRailLength) <= POSITION_TOLERANCE_METERS &&
            missionStart.startArcLengthMeters <= POSITION_TOLERANCE_METERS
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
}
