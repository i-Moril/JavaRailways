package work.art1st.javarailways.simpleato.api.station

import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.simpleato.api.mission.AtoMissionOrigin
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.route.PlannedRoute
import work.art1st.javarailways.basic.api.route.ExpandedRoute
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.timetable.retainedRouteFailure
import work.art1st.javarailways.simpleato.routing.RouteBuildResult
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.simpleato.routing.OperationalRoutePlanner
import work.art1st.javarailways.simpleato.simpleAtoWorldData

data class StationCallRequest(
    val serviceRunId: String,
    val origin: AtoMissionOrigin,
    val call: StationCall
) {
    init {
        require(serviceRunId.isNotBlank() && serviceRunId == serviceRunId.trim())
    }
}

data class ResolvedStationCall(
    val request: StationCallRequest,
    val plannedRoute: PlannedRoute,
    val stoppingOffsetMetersBefore: Double,
    val positionToleranceMeters: Double,
    val usableLengthMeters: Double?,
    val releaseDistanceMetersAfter: Double,
    val doorSide: DoorSide
) {
    init {
        require(plannedRoute.revision == request.call.missionRevision)
        require(stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
        require(positionToleranceMeters.isFinite() && positionToleranceMeters > 0.0)
        require(usableLengthMeters == null || usableLengthMeters.isFinite() && usableLengthMeters > 0.0)
        require(releaseDistanceMetersAfter.isFinite() && releaseDistanceMetersAfter >= 0.0)
    }

    val call: StationCall
        get() = request.call

    val berthRef: StationBerthRef
        get() = call.berthRef
}

sealed interface StationCallResolutionResult {
    data class Success(val resolved: ResolvedStationCall) : StationCallResolutionResult
    data class Failure(val reason: StationCallResolutionFailure, val message: String) :
        StationCallResolutionResult
}

enum class StationCallResolutionFailure {
    ORIGIN_UNRESOLVED,
    STATION_UNRESOLVED,
    BERTH_UNRESOLVED,
    BERTH_REMOVED,
    BERTH_DIRECTION_UNAVAILABLE,
    ROUTE_UNAVAILABLE,
    TRAIN_TOO_LONG,
    POLICY_REJECTED,
    RESOLVER_UNAVAILABLE,
    RESOLVER_ERROR,
    RESOLVER_INVALID_RESULT,
    MISSION_REVISION_MISMATCH
}

/** Main-thread resolution of one explicit StationCall into an immutable PlannedRoute target. */
class StationCallResolver(private val world: World) {
    private val routePlanner = OperationalRoutePlanner(world)

    @MainThread
    fun resolve(
        request: StationCallRequest,
        trainLengthMeters: Double? = null,
        requiredStartDirection: ViewDirection? = null,
        retainedRoute: ExpandedRoute? = null,
        retainedTopology: TopologyState? = null
    ): StationCallResolutionResult {
        require(trainLengthMeters == null || trainLengthMeters.isFinite() && trainLengthMeters > 0.0)
        val basicData = world.basicWorldData
        val operationalData = world.simpleAtoWorldData
        require((retainedRoute == null) == (retainedTopology == null)) { "retained station route requires its captured topology" }
        val origin = when (val source = request.origin) {
            is WayPointRef -> basicData.wayPoints[source.id] ?: return failure(
                StationCallResolutionFailure.ORIGIN_UNRESOLVED,
                "station call origin not found: ${request.origin.id}"
            )
            is AtoMissionOrigin.Physical -> {
                if (retainedRoute == null || trainLengthMeters == null) return failure(
                    StationCallResolutionFailure.ORIGIN_UNRESOLVED,
                    "physical station origin requires a retained route, topology and actual train length"
                )
                if (requiredStartDirection != null && requiredStartDirection != source.position.facing ||
                    retainedRoute.segments.first().railViewRef.direction != source.position.facing) return failure(
                    StationCallResolutionFailure.ROUTE_UNAVAILABLE, "physical station origin direction changed"
                )
                // Endpoint descriptor only: physical origins are never registered as authored markers.
                WayPoint(source.id, source.position.railId, source.position.arcLengthInAbMeters)
            }
        }
        val station = operationalData.stations[request.call.stationId]
            ?: return failure(
                StationCallResolutionFailure.STATION_UNRESOLVED,
                "station not found: ${request.call.stationId}"
            )
        if (request.call.berthRef !in station.berthRefs) {
            return failure(
                StationCallResolutionFailure.BERTH_UNRESOLVED,
                "berth ${request.call.berthRef} does not belong to station ${station.id}"
            )
        }
        val berth = operationalData.stations.berth(request.call.berthRef)
            ?: return failure(
                StationCallResolutionFailure.BERTH_UNRESOLVED,
                "berth not found: ${request.call.berthRef}"
            )
        if (berth.lifecycleState != RailObjectLifecycleState.ACTIVE) {
            return failure(
                StationCallResolutionFailure.BERTH_REMOVED,
                "berth is removed: ${request.call.berthRef}"
            )
        }
        if (trainLengthMeters != null && berth.usableLengthMeters != null &&
            trainLengthMeters > berth.usableLengthMeters
        ) {
            return failure(
                StationCallResolutionFailure.TRAIN_TOO_LONG,
                "train length $trainLengthMeters exceeds berth ${request.call.berthRef} " +
                    "usable length ${berth.usableLengthMeters}"
            )
        }

        val target = WayPoint(
            id = "station-call:${request.call.callId}:${request.call.berthRef}",
            railId = berth.location.rail.id,
            arcLength = berth.location.arcLengthIn(
                work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB
            )
        )
        val expanded = if (retainedRoute != null) {
            retainedRouteFailure(world, retainedRoute, requireNotNull(retainedTopology), origin, target,
                requiredStartDirection, berth.approachDirection)?.let {
                return failure(StationCallResolutionFailure.ROUTE_UNAVAILABLE, it)
            }
            retainedRoute
        } else when (val result = routePlanner.buildRoute(
            origin,
            target,
            requiredGoalDirection = berth.approachDirection,
            requiredStartDirection = requiredStartDirection
        )) {
            is RouteBuildResult.Success -> result.route
            is RouteBuildResult.Failure -> {
                val reachableWithoutDirection = berth.approachDirection != null &&
                    routePlanner.buildRoute(
                        origin,
                        target,
                        requiredStartDirection = requiredStartDirection
                    ) is RouteBuildResult.Success
                val type = if (reachableWithoutDirection) {
                    StationCallResolutionFailure.BERTH_DIRECTION_UNAVAILABLE
                } else {
                    StationCallResolutionFailure.ROUTE_UNAVAILABLE
                }
                return failure(type, result.reason)
            }
        }
        val planned = PlannedRoute(
            routeId = "${request.serviceRunId}/station/${request.call.callId}",
            revision = request.call.missionRevision,
            expandedRoute = expanded
        )
        val defaults = ResolvedStationCall(
            request = request,
            plannedRoute = planned,
            stoppingOffsetMetersBefore = request.call.stoppingOffsetMetersBeforeOverride
                ?: berth.stoppingOffsetMetersBefore,
            positionToleranceMeters = berth.positionToleranceMeters,
            usableLengthMeters = berth.usableLengthMeters,
            releaseDistanceMetersAfter = berth.releaseDistanceMetersAfter,
            doorSide = request.call.doorSideOverride ?: berth.doorSide
        )
        return operationalData.stationCallResolvers.resolve(StationCallResolutionContext(
            defaults,
            StationCallBerthSnapshot(berth.berthRef, berth.location.rail.id,
                berth.location.arcLengthIn(ViewDirection.AB), berth.approachDirection),
            trainLengthMeters, requiredStartDirection, retainedTopology ?: world.topologyState
        ))
    }

    private fun failure(
        reason: StationCallResolutionFailure,
        message: String
    ): StationCallResolutionResult.Failure = StationCallResolutionResult.Failure(reason, message)
}
