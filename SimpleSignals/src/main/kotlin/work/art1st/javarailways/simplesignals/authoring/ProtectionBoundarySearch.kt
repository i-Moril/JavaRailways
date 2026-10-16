package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
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
 * Bounded graph cut on Core's existing adjacency and crossing conflicts. Crossing links are only
 * for enclosure, never train continuations. Distance means shortest track distance from the
 * starting cut, including the interior of each segment; branches are never summed. Cycles therefore
 * have a finite, deterministic range. Only the radius-reachable component is visited.
 */
internal object ProtectionBoundarySearch {
    private data class Segment(val rail: UUID, val index: Int)
    private data class End(val segment: Segment, val upper: Boolean)
    private data class Visit(val end: End, val distance: Double)
    private data class RailPoints(
        val positions: List<Double>,
        val indices: Map<Double, Int>,
        val boundaries: Map<Double, ProtectionBoundaryDefinition>,
        val crossingTargets: Map<Double, List<RailCrossingPoint>>
    )

    fun search(topology: TopologyState, start: ProtectionBoundaryDefinition,
        boundaries: Collection<ProtectionBoundaryDefinition>, maxDistanceMeters: Double,
        undirectedBoundaryIds: Set<String> = emptySet(),
        fromInterior: Boolean = false): ProtectionBoundarySearchResult {
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
        val pointsByRail = hashMapOf<UUID, RailPoints>()
        fun points(rail: UUID): RailPoints = pointsByRail.getOrPut(rail) {
            validateCuts(rail)
            val boundariesByArc = cuts[rail].orEmpty().associateBy { it.position.arcLengthInAbMeters }
            val crossingTargets = topology.atGradeCrossingsOn(rail).map {
                if (it.first.railId == rail) it.first.arcLengthAbMeters to it.second
                else it.second.arcLengthAbMeters to it.first
            }.groupBy({ it.first }, { it.second })
            val seed = if (fromInterior && rail == start.position.railId) listOf(start.position.arcLengthInAbMeters) else emptyList()
            val positions = (listOf(0.0, topology.railLength(rail)) + boundariesByArc.keys + seed +
                crossingTargets.keys).distinct().sorted()
            RailPoints(positions, positions.withIndex().associate { it.value to it.index },
                boundariesByArc, crossingTargets)
        }
        fun limits(segment: Segment): Pair<Double, Double> {
            val positions = points(segment.rail).positions
            return positions[segment.index] to positions[segment.index + 1]
        }
        require(fromInterior || cuts[start.position.railId].orEmpty().any { it.boundaryId == start.boundaryId }) {
            "Starting boundary is absent"
        }
        val startIndex = points(start.position.railId).indices.getValue(start.position.arcLengthInAbMeters)
        val startEnd = End(Segment(start.position.railId,
            if (start.insideFacing == ViewDirection.AB) startIndex else startIndex - 1),
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
        if (fromInterior) {
            if (startIndex > 0) offer(End(Segment(start.position.railId, startIndex - 1), true), 0.0)
            if (startIndex < points(start.position.railId).positions.lastIndex)
                offer(End(Segment(start.position.railId, startIndex), false), 0.0)
        } else offer(startEnd, 0.0)
        while (frontier.isNotEmpty()) {
            val visit = frontier.remove()
            val end = visit.end
            if (visit.distance != distances[end] || !settled.add(end)) continue
            segments += end.segment
            val (lower, upper) = limits(end.segment)
            offer(end.copy(upper = !end.upper), visit.distance + upper - lower)
            val railPoints = points(end.segment.rail)
            val arc = if (end.upper) upper else lower
            val boundary = railPoints.boundaries[arc]
            // A diamond joins the protected conflict component, not the physical travel graph.
            railPoints.crossingTargets[arc].orEmpty().forEach { other ->
                val otherPoints = points(other.railId)
                val index = otherPoints.indices.getValue(other.arcLengthAbMeters)
                if (index > 0) offer(End(Segment(other.railId, index - 1), true), visit.distance)
                if (index < otherPoints.positions.lastIndex) offer(End(Segment(other.railId, index), false), visit.distance)
            }
            if (boundary != null) {
                val expected = if (end.upper) ViewDirection.BA else ViewDirection.AB
                if (boundary.boundaryId in undirectedBoundaryIds && boundary.boundaryId != start.boundaryId) {
                    if (found[boundary.boundaryId]?.insideFacing?.let { it != expected } == true) wrongFacing = true
                    found[boundary.boundaryId] = boundary.copy(position = boundary.position.copy(facing = expected))
                } else if (boundary.insideFacing != expected) wrongFacing = true
                else found[boundary.boundaryId] = boundary
            } else if (arc == 0.0 || arc == topology.railLength(end.segment.rail)) {
                val port = RailEndpointRef(end.segment.rail, if (end.upper) RailEndpoint.B else RailEndpoint.A)
                val connections = topology.continuations(port)
                if (connections.isEmpty()) unmarkedEnd = true
                connections.forEach { next ->
                    val fromB = next.endpoint == RailEndpoint.B
                    offer(End(Segment(next.railId, if (fromB) points(next.railId).positions.lastIndex - 1 else 0), fromB), visit.distance)
                }
            } else {
                offer(End(Segment(end.segment.rail,
                    if (end.upper) end.segment.index + 1 else end.segment.index - 1), !end.upper), visit.distance)
            }
        }
        var farthest = 0.0
        val splitInterior = segments.map { segment ->
            val (lower, upper) = limits(segment)
            val a = distances[End(segment, false)] ?: Double.POSITIVE_INFINITY
            val b = distances[End(segment, true)] ?: Double.POSITIVE_INFINITY
            // At x along the segment: min(a + x, b + length - x). Its maximum is
            // the two wavefronts' meeting point, or the far end if only one side was reached.
            farthest = max(farthest, if (a.isFinite() && b.isFinite()) (a + b + upper - lower) / 2
                else minOf(a, b) + upper - lower)
            ProtectionRegionInteriorSlice(segment.rail, lower, upper)
        }
        // Crossing splits are traversal details; retain the existing contiguous interior shape.
        val interior = mutableListOf<ProtectionRegionInteriorSlice>()
        splitInterior.groupBy { it.railId }.values.forEach { slices ->
            slices.sortedBy { it.startAbMeters }.forEach { slice ->
                val previous = interior.lastOrNull()
                if (previous?.railId == slice.railId && previous.endAbMeters == slice.startAbMeters &&
                    slice.startAbMeters !in points(slice.railId).boundaries) {
                    interior[interior.lastIndex] = previous.copy(endAbMeters = slice.endAbMeters)
                } else interior += slice
            }
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
