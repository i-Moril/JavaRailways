package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef

/**
 * Installs one explicit turnout whose stem ends at B and whose branches start at A.
 * Test callers construct that orientation directly; this fixture performs no geometry search.
 */
fun configureTurnoutForTest(stem: CoreRail, branches: List<CoreRail>) {
    require(branches.size == 2)
    require(branches.all { it.world === stem.world })
    IsolatedSimulationThreadMarker.run { stem.world.railTopologyTransaction {
        val members = listOf(RailEndpointRef(stem.id, RailEndpoint.B)) + branches.map { RailEndpointRef(it.id, RailEndpoint.A) }
        stem.viewAB.restoreConnections(branches.map(CoreRail::viewAB), branches.first().viewAB,
            members.filter { it != RailEndpointRef(stem.id, RailEndpoint.B) })
        branches.forEach { branch ->
            branch.viewBA.restoreConnections(listOf(stem.viewBA), stem.viewBA,
                members.filter { it != RailEndpointRef(branch.id, RailEndpoint.A) })
        }
    } }
}
