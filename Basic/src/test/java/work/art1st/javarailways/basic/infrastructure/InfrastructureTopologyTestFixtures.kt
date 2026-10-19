package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID
import work.art1st.javarailways.basic.infrastructure.admission.CompiledRouteProgressPath

internal fun lineTopologyState(revision: Long, vararg railIds: UUID): TopologyState =
    topologyState(
        revision = revision,
        railIds = railIds.toList(),
        connections = railIds.asList().zipWithNext().map { (first, second) ->
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A)
        }
    )

internal fun topologyState(
    revision: Long,
    railIds: Collection<UUID>,
    connections: Collection<Pair<RailEndpointRef, RailEndpointRef>>,
    atGradeCrossings: List<AtGradeRailCrossing> = emptyList()
): TopologyState {
    val continuations = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
    connections.forEach { (first, second) ->
        continuations.getOrPut(first, ::linkedSetOf) += second
        continuations.getOrPut(second, ::linkedSetOf) += first
    }
    return buildTestTopology(
        revision = revision,
        rails = railIds.map { TestTopologyRail(it, 10.0) },
        continuations = continuations,
        atGradeCrossings = atGradeCrossings
    )
}

internal fun endpoint(railId: UUID, endpoint: RailEndpoint): RailEndpointRef =
    RailEndpointRef(railId, endpoint)

internal fun testRouteProgressPath(
    railId: UUID = UUID(0L, Long.MAX_VALUE),
    railLengthMeters: Double = 10.0,
    routeStartAbMeters: Double = 0.0
): CompiledRouteProgressPath {
    val topology = buildTestTopology(
        revision = 0L,
        rails = listOf(TestTopologyRail(railId, railLengthMeters)),
        continuations = emptyMap()
    )
    val projected = TopologyRouteProjector.project(
        RouteSnapshot(
            "test:route/progress-path",
            0L,
            0L,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, routeStartAbMeters, railLengthMeters))
        ),
        topology
    ) as TopologyRouteProjectionResult.Projected
    return CompiledRouteProgressPath.compile(projected.route)
}
