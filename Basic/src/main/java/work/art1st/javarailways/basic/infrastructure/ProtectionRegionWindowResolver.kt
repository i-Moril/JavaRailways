package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.DirectedRouteBoundary
import work.art1st.javarailways.basic.api.route.ProtectedRouteWindow
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID
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

/** Validates one authored entry/exit window against one immutable route and region topology snapshot. */
internal object ProtectionRegionWindowResolver {
    fun resolve(
        route: RouteSnapshot,
        topology: ProtectionRegionTopologySnapshot,
        resolved: ResolvedProtectionWindow
    ): ProtectionRegionWindowResolution {
        val traversal = when (val built = buildTraversal(route, topology)) {
            is TraversalBuildResult.Built -> built.slices
            is TraversalBuildResult.Rejected -> return rejected(built.detail)
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
        traversal: List<RouteTraversalSlice>,
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
        traversal: List<RouteTraversalSlice>
    ): String? {
        val candidates = traversal.filter { slice ->
            slice.segment.railId == boundary.position.railId &&
                boundary.routeDistanceMeters >= slice.routeStartMeters - Curve.TOLERANCE &&
                boundary.routeDistanceMeters <= slice.routeEndMeters + Curve.TOLERANCE
        }
        val matching = candidates.filter { slice ->
            val localOffset = (boundary.routeDistanceMeters - slice.routeStartMeters)
                .coerceIn(0.0, slice.segment.lengthMeters)
            val localArc = slice.segment.startArcLengthMeters + localOffset
            val canonicalArc = when (slice.segment.facing) {
                ViewDirection.AB -> localArc
                ViewDirection.BA -> slice.railLengthMeters - localArc
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
        traversal: List<RouteTraversalSlice>
    ): List<RouteDistanceInterval> {
        val interiorByRail = region.interior.groupBy(ProtectionRegionInteriorSlice::railId)
        val intersections = ArrayList<RouteDistanceInterval>()
        for (routeSlice in traversal) {
            val canonicalStart = routeSlice.canonicalStartMeters
            val canonicalEnd = routeSlice.canonicalEndMeters
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

    private fun buildTraversal(
        route: RouteSnapshot,
        topology: ProtectionRegionTopologySnapshot
    ): TraversalBuildResult {
        val slices = ArrayList<RouteTraversalSlice>(route.segments.size)
        var routeDistance = 0.0
        for (segment in route.segments) {
            val rail = topology.rails[segment.railId]
                ?: return TraversalBuildResult.Rejected("route references missing rail ${segment.railId}")
            if (segment.endArcLengthMeters > rail.lengthMeters + Curve.TOLERANCE) {
                return TraversalBuildResult.Rejected("route segment exceeds rail ${segment.railId}")
            }
            slices += RouteTraversalSlice(
                segment = segment,
                railLengthMeters = rail.lengthMeters,
                routeStartMeters = routeDistance,
                routeEndMeters = routeDistance + segment.lengthMeters
            )
            routeDistance += segment.lengthMeters
        }
        for ((current, next) in slices.zipWithNext()) {
            if (!samePosition(current.segment.endArcLengthMeters, current.railLengthMeters) ||
                !samePosition(next.segment.startArcLengthMeters, 0.0)
            ) {
                return TraversalBuildResult.Rejected(
                    "route transition ${current.segment.railId} -> ${next.segment.railId} is not at rail endpoints"
                )
            }
            val rail = topology.rails.getValue(current.segment.railId)
            val connections = when (current.segment.facing) {
                ViewDirection.AB -> rail.connectionsFromB
                ViewDirection.BA -> rail.connectionsFromA
            }
            if (connections.none {
                    it.railId == next.segment.railId &&
                        it.enteringFacing == next.segment.facing
                }
            ) {
                return TraversalBuildResult.Rejected(
                    "route rails are not connected: ${current.segment.railId} -> ${next.segment.railId}"
                )
            }
        }
        return TraversalBuildResult.Built(slices)
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

    private data class RouteTraversalSlice(
        val segment: RouteSegmentSnapshot,
        val railLengthMeters: Double,
        val routeStartMeters: Double,
        val routeEndMeters: Double
    ) {
        val canonicalStartMeters: Double
            get() = when (segment.facing) {
                ViewDirection.AB -> segment.startArcLengthMeters
                ViewDirection.BA -> railLengthMeters - segment.startArcLengthMeters
            }

        val canonicalEndMeters: Double
            get() = when (segment.facing) {
                ViewDirection.AB -> segment.endArcLengthMeters
                ViewDirection.BA -> railLengthMeters - segment.endArcLengthMeters
            }
    }

    private data class RouteDistanceInterval(
        val startMeters: Double,
        val endMeters: Double
    )

    private sealed interface TraversalBuildResult {
        data class Built(val slices: List<RouteTraversalSlice>) : TraversalBuildResult
        data class Rejected(val detail: String) : TraversalBuildResult
    }
}
