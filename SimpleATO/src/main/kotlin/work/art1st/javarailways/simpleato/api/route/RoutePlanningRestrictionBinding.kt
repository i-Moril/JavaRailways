package work.art1st.javarailways.simpleato.api.route

import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictionSource
import work.art1st.javarailways.simpleato.api.NAMESPACED_SIMPLE_ATO_ID

/**
 * One independently owned operational route-planning restriction source.
 *
 * [sourceId] is used only for stable ownership, deterministic composition and diagnostics. The
 * source itself still returns Basic's signalling-neutral restriction values and is invoked once
 * before a route search, never while Dijkstra expands the query graph.
 *
 * @property sourceId stable namespaced source identity used across installed owners
 * @property source topology-bound compiler invoked once for each route-planning query
 */
data class RoutePlanningRestrictionBinding(
    val sourceId: String,
    val source: RoutePlanningRestrictionSource
) {
    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(sourceId)) { "sourceId must be namespaced" }
    }
}
