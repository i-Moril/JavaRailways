package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.*
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import java.util.Collections
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class CurrentRouteEnd { REACHED_EXIT, WRONG_EXIT, DEAD_END, LOOP, UNSELECTED, ILLEGAL_CONTINUATION, UNAVAILABLE }

/** Complete physical prefix, including failed paths. No authorization or persistence semantics. */
internal class CurrentRoutePath(
    val entry: ProtectionBoundaryDefinition,
    val requestedExit: ProtectionBoundaryDefinition,
    traversals: Collection<ProtectionRouteTraversal>,
    val reached: RailPositionSnapshot,
    val end: CurrentRouteEnd,
    val detail: String,
    val reachedBoundaryId: String? = null,
    val waitingForObservation: Boolean = false,
    watchedContinuations: Map<RailEndpointRef, RailEndpointRef?> = emptyMap(),
    railIds: Collection<UUID> = traversals.map { it.railId }
) {
    val traversals: List<ProtectionRouteTraversal> = java.util.List.copyOf(traversals)
    // Null is meaningful: a currently unselected entry must be watched for later opening.
    val watchedContinuations: Map<RailEndpointRef, RailEndpointRef?> =
        Collections.unmodifiableMap(LinkedHashMap(watchedContinuations))
    val railIds: Set<UUID> = java.util.Set.copyOf(railIds + entry.position.railId + requestedExit.position.railId)
}

/** Walks only the selected continuation. Uses Core's root-local adjacency, never a path finder. */
internal object CurrentRouteTracer {
    fun trace(topology: TopologyState, active: ActiveRailContinuationsSnapshot?,
        region: ProtectionRegionDefinition, entryId: String, exitId: String,
        available: Boolean = true): CurrentRoutePath {
        val entry = region.boundaries.single { it.boundaryId == entryId }
        val exit = region.boundaries.single { it.boundaryId == exitId }
        val path = arrayListOf<ProtectionRouteTraversal>()
        val watched = linkedMapOf<RailEndpointRef, RailEndpointRef?>()
        val rails = linkedSetOf(entry.position.railId, exit.position.railId)
        var point = entry.position
        fun finish(end: CurrentRouteEnd, detail: String, boundary: String? = null, waiting: Boolean = false) =
            CurrentRoutePath(entry, exit, path, point, end, detail, boundary, waiting, watched, rails)
        if (!available || region.boundaryDraft != null)
            return finish(CurrentRouteEnd.UNAVAILABLE, "Protection region is unavailable or has incomplete boundaries")
        if (active == null || active.topologyRevision != topology.revision)
            return finish(CurrentRouteEnd.UNAVAILABLE, "Waiting for a completed observation of the current topology", waiting = true)
        if (entryId == exitId) return finish(CurrentRouteEnd.UNAVAILABLE, "Choose a different exit boundary")
        val cutsByRail = region.boundaries.groupBy { it.position.railId }
            .mapValues { (_, cuts) -> cuts.sortedBy { it.position.arcLengthInAbMeters } }
        val visited = hashSetOf<RailPositionSnapshot>()
        var first = true
        while (visited.add(point)) {
            rails += point.railId
            val length = try { topology.railLength(point.railId) } catch (_: IllegalArgumentException) {
                return finish(CurrentRouteEnd.UNAVAILABLE, "The reached rail is missing from the current topology")
            }
            if (point.arcLengthInAbMeters > length + Curve.TOLERANCE)
                return finish(CurrentRouteEnd.UNAVAILABLE, "Boundary is outside its current rail")
            val forward = point.facing == ViewDirection.AB
            val cuts = cutsByRail[point.railId].orEmpty().let { if (forward) it else it.asReversed() }
            val cut = cuts.firstOrNull {
                !(first && it.boundaryId == entryId) &&
                    if (forward) it.position.arcLengthInAbMeters >= point.arcLengthInAbMeters - Curve.TOLERANCE
                    else it.position.arcLengthInAbMeters <= point.arcLengthInAbMeters + Curve.TOLERANCE
            }
            val end = cut?.position?.arcLengthInAbMeters ?: if (forward) length else 0.0
            val start = point.arcLengthInAbMeters.coerceIn(0.0, length)
            if (abs(end - start) > Curve.TOLERANCE)
                path += ProtectionRouteTraversal(point.railId, point.facing, min(start, end), max(start, end))
            point = point.copy(arcLengthInAbMeters = end)
            if (cut != null) {
                val reachedExit = cut.boundaryId == exitId && point.facing == cut.insideFacing.reversed()
                return finish(if (reachedExit) CurrentRouteEnd.REACHED_EXIT else CurrentRouteEnd.WRONG_EXIT,
                    if (reachedExit) "Current physical path reaches the selected exit"
                    else "Current physical path reaches boundary ${cut.boundaryId}${if (point.facing == cut.insideFacing) " from its outside" else " instead"}",
                    cut.boundaryId)
            }
            val port = RailEndpointRef(point.railId, if (forward) RailEndpoint.B else RailEndpoint.A)
            val legal = topology.continuations(port)
            val next = if (topology.structuralNodeAt(port) is TopologyNode.Turnout) {
                val selected = active.selectedContinuation(port)
                watched[port] = selected
                if (selected == null) return finish(CurrentRouteEnd.UNSELECTED, "No current turnout continuation at $port")
                if (selected !in legal) return finish(CurrentRouteEnd.ILLEGAL_CONTINUATION, "Observed turnout continuation is not physically legal at $port")
                selected
            } else legal.singleOrNull() ?: return finish(CurrentRouteEnd.DEAD_END, "Current path ends at $port")
            // A boundary on the connected side is reached before a positive-length next span.
            val nextLength = try { topology.railLength(next.railId) } catch (_: IllegalArgumentException) {
                return finish(CurrentRouteEnd.UNAVAILABLE, "Selected continuation references a missing rail")
            }
            point = RailPositionSnapshot(next.railId,
                if (next.endpoint == RailEndpoint.A) ViewDirection.AB else ViewDirection.BA,
                if (next.endpoint == RailEndpoint.A) 0.0 else nextLength)
            first = false
        }
        return finish(CurrentRouteEnd.LOOP, "Current selected path returns to the same directed position")
    }
}
