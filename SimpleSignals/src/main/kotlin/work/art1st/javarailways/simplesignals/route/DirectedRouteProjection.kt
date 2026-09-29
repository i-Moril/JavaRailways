package work.art1st.javarailways.simplesignals.route

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.region.ProtectionRegionTopology
import java.util.UUID

internal enum class DirectedRouteProjectionFailureCode {
    STALE_TOPOLOGY,
    INVALID_ROUTE
}

internal data class DirectedRouteProjectionFailure(
    val code: DirectedRouteProjectionFailureCode,
    val detail: String,
    val railId: UUID? = null
)

internal sealed interface DirectedRouteProjectionResult {
    data class Projected(val route: DirectedRouteProjection) : DirectedRouteProjectionResult
    data class Rejected(val failure: DirectedRouteProjectionFailure) : DirectedRouteProjectionResult
}

/**
 * One frozen-route occurrence with both route-local metres and canonical rail AB coordinates.
 * Route distances always increase in travel order, including for BA-facing occurrences.
 */
internal data class DirectedRouteOccurrence(
    val railId: UUID,
    val facing: ViewDirection,
    val canonicalStartAbMeters: Double,
    val canonicalEndAbMeters: Double,
    val routeStartMeters: Double,
    val routeEndMeters: Double
) {
    fun canonicalAt(routeDistanceMeters: Double): Double {
        val localOffset = (routeDistanceMeters - routeStartMeters)
            .coerceIn(0.0, routeEndMeters - routeStartMeters)
        return when (facing) {
            ViewDirection.AB -> canonicalStartAbMeters + localOffset
            ViewDirection.BA -> canonicalStartAbMeters - localOffset
        }
    }
}

internal class DirectedRouteProjection internal constructor(
    occurrences: Collection<DirectedRouteOccurrence>
) {
    val occurrences: List<DirectedRouteOccurrence> = java.util.List.copyOf(occurrences)
    val occurrencesByRail: Map<UUID, List<DirectedRouteOccurrence>> = java.util.Map.copyOf(
        this.occurrences.groupBy(DirectedRouteOccurrence::railId)
            .mapValues { (_, values) -> java.util.List.copyOf(values) }
    )
}

/** Shared SimpleSignals conversion used by region and fixed-block route-local matching. */
internal object DirectedRouteProjector {
    fun project(
        route: RouteSnapshot,
        topology: ProtectionRegionTopology
    ): DirectedRouteProjectionResult {
        if (route.topologyRevision != topology.topologyRevision) {
            return rejected(
                DirectedRouteProjectionFailureCode.STALE_TOPOLOGY,
                "route topology revision ${route.topologyRevision} does not match " +
                    topology.topologyRevision
            )
        }
        val occurrences = ArrayList<DirectedRouteOccurrence>(route.segments.size)
        var routeStart = 0.0
        for (segment in route.segments) {
            val rail = topology.rails[segment.railId]
                ?: return rejected(
                    DirectedRouteProjectionFailureCode.INVALID_ROUTE,
                    "route references missing rail ${segment.railId}",
                    segment.railId
                )
            if (segment.endArcLengthMeters > rail.lengthMeters + Curve.TOLERANCE) {
                return rejected(
                    DirectedRouteProjectionFailureCode.INVALID_ROUTE,
                    "route segment exceeds rail ${segment.railId}",
                    segment.railId
                )
            }
            val localStart = segment.startArcLengthMeters.coerceIn(0.0, rail.lengthMeters)
            val localEnd = segment.endArcLengthMeters.coerceIn(0.0, rail.lengthMeters)
            val canonicalStart = when (segment.facing) {
                ViewDirection.AB -> localStart
                ViewDirection.BA -> rail.lengthMeters - localStart
            }
            val canonicalEnd = when (segment.facing) {
                ViewDirection.AB -> localEnd
                ViewDirection.BA -> rail.lengthMeters - localEnd
            }
            occurrences += DirectedRouteOccurrence(
                railId = segment.railId,
                facing = segment.facing,
                canonicalStartAbMeters = canonicalStart,
                canonicalEndAbMeters = canonicalEnd,
                routeStartMeters = routeStart,
                routeEndMeters = routeStart + segment.lengthMeters
            )
            routeStart += segment.lengthMeters
        }
        return DirectedRouteProjectionResult.Projected(DirectedRouteProjection(occurrences))
    }

    private fun rejected(
        code: DirectedRouteProjectionFailureCode,
        detail: String,
        railId: UUID? = null
    ) = DirectedRouteProjectionResult.Rejected(
        DirectedRouteProjectionFailure(code, detail, railId)
    )
}
