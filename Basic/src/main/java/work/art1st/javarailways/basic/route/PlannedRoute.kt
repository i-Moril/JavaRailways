package work.art1st.javarailways.basic.route

import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailView
import kotlin.math.abs

/** Conservative look-ahead distance at which SimpleATO should extend its current authority. */
data class RouteAllocationTiming(
    val switchSetupSeconds: Double,
    val reactionTimeSeconds: Double,
    val safetyMarginMeters: Double
) {
    init {
        require(switchSetupSeconds.isFinite() && switchSetupSeconds >= 0.0)
        require(reactionTimeSeconds.isFinite() && reactionTimeSeconds >= 0.0)
        require(safetyMarginMeters.isFinite() && safetyMarginMeters >= 0.0)
    }

    fun triggerDistanceMeters(
        approachSpeedMetersPerSecond: Double,
        serviceDecelerationMetersPerSecondSquared: Double
    ): Double {
        require(approachSpeedMetersPerSecond.isFinite() && approachSpeedMetersPerSecond >= 0.0)
        require(
            serviceDecelerationMetersPerSecondSquared.isFinite() &&
                serviceDecelerationMetersPerSecondSquared > 0.0
        )
        return approachSpeedMetersPerSecond * approachSpeedMetersPerSecond /
            (2.0 * serviceDecelerationMetersPerSecondSquared) +
            approachSpeedMetersPerSecond * (switchSetupSeconds + reactionTimeSeconds) +
            safetyMarginMeters
    }
}

/** Immutable timetable-owned route after Basic has validated its physical traversal. */
data class PlannedRoute(
    val routeId: String,
    val revision: Long,
    val expandedRoute: ExpandedRoute
) {
    init {
        require(routeId.isNotBlank())
        require(revision >= 0L)
    }
}

sealed interface PlannedRouteCompilationResult {
    data class Success(val route: PlannedRoute) : PlannedRouteCompilationResult
    data class Failure(val reason: String) : PlannedRouteCompilationResult
}

data class TurnoutSafetyDefaults(
    val protectionDistanceMeters: Double = DEFAULT_PROTECTION_DISTANCE_METERS,
    val lockingDistanceMeters: Double = DEFAULT_LOCKING_DISTANCE_METERS,
    val decisionLeadTicks: Int = DEFAULT_DECISION_LEAD_TICKS,
    val retryTicks: Int = DEFAULT_RETRY_TICKS
) {
    init {
        require(protectionDistanceMeters.isFinite() && protectionDistanceMeters >= 0.0)
        require(lockingDistanceMeters.isFinite() && lockingDistanceMeters >= 0.0)
        require(lockingDistanceMeters <= protectionDistanceMeters)
        require(decisionLeadTicks > 0)
        require(retryTicks > 0)
    }

    companion object {
        const val DEFAULT_PROTECTION_DISTANCE_METERS = 20.0
        const val DEFAULT_LOCKING_DISTANCE_METERS = 5.0
        const val DEFAULT_DECISION_LEAD_TICKS = 5
        const val DEFAULT_RETRY_TICKS = 5
    }
}

/** Resolves stable route references and validates one already-selected physical traversal. */
class PlannedRouteCompiler(private val world: World) {
    fun compile(
        routeId: String,
        revision: Long,
        route: ExpandedRoute
    ): PlannedRouteCompilationResult {
        require(routeId.isNotBlank())
        require(revision >= 0L)

        val views = route.segments.map { segment ->
            resolve(segment.railViewRef)
                ?: return PlannedRouteCompilationResult.Failure(
                    "rail view no longer exists: ${segment.railViewRef}"
                )
        }
        for ((segment, view) in route.segments.zip(views)) {
            if (segment.endArcLength > view.curve.totalArcLength + EPSILON) {
                return PlannedRouteCompilationResult.Failure(
                    "route segment exceeds rail ${segment.railViewRef.railId}"
                )
            }
        }

        for (index in route.segments.indices) {
            val segment = route.segments[index]
            val current = views[index]
            if (index == route.segments.lastIndex) continue
            val nextSegment = route.segments[index + 1]
            val next = views[index + 1]
            if (abs(segment.endArcLength - current.curve.totalArcLength) > EPSILON ||
                nextSegment.startArcLength > EPSILON
            ) {
                return PlannedRouteCompilationResult.Failure(
                    "route transition does not occur at connected rail endpoints: " +
                        "${segment.railViewRef} -> ${nextSegment.railViewRef}"
                )
            }
            if (current.nextRails.none { it === next }) {
                return PlannedRouteCompilationResult.Failure(
                    "route views are not connected: ${segment.railViewRef} -> ${nextSegment.railViewRef}"
                )
            }
            if (!allowsDirection(next)) {
                return PlannedRouteCompilationResult.Failure(
                    "route selects a direction-restricted branch: ${nextSegment.railViewRef}"
                )
            }
        }
        return PlannedRouteCompilationResult.Success(
            PlannedRoute(routeId, revision, route)
        )
    }

    private fun resolve(ref: RailViewRef): RailView? =
        world.getRail(ref.railId)?.getView(ref.direction)

    private fun allowsDirection(view: RailView): Boolean =
        view.rail.infrastructureMetadata.operating.allowedDirection?.let { it == view.viewDirection } ?: true

    private companion object {
        const val EPSILON = 1.0e-6
    }
}
