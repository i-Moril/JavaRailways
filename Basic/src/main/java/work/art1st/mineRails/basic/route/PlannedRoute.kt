package work.art1st.mineRails.basic.route

import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailView
import java.util.Collections
import kotlin.math.abs

/** One selected branch on a planned route, measured from the route start. */
data class TurnoutRequirement(
    val from: RailViewRef,
    val selectedNext: RailViewRef,
    val routeDistanceMeters: Double,
    val protectionDistanceMeters: Double = TurnoutSafetyDefaults.DEFAULT_PROTECTION_DISTANCE_METERS,
    val lockingDistanceMeters: Double = TurnoutSafetyDefaults.DEFAULT_LOCKING_DISTANCE_METERS
) {
    init {
        require(from != selectedNext)
        require(routeDistanceMeters.isFinite() && routeDistanceMeters >= 0.0)
        require(protectionDistanceMeters.isFinite() && protectionDistanceMeters >= 0.0)
        require(lockingDistanceMeters.isFinite() && lockingDistanceMeters >= 0.0)
        require(lockingDistanceMeters <= protectionDistanceMeters)
    }
}

/** Atomic route-allocation unit between two protection/release points. */
@ConsistentCopyVisibility
data class RouteSection private constructor(
    val index: Int,
    val entryProtectionDistanceMeters: Double,
    val releaseDistanceMeters: Double,
    val turnoutRequirements: List<TurnoutRequirement>
) {
    constructor(
        index: Int,
        entryProtectionDistanceMeters: Double,
        releaseDistanceMeters: Double,
        turnoutRequirements: Collection<TurnoutRequirement>
    ) : this(
        index,
        entryProtectionDistanceMeters,
        releaseDistanceMeters,
        immutableSortedCopy(turnoutRequirements)
    )

    init {
        require(index >= 0)
        require(entryProtectionDistanceMeters.isFinite() && entryProtectionDistanceMeters >= 0.0)
        require(releaseDistanceMeters.isFinite() && releaseDistanceMeters > entryProtectionDistanceMeters)
        require(turnoutRequirements.all {
            it.routeDistanceMeters > entryProtectionDistanceMeters - EPSILON &&
                it.routeDistanceMeters <= releaseDistanceMeters + EPSILON
        }) { "turnout requirement lies outside RouteSection $index" }
    }

    private companion object {
        const val EPSILON = 1.0e-6

        fun immutableSortedCopy(source: Collection<TurnoutRequirement>): List<TurnoutRequirement> =
            Collections.unmodifiableList(source.sortedBy(TurnoutRequirement::routeDistanceMeters))
    }
}

/** Conservative distance upstream of a section protection point at which allocation must start. */
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

/** Immutable timetable-owned route plus its atomic allocation sections. */
@ConsistentCopyVisibility
data class PlannedRoute private constructor(
    val routeId: String,
    val revision: Long,
    val expandedRoute: ExpandedRoute,
    val sections: List<RouteSection>
) {
    constructor(
        routeId: String,
        revision: Long,
        expandedRoute: ExpandedRoute,
        sections: Collection<RouteSection>
    ) : this(routeId, revision, expandedRoute, immutableCopy(sections))

    init {
        require(routeId.isNotBlank())
        require(revision >= 0L)
        require(sections.isNotEmpty()) { "PlannedRoute must contain at least one RouteSection" }
        require(sections.map(RouteSection::index) == sections.indices.toList()) {
            "RouteSection indexes must be contiguous from zero"
        }
        require(abs(sections.first().entryProtectionDistanceMeters) <= EPSILON)
        require(sections.zipWithNext().all { (current, next) ->
            abs(current.releaseDistanceMeters - next.entryProtectionDistanceMeters) <= EPSILON
        }) { "RouteSections must be contiguous" }
        require(abs(sections.last().releaseDistanceMeters - expandedRoute.totalLength) <= EPSILON) {
            "RouteSections must cover the complete ExpandedRoute"
        }
    }

    private companion object {
        const val EPSILON = 1.0e-6

        fun immutableCopy(source: Collection<RouteSection>): List<RouteSection> =
            Collections.unmodifiableList(ArrayList(source))
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

/** Resolves stable route references and compiles turnout selections into atomic sections. */
class PlannedRouteCompiler(
    private val world: World,
    private val turnoutDefaults: TurnoutSafetyDefaults = TurnoutSafetyDefaults()
) {
    fun compile(
        routeId: String,
        revision: Long,
        route: ExpandedRoute,
        protectionPointDistancesMeters: Collection<Double> = emptyList()
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

        val requirements = ArrayList<TurnoutRequirement>()
        var routeDistance = 0.0
        for (index in route.segments.indices) {
            val segment = route.segments[index]
            val current = views[index]
            routeDistance += segment.length
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
            val canonicalTurnout = when {
                current.nextRails.size > 1 -> current to next
                next.reversed.nextRails.size > 1 &&
                    next.reversed.nextRails.any { it === current.reversed } ->
                    next.reversed to current.reversed
                else -> null
            }
            if (canonicalTurnout != null) {
                val (stemToJoint, selectedBranch) = canonicalTurnout
                val endpointMetadata = when (stemToJoint.viewDirection) {
                    work.art1st.mineRails.core.api.physics.rail.ViewDirection.AB ->
                        stemToJoint.rail.metadata.turnoutAtB
                    work.art1st.mineRails.core.api.physics.rail.ViewDirection.BA ->
                        stemToJoint.rail.metadata.turnoutAtA
                }
                requirements += TurnoutRequirement(
                    from = RailViewRef.from(stemToJoint),
                    selectedNext = RailViewRef.from(selectedBranch),
                    routeDistanceMeters = routeDistance,
                    protectionDistanceMeters = endpointMetadata?.protectionDistanceMeters
                        ?: turnoutDefaults.protectionDistanceMeters,
                    lockingDistanceMeters = endpointMetadata?.lockingDistanceMeters
                        ?: turnoutDefaults.lockingDistanceMeters
                )
            }
        }

        val turnoutProtectionPoints = requirements.asSequence()
            .map(TurnoutRequirement::routeDistanceMeters)
            .filter { it > EPSILON && it < route.totalLength - EPSILON }
            .toList()
        val boundaries = buildBoundaries(
            route.totalLength,
            protectionPointDistancesMeters + turnoutProtectionPoints
        )
            ?: return PlannedRouteCompilationResult.Failure(
                "protection points must be finite and inside the expanded route"
            )
        val sections = boundaries.zipWithNext().mapIndexed { index, (entry, release) ->
            RouteSection(
                index = index,
                entryProtectionDistanceMeters = entry,
                releaseDistanceMeters = release,
                turnoutRequirements = requirements.filter { requirement ->
                    requirement.routeDistanceMeters > entry + EPSILON &&
                        requirement.routeDistanceMeters <= release + EPSILON
                }
            )
        }
        return PlannedRouteCompilationResult.Success(
            PlannedRoute(routeId, revision, route, sections)
        )
    }

    private fun resolve(ref: RailViewRef): RailView? =
        world.getRail(ref.railId)?.getView(ref.direction)

    private fun allowsDirection(view: RailView): Boolean =
        view.rail.metadata.allowedDirection?.let { it == view.viewDirection } ?: true

    private fun buildBoundaries(totalLength: Double, requested: Collection<Double>): List<Double>? {
        val points = ArrayList<Double>(requested.size + 2)
        points += 0.0
        requested.forEach { point ->
            if (!point.isFinite() || point <= 0.0 || point >= totalLength) return null
            points += point
        }
        points += totalLength
        val sorted = points.distinct().sorted()
        return sorted.takeIf { boundaries ->
            boundaries.zipWithNext().all { (first, second) -> second - first > EPSILON }
        }
    }

    private companion object {
        const val EPSILON = 1.0e-6
    }
}
