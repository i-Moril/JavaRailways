package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.region.*
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.abs

internal class DiscoveredProtectionRoutes(routes: Collection<ProtectionRouteDefinition>, warnings: Collection<String>) {
    val routes: List<ProtectionRouteDefinition> = java.util.List.copyOf(routes)
    val warningBoundaryIds: Set<String> = java.util.Set.copyOf(warnings)
}

/** Local directed graph only. One Dijkstra per entry; alternatives are tested, never enumerated. */
internal object ProtectionRouteDiscovery {
    fun discover(topology: TopologyState, region: ProtectionRegionDefinition,
        interior: List<ProtectionRegionInteriorSlice>, directions: Map<UUID, ViewDirection?>): DiscoveredProtectionRoutes {
        require(region.boundaryDraft == null) { "Cannot generate routes for incomplete boundaries" }
        require(interior.all { directions.containsKey(it.railId) }) { "Region operating directions are incomplete" }
        val spans = interior.flatMap { slice -> ViewDirection.entries.filter { facing ->
            directions[slice.railId] == null || directions[slice.railId] == facing
        }.map { facing -> ProtectionRouteTraversal(slice.railId, facing, slice.startAbMeters, slice.endAbMeters) } }
            .sortedWith(compareBy({ it.railId.toString() }, { it.lowerAbMeters }, { it.upperAbMeters }, { it.facing }))
        val byRail = spans.indices.groupBy { spans[it].railId }
        val edges = spans.map { span ->
            val length = topology.railLength(span.railId)
            val endpoint = if (span.facing == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A
            val reachesEnd = abs(span.travelEndAbMeters - if (endpoint == RailEndpoint.B) length else 0.0) <= Curve.TOLERANCE
            if (!reachesEnd) emptyList() else topology.continuations(RailEndpointRef(span.railId, endpoint)).flatMap { port ->
                val facing = if (port.endpoint == RailEndpoint.A) ViewDirection.AB else ViewDirection.BA
                val start = if (port.endpoint == RailEndpoint.A) 0.0 else topology.railLength(port.railId)
                byRail[port.railId].orEmpty().filter { spans[it].facing == facing && abs(spans[it].travelStartAbMeters - start) <= Curve.TOLERANCE }
            }.distinct().sorted()
        }
        val boundaries = region.boundaries.sortedBy { it.boundaryId }
        val starts = boundaries.associate { boundary -> boundary.boundaryId to byRail[boundary.position.railId].orEmpty().singleOrNull {
            spans[it].facing == boundary.insideFacing && abs(spans[it].travelStartAbMeters - boundary.position.arcLengthInAbMeters) <= Curve.TOLERANCE
        } }
        val ends = boundaries.associate { boundary -> boundary.boundaryId to byRail[boundary.position.railId].orEmpty().singleOrNull {
            spans[it].facing == boundary.insideFacing.reversed() && abs(spans[it].travelEndAbMeters - boundary.position.arcLengthInAbMeters) <= Curve.TOLERANCE
        } }
        val routes = arrayListOf<ProtectionRouteDefinition>()
        val warnings = linkedSetOf<String>()
        data class Visit(val index: Int, val distance: Double)
        for (entry in boundaries) {
            val start = starts[entry.boundaryId] ?: continue
            val distances = DoubleArray(spans.size) { Double.POSITIVE_INFINITY }
            val previous = IntArray(spans.size) { -1 }
            val queue = PriorityQueue(compareBy(Visit::distance, Visit::index))
            distances[start] = spans[start].upperAbMeters - spans[start].lowerAbMeters
            queue += Visit(start, distances[start])
            while (queue.isNotEmpty()) {
                val visit = queue.remove()
                if (visit.distance != distances[visit.index]) continue
                for (next in edges[visit.index]) {
                    val distance = visit.distance + spans[next].upperAbMeters - spans[next].lowerAbMeters
                    if (distance < distances[next]) {
                        distances[next] = distance; previous[next] = visit.index; queue += Visit(next, distance)
                    }
                }
            }
            for (exit in boundaries) {
                if (entry == exit) continue
                val end = ends[exit.boundaryId] ?: continue
                if (!distances[end].isFinite()) continue
                val path = arrayListOf<Int>()
                var cursor = end
                while (cursor != -1) { path += cursor; cursor = previous[cursor] }
                path.reverse()
                routes += ProtectionRouteDefinition(stableId(region.regionId, entry.boundaryId, exit.boundaryId),
                    entry.boundaryId, exit.boundaryId, path.map { spans[it] })
                // Any distinct simple path omits an edge of this one. Reachability after omitting
                // each edge proves existence of an alternative without enumerating cyclic walks.
                if (path.zipWithNext().any { omitted -> reachable(edges, start, end, omitted) }) {
                    warnings += entry.boundaryId; warnings += exit.boundaryId
                }
            }
        }
        return DiscoveredProtectionRoutes(routes, warnings)
    }

    private fun reachable(edges: List<List<Int>>, start: Int, end: Int, omitted: Pair<Int, Int>): Boolean {
        val visited = BooleanArray(edges.size)
        val pending = ArrayDeque<Int>()
        pending += start; visited[start] = true
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (at == end) return true
            for (next in edges[at]) if ((at != omitted.first || next != omitted.second) && !visited[next]) {
                visited[next] = true; pending += next
            }
        }
        return false
    }

    fun stableId(vararg parts: String): String = "simplesignals:${UUID.nameUUIDFromBytes(parts.joinToString("\n").toByteArray(StandardCharsets.UTF_8))}"
}
