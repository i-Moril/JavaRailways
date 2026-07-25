package work.art1st.mineRails.basic.api.ato.mission

import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.route.PlannedRoute
import work.art1st.mineRails.basic.route.PlannedRouteCompilationResult
import work.art1st.mineRails.basic.route.PlannedRouteCompiler
import work.art1st.mineRails.basic.route.RouteBuildResult
import work.art1st.mineRails.basic.route.RoutePlanner
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.api.util.annotation.MainThread
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
    val requiredStartDirection: ViewDirection?
) {
    constructor(
        id: String,
        revision: Long,
        origin: WayPointRef,
        points: Collection<AtoMissionPoint>,
        requiredStartDirection: ViewDirection? = null
    ) : this(id, revision, origin, immutableCopy(points), requiredStartDirection)

    init {
        require(id.isNotBlank() && id == id.trim())
        require(revision >= 0L)
        require(points.isNotEmpty()) { "AtoMissionRequest must contain at least one point" }
    }

    private companion object {
        fun immutableCopy(source: Collection<AtoMissionPoint>): List<AtoMissionPoint> =
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
    private val routePlanner = RoutePlanner(world)
    private val plannedRouteCompiler = PlannedRouteCompiler(
        world,
        world.basicWorldData.turnoutSafetyDefaults
    )

    @MainThread
    fun resolve(request: AtoMissionRequest): AtoMissionResolutionResult {
        val wayPoints = world.basicWorldData.wayPoints
        if (wayPoints[request.origin.id] == null) {
            return AtoMissionResolutionResult.TargetUnresolved(request.origin)
        }

        val legs = ArrayList<AtoMissionLeg>(request.points.size)
        var from = request.origin
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
                requiredStartDirection = request.requiredStartDirection.takeIf { index == 0 }
            )) {
                is RouteBuildResult.Success -> result.route
                is RouteBuildResult.Failure -> return AtoMissionResolutionResult.RouteUnavailable(
                    from,
                    to,
                    result.reason
                )
            }
            val planned = when (val result = plannedRouteCompiler.compile(
                routeId = "${request.id}/leg/$index",
                revision = request.revision,
                route = expanded
            )) {
                is PlannedRouteCompilationResult.Success -> result.route
                is PlannedRouteCompilationResult.Failure -> return AtoMissionResolutionResult.RouteUnavailable(
                    from,
                    to,
                    result.reason
                )
            }
            legs += AtoMissionLeg(index, point, planned)
            from = to
        }
        return AtoMissionResolutionResult.Success(
            AtoMission(request.id, request.revision, request.origin, legs)
        )
    }
}
