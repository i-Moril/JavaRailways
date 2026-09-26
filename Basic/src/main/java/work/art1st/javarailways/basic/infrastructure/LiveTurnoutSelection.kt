package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode

/** The only Basic runtime primitive that writes a turnout's live bidirectional selection. */
internal class LiveTurnoutSelection(private val world: World) {
    fun resolve(
        expectedResource: StableInfrastructureResource,
        fromPort: RailEndpointRef,
        toPort: RailEndpointRef
    ): PreparedTurnoutSelection {
        val topology = world.topologyState
        val turnout = topology.structuralNodeAt(fromPort)
            as? TopologyNode.Turnout
            ?: error("turnout source port is not part of a turnout: $fromPort")
        require(topology.structuralNodeAt(toPort) === turnout &&
            toPort in turnout.legalContinuations.getValue(fromPort)) {
            "turnout continuation no longer exists: $fromPort -> $toPort"
        }
        require(TurnoutResourceIdentity.fromPorts(turnout.ports) == expectedResource) {
            "turnout resource does not match current topology: $expectedResource"
        }

        val from = arrivingView(fromPort)
        val to = arrivingView(toPort)
        val operations = listOf(
            SelectionOperation(from, to.reversed),
            SelectionOperation(to, from.reversed)
        )
        operations.forEach { operation ->
            require(operation.from.nextRails.any { it === operation.to }) {
                "live turnout continuation no longer exists: $fromPort -> $toPort"
            }
        }
        return PreparedTurnoutSelection(operations)
    }

    private fun arrivingView(port: RailEndpointRef): RailView {
        val rail = requireNotNull(world.getRail(port.railId)) {
            "turnout port rail no longer exists: ${port.railId}"
        }
        val direction = when (port.endpoint) {
            RailEndpoint.A -> ViewDirection.BA
            RailEndpoint.B -> ViewDirection.AB
        }
        return rail.getView(direction)
    }

    internal class PreparedTurnoutSelection(
        private val operations: List<SelectionOperation>
    ) {
        val alreadySelected: Boolean
            get() = operations.all { it.from.nextActiveRail === it.to }

        fun apply() {
            operations.forEach { operation ->
                if (operation.from.nextActiveRail !== operation.to) {
                    operation.from.selectNextRail(operation.to)
                }
            }
        }
    }

    internal data class SelectionOperation(val from: RailView, val to: RailView)
}
