package work.art1st.javarailways.simplesignals.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.basic.api.route.planning.OperationalRouteLocation
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanner
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningInput
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningResult
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.simplesignals.region.ProtectionRegionTopology
import java.util.UUID

class DirectedRouteProjectorTest {
    private val rail = UUID(0L, 91L)

    @Test
    fun `planner generated BA segment projects to a non-negative canonical interval`() {
        val topology = buildTestTopology(1L, listOf(TestTopologyRail(rail, 100.0)), emptyMap())
        val planned = TopologyRoutePlanner.plan(TopologyRoutePlanningInput(
            topology = topology,
            restrictions = RoutePlanningRestrictions.unrestricted(topology),
            start = OperationalRouteLocation(rail, 90.0),
            end = OperationalRouteLocation(rail, 10.0),
            routeId = "ba-projection-test",
            routeRevision = 0L,
            startFacing = ViewDirection.BA,
            endFacing = ViewDirection.BA,
            requireUniquePath = true
        ))
        assertTrue(planned is TopologyRoutePlanningResult.Planned, (planned as? TopologyRoutePlanningResult.Rejected)?.detail)
        val projected = DirectedRouteProjector.project(
            (planned as TopologyRoutePlanningResult.Planned).route,
            ProtectionRegionTopology.direct(topology)
        )
        assertTrue(projected is DirectedRouteProjectionResult.Projected, (projected as? DirectedRouteProjectionResult.Rejected)?.failure?.detail)
        val occurrences = (projected as DirectedRouteProjectionResult.Projected).route.occurrences
        assertTrue(occurrences.isNotEmpty())
        occurrences.forEach { occurrence ->
            assertEquals(ViewDirection.BA, occurrence.facing)
            assertTrue(occurrence.canonicalStartAbMeters >= 0.0, "canonical start outside rail: $occurrence")
            assertTrue(occurrence.canonicalEndAbMeters >= 0.0, "canonical end outside rail: $occurrence")
            assertTrue(occurrence.canonicalStartAbMeters <= 100.0, "canonical start beyond rail: $occurrence")
            assertTrue(occurrence.canonicalEndAbMeters <= 100.0, "canonical end beyond rail: $occurrence")
        }
    }
}
