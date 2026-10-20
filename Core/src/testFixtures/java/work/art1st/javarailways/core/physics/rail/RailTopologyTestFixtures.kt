package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

/**
 * Installs one explicit turnout whose stem ends at B and whose branches start at A.
 * Test callers construct that orientation directly; this fixture performs no geometry search.
 */
fun configureTurnoutForTest(stem: CoreRail, branches: List<CoreRail>) {
    require(branches.size == 2)
    require(branches.all { it.world === stem.world })
    IsolatedSimulationThreadMarker.run { stem.world.railTopologyTransaction {
        setNextForTest(stem.viewAB, branches.map(CoreRail::viewAB))
        branches.forEach { branch ->
            setNextForTest(branch.viewBA, listOf(stem.viewBA))
        }
    } }
}

private fun setNextForTest(view: RailView, next: List<RailView>) {
    view.restoreConnections(next, next.firstOrNull())
}
