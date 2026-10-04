package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionInteriorSlice
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.max

internal data class ProtectionBoundarySearchResult(
    val complete: Boolean,
    val boundaries: List<ProtectionBoundaryDefinition>,
    val interior: List<ProtectionRegionInteriorSlice>,
    val farthestDistanceMeters: Double,
    val detail: String
)

/**
 * Bounded graph cut on Core's existing adjacency. Distance means shortest track distance from the
 * starting cut, including the interior of each segment; branches are never summed. Cycles therefore
 * have a finite, deterministic range. Only the radius-reachable component is visited.
 */
internal object ProtectionBoundarySearch {
    private data class Segment(val rail: UUID, val index: Int)
    private data class End(val segment: Segment, val upper: Boolean)
    private data class Visit(val end: End, val distance: Double)

    fun search(topology: TopologyState, start: ProtectionBoundaryDefinition,
        boundaries: Collection<ProtectionBoundaryDefinition>, maxDistanceMeters: Double): ProtectionBoundarySearchResult {
        require(maxDistanceMeters.isFinite() && maxDistanceMeters > 0.0)
        val cuts = boundaries.groupBy { it.position.railId }.mapValues { (_, values) ->
            values.sortedBy { it.position.arcLengthInAbMeters }
        }
        val validated = hashSetOf<UUID>()
        fun validateCuts(rail: UUID) {
            if (!validated.add(rail)) return
            val values = cuts[rail].orEmpty()
            val length = topology.railLength(rail)
            require(values.all { it.position.arcLengthInAbMeters > Curve.TOLERANCE &&
                it.position.arcLengthInAbMeters < length - Curve.TOLERANCE }) { "Region cuts must lie inside a rail" }
            require(values.zipWithNext().all { (a, b) -> !samePoint(a.position, b.position) }) {
                "Several protection boundaries occupy the same rail position"
            }
        }
        fun limits(segment: Segment): Pair<Double, Double> {
            validateCuts(segment.rail)
            val railCuts = cuts[segment.rail].orEmpty()
            return (if (segment.index == 0) 0.0 else railCuts[segment.index - 1].position.arcLengthInAbMeters) to
                (if (segment.index == railCuts.size) topology.railLength(segment.rail)
                else railCuts[segment.index].position.arcLengthInAbMeters)
        }
        val startIndex = cuts[start.position.railId].orEmpty().indexOfFirst { it.boundaryId == start.boundaryId }
        require(startIndex >= 0) { "Starting boundary is absent" }
        val startEnd = End(Segment(start.position.railId,
            if (start.insideFacing == ViewDirection.AB) startIndex + 1 else startIndex),
            start.insideFacing == ViewDirection.BA)
        val distances = hashMapOf<End, Double>()
        val settled = hashSetOf<End>()
        val segments = linkedSetOf<Segment>()
        val found = linkedMapOf<String, ProtectionBoundaryDefinition>()
        val frontier = PriorityQueue(compareBy<Visit> { it.distance })
        var unmarkedEnd = false
        var wrongFacing = false
        fun offer(end: End, distance: Double) {
            if (distance <= maxDistanceMeters + Curve.TOLERANCE && distance < (distances[end] ?: Double.POSITIVE_INFINITY)) {
                distances[end] = distance
                frontier += Visit(end, distance)
            }
        }
        offer(startEnd, 0.0)
        while (frontier.isNotEmpty()) {
            val visit = frontier.remove()
            val end = visit.end
            if (visit.distance != distances[end] || !settled.add(end)) continue
            segments += end.segment
            val (lower, upper) = limits(end.segment)
            offer(end.copy(upper = !end.upper), visit.distance + upper - lower)
            val railCuts = cuts[end.segment.rail].orEmpty()
            val boundary = if (end.upper) railCuts.getOrNull(end.segment.index) else railCuts.getOrNull(end.segment.index - 1)
            if (boundary != null) {
                val expected = if (end.upper) ViewDirection.BA else ViewDirection.AB
                if (boundary.insideFacing != expected) wrongFacing = true
                else found[boundary.boundaryId] = boundary
            } else {
                val port = RailEndpointRef(end.segment.rail, if (end.upper) RailEndpoint.B else RailEndpoint.A)
                val connections = topology.continuations(port)
                if (connections.isEmpty()) unmarkedEnd = true
                connections.forEach { next ->
                    val fromB = next.endpoint == RailEndpoint.B
                    offer(End(Segment(next.railId, if (fromB) cuts[next.railId].orEmpty().size else 0), fromB), visit.distance)
                }
            }
        }
        var farthest = 0.0
        val interior = segments.map { segment ->
            val (lower, upper) = limits(segment)
            val a = distances[End(segment, false)] ?: Double.POSITIVE_INFINITY
            val b = distances[End(segment, true)] ?: Double.POSITIVE_INFINITY
            // At x along the segment: min(a + x, b + length - x). Its maximum is
            // the two wavefronts' meeting point, or the far end if only one side was reached.
            farthest = max(farthest, if (a.isFinite() && b.isFinite()) (a + b + upper - lower) / 2
                else minOf(a, b) + upper - lower)
            ProtectionRegionInteriorSlice(segment.rail, lower, upper)
        }
        val detail = when {
            farthest > maxDistanceMeters + Curve.TOLERANCE -> "Boundary saved; region exceeds maximum along-track range of $maxDistanceMeters m"
            wrongFacing -> "Boundary saved; cuts face different interiors or the outside reconnects"
            unmarkedEnd -> "Boundary saved; an unmarked rail end remains open"
            else -> "Protection region is closed"
        }
        return ProtectionBoundarySearchResult(
            farthest <= maxDistanceMeters + Curve.TOLERANCE && !wrongFacing && !unmarkedEnd,
            found.values.toList(), interior, farthest, detail)
    }
}
