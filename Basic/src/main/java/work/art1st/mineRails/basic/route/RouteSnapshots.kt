package work.art1st.mineRails.basic.route

import work.art1st.mineRails.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.mineRails.basic.api.ato.snapshot.RouteSnapshot

/** Capture this expanded route as an immutable, revisioned ATO route input. */
fun ExpandedRoute.toSnapshot(routeId: String, revision: Long): RouteSnapshot = RouteSnapshot(
    routeId = routeId,
    revision = revision,
    segments = segments.map { segment ->
        RouteSegmentSnapshot(
            railId = segment.railViewRef.railId,
            facing = segment.railViewRef.direction,
            startArcLengthMeters = segment.startArcLength,
            endArcLengthMeters = segment.endArcLength
        )
    }
)
