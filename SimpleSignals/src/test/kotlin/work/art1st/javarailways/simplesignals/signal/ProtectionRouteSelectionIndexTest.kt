package work.art1st.javarailways.simplesignals.signal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.simplesignals.region.*
import java.util.UUID

class ProtectionRouteSelectionIndexTest {
    @Test
    fun `route is physically open only when every turnout follows it`() {
        val entry = rail(1)
        val selected = rail(2)
        val other = rail(3)
        val topology = topologyState(7L, listOf(entry, selected, other), listOf(
            endpoint(entry, RailEndpoint.B) to endpoint(selected, RailEndpoint.A),
            endpoint(entry, RailEndpoint.B) to endpoint(other, RailEndpoint.A)))
        val route = route("test:selected", "test:entry", entry, selected)
        val index = ProtectionRouteSelectionIndex.compile(topology, listOf(table(listOf(route))))
        val compiled = index.routesById.getValue(route.routeId)
        assertEquals(1, compiled.requiredContinuations.size)
        assertTrue(compiled.isPhysicallyOpen(ActiveRailContinuationsSnapshot.create(topology.revision, 1L,
            mapOf(endpoint(entry, RailEndpoint.B) to endpoint(selected, RailEndpoint.A)))))
        assertFalse(compiled.isPhysicallyOpen(ActiveRailContinuationsSnapshot.create(topology.revision, 2L,
            mapOf(endpoint(entry, RailEndpoint.B) to endpoint(other, RailEndpoint.A)))))
        assertFalse(compiled.isPhysicallyOpen(ActiveRailContinuationsSnapshot.empty(topology.revision)))
    }

    @Test
    fun `alternative routes sharing an entrance and turnout compile together`() {
        val entry = rail(10)
        val firstExit = rail(11)
        val secondExit = rail(12)
        val topology = topologyState(8L, listOf(entry, firstExit, secondExit), listOf(
            endpoint(entry, RailEndpoint.B) to endpoint(firstExit, RailEndpoint.A),
            endpoint(entry, RailEndpoint.B) to endpoint(secondExit, RailEndpoint.A)))
        val first = route("test:first", "test:entry", entry, firstExit)
        val second = route("test:second", "test:entry", entry, secondExit)
        val index = ProtectionRouteSelectionIndex.compile(topology, listOf(table(listOf(first, second))))
        assertEquals(setOf(first.routeId, second.routeId), index.routesById.keys)
        val active = ActiveRailContinuationsSnapshot.create(topology.revision, 1L,
            mapOf(endpoint(entry, RailEndpoint.B) to endpoint(firstExit, RailEndpoint.A)))
        assertTrue(index.routesById.getValue(first.routeId).isPhysicallyOpen(active))
        assertFalse(index.routesById.getValue(second.routeId).isPhysicallyOpen(active))
        val retained = ProtectionRouteSelectionIndex.compile(topology, listOf(table(listOf(first, second))), index)
        assertSame(index.routesById.getValue(first.routeId), retained.routesById.getValue(first.routeId))
    }

    @Test
    fun `duplicate route identity is rejected within and across tables`() {
        val entry = rail(20)
        val exit = rail(21)
        val topology = topologyState(9L, listOf(entry, exit),
            listOf(endpoint(entry, RailEndpoint.B) to endpoint(exit, RailEndpoint.A)))
        val route = route("test:route", "test:entry", entry, exit)
        assertThrows(IllegalArgumentException::class.java) {
            ProtectionRouteSelectionIndex.compile(topology, listOf(table(listOf(route, route))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtectionRouteSelectionIndex.compile(topology, listOf(table(listOf(route)),
                ProtectionRouteTableDefinition("test:other-table", 1L, "test:region", 1L, listOf(route))))
        }
    }

    @Test
    fun `disconnected directed traversal is rejected before runtime publication`() {
        val entry = rail(30)
        val exit = rail(31)
        val topology = topologyState(10L, listOf(entry, exit), emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            ProtectionRouteSelectionIndex.compile(topology,
                listOf(table(listOf(route("test:route", "test:entry", entry, exit)))))
        }
    }

    private fun table(routes: Collection<ProtectionRouteDefinition>) =
        ProtectionRouteTableDefinition("test:table", 1L, "test:region", 1L, routes)

    private fun route(id: String, entry: String, entryRail: UUID, exitRail: UUID) =
        ProtectionRouteDefinition(id, entry, "$id-exit", listOf(
            ProtectionRouteTraversal(entryRail, ViewDirection.AB, 0.0, 10.0),
            ProtectionRouteTraversal(exitRail, ViewDirection.AB, 0.0, 10.0)))

    private fun rail(number: Long): UUID = UUID(0L, number)
}
