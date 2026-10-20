package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.api.physics.infrastructure.TurnoutResourceIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import java.util.UUID

class PhysicalRouteDependencyCompilerTest {
    @Test
    fun `plan without known routes needs no physical dependency`() {
        val topology = topologyState(
            revision = 1L,
            railIds = emptyList(),
            connections = emptyList()
        )

        assertEquals(
            PhysicalRouteDependencyCompilation.NoDependencies,
            PhysicalRouteDependencyCompiler.compile(topology, emptyList())
        )
    }

    @Test
    fun `known route compiles exact rails traversed node turnout and diamond`() {
        val trunk = rail(1)
        val straight = rail(2)
        val branch = rail(3)
        val crossingRail = rail(4)
        val crossingPoints = listOf(
            RailCrossingPoint(trunk, 5.0),
            RailCrossingPoint(crossingRail, 5.0)
        ).sortedBy { it.railId.toString() }
        val crossing = AtGradeRailCrossing(crossingPoints[0], crossingPoints[1])
        val topology = topologyState(
            revision = 7L,
            railIds = listOf(trunk, straight, branch, crossingRail),
            connections = listOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(straight, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)
            ),
            atGradeCrossings = listOf(crossing)
        )
        val route = RouteSnapshot(
            "test:timetable/route",
            0L,
            topology.revision,
            listOf(
                RouteSegmentSnapshot(trunk, ViewDirection.AB, 1.0, 10.0),
                RouteSegmentSnapshot(straight, ViewDirection.AB, 0.0, 6.0)
            )
        )

        val result = assertInstanceOf(
            PhysicalRouteDependencyCompilation.Compiled::class.java,
            PhysicalRouteDependencyCompiler.compile(topology, listOf(route))
        )
        val turnout = topology.nodes.values.filterIsInstance<TopologyNode.Turnout>().single()

        assertEquals(setOf(trunk, straight), result.dependencies.railIds)
        assertEquals(setOf(StableTopologyNodeKey(turnout.ports)), result.dependencies.topologyNodeKeys)
        assertEquals(
            setOf(
                TurnoutResourceIdentity.fromPorts(turnout.ports),
                PhysicalTopologyResources.diamondResource(crossing)
            ),
            result.dependencies.resourceIds
        )
    }

    @Test
    fun `invalid known route is rejected without choosing another turnout branch`() {
        val trunk = rail(1)
        val straight = rail(2)
        val branch = rail(3)
        val topology = topologyState(
            revision = 2L,
            railIds = listOf(trunk, straight, branch),
            connections = listOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(straight, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)
            )
        )
        val illegal = RouteSnapshot(
            "test:timetable/illegal",
            0L,
            topology.revision,
            listOf(
                RouteSegmentSnapshot(branch, ViewDirection.BA, 0.0, 10.0),
                RouteSegmentSnapshot(straight, ViewDirection.AB, 0.0, 10.0)
            )
        )

        val result = assertInstanceOf(
            PhysicalRouteDependencyCompilation.Rejected::class.java,
            PhysicalRouteDependencyCompiler.compile(topology, listOf(illegal))
        )

        assertTrue(result.detail.contains("illegal turnout continuation"))
    }

    private fun rail(value: Long) = UUID(0L, value)
}
