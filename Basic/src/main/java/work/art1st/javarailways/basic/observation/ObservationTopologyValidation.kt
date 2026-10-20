package work.art1st.javarailways.basic.observation

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.route.TopologyRoutePlanner
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import kotlin.math.abs

/** Validates only the observed train's physical references after unrelated graph publication. */
fun RailwayTrainObservation.invalidTopologyReason(topology: TopologyState): String? {
    fun length(railId: java.util.UUID): Double? = topology.railProjection[railId]?.singleOrNull()
        ?.let { abs(it.railEndAbMeters - it.railStartAbMeters) }
    for (position in listOf(head, tail)) {
        val railLength = length(position.railId) ?: return "observed rail is missing: ${position.railId}"
        if (position.arcLengthInAbMeters !in -Curve.TOLERANCE..(railLength + Curve.TOLERANCE)) return "observed position is outside its rail"
    }
    for (interval in occupiedIntervals) {
        val railLength = length(interval.railId) ?: return "occupied rail is missing: ${interval.railId}"
        if (interval.minArcLengthInAbMeters < -Curve.TOLERANCE ||
            interval.maxArcLengthInAbMeters > railLength + Curve.TOLERANCE) {
            return "observed occupancy is outside its rail"
        }
    }
    if (bodyTraversal.isEmpty()) return "observed train has no aligned body traversal"
    val segments = bodyTraversal.map { segment ->
        val railLength = length(segment.railId) ?: return "body rail is missing: ${segment.railId}"
        val start = if (segment.facing == ViewDirection.AB) segment.startArcLengthInAbMeters
            else railLength - segment.startArcLengthInAbMeters
        val end = if (segment.facing == ViewDirection.AB) segment.endArcLengthInAbMeters
            else railLength - segment.endArcLengthInAbMeters
        if (start < -Curve.TOLERANCE || end < start || end > railLength + Curve.TOLERANCE) {
            return "observed body is outside its rail"
        }
        RouteSegmentSnapshot(segment.railId, segment.facing,
            start.coerceIn(0.0, railLength), end.coerceIn(0.0, railLength))
    }
    return TopologyRoutePlanner.invalidRouteReason(topology,
        RouteSnapshot("observation:$trainId", frameRevision, topology.revision, segments))
}
