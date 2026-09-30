package work.art1st.javarailways.simplesignals.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.simplesignals.region.ProtectionRouteDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.simplesignals.region.topologyState
import java.util.UUID

class ProtectionRouteSelectionIndexTest {
    @Test
    fun `configured default route is open only when every turnout follows it`() {
        val entry = rail(1)
        val selected = rail(2)
        val other = rail(3)
        val topology = topologyState(
            revision = 7L,
            railIds = listOf(entry, selected, other),
            connections = listOf(
                endpoint(entry, RailEndpoint.B) to endpoint(selected, RailEndpoint.A),
                endpoint(entry, RailEndpoint.B) to endpoint(other, RailEndpoint.A)
            )
        )
        val route = route("test:selected", "test:entry", entry, selected)
        val index = ProtectionRouteSelectionIndex.compile(
            topology,
            listOf(table(listOf(route), listOf(route.routeId)))
        )

        val matching = ActiveRailContinuationsSnapshot.create(
            topology.revision,
            1L,
            mapOf(endpoint(entry, RailEndpoint.B) to endpoint(selected, RailEndpoint.A))
        )
        val diverging = ActiveRailContinuationsSnapshot.create(
            topology.revision,
            2L,
            mapOf(endpoint(entry, RailEndpoint.B) to endpoint(other, RailEndpoint.A))
        )

        assertEquals(route.routeId, index.defaultRoute("test:entry", matching)?.definition?.routeId)
        assertNull(index.defaultRoute("test:entry", diverging))
        assertNull(index.defaultRoute("test:unconfigured-entry", matching))
    }

    @Test
    fun `several physically disjoint defaults compile together without claims`() {
        val firstEntry = rail(10)
        val firstExit = rail(11)
        val secondEntry = rail(12)
        val secondExit = rail(13)
        val topology = topologyState(
            revision = 8L,
            railIds = listOf(firstEntry, firstExit, secondEntry, secondExit),
            connections = listOf(
                endpoint(firstEntry, RailEndpoint.B) to endpoint(firstExit, RailEndpoint.A),
                endpoint(secondEntry, RailEndpoint.B) to endpoint(secondExit, RailEndpoint.A)
            )
        )
        val first = route("test:first", "test:first-entry", firstEntry, firstExit)
        val second = route("test:second", "test:second-entry", secondEntry, secondExit)
        val index = ProtectionRouteSelectionIndex.compile(
            topology,
            listOf(table(listOf(first, second), listOf(first.routeId, second.routeId)))
        )
        val active = ActiveRailContinuationsSnapshot.empty(topology.revision)

        assertEquals(first.routeId, index.defaultRoute("test:first-entry", active)?.definition?.routeId)
        assertEquals(second.routeId, index.defaultRoute("test:second-entry", active)?.definition?.routeId)
    }

    @Test
    fun `shared rail and diamond defaults are rejected as conflicts`() {
        val entry = rail(20)
        val firstExit = rail(21)
        val secondExit = rail(22)
        val branchingTopology = topologyState(
            revision = 9L,
            railIds = listOf(entry, firstExit, secondExit),
            connections = listOf(
                endpoint(entry, RailEndpoint.B) to endpoint(firstExit, RailEndpoint.A),
                endpoint(entry, RailEndpoint.B) to endpoint(secondExit, RailEndpoint.A)
            )
        )
        val first = route("test:first", "test:shared-entry", entry, firstExit)
        val second = route("test:second", "test:shared-entry", entry, secondExit)

        assertThrows(IllegalArgumentException::class.java) {
            ProtectionRouteSelectionIndex.compile(
                branchingTopology,
                listOf(table(listOf(first, second), listOf(first.routeId, second.routeId)))
            )
        }

        val crossingFirst = rail(23)
        val crossingSecond = rail(24)
        val crossingTopology = topologyState(
            revision = 10L,
            railIds = listOf(crossingFirst, crossingSecond),
            connections = emptyList(),
            atGradeCrossings = listOf(
                AtGradeRailCrossing(
                    RailCrossingPoint(crossingFirst, 5.0),
                    RailCrossingPoint(crossingSecond, 5.0)
                )
            )
        )
        val acrossFirst = singleRailRoute("test:across-first", "test:first-entry", crossingFirst)
        val acrossSecond = singleRailRoute("test:across-second", "test:second-entry", crossingSecond)

        assertThrows(IllegalArgumentException::class.java) {
            ProtectionRouteSelectionIndex.compile(
                crossingTopology,
                listOf(
                    table(
                        listOf(acrossFirst, acrossSecond),
                        listOf(acrossFirst.routeId, acrossSecond.routeId)
                    )
                )
            )
        }
    }

    @Test
    fun `unknown default route is rejected before runtime publication`() {
        val first = rail(30)
        val second = rail(31)
        val topology = topologyState(
            revision = 11L,
            railIds = listOf(first, second),
            connections = listOf(endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A))
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProtectionRouteSelectionIndex.compile(
                topology,
                listOf(table(listOf(route("test:route", "test:entry", first, second)), listOf("test:missing")))
            )
        }
    }

    private fun table(
        routes: Collection<ProtectionRouteDefinition>,
        defaults: Collection<String>
    ) = ProtectionRouteTableDefinition(
        tableId = "test:table",
        revision = 1L,
        regionId = "test:region",
        regionRevision = 1L,
        routes = routes,
        defaultRouteIds = defaults
    )

    private fun route(
        routeId: String,
        entryBoundaryId: String,
        entryRail: UUID,
        exitRail: UUID
    ) = ProtectionRouteDefinition(
        routeId,
        entryBoundaryId,
        "$routeId-exit",
        listOf(
            ProtectionRouteTraversal(entryRail, ViewDirection.AB, 0.0, 10.0),
            ProtectionRouteTraversal(exitRail, ViewDirection.AB, 0.0, 10.0)
        )
    )

    private fun singleRailRoute(
        routeId: String,
        entryBoundaryId: String,
        railId: UUID
    ) = ProtectionRouteDefinition(
        routeId,
        entryBoundaryId,
        "$routeId-exit",
        listOf(ProtectionRouteTraversal(railId, ViewDirection.AB, 0.0, 10.0))
    )

    private fun rail(number: Long): UUID = UUID(0L, number)
}
