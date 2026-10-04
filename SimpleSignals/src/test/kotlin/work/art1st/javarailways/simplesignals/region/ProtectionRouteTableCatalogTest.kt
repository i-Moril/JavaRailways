package work.art1st.javarailways.simplesignals.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.basic.route.OperationalRouteLocation
import work.art1st.javarailways.basic.route.TopologyRoutePlanner
import work.art1st.javarailways.basic.route.TopologyRoutePlanningInput
import work.art1st.javarailways.basic.route.TopologyRoutePlanningRejection
import work.art1st.javarailways.basic.route.TopologyRoutePlanningResult
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

class ProtectionRouteTableCatalogTest {
    @Test
    fun `incomplete region does not allow a table to target a future region revision`() {
        val fixture = fixture()
        val draft = ProtectionRegionDefinition(fixture.region.regionId, 2,
            fixture.region.boundaries, emptyList())
        assertEquals(ProtectionRegionCatalogStatus.PUBLISHED,
            fixture.regions.publish(fixture.topology, 2, listOf(draft)).status)
        val tables = ProtectionRouteTableCatalog(fixture.regions)
        val retainedTable = fixture.table(1, listOf(fixture.upperRoute()))
        val invalidTable = ProtectionRouteTableDefinition(retainedTable.tableId, 1,
            retainedTable.regionId, 3, retainedTable.routes)
        val rejected = tables.publish(2, listOf(invalidTable))
        assertEquals(ProtectionRouteTableCatalogStatus.REJECTED, rejected.status)
        assertEquals(ProtectionRouteTableValidationCode.STALE_REGION_REVISION, rejected.diagnostics.single().code)
        assertEquals(ProtectionRouteTableCatalogStatus.PUBLISHED, tables.publish(2, listOf(retainedTable)).status)
        assertTrue(tables.usableDefinitions().isEmpty())
        assertEquals(setOf(draft.regionId), tables.unavailableRegionIds())
        assertTrue(tables.restrictionSource.restrictions(fixture.topology).forbiddenTraversals.isNotEmpty())
    }

    @Test
    fun `one permitted complete path replaces every free path through the region`() {
        val fixture = fixture()
        val tables = publishedTables(fixture, listOf(fixture.upperRoute()))

        val result = fixture.plan(tables.restrictionSource.restrictions(fixture.topology))
            as TopologyRoutePlanningResult.Planned
        val rails = result.route.segments.map { it.railId }

        assertTrue(rails.contains(fixture.upperIn))
        assertTrue(rails.contains(fixture.upperOut))
        assertFalse(rails.contains(fixture.lowerIn))
        assertFalse(rails.contains(fixture.lowerOut))
    }

    @Test
    fun `multiple table routes remain individually selectable`() {
        val fixture = fixture()
        val tables = publishedTables(fixture, listOf(fixture.upperRoute(), fixture.lowerRoute()))
        val compiled = tables.restrictionSource.restrictions(fixture.topology)
        assertEquals(2, compiled.pathReplacements.single().permittedPaths.size)

        val upperUnavailable = RoutePlanningRestrictions(
            fixture.topology.revision,
            forbiddenTraversals = listOf(fixture.span(fixture.upperOut))
        )
        val result = fixture.plan(
            RoutePlanningRestrictions.combine(fixture.topology, listOf(compiled, upperUnavailable))
        ) as TopologyRoutePlanningResult.Planned
        val rails = result.route.segments.map { it.railId }

        assertTrue(rails.contains(fixture.lowerIn))
        assertTrue(rails.contains(fixture.lowerOut))
        assertFalse(rails.contains(fixture.upperIn))
        assertFalse(rails.contains(fixture.upperOut))
    }

    @Test
    fun `one region may publish physically disjoint routes without a region resource`() {
        val upperEntry = rail(11)
        val upperCore = rail(12)
        val upperExit = rail(13)
        val lowerEntry = rail(14)
        val lowerCore = rail(15)
        val lowerExit = rail(16)
        val connector = rail(17)
        val topology = topologyState(
            revision = 42L,
            railIds = listOf(
                upperEntry, upperCore, upperExit,
                lowerEntry, lowerCore, lowerExit,
                connector
            ),
            connections = listOf(
                endpoint(upperEntry, RailEndpoint.B) to endpoint(upperCore, RailEndpoint.A),
                endpoint(upperCore, RailEndpoint.B) to endpoint(upperExit, RailEndpoint.A),
                endpoint(upperCore, RailEndpoint.B) to endpoint(connector, RailEndpoint.A),
                endpoint(lowerEntry, RailEndpoint.B) to endpoint(lowerCore, RailEndpoint.A),
                endpoint(lowerCore, RailEndpoint.B) to endpoint(lowerExit, RailEndpoint.A),
                endpoint(lowerCore, RailEndpoint.B) to endpoint(connector, RailEndpoint.B)
            )
        )
        val region = ProtectionRegionDefinition(
            regionId = "test:disjoint-throat",
            revision = 1L,
            boundaries = listOf(
                boundary("test:upper-entry", upperEntry, ViewDirection.AB),
                boundary("test:upper-exit", upperExit, ViewDirection.BA),
                boundary("test:lower-entry", lowerEntry, ViewDirection.AB),
                boundary("test:lower-exit", lowerExit, ViewDirection.BA)
            )
        )
        val regions = ProtectionRegionCatalog()
        assertEquals(
            ProtectionRegionCatalogStatus.PUBLISHED,
            regions.publish(topology, 1L, listOf(region)).status
        )
        val route = { id: String, entryId: String, exitId: String, rails: List<UUID> ->
            ProtectionRouteDefinition(
                routeId = id,
                entryBoundaryId = entryId,
                exitBoundaryId = exitId,
                traversals = listOf(
                    ProtectionRouteTraversal(rails[0], ViewDirection.AB, 5.0, 10.0),
                    ProtectionRouteTraversal(rails[1], ViewDirection.AB, 0.0, 10.0),
                    ProtectionRouteTraversal(rails[2], ViewDirection.AB, 0.0, 5.0)
                )
            )
        }
        val tables = ProtectionRouteTableCatalog(regions)
        assertEquals(
            ProtectionRouteTableCatalogStatus.PUBLISHED,
            tables.publish(
                1L,
                listOf(
                    ProtectionRouteTableDefinition(
                        tableId = "test:disjoint-throat-table",
                        revision = 1L,
                        regionId = region.regionId,
                        regionRevision = region.revision,
                        routes = listOf(
                            route(
                                "test:upper",
                                "test:upper-entry",
                                "test:upper-exit",
                                listOf(upperEntry, upperCore, upperExit)
                            ),
                            route(
                                "test:lower",
                                "test:lower-entry",
                                "test:lower-exit",
                                listOf(lowerEntry, lowerCore, lowerExit)
                            )
                        )
                    )
                )
            ).status
        )

        val replacement = tables.restrictionSource.restrictions(topology).pathReplacements.single()
        val pathRailSets = replacement.permittedPaths.map { path ->
            path.spans.mapTo(linkedSetOf(), DirectedRailSpan::railId)
        }

        assertEquals(2, pathRailSets.size)
        assertTrue(pathRailSets[0].intersect(pathRailSets[1]).isEmpty())
        // PathReplacement deliberately carries only physical traversals. A region ID is policy
        // metadata, not an exclusive Basic resource shared by both paths.
        assertEquals(
            setOf(upperEntry, upperCore, upperExit, lowerEntry, lowerCore, lowerExit),
            pathRailSets.flatten().toSet()
        )
    }

    @Test
    fun `search cannot assemble an unlisted hybrid from two permitted routes`() {
        val fixture = fixture()
        val tables = publishedTables(fixture, listOf(fixture.upperRoute(), fixture.lowerRoute()))
        val compiled = tables.restrictionSource.restrictions(fixture.topology)
        val onlyHybridRemainsPhysically = RoutePlanningRestrictions(
            fixture.topology.revision,
            forbiddenTraversals = listOf(
                fixture.span(fixture.upperOut),
                fixture.span(fixture.lowerIn)
            )
        )

        val rejected = fixture.plan(
            RoutePlanningRestrictions.combine(
                fixture.topology,
                listOf(compiled, onlyHybridRemainsPhysically)
            )
        ) as TopologyRoutePlanningResult.Rejected

        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejected.reason)
    }

    @Test
    fun `invalid route publication is atomic and reports the failed invariant`() {
        val fixture = fixture()
        val tables = publishedTables(fixture, listOf(fixture.upperRoute()))
        val retained = tables.restrictionSource.restrictions(fixture.topology)

        val invalidRoutes = listOf(
            fixture.route(
                routeId = "test:wrong-entry-direction",
                traversals = listOf(
                    ProtectionRouteTraversal(fixture.entry, ViewDirection.BA, 5.0, 10.0)
                ) + fixture.upperRoute().traversals.drop(1)
            ) to ProtectionRouteTableValidationCode.ENTRY_MISMATCH,
            fixture.route(
                routeId = "test:outside-region",
                traversals = fixture.upperRoute().traversals.toMutableList().apply {
                    add(1, ProtectionRouteTraversal(fixture.entry, ViewDirection.AB, 0.0, 5.0))
                }
            ) to ProtectionRouteTableValidationCode.TRAVERSAL_OUTSIDE_REGION,
            fixture.route(
                routeId = "test:discontinuous",
                traversals = listOf(
                    fixture.entryTraversal(),
                    fixture.traversal(fixture.upperOut),
                    fixture.exitTraversal()
                )
            ) to ProtectionRouteTableValidationCode.DISCONTINUOUS_PATH,
            fixture.route(
                routeId = "test:wrong-exit-direction",
                traversals = fixture.upperRoute().traversals.dropLast(1) +
                    ProtectionRouteTraversal(fixture.exit, ViewDirection.BA, 0.0, 5.0)
            ) to ProtectionRouteTableValidationCode.EXIT_MISMATCH
        )

        invalidRoutes.forEach { (route, expectedCode) ->
            val result = tables.publish(
                2L,
                listOf(fixture.table(2L, listOf(route)))
            )
            assertEquals(ProtectionRouteTableCatalogStatus.REJECTED, result.status)
            assertEquals(expectedCode, result.diagnostics.single().code)
            assertSame(retained, tables.restrictionSource.restrictions(fixture.topology))
        }
    }

    @Test
    fun `cached source fails closed after topology or region publication changes`() {
        val fixture = fixture()
        val tables = publishedTables(fixture, listOf(fixture.upperRoute()))
        val newTopology = fixture.topology(42L)

        assertThrows(IllegalStateException::class.java) {
            tables.restrictionSource.restrictions(newTopology)
        }

        assertEquals(
            ProtectionRegionCatalogStatus.PUBLISHED,
            fixture.regions.publish(newTopology, 2L, listOf(fixture.region)).status
        )
        assertThrows(IllegalStateException::class.java) {
            tables.restrictionSource.restrictions(fixture.topology)
        }
    }

    @Test
    fun `regions without a fixed route table contribute no replacement`() {
        val fixture = fixture()
        val tables = ProtectionRouteTableCatalog(fixture.regions)

        assertEquals(
            ProtectionRouteTableCatalogStatus.PUBLISHED,
            tables.publish(1L, emptyList()).status
        )
        assertTrue(
            tables.restrictionSource.restrictions(fixture.topology).pathReplacements.isEmpty()
        )
    }

    private fun publishedTables(
        fixture: Fixture,
        routes: Collection<ProtectionRouteDefinition>
    ): ProtectionRouteTableCatalog = ProtectionRouteTableCatalog(fixture.regions).also { tables ->
        assertEquals(
            ProtectionRouteTableCatalogStatus.PUBLISHED,
            tables.publish(1L, listOf(fixture.table(1L, routes))).status
        )
    }

    private fun boundary(
        boundaryId: String,
        railId: UUID,
        facing: ViewDirection
    ) = ProtectionBoundaryDefinition(
        boundaryId,
        RailPositionSnapshot(railId, facing, 5.0)
    )

    private fun fixture(): Fixture {
        val fixture = Fixture(
            entry = rail(1),
            upperIn = rail(2),
            lowerIn = rail(3),
            upperOut = rail(4),
            lowerOut = rail(5),
            exit = rail(6)
        )
        val topology = fixture.topology(41L)
        val region = ProtectionRegionDefinition(
            regionId = "test:throat",
            revision = 1L,
            boundaries = listOf(
                ProtectionBoundaryDefinition(
                    "test:entry",
                    RailPositionSnapshot(fixture.entry, ViewDirection.AB, 5.0)
                ),
                ProtectionBoundaryDefinition(
                    "test:exit",
                    RailPositionSnapshot(fixture.exit, ViewDirection.BA, 5.0)
                )
            )
        )
        val regions = ProtectionRegionCatalog()
        assertEquals(
            ProtectionRegionCatalogStatus.PUBLISHED,
            regions.publish(topology, 1L, listOf(region)).status
        )
        fixture.topology = topology
        fixture.region = region
        fixture.regions = regions
        return fixture
    }

    private class Fixture(
        val entry: UUID,
        val upperIn: UUID,
        val lowerIn: UUID,
        val upperOut: UUID,
        val lowerOut: UUID,
        val exit: UUID
    ) {
        lateinit var topology: TopologyState
        lateinit var region: ProtectionRegionDefinition
        lateinit var regions: ProtectionRegionCatalog

        fun topology(revision: Long): TopologyState = topologyState(
            revision = revision,
            railIds = listOf(entry, upperIn, lowerIn, upperOut, lowerOut, exit),
            connections = listOf(
                endpoint(entry, RailEndpoint.B) to endpoint(upperIn, RailEndpoint.A),
                endpoint(entry, RailEndpoint.B) to endpoint(lowerIn, RailEndpoint.A),
                endpoint(upperIn, RailEndpoint.B) to endpoint(upperOut, RailEndpoint.A),
                endpoint(upperIn, RailEndpoint.B) to endpoint(lowerOut, RailEndpoint.A),
                endpoint(lowerIn, RailEndpoint.B) to endpoint(lowerOut, RailEndpoint.A),
                endpoint(lowerIn, RailEndpoint.B) to endpoint(upperOut, RailEndpoint.A),
                endpoint(upperOut, RailEndpoint.B) to endpoint(exit, RailEndpoint.A),
                endpoint(lowerOut, RailEndpoint.B) to endpoint(exit, RailEndpoint.A)
            )
        )

        fun table(
            revision: Long,
            routes: Collection<ProtectionRouteDefinition>
        ) = ProtectionRouteTableDefinition(
            tableId = "test:throat-table",
            revision = revision,
            regionId = region.regionId,
            regionRevision = region.revision,
            routes = routes
        )

        fun upperRoute() = ProtectionRouteDefinition(
            routeId = "test:upper",
            entryBoundaryId = "test:entry",
            exitBoundaryId = "test:exit",
            traversals = listOf(
                entryTraversal(),
                traversal(upperIn),
                traversal(upperOut),
                exitTraversal()
            )
        )

        fun lowerRoute() = ProtectionRouteDefinition(
            routeId = "test:lower",
            entryBoundaryId = "test:entry",
            exitBoundaryId = "test:exit",
            traversals = listOf(
                entryTraversal(),
                traversal(lowerIn),
                traversal(lowerOut),
                exitTraversal()
            )
        )

        fun route(
            routeId: String,
            traversals: Collection<ProtectionRouteTraversal>
        ) = ProtectionRouteDefinition(
            routeId = routeId,
            entryBoundaryId = "test:entry",
            exitBoundaryId = "test:exit",
            traversals = traversals
        )

        fun entryTraversal() = ProtectionRouteTraversal(entry, ViewDirection.AB, 5.0, 10.0)

        fun exitTraversal() = ProtectionRouteTraversal(exit, ViewDirection.AB, 0.0, 5.0)

        fun traversal(railId: UUID) = ProtectionRouteTraversal(
            railId,
            ViewDirection.AB,
            0.0,
            10.0
        )

        fun span(railId: UUID) = DirectedRailSpan(railId, ViewDirection.AB, 0.0, 10.0)

        fun plan(restrictions: RoutePlanningRestrictions): TopologyRoutePlanningResult =
            TopologyRoutePlanner.plan(
                TopologyRoutePlanningInput(
                    topology = topology,
                    restrictions = restrictions,
                    start = OperationalRouteLocation(entry, 0.0),
                    end = OperationalRouteLocation(exit, 10.0),
                    routeId = "test:planned",
                    routeRevision = 1L,
                    startFacing = ViewDirection.AB,
                    endFacing = ViewDirection.AB
                )
            )
    }

    private fun rail(number: Long): UUID = UUID(0L, number)
}
