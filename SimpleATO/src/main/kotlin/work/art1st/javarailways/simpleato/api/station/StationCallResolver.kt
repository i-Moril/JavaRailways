package work.art1st.javarailways.simpleato.api.station

import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.route.PlannedRoute
import work.art1st.javarailways.basic.route.RouteBuildResult
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.simpleato.routing.OperationalRoutePlanner
import work.art1st.javarailways.simpleato.simpleAtoWorldData

data class StationCallRequest(
    val serviceRunId: String,
    val origin: WayPointRef,
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
    MISSION_REVISION_MISMATCH
}

/** Main-thread resolution of one explicit StationCall into an immutable PlannedRoute target. */
class StationCallResolver(private val world: World) {
    private val routePlanner = OperationalRoutePlanner(world)

    @MainThread
    fun resolve(
        request: StationCallRequest,
        trainLengthMeters: Double? = null,
        requiredStartDirection: ViewDirection? = null
    ): StationCallResolutionResult {
        require(trainLengthMeters == null || trainLengthMeters.isFinite() && trainLengthMeters > 0.0)
        val basicData = world.basicWorldData
        val operationalData = world.simpleAtoWorldData
        val origin = basicData.wayPoints[request.origin.id]
            ?: return failure(
                StationCallResolutionFailure.ORIGIN_UNRESOLVED,
                "station call origin not found: ${request.origin.id}"
            )
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
            displayName = request.call.berthRef.toString(),
            railId = berth.location.rail.id,
            arcLength = berth.location.arcLengthIn(
                work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB
            )
        )
        val expanded = when (val result = routePlanner.buildRoute(
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
        return StationCallResolutionResult.Success(
            ResolvedStationCall(
                request = request,
                plannedRoute = planned,
                stoppingOffsetMetersBefore = request.call.stoppingOffsetMetersBeforeOverride
                    ?: berth.stoppingOffsetMetersBefore,
                positionToleranceMeters = berth.positionToleranceMeters,
                usableLengthMeters = berth.usableLengthMeters,
                releaseDistanceMetersAfter = berth.releaseDistanceMetersAfter,
                doorSide = request.call.doorSideOverride ?: berth.doorSide
            )
        )
    }

    private fun failure(
        reason: StationCallResolutionFailure,
        message: String
    ): StationCallResolutionResult.Failure = StationCallResolutionResult.Failure(reason, message)
}
