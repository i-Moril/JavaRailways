package work.art1st.javarailways.basic.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.PathReplacement
import work.art1st.javarailways.basic.api.route.planning.PhysicalTraversalPath
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class TopologyRouteUniquenessTest {
    private fun rail(id: Int, length: Double = 10.0) = TestTopologyRail(UUID(0, id.toLong()), length)
    private fun TestTopologyRail.a() = RailEndpointRef(id, RailEndpoint.A)
    private fun TestTopologyRail.b() = RailEndpointRef(id, RailEndpoint.B)
    private fun location(rail: TestTopologyRail, at: Double) = OperationalRouteLocation(rail.id, at)
    private fun topology(rails: List<TestTopologyRail>, vararg connections: Pair<RailEndpointRef, RailEndpointRef>): TopologyState {
        val continuations = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
        connections.forEach { (a, b) -> continuations.getOrPut(a) { linkedSetOf() } += b; continuations.getOrPut(b) { linkedSetOf() } += a }
        return buildTestTopology(1L, rails, continuations)
    }
    private fun input(topology: TopologyState, start: OperationalRouteLocation, end: OperationalRouteLocation) =
        TopologyRoutePlanningInput(topology, RoutePlanningRestrictions.unrestricted(topology), start, end,
            "unique-test", 1L, startFacing = ViewDirection.AB, requireUniquePath = true)
    private fun rejection(input: TopologyRoutePlanningInput) = (TopologyRoutePlanner.plan(input) as TopologyRoutePlanningResult.Rejected).reason

    @Test fun `both equal and different length physical alternatives are ambiguous while default stays shortest`() {
        for (length in listOf(10.0, 20.0)) {
            val entry = rail(1); val shorter = rail(2); val longer = rail(3, length); val exit = rail(4)
            val topology = topology(listOf(entry, shorter, longer, exit), entry.b() to shorter.a(), entry.b() to longer.a(), shorter.b() to exit.a(), longer.b() to exit.a())
            val query = input(topology, location(entry, 0.0), location(exit, 10.0))
            assertEquals(TopologyRoutePlanningRejection.AMBIGUOUS, rejection(query))
            assertTrue(TopologyRoutePlanner.plan(query.copy(requireUniquePath = false)) is TopologyRoutePlanningResult.Planned)
            val restricted = RoutePlanningRestrictions(topology.revision, forbiddenTraversals = listOf(DirectedRailSpan(longer.id, ViewDirection.AB, 0.0, length)))
            assertTrue(TopologyRoutePlanner.plan(query.copy(restrictions = restricted)) is TopologyRoutePlanningResult.Planned)
        }
    }

    @Test fun `protected alternatives retain physical identity and duplicate split declarations do not add paths`() {
        val entry = rail(1); val shorter = rail(2); val longer = rail(3, 20.0); val exit = rail(4)
        val topology = topology(listOf(entry, shorter, longer, exit), entry.b() to shorter.a(), entry.b() to longer.a(), shorter.b() to exit.a(), longer.b() to exit.a())
        val shortSpan = DirectedRailSpan(shorter.id, ViewDirection.AB, 0.0, 10.0)
        val longSpan = DirectedRailSpan(longer.id, ViewDirection.AB, 0.0, 20.0)
        val query = input(topology, location(entry, 0.0), location(exit, 10.0))
        fun restrictions(paths: List<PhysicalTraversalPath>) = RoutePlanningRestrictions(topology.revision,
            pathReplacements = listOf(PathReplacement(listOf(shortSpan, longSpan), paths)))
        assertEquals(TopologyRoutePlanningRejection.AMBIGUOUS, rejection(query.copy(restrictions = restrictions(listOf(PhysicalTraversalPath(listOf(shortSpan)), PhysicalTraversalPath(listOf(longSpan)))))))
        val split = PhysicalTraversalPath(listOf(DirectedRailSpan(shorter.id, ViewDirection.AB, 0.0, 4.0), DirectedRailSpan(shorter.id, ViewDirection.AB, 4.0, 10.0)))
        val duplicate = restrictions(listOf(PhysicalTraversalPath(listOf(shortSpan)), split, split))
        assertTrue(TopologyRoutePlanner.plan(query.copy(restrictions = duplicate)) is TopologyRoutePlanningResult.Planned)
        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejection(query.copy(restrictions = duplicate, blockedLocations = listOf(location(shorter, 5.0)))))
        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejection(query.copy(restrictions = duplicate, end = location(shorter, 5.0))))
    }

    @Test fun `blocked intermediate locations require explicit adjacent queries in both directions`() {
        val rail = rail(1, 30.0); val topology = topology(listOf(rail))
        val start = location(rail, 2.0); val via = location(rail, 15.0); val end = location(rail, 28.0)
        for (reverse in listOf(false, true)) {
            val from = if (reverse) end else start; val to = if (reverse) start else end
            val query = input(topology, from, to).copy(startFacing = if (reverse) ViewDirection.BA else ViewDirection.AB, blockedLocations = listOf(start, via, end))
            assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejection(query))
            assertTrue(TopologyRoutePlanner.plan(query.copy(end = via)) is TopologyRoutePlanningResult.Planned)
            assertTrue(TopologyRoutePlanner.plan(query.copy(start = via)) is TopologyRoutePlanningResult.Planned)
            assertTrue(TopologyRoutePlanner.plan(query.copy(blockedLocations = listOf(start, end, start))) is TopologyRoutePlanningResult.Planned)
        }
    }

    @Test fun `same physical rail boundary aliases are legitimate endpoints`() {
        val first = rail(1); val second = rail(2)
        val topology = topology(listOf(first, second), first.b() to second.a())
        val query = input(topology, location(first, 1.0), location(second, 0.0))
        assertTrue(TopologyRoutePlanner.plan(query.copy(blockedLocations = listOf(location(first, 10.0)))) is TopologyRoutePlanningResult.Planned)
        assertEquals(TopologyRoutePlanningRejection.INVALID_LOCATION, rejection(query.copy(start = location(first, 10.0))))
    }

    @Test fun `single required loop wrap is allowed but extra junction detours are not another simple path`() {
        val first = rail(1); val second = rail(2); val third = rail(3)
        val ring = topology(listOf(first, second, third), first.b() to second.a(), second.b() to third.a(), third.b() to first.a())
        val wrap = TopologyRoutePlanner.plan(input(ring, location(first, 8.0), location(first, 2.0))) as TopologyRoutePlanningResult.Planned
        assertEquals(24.0, wrap.route.totalLengthMeters, 1e-9)
        val entry = rail(4); val exit = rail(5)
        val loop = topology(listOf(entry, exit, first, second, third), entry.b() to exit.a(), entry.b() to first.a(), first.b() to second.a(), second.b() to third.a(), third.b() to exit.a())
        val result = TopologyRoutePlanner.plan(input(loop, location(entry, 0.0), location(exit, 10.0))) as TopologyRoutePlanningResult.Planned
        assertEquals(listOf(entry.id, exit.id), result.route.segments.filter { it.lengthMeters > 0.0 }.map { it.railId })
    }

    @Test fun `work limit is an explicit inconclusive rejection and invalid prune locations are not ignored`() {
        val rail = rail(1); val topology = topology(listOf(rail)); val query = input(topology, location(rail, 1.0), location(rail, 9.0))
        assertEquals(TopologyRoutePlanningRejection.SEARCH_LIMIT_EXCEEDED, rejection(query.copy(uniqueSearchLimit = 1)))
        assertEquals(TopologyRoutePlanningRejection.INVALID_LOCATION, rejection(query.copy(blockedLocations = listOf(OperationalRouteLocation(UUID(9, 9), 1.0)))))
    }
}
