package work.art1st.javarailways.basic.api.route.planning

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.topology.*
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class PhysicalRailDistanceTest {
    private fun rail(id: Long, length: Double) = TestTopologyRail(UUID(0, id), length)
    private fun port(rail: TestTopologyRail, end: RailEndpoint) = RailEndpointRef(rail.id, end)
    private fun topology(rails: List<TestTopologyRail>, vararg connections: Pair<RailEndpointRef, RailEndpointRef>,
        crossings: List<AtGradeRailCrossing> = emptyList()): TopologyState {
        val links = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
        connections.forEach { (a, b) -> links.getOrPut(a, ::linkedSetOf) += b; links.getOrPut(b, ::linkedSetOf) += a }
        return buildTestTopology(1, rails, links, atGradeCrossings = crossings)
    }

    @Test fun `physical distance chooses metre shortest branch independently of path hop count`() {
        val entry = rail(1, 10.0); val exit = rail(2, 10.0)
        val long = rail(3, 50.0); val a = rail(4, 2.0); val b = rail(5, 3.0)
        val root = topology(listOf(entry, exit, long, a, b),
            port(entry, RailEndpoint.B) to port(long, RailEndpoint.A),
            port(entry, RailEndpoint.B) to port(a, RailEndpoint.A),
            port(a, RailEndpoint.B) to port(b, RailEndpoint.A),
            port(b, RailEndpoint.B) to port(exit, RailEndpoint.A),
            port(long, RailEndpoint.B) to port(exit, RailEndpoint.A))
        assertEquals(9.0, PhysicalRailDistance.between(root, entry.id, 8.0, exit.id, 2.0))
        assertEquals(9.0, PhysicalRailDistance.between(root, exit.id, 2.0, entry.id, 8.0))
    }

    @Test fun `valid same point shared joint and subnanometre positions are zero under planner precision`() {
        val a = rail(1, 10.0); val b = rail(2, 10.0)
        val root = topology(listOf(a, b), port(a, RailEndpoint.B) to port(b, RailEndpoint.A))
        assertEquals(0.0, PhysicalRailDistance.between(root, a.id, 3.0, a.id, 3.0))
        assertEquals(0.0, PhysicalRailDistance.between(root, a.id, 10.0, b.id, 0.0))
        assertEquals(0.0, PhysicalRailDistance.between(root, a.id, 3.0, a.id, 3.0 + 1e-10))
        for (bad in listOf(-0.1, 11.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { PhysicalRailDistance.between(root, a.id, bad, a.id, bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { PhysicalRailDistance.between(root, UUID(0, 99), 0.0, UUID(0, 99), 0.0) }
    }

    @Test fun `same rail loop may be shorter than direct distance and search has no fixed range`() {
        val a = rail(1, 100.0); val loop = rail(2, 1.0)
        val root = topology(listOf(a, loop), port(a, RailEndpoint.A) to port(loop, RailEndpoint.B),
            port(a, RailEndpoint.B) to port(loop, RailEndpoint.A))
        assertEquals(5.0, PhysicalRailDistance.between(root, a.id, 2.0, a.id, 98.0))
        val isolated = topology(listOf(rail(3, 100_000.0)))
        assertEquals(100_000.0, PhysicalRailDistance.between(isolated, UUID(0, 3), 0.0, UUID(0, 3), 100_000.0))
    }

    @Test fun `disconnected crossing and illegal sideways turnout traversal do not become connections`() {
        val trunk = rail(1, 10.0); val left = rail(2, 10.0); val right = rail(3, 10.0)
        val root = topology(listOf(trunk, left, right),
            port(trunk, RailEndpoint.B) to port(left, RailEndpoint.A),
            port(trunk, RailEndpoint.B) to port(right, RailEndpoint.A))
        assertNull(PhysicalRailDistance.between(root, left.id, 5.0, right.id, 5.0))
        assertEquals(4.0, PhysicalRailDistance.between(root, trunk.id, 8.0, right.id, 2.0))
        val crossing = topology(listOf(left, right), crossings = listOf(AtGradeRailCrossing(
            RailCrossingPoint(left.id, 5.0), RailCrossingPoint(right.id, 5.0))))
        assertNull(PhysicalRailDistance.between(crossing, left.id, 5.0, right.id, 5.0))
    }
}
