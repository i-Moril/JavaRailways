package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import kotlin.math.abs
import kotlin.math.min

/** Partial geometry is retained for source validation even when the full margin is unavailable. */
internal data class TerminalMarginProposal(val route: RouteSnapshot, val failure: String? = null)

/** Walks only determined ordinary connections; it never selects a turnout or replacement path. */
internal object TerminalRouteMargin {
    /** Rechecks reused and provider-supplied geometry, not only the newly walked margin. */
    fun requireLegalPrefix(topology: TopologyState, restrictions: RoutePlanningRestrictions,
        route: RouteSnapshot, requiredEndRouteMeters: Double, selectedEndRouteMeters: Double) {
        require(restrictions.topologyRevision == topology.revision)
        require(requiredEndRouteMeters.isFinite() && requiredEndRouteMeters >= 0.0 &&
            route.totalLengthMeters + EPSILON >= requiredEndRouteMeters) {
            "selected terminal policy cannot provide the configured stopping margin"
        }
        var start = 0.0
        route.segments.forEach { segment ->
            val covered = min(segment.lengthMeters, (requiredEndRouteMeters - start).coerceAtLeast(0.0))
            if (covered > EPSILON) {
                val rail = requireNotNull(topology.railProjection[segment.railId]?.singleOrNull()) {
                    "terminal route references an ambiguous or missing rail ${segment.railId}"
                }
                val length = abs(rail.railEndAbMeters - rail.railStartAbMeters)
                fun overlaps(span: DirectedRailSpan, from: Double = 0.0): Boolean {
                    val lower = if (segment.facing == ViewDirection.AB) segment.startArcLengthMeters + from
                        else length - segment.startArcLengthMeters - covered
                    val upper = if (segment.facing == ViewDirection.AB) segment.startArcLengthMeters + covered
                        else length - segment.startArcLengthMeters - from
                    return span.railId == segment.railId && span.facing == segment.facing &&
                        min(upper, span.upperAbMeters) - maxOf(lower, span.lowerAbMeters) > EPSILON
                }
                require(restrictions.forbiddenTraversals.none { overlaps(it) }) {
                    "operating route, train body or stopping margin crosses a forbidden traversal or operating direction"
                }
                val unselectedStart = (selectedEndRouteMeters - start).coerceAtLeast(0.0)
                require(unselectedStart + EPSILON >= covered || restrictions.pathReplacements.none { replacement ->
                    replacement.replacedTraversals.any { overlaps(it, unselectedStart) }
                }) { "terminal margin enters a replacement path that was not selected" }
            }
            start += segment.lengthMeters
        }
    }

    fun propose(topology: TopologyState, restrictions: RoutePlanningRestrictions, route: RouteSnapshot,
        requiredEndRouteMeters: Double): TerminalMarginProposal {
        require(requiredEndRouteMeters.isFinite() && requiredEndRouteMeters >= 0.0)
        val invalid = work.art1st.javarailways.basic.route.TopologyRoutePlanner
            .invalidRestrictionsReason(topology, restrictions)
        require(invalid == null) { invalid.orEmpty() }
        val segments = route.segments.toMutableList()
        var distance = route.totalLengthMeters
        fun proposal(failure: String? = null) = TerminalMarginProposal(
            if (segments == route.segments) route else RouteSnapshot(route.routeId, route.revision,
                topology.revision, segments), failure)
        if (distance + EPSILON >= requiredEndRouteMeters) return proposal()
        var end = segments.lastOrNull() ?: return proposal("cannot extend an empty operating route")
        var railId = end.railId
        var facing = end.facing
        var arc = end.endArcLengthMeters
        while (distance + EPSILON < requiredEndRouteMeters) {
            val rail = topology.railProjection[railId]?.singleOrNull()
                ?: return proposal("terminal margin references an ambiguous or missing rail $railId")
            val length = abs(rail.railEndAbMeters - rail.railStartAbMeters)
            val retained = min((length - arc).coerceAtLeast(0.0), requiredEndRouteMeters - distance)
            if (retained > EPSILON) {
                val next = RouteSegmentSnapshot(railId, facing, arc, arc + retained)
                val lower = if (facing == ViewDirection.AB) arc else length - arc - retained
                val upper = lower + retained
                fun overlaps(span: DirectedRailSpan) = span.railId == railId && span.facing == facing &&
                    min(upper, span.upperAbMeters) - maxOf(lower, span.lowerAbMeters) > EPSILON
                if (restrictions.forbiddenTraversals.any(::overlaps)) {
                    return proposal("terminal margin crosses a forbidden traversal or operating direction")
                }
                if (restrictions.pathReplacements.any { it.replacedTraversals.any(::overlaps) }) {
                    return proposal("terminal margin enters a replacement path that was not selected")
                }
                segments += next
                distance += retained
                arc += retained
                if (distance + EPSILON >= requiredEndRouteMeters) return proposal()
            }
            val port = RailEndpointRef(railId, if (facing == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A)
            val node = topology.structuralNodeAt(port)
            val nextPort = when (node) {
                null -> topology.ordinaryContinuationAt(port)
                is TopologyNode.LoopAnchor -> node.ports.singleOrNull { it != port }
                else -> null
            } ?: return proposal("terminal margin reaches an unselected junction or physical track end at $port")
            railId = nextPort.railId
            facing = if (nextPort.endpoint == RailEndpoint.A) ViewDirection.AB else ViewDirection.BA
            arc = 0.0
        }
        return proposal()
    }

    private const val EPSILON = 1.0e-6
}
