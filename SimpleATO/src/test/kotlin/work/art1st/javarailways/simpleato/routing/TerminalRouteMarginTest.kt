package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.PathReplacement
import work.art1st.javarailways.basic.api.route.planning.PhysicalTraversalPath
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class TerminalRouteMarginTest {
    private val first = UUID(0L, 71L)
    private val second = UUID(0L, 72L)
    private val branch = UUID(0L, 73L)

    @Test
    fun `five meter margin appends real same rail geometry without rewriting frozen prefix`() {
        val topology = buildTestTopology(1L, listOf(TestTopologyRail(first, 100.0)), emptyMap())
        val route = route(segment(first, ViewDirection.AB, 10.0, 70.0),
            segment(first, ViewDirection.AB, 70.0, 82.0))

        val proposal = TerminalRouteMargin.propose(topology, RoutePlanningRestrictions.unrestricted(topology), route, 77.0)

        assertNull(proposal.failure)
        assertEquals(route.segments, proposal.route.segments.take(2))
        assertEquals(segment(first, ViewDirection.AB, 82.0, 87.0), proposal.route.segments.last())
        assertEquals(77.0, proposal.route.totalLengthMeters)
        assertEquals(route.routeId, proposal.route.routeId)
        assertEquals(route.revision, proposal.route.revision)
        assertEquals(72.0, route.totalLengthMeters, "the immutable input is not extended in place")
    }

    @Test
    fun `ordinary physical continuation supplies remaining margin in AB and BA views`() {
        ViewDirection.entries.forEach { facing ->
            val exit = RailEndpointRef(first, if (facing == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A)
            val entrance = RailEndpointRef(second, if (facing == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B)
            val topology = buildTestTopology(1L, listOf(TestTopologyRail(first, 100.0), TestTopologyRail(second, 30.0)),
                mapOf(exit to setOf(entrance), entrance to setOf(exit)))
            val route = route(segment(first, facing, 10.0, 98.0))

            val proposal = TerminalRouteMargin.propose(topology, RoutePlanningRestrictions.unrestricted(topology), route, 93.0)

            assertNull(proposal.failure)
            assertEquals(route.segments + listOf(segment(first, facing, 98.0, 100.0),
                segment(second, facing, 0.0, 3.0)), proposal.route.segments)
            assertEquals(93.0, proposal.route.totalLengthMeters)
        }
    }

    @Test
    fun `already frozen margin and zero extra distance retain exact route instance`() {
        val topology = buildTestTopology(1L, listOf(TestTopologyRail(first, 100.0)), emptyMap())
        val route = route(segment(first, ViewDirection.AB, 10.0, 90.0))
        listOf(75.0, route.totalLengthMeters).forEach { required ->
            val proposal = TerminalRouteMargin.propose(topology, RoutePlanningRestrictions.unrestricted(topology), route, required)
            assertNull(proposal.failure)
            assertSame(route, proposal.route)
        }
    }

    @Test
    fun `forbidden directed geometry rejects margin including BA canonical interval conversion`() {
        val topology = buildTestTopology(1L, listOf(TestTopologyRail(first, 100.0)), emptyMap())
        ViewDirection.entries.forEach { facing ->
            val route = route(segment(first, facing, 10.0, 80.0))
            val lower = if (facing == ViewDirection.AB) 80.0 else 15.0
            val restriction = RoutePlanningRestrictions(topology.revision,
                forbiddenTraversals = listOf(DirectedRailSpan(first, facing, lower, lower + 5.0)))
            val rejected = TerminalRouteMargin.propose(topology, restriction, route, 75.0)
            assertTrue(rejected.failure.orEmpty().contains("forbidden"))
            assertSame(route, rejected.route)

            val opposite = if (facing == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB
            val allowed = TerminalRouteMargin.propose(topology, RoutePlanningRestrictions(topology.revision,
                forbiddenTraversals = listOf(DirectedRailSpan(first, opposite, lower, lower + 5.0))), route, 75.0)
            assertNull(allowed.failure, "an opposite-facing restriction is not this traversal")
            assertEquals(75.0, allowed.route.totalLengthMeters)
        }
    }

    @Test
    fun `margin does not invent selection of a replacement path`() {
        val topology = buildTestTopology(1L, listOf(TestTopologyRail(first, 100.0)), emptyMap())
        val route = route(segment(first, ViewDirection.AB, 10.0, 80.0))
        val replaced = DirectedRailSpan(first, ViewDirection.AB, 80.0, 90.0)
        val restrictions = RoutePlanningRestrictions(topology.revision,
            pathReplacements = listOf(PathReplacement(listOf(replaced), listOf(PhysicalTraversalPath(listOf(replaced))))))

        val proposal = TerminalRouteMargin.propose(topology, restrictions, route, 75.0)

        assertTrue(proposal.failure.orEmpty().contains("replacement path"))
        assertSame(route, proposal.route)
    }

    @Test
    fun `physical terminus retains available partial geometry but reports insufficient margin`() {
        val topology = buildTestTopology(1L, listOf(TestTopologyRail(first, 100.0)), emptyMap())
        val route = route(segment(first, ViewDirection.AB, 10.0, 98.0))

        val proposal = TerminalRouteMargin.propose(topology, RoutePlanningRestrictions.unrestricted(topology), route, 93.0)

        assertNotNull(proposal.failure)
        assertEquals(route.segments + segment(first, ViewDirection.AB, 98.0, 100.0), proposal.route.segments)
        assertEquals(90.0, proposal.route.totalLengthMeters)
    }

    @Test
    fun `turnout is not an ordinary continuation even when branches have enough track`() {
        val stem = RailEndpointRef(first, RailEndpoint.B)
        val straight = RailEndpointRef(second, RailEndpoint.A)
        val diverging = RailEndpointRef(branch, RailEndpoint.A)
        val topology = buildTestTopology(1L,
            listOf(TestTopologyRail(first, 100.0), TestTopologyRail(second, 30.0), TestTopologyRail(branch, 30.0)),
            mapOf(stem to setOf(straight, diverging), straight to setOf(stem), diverging to setOf(stem)))
        val route = route(segment(first, ViewDirection.AB, 10.0, 98.0))

        val proposal = TerminalRouteMargin.propose(topology, RoutePlanningRestrictions.unrestricted(topology), route, 93.0)

        assertTrue(proposal.failure.orEmpty().contains("unselected junction"))
        assertEquals(route.segments + segment(first, ViewDirection.AB, 98.0, 100.0), proposal.route.segments)
        assertTrue(proposal.route.segments.none { it.railId == second || it.railId == branch })
    }

    private fun route(vararg segments: RouteSegmentSnapshot) = RouteSnapshot("stop", 3L, 1L, segments.toList())

    private fun segment(rail: UUID, facing: ViewDirection, start: Double, end: Double) =
        RouteSegmentSnapshot(rail, facing, start, end)
}
