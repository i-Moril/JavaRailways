package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjectionResult
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjector

/** Local guard for retained authored cuts. No region resource, occupancy area or persisted subgraph. */
internal class UnavailableProtectionTerritory(
    private val regions: ProtectionRegionCompilationSnapshot,
    regionIds: Collection<String>
) {
    private val slicesByRail = regionIds.flatMap { id ->
        regions.regionsById.getValue(id).interior.map { id to it }
    }.groupBy { (_, slice) -> slice.railId }

    fun requireUsable(route: RouteSnapshot, authorityEndMeters: Double = Double.POSITIVE_INFINITY) {
        check(route.topologyRevision == regions.topology.revision) { "protection territory uses stale topology" }
        if (slicesByRail.isEmpty() || route.segments.none { it.railId in slicesByRail }) return
        val projection = when (val result = DirectedRouteProjector.project(route, regions.derivedTopology)) {
            is DirectedRouteProjectionResult.Projected -> result.route
            is DirectedRouteProjectionResult.Rejected -> error(result.failure.detail)
        }
        for (occurrence in projection.occurrences) {
            if (occurrence.routeStartMeters >= authorityEndMeters) break
            val last = occurrence.canonicalAt(minOf(authorityEndMeters, occurrence.routeEndMeters))
            val lower = minOf(occurrence.canonicalStartAbMeters, last)
            val upper = maxOf(occurrence.canonicalStartAbMeters, last)
            for ((id, slice) in slicesByRail[occurrence.railId].orEmpty()) {
                check(minOf(upper, slice.endAbMeters) - maxOf(lower, slice.startAbMeters) <= Curve.TOLERANCE) {
                    "ATO admission is forbidden by unavailable protection region $id"
                }
            }
        }
    }
}
