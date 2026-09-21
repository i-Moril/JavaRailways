package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

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
    connections: Collection<Pair<RailEndpointRef, RailEndpointRef>>
): TopologyState {
    val continuations = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
    connections.forEach { (first, second) ->
        continuations.getOrPut(first, ::linkedSetOf) += second
        continuations.getOrPut(second, ::linkedSetOf) += first
    }
    return buildTestTopology(
        revision = revision,
        rails = railIds.map { TestTopologyRail(it, 10.0) },
        continuations = continuations
    )
}

internal fun endpoint(railId: UUID, endpoint: RailEndpoint): RailEndpointRef =
    RailEndpointRef(railId, endpoint)

private val TEST_ROUTE_PROGRESS_PATH: CompiledRouteProgressPath by lazy {
    val railId = UUID(0L, Long.MAX_VALUE)
    val topology = lineTopologyState(0L, railId)
    val projected = TopologyRouteProjector.project(
        RouteSnapshot(
            "test:route/progress-path",
            0L,
            0L,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 10.0))
        ),
        topology
    ) as TopologyRouteProjectionResult.Projected
    CompiledRouteProgressPath.compile(projected.route)
}

internal fun testRouteProgressPath(): CompiledRouteProgressPath = TEST_ROUTE_PROGRESS_PATH
