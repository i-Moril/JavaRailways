package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.DirectedRouteBoundary
import work.art1st.javarailways.basic.api.route.ProtectedRouteWindow
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ResolvedProtectionWindow(
    val request: ProtectedRouteWindow,
    val region: ResolvedProtectionRegion
)

internal enum class ProtectionRegionWindowRejection {
    STALE_TOPOLOGY,
    MISSING_REGION,
    STALE_REGION,
    INVALID_ROUTE
}

internal sealed interface ProtectionRegionWindowResolution {
    data class Resolved(val window: ResolvedProtectionWindow?) : ProtectionRegionWindowResolution

    data class Rejected(
        val reason: ProtectionRegionWindowRejection,
        val detail: String
    ) : ProtectionRegionWindowResolution {
        init {
            require(detail.isNotBlank())
        }
    }
}

/** Validates one authored entry/exit window against one route and one revision-bound topology derivation. */
internal object ProtectionRegionWindowResolver {
    fun resolve(
        route: RouteSnapshot,
        topology: ProtectionRegionTopology,
        resolved: ResolvedProtectionWindow
    ): ProtectionRegionWindowResolution {
        val traversal = when (
            val projection = TopologyRouteProjector.project(
                route = route,
                topology = topology.topologyState,
                expectedTopologyRevision = topology.topologyRevision
            )
        ) {
            is TopologyRouteProjectionResult.Projected -> projection.route.occurrences
            is TopologyRouteProjectionResult.Rejected -> {
                val reason = when (projection.reason) {
                    TopologyRouteProjectionRejection.STALE_TOPOLOGY ->
                        ProtectionRegionWindowRejection.STALE_TOPOLOGY
                    TopologyRouteProjectionRejection.INVALID_ROUTE ->
                        ProtectionRegionWindowRejection.INVALID_ROUTE
                }
                return ProtectionRegionWindowResolution.Rejected(reason, projection.detail)
            }
        }
        validateBoundary(
            resolved.request.entry,
            resolved.region,
            traversal,
            entering = true
        )?.let { return rejected(it) }
        validateBoundary(
            resolved.request.exit,
            resolved.region,
            traversal,
            entering = false
        )?.let { return rejected(it) }
        validateRoutePoint(resolved.request.exitClearance.clearUntil, traversal)?.let {
            return rejected("exit clearance ${resolved.request.exitClearance.clearUntil.boundaryId}: $it")
        }
        val intervals = interiorIntervals(resolved.region, traversal)
        if (intervals.size != 1) {
            return rejected(
                "route must traverse region ${resolved.request.region.regionId} exactly once; " +
                    "found ${intervals.size} interior intervals"
            )
        }
        val interior = intervals.single()
        if (!samePosition(interior.startMeters, resolved.request.entry.routeDistanceMeters) ||
            !samePosition(interior.endMeters, resolved.request.exit.routeDistanceMeters)
        ) {
            return rejected(
                "region ${resolved.request.region.regionId} route interior " +
                    "${interior.startMeters}..${interior.endMeters} does not match declared window " +
                    "${resolved.request.entry.routeDistanceMeters}.." +
                    resolved.request.exit.routeDistanceMeters
            )
        }
        return ProtectionRegionWindowResolution.Resolved(resolved)
    }

    private fun validateBoundary(
        requested: DirectedRouteBoundary,
        region: ResolvedProtectionRegion,
        traversal: List<ProjectedRouteOccurrence>,
        entering: Boolean
    ): String? {
        val authored = region.definition.boundaries.singleOrNull { it.boundaryId == requested.boundaryId }
            ?: return "boundary ${requested.boundaryId} does not belong to region " +
                region.definition.region.regionId
        if (authored.position.railId != requested.position.railId ||
            !samePosition(authored.position.arcLengthInAbMeters, requested.position.arcLengthInAbMeters)
        ) {
            return "boundary ${requested.boundaryId} does not match its authored rail position"
        }
        val routePoint = validateRoutePoint(requested, traversal)
        if (routePoint != null) return "boundary ${requested.boundaryId}: $routePoint"
        val expectedFacing = if (entering) {
            authored.position.facing
        } else {
            authored.position.facing.opposite()
        }
        if (requested.position.facing != expectedFacing) {
            val action = if (entering) "enter" else "exit"
            return "boundary ${requested.boundaryId} does not $action region in the required direction"
        }
        return null
    }

    private fun validateRoutePoint(
        boundary: DirectedRouteBoundary,
        traversal: List<ProjectedRouteOccurrence>
    ): String? {
        val candidates = traversal.filter { slice ->
            slice.segment.railId == boundary.position.railId &&
                boundary.routeDistanceMeters >= slice.routeStartMeters - Curve.TOLERANCE &&
                boundary.routeDistanceMeters <= slice.routeEndMeters + Curve.TOLERANCE
        }
        val matching = candidates.filter { slice ->
            val localOffset = (boundary.routeDistanceMeters - slice.routeStartMeters)
                .coerceIn(0.0, slice.segment.lengthMeters)
            val canonicalArc = when (slice.segment.facing) {
                ViewDirection.AB -> slice.canonicalStartAbMeters + localOffset
                ViewDirection.BA -> slice.canonicalStartAbMeters - localOffset
            }
            samePosition(canonicalArc, boundary.position.arcLengthInAbMeters) &&
                slice.segment.facing == boundary.position.facing
        }
        return when (matching.size) {
            1 -> null
            0 -> "declared route distance, rail position, and facing do not identify one route point"
            else -> "route point is ambiguous on a repeated rail"
        }
    }

    private fun interiorIntervals(
        region: ResolvedProtectionRegion,
        traversal: List<ProjectedRouteOccurrence>
    ): List<RouteDistanceInterval> {
        val interiorByRail = region.interior.groupBy(ProtectionRegionInteriorSlice::railId)
        val intersections = ArrayList<RouteDistanceInterval>()
        for (routeSlice in traversal) {
            val canonicalStart = routeSlice.canonicalStartAbMeters
            val canonicalEnd = routeSlice.canonicalEndAbMeters
            val routeLow = min(canonicalStart, canonicalEnd)
            val routeHigh = max(canonicalStart, canonicalEnd)
            for (interior in interiorByRail[routeSlice.segment.railId].orEmpty()) {
                val overlapLow = max(routeLow, interior.startAbMeters)
                val overlapHigh = min(routeHigh, interior.endAbMeters)
                if (overlapHigh - overlapLow <= Curve.TOLERANCE) continue
                val (start, end) = when (routeSlice.segment.facing) {
                    ViewDirection.AB ->
                        routeSlice.routeStartMeters + overlapLow - canonicalStart to
                            routeSlice.routeStartMeters + overlapHigh - canonicalStart
                    ViewDirection.BA ->
                        routeSlice.routeStartMeters + canonicalStart - overlapHigh to
                            routeSlice.routeStartMeters + canonicalStart - overlapLow
                }
                intersections += RouteDistanceInterval(start, end)
            }
        }
        if (intersections.isEmpty()) return emptyList()
        val merged = ArrayList<RouteDistanceInterval>()
        for (interval in intersections.sortedBy(RouteDistanceInterval::startMeters)) {
            val previous = merged.lastOrNull()
            if (previous != null && interval.startMeters <= previous.endMeters + Curve.TOLERANCE) {
                merged[merged.lastIndex] = RouteDistanceInterval(
                    previous.startMeters,
                    max(previous.endMeters, interval.endMeters)
                )
            } else {
                merged += interval
            }
        }
        return merged
    }

    private fun rejected(detail: String) = ProtectionRegionWindowResolution.Rejected(
        ProtectionRegionWindowRejection.INVALID_ROUTE,
        detail
    )

    private fun samePosition(first: Double, second: Double): Boolean =
        abs(first - second) <= Curve.TOLERANCE

    private fun ViewDirection.opposite(): ViewDirection = when (this) {
        ViewDirection.AB -> ViewDirection.BA
        ViewDirection.BA -> ViewDirection.AB
    }

    private data class RouteDistanceInterval(
        val startMeters: Double,
        val endMeters: Double
    )

}
