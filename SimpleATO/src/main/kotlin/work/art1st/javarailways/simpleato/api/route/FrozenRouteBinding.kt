package work.art1st.javarailways.simpleato.api.route

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot

/**
 * Constant-time identity and range binding for one already frozen route.
 *
 * This value deliberately contains no route segments. A consumer uses it to prove that a value was
 * computed for the same route selected by SimpleATO; the host retains the authoritative traversal.
 *
 * @property routeId stable route identity chosen by SimpleATO
 * @property routeRevision revision of that route identity, independent of topology revision
 * @property topologyRevision Core topology revision used to freeze the route
 * @property totalLengthMeters full frozen-route length in metres
 */
data class FrozenRouteBinding(
    val routeId: String,
    val routeRevision: Long,
    val topologyRevision: Long,
    val totalLengthMeters: Double
) {
    init {
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(routeRevision >= 0L) { "routeRevision must be non-negative" }
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
        require(totalLengthMeters.isFinite() && totalLengthMeters >= 0.0) {
            "totalLengthMeters must be finite and non-negative"
        }
    }

    /** Factory methods for bindings created from host-owned frozen routes. */
    companion object {
        /** Captures the constant-size identity and range fields from [route]. */
        @JvmStatic
        fun from(route: RouteSnapshot): FrozenRouteBinding = FrozenRouteBinding(
            route.routeId,
            route.revision,
            route.topologyRevision,
            route.totalLengthMeters
        )
    }
}
