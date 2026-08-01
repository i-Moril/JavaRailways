package work.art1st.javarailways.basic.api.station

import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.mission.WayPointRef
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.route.PlannedRoute
import work.art1st.javarailways.basic.route.PlannedRouteCompilationResult
import work.art1st.javarailways.basic.route.PlannedRouteCompiler
import work.art1st.javarailways.basic.route.RouteBuildResult
import work.art1st.javarailways.basic.route.RoutePlanner
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.annotation.MainThread

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
    private val routePlanner = RoutePlanner(world)
    private val routeCompiler = PlannedRouteCompiler(
        world,
        world.basicWorldData.turnoutSafetyDefaults
    )

    @MainThread
    fun resolve(
        request: StationCallRequest,
        trainLengthMeters: Double? = null,
        requiredStartDirection: ViewDirection? = null
    ): StationCallResolutionResult {
        require(trainLengthMeters == null || trainLengthMeters.isFinite() && trainLengthMeters > 0.0)
        val data = world.basicWorldData
        val origin = data.wayPoints[request.origin.id]
            ?: return failure(
                StationCallResolutionFailure.ORIGIN_UNRESOLVED,
                "station call origin not found: ${request.origin.id}"
            )
        val station = data.stations[request.call.stationId]
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
        val berth = data.stations.berth(request.call.berthRef)
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
        val planned = when (val result = routeCompiler.compile(
            routeId = "${request.serviceRunId}/station/${request.call.callId}",
            revision = request.call.missionRevision,
            route = expanded
        )) {
            is PlannedRouteCompilationResult.Success -> result.route
            is PlannedRouteCompilationResult.Failure -> return failure(
                StationCallResolutionFailure.ROUTE_UNAVAILABLE,
                result.reason
            )
        }
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
