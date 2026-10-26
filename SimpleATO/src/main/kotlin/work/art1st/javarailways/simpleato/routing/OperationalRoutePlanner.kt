package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.route.planning.OperatingDirectionOverlay
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.route.ExpandedRoute
import work.art1st.javarailways.basic.route.OperationalRouteLocation
import work.art1st.javarailways.basic.api.route.RailViewRef
import work.art1st.javarailways.simpleato.routing.RouteBuildResult
import work.art1st.javarailways.basic.api.route.RouteSegment
import work.art1st.javarailways.basic.route.TopologyRoutePlanner
import work.art1st.javarailways.basic.route.TopologyRoutePlanningInput
import work.art1st.javarailways.basic.route.TopologyRoutePlanningRejection
import work.art1st.javarailways.basic.route.TopologyRoutePlanningResult
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.simpleAtoWorldData

/**
 * The single SimpleATO seam for selecting an operational route between two stable waypoints.
 *
 * Every query captures one immutable topology root, compiles operating directions and registered
 * restriction sources against that root, and invokes Basic's topology planner exactly once. The
 * returned traversal is adapted to the existing timetable-owned [ExpandedRoute] value; no live
 * RailView graph is read and no later Dispatcher/admission stage repeats the search.
 */
class OperationalRoutePlanner(private val world: World) {
    fun buildRoute(
        fromWayPointId: String,
        toWayPointId: String,
        requiredGoalDirection: ViewDirection? = null,
        requiredStartDirection: ViewDirection? = null,
        requireUniquePath: Boolean = false,
        pruneNonTargetWaypoints: Boolean = false
    ): RouteBuildResult {
        val wayPoints = world.basicWorldData.wayPoints
        val from = wayPoints[fromWayPointId]
            ?: return RouteBuildResult.Failure("路径点不存在: $fromWayPointId")
        val to = wayPoints[toWayPointId]
            ?: return RouteBuildResult.Failure("路径点不存在: $toWayPointId")
        return buildRoute(from, to, requiredGoalDirection, requiredStartDirection, requireUniquePath, pruneNonTargetWaypoints)
    }

    fun buildRoute(
        from: WayPoint,
        to: WayPoint,
        requiredGoalDirection: ViewDirection? = null,
        requiredStartDirection: ViewDirection? = null,
        requireUniquePath: Boolean = false,
        pruneNonTargetWaypoints: Boolean = false
    ): RouteBuildResult {
        val topology = world.topologyState
        val simpleAto = world.simpleAtoWorldData
        return when (val result = plan(
            topology = topology,
            operatingDirections = world.basicWorldData.operatingDirections.overlay(topology),
            planningRestrictions = simpleAto.routePlanningRestrictions,
            start = OperationalRouteLocation(from.railId, from.arcLength),
            end = OperationalRouteLocation(to.railId, to.arcLength),
            routeId = "operational-query:${from.id}->${to.id}",
            routeRevision = 0L,
            startFacing = requiredStartDirection,
            endFacing = requiredGoalDirection,
            requireUniquePath = requireUniquePath,
            blockedLocations = if (pruneNonTargetWaypoints) world.basicWorldData.wayPoints.all().map {
                OperationalRouteLocation(it.railId, it.arcLength)
            } else emptyList()
        )) {
            is TopologyRoutePlanningResult.Planned -> {
                val segments = result.route.segments.filter { segment ->
                    segment.lengthMeters > ROUTE_POSITION_EPSILON
                }.map { segment ->
                    RouteSegment(
                        railViewRef = RailViewRef(segment.railId, segment.facing),
                        startArcLength = segment.startArcLengthMeters,
                        endArcLength = segment.endArcLengthMeters
                    )
                }
                if (segments.isEmpty()) {
                    return RouteBuildResult.Failure("planned route contains no positive-length traversal")
                }
                RouteBuildResult.Success(ExpandedRoute(
                    fromWayPointId = from.id,
                    toWayPointId = to.id,
                    segments = segments
                ))
            }

            is TopologyRoutePlanningResult.Rejected -> RouteBuildResult.Failure(result.detail, result.reason)
        }
    }

    internal companion object {
        private const val ROUTE_POSITION_EPSILON = 1.0e-9

        /** Shared restriction-composition and immutable-topology planning path for SimpleATO. */
        fun plan(
            topology: TopologyState,
            operatingDirections: OperatingDirectionOverlay,
            planningRestrictions: RoutePlanningRestrictionRuntime,
            start: OperationalRouteLocation,
            end: OperationalRouteLocation,
            routeId: String,
            routeRevision: Long,
            startFacing: ViewDirection? = null,
            endFacing: ViewDirection? = null,
            requireUniquePath: Boolean = false,
            blockedLocations: List<OperationalRouteLocation> = emptyList()
        ): TopologyRoutePlanningResult {
            val restrictions = try {
                planningRestrictions.compile(topology, operatingDirections)
            } catch (failure: IllegalArgumentException) {
                return TopologyRoutePlanningResult.Rejected(
                    reason = TopologyRoutePlanningRejection.INVALID_RESTRICTIONS,
                    detail = failure.message ?: "route-planning restrictions are invalid"
                )
            }
            return TopologyRoutePlanner.plan(
                TopologyRoutePlanningInput(
                    topology = topology,
                    restrictions = restrictions,
                    start = start,
                    end = end,
                    routeId = routeId,
                    routeRevision = routeRevision,
                    startFacing = startFacing,
                    endFacing = endFacing,
                    requireUniquePath = requireUniquePath,
                    blockedLocations = blockedLocations
                )
            )
        }
    }
}
