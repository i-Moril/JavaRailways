package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.route.planning.OperatingDirectionOverlay

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.route.OperationalRouteLocation
import work.art1st.javarailways.basic.route.TopologyRoutePlanner
import work.art1st.javarailways.basic.route.TopologyRoutePlanningRejection
import work.art1st.javarailways.basic.route.TopologyRoutePlanningResult
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState

/** One observation-scoped train lookup shared by all route selections in a Dispatcher pass. */
internal class FrozenRouteSelectionContext(
    val observation: RailwayObservation
) {
    private val trainsById: Map<String, RailwayTrainObservation> =
        observation.trains.associateBy(RailwayTrainObservation::trainId).also { indexed ->
            require(indexed.size == observation.trains.size) {
                "observation contains duplicate stable train IDs"
            }
        }

    fun train(trainId: String): RailwayTrainObservation? = trainsById[trainId]
}

/** One coherent internal request to freeze an operational route from a published train tail. */
internal data class FrozenRouteSelectionInput(
    val topology: TopologyState,
    val context: FrozenRouteSelectionContext,
    val operatingDirections: OperatingDirectionOverlay,
    val planningRestrictions: RoutePlanningRestrictionRuntime,
    val trainId: String,
    val target: OperationalRouteLocation,
    val routeId: String,
    val routeRevision: Long
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(routeRevision >= 0L) { "routeRevision must be non-negative" }
    }

    val observation: RailwayObservation
        get() = context.observation
}

internal enum class FrozenRouteSelectionRejection {
    STALE_OBSERVATION,
    STALE_OPERATING_DIRECTIONS,
    TRAIN_NOT_FOUND,
    INVALID_TRAIN,
    PLANNER_REJECTED
}

internal sealed interface FrozenRouteSelectionResult {
    data class Selected(
        val trainId: String,
        val observationStamp: RailwayStateStamp,
        val route: RouteSnapshot
    ) : FrozenRouteSelectionResult

    data class Rejected(
        val reason: FrozenRouteSelectionRejection,
        val detail: String,
        val plannerReason: TopologyRoutePlanningRejection? = null
    ) : FrozenRouteSelectionResult {
        init {
            require(detail.isNotBlank())
            require((reason == FrozenRouteSelectionRejection.PLANNER_REJECTED) == (plannerReason != null)) {
                "plannerReason must be present exactly for PLANNER_REJECTED"
            }
        }
    }
}

/**
 * Binds one existing topology search to one coherent authoritative train observation.
 *
 * This seam performs no route search of its own. It selects the named train from [RailwayObservation],
 * derives the start from that train's tail and delegates exactly once to [TopologyRoutePlanner].
 */
internal object FrozenRouteSelector {
    fun select(input: FrozenRouteSelectionInput): FrozenRouteSelectionResult {
        val topologyRevision = input.topology.revision
        if (input.observation.stamp.topologyRevision != topologyRevision) {
            return rejected(
                FrozenRouteSelectionRejection.STALE_OBSERVATION,
                "observation topology revision ${input.observation.stamp.topologyRevision} " +
                    "does not match $topologyRevision"
            )
        }
        if (input.operatingDirections.topologyRevision != topologyRevision) {
            return rejected(
                FrozenRouteSelectionRejection.STALE_OPERATING_DIRECTIONS,
                "operating-direction topology revision ${input.operatingDirections.topologyRevision} " +
                    "does not match $topologyRevision"
            )
        }

        val train = input.context.train(input.trainId)
            ?: return rejected(
                FrozenRouteSelectionRejection.TRAIN_NOT_FOUND,
                "train ${input.trainId} is absent from observation epoch ${input.observation.stamp.physicsEpoch}"
            )
        if (train.derailed) {
            return rejected(
                FrozenRouteSelectionRejection.INVALID_TRAIN,
                "train ${input.trainId} is derailed in observation epoch ${input.observation.stamp.physicsEpoch}"
            )
        }
        val start = OperationalRouteLocation(
            train.tail.railId,
            train.tail.arcLengthInAbMeters
        )
        if (!TopologyRoutePlanner.isValidLocation(input.topology, start)) {
            return rejected(
                FrozenRouteSelectionRejection.INVALID_TRAIN,
                "train ${input.trainId} tail is not valid in topology revision $topologyRevision"
            )
        }

        val planned = OperationalRoutePlanner.plan(
            topology = input.topology,
            operatingDirections = input.operatingDirections,
            planningRestrictions = input.planningRestrictions,
            start = start,
            end = input.target,
            routeId = input.routeId,
            routeRevision = input.routeRevision,
            startFacing = train.tail.facing
        )
        return when (planned) {
            is TopologyRoutePlanningResult.Planned -> FrozenRouteSelectionResult.Selected(
                trainId = train.trainId,
                observationStamp = input.observation.stamp,
                route = planned.route
            )

            is TopologyRoutePlanningResult.Rejected -> FrozenRouteSelectionResult.Rejected(
                reason = FrozenRouteSelectionRejection.PLANNER_REJECTED,
                detail = planned.detail,
                plannerReason = planned.reason
            )
        }
    }

    private fun rejected(
        reason: FrozenRouteSelectionRejection,
        detail: String
    ): FrozenRouteSelectionResult.Rejected = FrozenRouteSelectionResult.Rejected(reason, detail)
}
