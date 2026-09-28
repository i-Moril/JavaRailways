package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot

private val NAMESPACED_ROUTE_WINDOW_ID = Regex("[a-z0-9_.-]+:[A-Za-z0-9_./-]+")

/** One resolved boundary occurrence measured from the start of the supplied frozen route. */
internal data class ProtectionRouteBoundary(
    val boundaryId: String,
    val position: RailPositionSnapshot,
    val routeDistanceMeters: Double
) {
    init {
        require(NAMESPACED_ROUTE_WINDOW_ID.matches(boundaryId)) { "boundaryId must be namespaced" }
        require(routeDistanceMeters.isFinite() && routeDistanceMeters >= 0.0) {
            "routeDistanceMeters must be finite and non-negative"
        }
    }
}

/** The single continuous entry-to-exit traversal of one protection region. */
internal data class ProtectionRegionRouteWindow(
    val regionId: String,
    val regionRevision: Long,
    val entry: ProtectionRouteBoundary,
    val exit: ProtectionRouteBoundary
) {
    init {
        require(NAMESPACED_ROUTE_WINDOW_ID.matches(regionId)) { "regionId must be namespaced" }
        require(regionRevision >= 0L) { "regionRevision must be non-negative" }
        require(entry.routeDistanceMeters < exit.routeDistanceMeters) {
            "protection-region entry must precede exit"
        }
    }
}
