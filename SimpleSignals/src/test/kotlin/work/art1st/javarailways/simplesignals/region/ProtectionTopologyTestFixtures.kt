package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
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
    connections: Collection<Pair<RailEndpointRef, RailEndpointRef>>,
    atGradeCrossings: List<AtGradeRailCrossing> = emptyList(),
    preservedRailIdentities: Map<UUID, UUID> = emptyMap()
): TopologyState {
    val continuations = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
    connections.forEach { (first, second) ->
        continuations.getOrPut(first, ::linkedSetOf) += second
        continuations.getOrPut(second, ::linkedSetOf) += first
    }
    return buildTestTopology(
        revision = revision,
        rails = railIds.map { TestTopologyRail(it, 10.0, preservedRailIdentities[it] ?: UUID.randomUUID()) },
        continuations = continuations,
        atGradeCrossings = atGradeCrossings
    )
}

internal fun endpoint(railId: UUID, endpoint: RailEndpoint): RailEndpointRef =
    RailEndpointRef(railId, endpoint)
