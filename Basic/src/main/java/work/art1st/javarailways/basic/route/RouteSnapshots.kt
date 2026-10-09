package work.art1st.javarailways.basic.route

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.ExpandedRoute

/** Capture this expanded route as an immutable, revisioned ATO route input. */
fun ExpandedRoute.toSnapshot(
    routeId: String,
    revision: Long,
    topologyRevision: Long
): RouteSnapshot = RouteSnapshot(
    routeId = routeId,
    revision = revision,
    topologyRevision = topologyRevision,
    segments = segments.map { segment ->
        RouteSegmentSnapshot(
            railId = segment.railViewRef.railId,
            facing = segment.railViewRef.direction,
            startArcLengthMeters = segment.startArcLength,
            endArcLengthMeters = segment.endArcLength
        )
    }
)
