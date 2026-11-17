package work.art1st.javarailways.simpleato.api.mission

import work.art1st.javarailways.basic.api.route.wayPoints
import work.art1st.javarailways.basic.api.route.PlannedRoute
import work.art1st.javarailways.basic.api.route.ExpandedRoute
import work.art1st.javarailways.simpleato.routing.RouteBuildResult
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.simpleato.routing.OperationalRoutePlanner
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import java.util.Collections

/** Exact mission origin; structural outputs retain their published physical anchor. */
sealed interface AtoMissionOrigin {
    val id: String

    sealed interface Physical : AtoMissionOrigin {
        val position: work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
    }

    data class FormationContact(
        val eventInstanceId: String,
        override val position: work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
    ) : Physical {
        init { require(eventInstanceId.isNotBlank()) }
        override val id: String = "formation-contact:${eventInstanceId.length}:$eventInstanceId"
    }

    data class FormationOutput(
        val eventInstanceId: String,
        val outputId: String,
        override val position: work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
    ) : Physical {
        init { require(eventInstanceId.isNotBlank() && outputId.isNotBlank()) }
        override val id: String = "formation:${eventInstanceId.length}:$eventInstanceId:$outputId"
    }

}

/** Exact observed head used when an in-place reversal starts the next operating Mission. */
internal data class ObservedAtoMissionOrigin(
    val operationId: String,
    override val position: work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
) : AtoMissionOrigin.Physical {
    init { require(operationId.isNotBlank()) }
    override val id: String = "observed:${operationId.length}:$operationId"
}

/** Stable reference to a Basic WayPoint without retaining a live rail object. */
data class WayPointRef(override val id: String) : AtoMissionOrigin {
    init {
        require(id.isNotBlank() && id == id.trim())
    }
}

sealed interface AtoMissionPoint {
    val target: WayPointRef

    data class Via(override val target: WayPointRef) : AtoMissionPoint

    data class Stop(
        override val target: WayPointRef,
        val offsetMetersBefore: Double = 0.0,
        /** Domain-classified station stopping policy, independent of arrival tolerance. */
        val stationStop: Boolean = false
    ) : AtoMissionPoint {
        init {
            require(offsetMetersBefore.isFinite() && offsetMetersBefore >= 0.0)
        }
    }
}

/** Immutable unresolved command submitted by a timetable Dispatcher. */
@ConsistentCopyVisibility
data class AtoMissionRequest private constructor(
    val id: String,
    val revision: Long,
    val origin: WayPointRef,
    val points: List<AtoMissionPoint>,
    val requiredStartDirection: ViewDirection?,
    val requiredGoalDirections: List<ViewDirection?>,
    val requireUniquePath: Boolean,
    val pruneNonTargetWaypoints: Boolean
) {
    constructor(
        id: String,
        revision: Long,
        origin: WayPointRef,
        points: Collection<AtoMissionPoint>,
        requiredStartDirection: ViewDirection? = null,
        requiredGoalDirections: Collection<ViewDirection?> = List(points.size) { null },
        requireUniquePath: Boolean = false,
        pruneNonTargetWaypoints: Boolean = false
    ) : this(
        id,
        revision,
        origin,
        immutableCopy(points),
        requiredStartDirection,
        immutableDirections(requiredGoalDirections),
        requireUniquePath,
        pruneNonTargetWaypoints
    )

    init {
        require(id.isNotBlank() && id == id.trim())
        require(revision >= 0L)
        require(points.isNotEmpty()) { "AtoMissionRequest must contain at least one point" }
        require(requiredGoalDirections.size == points.size) {
            "AtoMissionRequest goal directions must match point count"
        }
    }

    private companion object {
        fun immutableCopy(source: Collection<AtoMissionPoint>): List<AtoMissionPoint> =
            Collections.unmodifiableList(ArrayList(source))

        fun immutableDirections(source: Collection<ViewDirection?>): List<ViewDirection?> =
            Collections.unmodifiableList(ArrayList(source))
    }
}

data class AtoMissionLeg @JvmOverloads constructor(
    val index: Int,
    val point: AtoMissionPoint,
    val plannedRoute: PlannedRoute,
    /** Original operating target when structural arrival bounds replace the physical Stop. */
    val operatingPoint: AtoMissionPoint? = null,
    val operatingTargetPrefix: ExpandedRoute? = null
) {
    init {
        require(index >= 0)
        require(plannedRoute.expandedRoute.toWayPointId == point.target.id)
    }
}

/** Immutable resolved mission consumed by one ATO on physics Tick boundaries. */
@ConsistentCopyVisibility
data class AtoMission private constructor(
    val id: String,
    val revision: Long,
    val origin: AtoMissionOrigin,
    val legs: List<AtoMissionLeg>
) {
    constructor(
        id: String,
        revision: Long,
        origin: AtoMissionOrigin,
        legs: Collection<AtoMissionLeg>
    ) : this(id, revision, origin, immutableCopy(legs))

    init {
        require(id.isNotBlank() && id == id.trim())
        require(revision >= 0L)
        require(legs.isNotEmpty()) { "AtoMission must contain at least one leg" }
        require(legs.map(AtoMissionLeg::index) == legs.indices.toList())
        require(legs.all { it.plannedRoute.revision == revision })
        var expectedFrom = origin.id
        legs.forEach { leg ->
            require(leg.plannedRoute.expandedRoute.fromWayPointId == expectedFrom) {
                "AtoMission legs must form one continuous chain from its exact origin"
            }
            expectedFrom = leg.point.target.id
        }
    }

    private companion object {
        fun immutableCopy(source: Collection<AtoMissionLeg>): List<AtoMissionLeg> =
            Collections.unmodifiableList(ArrayList(source))
    }
}

sealed interface AtoMissionResolutionResult {
    data class Success(val mission: AtoMission) : AtoMissionResolutionResult
    data class TargetUnresolved(val target: WayPointRef) : AtoMissionResolutionResult
    data class RouteUnavailable(
        val from: WayPointRef,
        val to: WayPointRef,
        val reason: String
    ) : AtoMissionResolutionResult
}

sealed interface AtoMissionSubmissionResult {
    data class Accepted(val mission: AtoMission) : AtoMissionSubmissionResult
    data class StaleRevision(
        val submittedRevision: Long,
        val latestAcceptedRevision: Long
    ) : AtoMissionSubmissionResult
    data class TargetUnresolved(val target: WayPointRef) : AtoMissionSubmissionResult
    data class RouteUnavailable(
        val from: WayPointRef,
        val to: WayPointRef,
        val reason: String
    ) : AtoMissionSubmissionResult
}

/** Resolves WayPoint references and compiles every mission leg before it reaches physics state. */
class AtoMissionResolver(private val world: World) {
    private val routePlanner = OperationalRoutePlanner(world)

    @MainThread
    fun resolve(request: AtoMissionRequest): AtoMissionResolutionResult {
        val wayPoints = world.wayPoints
        if (wayPoints[request.origin.id] == null) {
            return AtoMissionResolutionResult.TargetUnresolved(request.origin)
        }

        val legs = ArrayList<AtoMissionLeg>(request.points.size)
        var from = request.origin
        var currentDirection = request.requiredStartDirection
        request.points.forEachIndexed { index, point ->
            val to = point.target
            if (wayPoints[to.id] == null) {
                return AtoMissionResolutionResult.TargetUnresolved(to)
            }
            val fromWayPoint = requireNotNull(wayPoints[from.id])
            val toWayPoint = requireNotNull(wayPoints[to.id])
            val expanded = when (val result = routePlanner.buildRoute(
                fromWayPoint,
                toWayPoint,
                requiredGoalDirection = request.requiredGoalDirections[index],
                requiredStartDirection = currentDirection,
                requireUniquePath = request.requireUniquePath,
                pruneNonTargetWaypoints = request.pruneNonTargetWaypoints
            )) {
                is RouteBuildResult.Success -> result.route
                is RouteBuildResult.Failure -> return AtoMissionResolutionResult.RouteUnavailable(
                    from,
                    to,
                    result.reason
                )
            }
            val planned = PlannedRoute(
                routeId = "${request.id}/leg/$index",
                revision = request.revision,
                expandedRoute = expanded
            )
            val resolvedPoint = if (point is AtoMissionPoint.Stop &&
                world.simpleAtoWorldData.destinations[point.target.id]?.role is work.art1st.javarailways.simpleato.api.destination.DestinationRole.StationBerthRole
            ) point.copy(stationStop = true) else point
            legs += AtoMissionLeg(index, resolvedPoint, planned)
            from = to
            currentDirection = expanded.segments.last().railViewRef.direction
        }
        return AtoMissionResolutionResult.Success(
            AtoMission(request.id, request.revision, request.origin, legs)
        )
    }
}
