package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.*
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.simplesignals.region.*
import java.util.UUID

class ProtectionRouteDiscoveryTest {
    private val a = UUID(0, 1)
    private val short = UUID(0, 2)
    private val long = UUID(0, 3)
    private val z = UUID(0, 4)
    private fun cut(id: String, rail: UUID, facing: ViewDirection, ab: Double = 5.0) =
        ProtectionBoundaryDefinition("test:$id", RailPositionSnapshot(rail, facing, ab))
    private val region = ProtectionRegionDefinition("test:region", 0,
        listOf(cut("entry", a, ViewDirection.AB), cut("exit", z, ViewDirection.BA)))
    private fun graph(longLength: Double = 20.0, extra: List<TestTopologyRail> = emptyList()): TopologyState {
        val connections = listOf(endpoint(a, RailEndpoint.B) to endpoint(short, RailEndpoint.A),
            endpoint(a, RailEndpoint.B) to endpoint(long, RailEndpoint.A),
            endpoint(short, RailEndpoint.B) to endpoint(z, RailEndpoint.A),
            endpoint(long, RailEndpoint.B) to endpoint(z, RailEndpoint.A))
        val adjacency = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
        for ((from, to) in connections) { adjacency.getOrPut(from, ::linkedSetOf) += to; adjacency.getOrPut(to, ::linkedSetOf) += from }
        return buildTestTopology(1, listOf(TestTopologyRail(a, 10.0), TestTopologyRail(short, 10.0),
            TestTopologyRail(long, longLength), TestTopologyRail(z, 10.0)) + extra, adjacency)
    }
    private fun find(root: TopologyState = graph(), policies: Map<UUID, ViewDirection?> = mapOf(a to null, short to null, long to null, z to null),
        definition: ProtectionRegionDefinition = region): DiscoveredProtectionRoutes {
        val resolved = ProtectionRegionGraphResolver.resolve(definition, ProtectionRegionTopology.direct(root)) as ProtectionRegionGraphResolution.Resolved
        return ProtectionRouteDiscovery.discover(root, definition, resolved.region.interior, policies)
    }

    @Test fun `one shortest route per ordered pair warns only both boundaries`() {
        val found = find()
        assertEquals(2, found.routes.size)
        val forward = found.routes.single { it.entryBoundaryId == "test:entry" }
        assertEquals(listOf(a, short, z), forward.traversals.map { it.railId })
        assertEquals(20.0, forward.traversals.sumOf { it.upperAbMeters - it.lowerAbMeters })
        assertEquals(setOf("test:entry", "test:exit"), found.warningBoundaryIds)
        val reverse = found.routes.single { it.entryBoundaryId == "test:exit" }
        assertEquals(listOf(z, short, a), reverse.traversals.map { it.railId })
        assertTrue(reverse.traversals.all { it.facing == ViewDirection.BA })
    }

    @Test fun `equal length choices are stable independent of boundary input ordering`() {
        val root = graph(10.0)
        val first = find(root)
        val reversed = ProtectionRegionDefinition(region.regionId, 0, region.boundaries.reversed())
        assertEquals(first.routes, find(root, definition = reversed).routes)
        assertTrue(first.routes.all { short in it.traversals.map { span -> span.railId } })
    }

    @Test fun `operating directions remove unavailable entries and illegal alternate branches`() {
        val found = find(policies = mapOf(a to ViewDirection.AB, short to ViewDirection.AB,
            long to ViewDirection.BA, z to ViewDirection.AB))
        assertEquals(1, found.routes.size)
        assertEquals(listOf(a, short, z), found.routes.single().traversals.map { it.railId })
        assertTrue(found.warningBoundaryIds.isEmpty())
    }

    @Test fun `no direction legal path produces no routes and no warning`() {
        val found = find(policies = mapOf(a to ViewDirection.AB, short to ViewDirection.BA,
            long to ViewDirection.BA, z to ViewDirection.AB))
        assertTrue(found.routes.isEmpty())
        assertTrue(found.warningBoundaryIds.isEmpty())
    }

    @Test fun `same rail cuts stop at exit and never search outside regional rails`() {
        val root = lineTopologyState(1, a, short, long, z)
        val definition = ProtectionRegionDefinition("test:small", 0,
            listOf(cut("entry", a, ViewDirection.AB, 2.0), cut("exit", a, ViewDirection.BA, 8.0)))
        val found = find(root, mapOf(a to null), definition)
        assertEquals(2, found.routes.size)
        assertTrue(found.routes.all { it.traversals.single().railId == a })
        assertTrue(found.warningBoundaryIds.isEmpty())
    }

    @Test fun `large disconnected world does not change regional route result or requested directions`() {
        val extra = (100L..800L).map { TestTopologyRail(UUID(0, it), 1000.0) }
        assertEquals(find().routes, find(graph(extra = extra)).routes)
    }

    @Test fun `turnout branch ports cannot transfer directly to another branch`() {
        val root = topologyState(1, listOf(a, short, long), listOf(
            endpoint(a, RailEndpoint.B) to endpoint(short, RailEndpoint.A),
            endpoint(a, RailEndpoint.B) to endpoint(long, RailEndpoint.A)))
        val definition = ProtectionRegionDefinition("test:turnout", 0, listOf(
            cut("stem", a, ViewDirection.AB), cut("first", short, ViewDirection.BA), cut("second", long, ViewDirection.BA)))
        val found = find(root, mapOf(a to null, short to null, long to null), definition)
        assertEquals(4, found.routes.size)
        assertFalse(found.routes.any { it.entryBoundaryId == "test:first" && it.exitBoundaryId == "test:second" })
        assertFalse(found.routes.any { it.entryBoundaryId == "test:second" && it.exitBoundaryId == "test:first" })
        assertTrue(found.warningBoundaryIds.isEmpty())
    }

    @Test fun `diamond conflicts do not add cross line routes`() {
        val other = UUID(0, 100)
        val root = topologyState(1, listOf(a, other), emptyList(), listOf(AtGradeRailCrossing(RailCrossingPoint(a, 5.0), RailCrossingPoint(other, 5.0))))
        val definition = ProtectionRegionDefinition("test:line", 0,
            listOf(cut("entry", a, ViewDirection.AB, 2.0), cut("exit", a, ViewDirection.BA, 8.0)))
        assertTrue(find(root, mapOf(a to null), definition).routes.all { it.traversals.single().railId == a })
    }

    @Test fun `extra cyclic walk containing all shortest edges is not another simple route`() {
        val loop1 = UUID(0, 7); val loop2 = UUID(0, 8)
        val root = topologyState(1, listOf(a, short, z, loop1, loop2), listOf(
            endpoint(a, RailEndpoint.B) to endpoint(short, RailEndpoint.A),
            endpoint(short, RailEndpoint.B) to endpoint(loop1, RailEndpoint.A),
            endpoint(loop1, RailEndpoint.B) to endpoint(loop2, RailEndpoint.A),
            endpoint(loop2, RailEndpoint.B) to endpoint(short, RailEndpoint.A),
            endpoint(short, RailEndpoint.B) to endpoint(z, RailEndpoint.A)))
        // The detour returns to short AB: every exit walk still contains all shortest edges.
        val found = find(root, mapOf(a to ViewDirection.AB, short to ViewDirection.AB,
            z to ViewDirection.AB, loop1 to ViewDirection.AB, loop2 to ViewDirection.AB))
        assertEquals(1, found.routes.size)
        assertTrue(found.warningBoundaryIds.isEmpty())
    }
}
