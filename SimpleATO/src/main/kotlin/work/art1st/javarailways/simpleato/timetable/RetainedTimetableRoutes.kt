package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.route.ExpandedRoute
import work.art1st.javarailways.basic.api.route.RouteSegment
import work.art1st.javarailways.basic.api.infrastructure.PhysicalRouteDependencyCompilation
import work.art1st.javarailways.basic.api.infrastructure.PhysicalRouteDependencyCompiler
import work.art1st.javarailways.basic.route.toSnapshot
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.api.station.StationCall
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import kotlin.math.abs

/** Revalidates selected geometry and endpoint bindings; never performs a route search. */
internal fun retainedRouteFailure(world: World, route: ExpandedRoute, topology: TopologyState,
    origin: WayPoint, target: WayPoint, startDirection: ViewDirection?, goalDirection: ViewDirection?): String? {
    val railIds = route.segments.map { it.railViewRef.railId }.toSet()
    if (!world.topologyState.preservesRails(topology, railIds)) return "validated route topology changed"
    if (route.fromWayPointId != origin.id || route.toWayPointId != target.id) return "validated route endpoint identity changed"
    val first = route.segments.first()
    val last = route.segments.last()
    if (startDirection != null && first.railViewRef.direction != startDirection ||
        goalDirection != null && last.railViewRef.direction != goalDirection) return "validated route direction changed"
    fun matches(segment: RouteSegment, point: WayPoint, arc: Double): Boolean {
        if (segment.railViewRef.railId != point.railId) return false
        val rail = world.getRail(point.railId) ?: return false
        val canonical = if (segment.railViewRef.direction == ViewDirection.AB) arc else rail.curve.totalArcLength - arc
        return abs(canonical - point.arcLength) <= 1e-6
    }
    if (!matches(first, origin, first.startArcLength) || !matches(last, target, last.endArcLength)) {
        return "validated route endpoint geometry changed"
    }
    return when (val physical = PhysicalRouteDependencyCompiler.compile(world.topologyState,
        listOf(route.toSnapshot("retained-route-check", 0L, world.topologyState.revision)))) {
        is PhysicalRouteDependencyCompilation.Compiled -> null
        PhysicalRouteDependencyCompilation.NoDependencies -> "validated route has no physical dependencies"
        is PhysicalRouteDependencyCompilation.Rejected -> physical.detail
    }
}

/** Exact selected mission prefix to a station marker, including an interior marker on a leg. */
internal fun stationCallRoutePrefix(world: World, originId: String, call: StationCall,
    routes: List<ExpandedRoute>): ExpandedRoute? {
    val berth = world.simpleAtoWorldData.stations.berth(call.berthRef) ?: return null
    val prefix = ArrayList<RouteSegment>()
    for (segment in routes.flatMap { it.segments }) {
        val direction = segment.railViewRef.direction
        val arc = berth.location.arcLengthIn(direction)
        if (segment.railViewRef.railId == berth.location.rail.id &&
            (berth.approachDirection == null || direction == berth.approachDirection) &&
            arc >= segment.startArcLength && arc <= segment.endArcLength) {
            if (arc > segment.startArcLength) prefix += segment.copy(endArcLength = arc)
            if (prefix.none { it.endArcLength > it.startArcLength }) return null
            return ExpandedRoute(originId, "station-call:${call.callId}:${call.berthRef}", prefix)
        }
        prefix += segment
    }
    return null
}
