package work.art1st.javarailways.basic.contract

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode

/** Compile fixture proving a separate module can inspect topology using Core API packages only. */
internal object CoreTopologyApiConsumerCompileFixture {
    fun inspect(world: World): Summary {
        val state = world.topologyState
        return Summary(
            revision = state.revision,
            terminusCount = state.nodes.values.count { it is TopologyNode.Terminus },
            edgeCount = state.edges.size,
            projectedRailCount = state.railProjection.size
        )
    }

    data class Summary(
        val revision: Long,
        val terminusCount: Int,
        val edgeCount: Int,
        val projectedRailCount: Int
    )
}
