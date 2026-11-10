package work.art1st.javarailways.basic.rail

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView

/** Topology-derived turnout invariants shared by authoring operations. */
object TurnoutTopology {
    fun isTurnoutEndpoint(viewToEndpoint: RailView): Boolean =
        turnoutAt(viewToEndpoint) != null

    fun hasTurnoutEndpoint(rail: CoreRail): Boolean =
        isTurnoutEndpoint(rail.viewBA) || isTurnoutEndpoint(rail.viewAB)

    /** Full authored turnout closure, including a junction whose common leg is not built yet. */
    fun turnoutRailsTouching(rails: Collection<CoreRail>): Set<CoreRail> = rails.asSequence()
        .flatMap { rail -> sequenceOf(rail.viewBA, rail.viewAB) }
        .flatMap { view ->
            turnoutAt(view)?.ports.orEmpty().asSequence().map { endpoint ->
                requireNotNull(view.rail.world.getRail(endpoint.railId)) {
                    "Authored turnout references missing rail ${endpoint.railId}"
                }
            }
        }
        .toCollection(linkedSetOf())

    private fun turnoutAt(viewToEndpoint: RailView): TopologyNode.Turnout? =
        viewToEndpoint.rail.world.topologyState.structuralNodeAt(RailEndpointRef(
            viewToEndpoint.rail.id,
            if (viewToEndpoint.viewDirection == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A
        )) as? TopologyNode.Turnout

}
