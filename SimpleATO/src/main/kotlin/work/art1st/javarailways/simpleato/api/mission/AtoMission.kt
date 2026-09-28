package work.art1st.javarailways.simpleato.api.mission

import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.route.PlannedRoute
import work.art1st.javarailways.basic.route.RouteBuildResult
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.simpleato.routing.OperationalRoutePlanner
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import java.util.Collections

/** Stable reference to a Basic WayPoint without retaining a live rail object. */
data class WayPointRef(val id: String) {
    init {
        require(id.isNotBlank() && id == id.trim())
    }
}

sealed interface AtoMissionPoint {
    val target: WayPointRef

    data class Via(override val target: WayPointRef) : AtoMissionPoint

    data class Stop(
        override val target: WayPointRef,
        val offsetMetersBefore: Double = 0.0
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
    val requiredGoalDirections: List<ViewDirection?>
) {
    constructor(
        id: String,
        revision: Long,
        origin: WayPointRef,
        points: Collection<AtoMissionPoint>,
        requiredStartDirection: ViewDirection? = null,
        requiredGoalDirections: Collection<ViewDirection?> = List(points.size) { null }
    ) : this(
        id,
        revision,
        origin,
        immutableCopy(points),
        requiredStartDirection,
        immutableDirections(requiredGoalDirections)
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

data class AtoMissionLeg(
    val index: Int,
    val point: AtoMissionPoint,
    val plannedRoute: PlannedRoute
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
    val origin: WayPointRef,
    val legs: List<AtoMissionLeg>
) {
    constructor(
        id: String,
        revision: Long,
        origin: WayPointRef,
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
                "AtoMission legs must form one continuous WayPoint chain"
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
        val wayPoints = world.basicWorldData.wayPoints
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
                requiredStartDirection = currentDirection
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
            legs += AtoMissionLeg(index, point, planned)
            from = to
            currentDirection = expanded.segments.last().railViewRef.direction
        }
        return AtoMissionResolutionResult.Success(
            AtoMission(request.id, request.revision, request.origin, legs)
        )
    }
}
