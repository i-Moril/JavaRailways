package work.art1st.javarailways.basic.api.route.planning

import work.art1st.javarailways.basic.route.TopologyRoutePlannerEngine
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

/** Pure physical-distance query over a caller-captured immutable topology; safe on a worker.
 *
 * Both coordinates are canonical A-to-B metres. Neither endpoint constrains travel direction.
 * Only structural legal continuations apply: live turnout selection, operating directions,
 * signal policies and resources are not read or changed. At-grade crossings are not connections.
 * No world, registration, grant or persisted identity is created. The caller owns world/root
 * freshness checks. Each nonzero query compiles the existing unrestricted query graph and searches
 * it, with graph-sized preparation and Dijkstra cost; this is not a constant-time lookup.
 */
object PhysicalRailDistance {
    /** Returns shortest track metres, zero for the same valid point, or null only when disconnected.
     * @throws IllegalArgumentException if either rail/coordinate is absent or outside the topology.
     */
    @JvmStatic
    fun between(topology: TopologyState, startRailId: UUID, startAbMeters: Double,
        endRailId: UUID, endAbMeters: Double): Double? {
        val start = OperationalRouteLocation(startRailId, startAbMeters)
        val end = OperationalRouteLocation(endRailId, endAbMeters)
        return TopologyRoutePlannerEngine.physicalDistance(topology, start, end)
    }
}
