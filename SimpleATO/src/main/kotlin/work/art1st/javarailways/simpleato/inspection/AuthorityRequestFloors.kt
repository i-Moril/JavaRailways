package work.art1st.javarailways.simpleato.inspection

import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import kotlin.math.max

internal data class AuthorityRequestFloor(
    val triggerRouteDistanceMeters: Double,
    val minimumEndRouteDistanceMeters: Double,
    val declarationId: String
)

/** Sorted route-local request floors compiled once with one frozen inspection binding. */
internal class CompiledAuthorityRequestFloors private constructor(
    private val route: FrozenRouteBinding,
    private val floors: List<AuthorityRequestFloor>,
    private val prefixMaximumEnds: DoubleArray
) {
    fun adjust(
        currentRoute: FrozenRouteBinding,
        nominalBoundary: RouteBoundary
    ): RouteBoundary {
        require(currentRoute == route) { "authority request floors do not match the current frozen route" }
        val nominal = nominalBoundary.distanceFromRouteStartMeters
        require(nominal <= route.totalLengthMeters) { "nominal authority exceeds the frozen route" }
        if (floors.isEmpty()) return nominalBoundary

        // A request ending exactly at an entry has not entered that resource. Find only triggers
        // strictly behind the nominal boundary, then use the prefix maximum without rescanning them.
        var included = lowerBoundTrigger(nominal)
        if (included == 0) return nominalBoundary
        var adjusted = max(nominal, prefixMaximumEnds[included - 1])

        // A raised boundary may enter a later overlapping window. Each newly crossed floor is
        // visited once; an adjacent floor whose trigger equals the boundary remains untouched.
        while (included < floors.size &&
            floors[included].triggerRouteDistanceMeters < adjusted - EPSILON_METERS
        ) {
            adjusted = max(adjusted, floors[included].minimumEndRouteDistanceMeters)
            included++
        }
        return if (adjusted == nominal) nominalBoundary else RouteBoundary(adjusted)
    }

    private fun lowerBoundTrigger(boundaryMeters: Double): Int {
        var low = 0
        var high = floors.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (floors[middle].triggerRouteDistanceMeters < boundaryMeters - EPSILON_METERS) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    companion object {
        fun compile(
            route: FrozenRouteBinding,
            floors: Collection<AuthorityRequestFloor>
        ): CompiledAuthorityRequestFloors {
            val sorted = floors.sortedWith(
                compareBy(AuthorityRequestFloor::triggerRouteDistanceMeters)
                    .thenBy(AuthorityRequestFloor::declarationId)
            )
            val prefixMaximumEnds = DoubleArray(sorted.size)
            var maximum = 0.0
            sorted.forEachIndexed { index, floor ->
                require(floor.triggerRouteDistanceMeters.isFinite() &&
                    floor.triggerRouteDistanceMeters >= 0.0
                ) { "authority request trigger must be finite and non-negative" }
                require(floor.minimumEndRouteDistanceMeters.isFinite() &&
                    floor.minimumEndRouteDistanceMeters >= floor.triggerRouteDistanceMeters &&
                    floor.minimumEndRouteDistanceMeters <= route.totalLengthMeters
                ) { "authority request floor must be within the frozen route" }
                maximum = max(maximum, floor.minimumEndRouteDistanceMeters)
                prefixMaximumEnds[index] = maximum
            }
            return CompiledAuthorityRequestFloors(
                route,
                java.util.List.copyOf(sorted),
                prefixMaximumEnds
            )
        }

        private const val EPSILON_METERS = 1.0e-9
    }
}
