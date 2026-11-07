package work.art1st.javarailways.simplesignals.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.simplesignals.block.FixedBlockAuthorityMatchResult
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalog
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalogStatus
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteMatcher
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCatalog
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCatalogStatus
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionRouteMatchResult
import work.art1st.javarailways.simplesignals.region.ProtectionRegionTopology
import java.util.UUID

class DirectedRouteBoundaryPrecisionTest {
    @Test
    fun `projection reuses exact endpoints in both directions and both coordinate systems`() {
        for (facing in ViewDirection.entries) {
            val fixture = Fixture(facing)
            val route = fixture.route()
            val projection = assertInstanceOf(DirectedRouteProjectionResult.Projected::class.java,
                DirectedRouteProjector.project(route, ProtectionRegionTopology.direct(fixture.topology))).route
            val occurrence = projection.occurrences.last()
            assertEquals(PREFIX_LENGTH, occurrence.routeDistanceAt(occurrence.canonicalStartAbMeters))
            assertEquals(route.totalLengthMeters, occurrence.routeDistanceAt(occurrence.canonicalEndAbMeters))
            assertEquals(occurrence.canonicalStartAbMeters, occurrence.canonicalAt(occurrence.routeStartMeters))
            assertEquals(occurrence.canonicalEndAbMeters, occurrence.canonicalAt(occurrence.routeEndMeters))
        }
    }

    @Test
    fun `internal cuts use local signed distance and do not snap near endpoints`() {
        for (facing in ViewDirection.entries) {
            // Non-zero canonical start and a large route prefix expose add-before-subtract rounding.
            val start = if (facing == ViewDirection.AB) 143.80706733283614 else RAIL_LENGTH
            val end = if (facing == ViewDirection.AB) RAIL_LENGTH else 143.80706733283614
            val occurrence = DirectedRouteOccurrence(TARGET_RAIL, facing, start, end,
                PREFIX_LENGTH, PREFIX_LENGTH + LOCAL_END)
            val cut = 150.123456789
            val offset = if (facing == ViewDirection.AB) cut - start else start - cut
            assertEquals(PREFIX_LENGTH + offset, occurrence.routeDistanceAt(cut))

            // A small prefix makes the next representable point distinguishable in route metres.
            val small = occurrence.copy(routeStartMeters = 1.0, routeEndMeters = 1.0 + LOCAL_END)
            val nearStart = if (facing == ViewDirection.AB) Math.nextUp(start) else Math.nextDown(start)
            val nearOffset = if (facing == ViewDirection.AB) nearStart - start else start - nearStart
            assertEquals(1.0 + nearOffset, small.routeDistanceAt(nearStart))
            assertNotEquals(small.routeStartMeters, small.routeDistanceAt(nearStart))
        }
    }

    @Test
    fun `fixed block retains exact route endpoints and exact partial authority cuts`() {
        for (facing in ViewDirection.entries) {
            val fixture = Fixture(facing)
            val route = fixture.route()
            val snapshot = fixture.blocks.snapshot(fixture.topology)
            val full = assertInstanceOf(FixedBlockAuthorityMatchResult.Matched::class.java,
                FixedBlockRouteMatcher.matchRoute(snapshot, route)).windows.single()
            assertEquals(PREFIX_LENGTH, full.entryRouteDistanceMeters)
            assertEquals(route.totalLengthMeters, full.exitRouteDistanceMeters)

            for (cut in listOf(PREFIX_LENGTH + 10.123456789, Math.nextDown(route.totalLengthMeters))) {
                val partial = assertInstanceOf(FixedBlockAuthorityMatchResult.Matched::class.java,
                    FixedBlockRouteMatcher.matchAuthority(snapshot, route, cut)).windows.single()
                assertEquals(PREFIX_LENGTH, partial.entryRouteDistanceMeters)
                assertEquals(cut, partial.exitRouteDistanceMeters)
            }
        }
    }

    @Test
    fun `region retains exact occurrence endpoints and canonical boundary positions`() {
        for (facing in ViewDirection.entries) {
            val fixture = Fixture(facing)
            val route = fixture.route()
            val window = assertInstanceOf(ProtectionRegionRouteMatchResult.Matched::class.java,
                fixture.regions.match(route)).windows.single()
            assertEquals(REGION_ENTRY, window.entry.routeDistanceMeters)
            assertEquals(route.totalLengthMeters, window.exit.routeDistanceMeters)
            assertEquals(REGION_ENTRY, window.entry.position.arcLengthInAbMeters)
            assertEquals(fixture.canonicalEnd, window.exit.position.arcLengthInAbMeters)
        }
    }

    @Test
    fun `repeated occurrences retain separate windows including internal and terminal cuts`() {
        for (facing in ViewDirection.entries) {
            val fixture = Fixture(facing)
            val route = fixture.route(repeated = true)
            val secondStart = PREFIX_LENGTH + RAIL_LENGTH + CONNECTOR_LENGTH + PREFIX_LENGTH
            val blocks = assertInstanceOf(FixedBlockAuthorityMatchResult.Matched::class.java,
                FixedBlockRouteMatcher.matchRoute(fixture.blocks.snapshot(fixture.topology), route)).windows
            assertEquals(2, blocks.size)
            assertEquals(PREFIX_LENGTH, blocks.first().entryRouteDistanceMeters)
            assertEquals(PREFIX_LENGTH + RAIL_LENGTH, blocks.first().exitRouteDistanceMeters)
            assertEquals(secondStart, blocks.last().entryRouteDistanceMeters)
            assertEquals(route.totalLengthMeters, blocks.last().exitRouteDistanceMeters)

            val cut = secondStart + 10.123456789
            val partial = assertInstanceOf(FixedBlockAuthorityMatchResult.Matched::class.java,
                FixedBlockRouteMatcher.matchAuthority(fixture.blocks.snapshot(fixture.topology), route, cut)).windows
            assertEquals(2, partial.size)
            assertEquals(blocks.first(), partial.first())
            assertEquals(cut, partial.last().exitRouteDistanceMeters)

            val regions = assertInstanceOf(ProtectionRegionRouteMatchResult.Matched::class.java,
                fixture.regions.match(route)).windows
            assertEquals(2, regions.size)
            val localCut = if (facing == ViewDirection.AB) fixture.canonicalEnd - fixture.canonicalStart
                else fixture.canonicalStart - fixture.canonicalEnd
            assertEquals(PREFIX_LENGTH + localCut, regions.first().exit.routeDistanceMeters)
            assertEquals(PREFIX_LENGTH + RAIL_LENGTH + CONNECTOR_LENGTH + REGION_ENTRY,
                regions.last().entry.routeDistanceMeters)
            assertEquals(route.totalLengthMeters, regions.last().exit.routeDistanceMeters)
        }
    }

    private class Fixture(val facing: ViewDirection) {
        val canonicalStart = if (facing == ViewDirection.AB) 0.0 else RAIL_LENGTH
        val canonicalEnd = if (facing == ViewDirection.AB) LOCAL_END else RAIL_LENGTH - LOCAL_END
        private val entry = if (facing == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
        private val exit = entry.opposite()
        private val connections = listOf(
            RailEndpointRef(PREFIX_RAIL, RailEndpoint.B) to RailEndpointRef(TARGET_RAIL, entry),
            RailEndpointRef(TARGET_RAIL, exit) to RailEndpointRef(CONNECTOR_RAIL, RailEndpoint.A),
            RailEndpointRef(CONNECTOR_RAIL, RailEndpoint.B) to RailEndpointRef(PREFIX_RAIL, RailEndpoint.A)
        ).flatMap { (a, b) -> listOf(a to b, b to a) }.groupBy({ it.first }, { it.second })
        val topology = buildTestTopology(1L, listOf(
            TestTopologyRail(PREFIX_RAIL, PREFIX_LENGTH), TestTopologyRail(TARGET_RAIL, RAIL_LENGTH),
            TestTopologyRail(CONNECTOR_RAIL, CONNECTOR_LENGTH)
        ), connections.mapValues { it.value.toSet() })
        val blocks = FixedBlockCatalog().also {
            val result = it.publish(topology, 1L, listOf(FixedBlockDefinition("test:precision-block", 1L,
                listOf(FixedBlockTraversal(TARGET_RAIL, facing, 0.0, RAIL_LENGTH)))))
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, result.status, result.diagnostics.toString())
        }
        val regions = ProtectionRegionCatalog().also {
            val opposite = if (facing == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB
            val result = it.publish(topology, 1L, listOf(ProtectionRegionDefinition("test:precision-region", 1L,
                listOf(
                    ProtectionBoundaryDefinition("test:entry", RailPositionSnapshot(PREFIX_RAIL, ViewDirection.AB, REGION_ENTRY)),
                    ProtectionBoundaryDefinition("test:exit", RailPositionSnapshot(TARGET_RAIL, opposite, canonicalEnd))
                ))))
            assertEquals(ProtectionRegionCatalogStatus.PUBLISHED, result.status, result.diagnostics.toString())
        }

        fun route(repeated: Boolean = false): RouteSnapshot {
            val prefix = RouteSegmentSnapshot(PREFIX_RAIL, ViewDirection.AB, 0.0, PREFIX_LENGTH)
            val segments = mutableListOf(prefix)
            if (repeated) {
                segments += RouteSegmentSnapshot(TARGET_RAIL, facing, 0.0, RAIL_LENGTH)
                segments += RouteSegmentSnapshot(CONNECTOR_RAIL, ViewDirection.AB, 0.0, CONNECTOR_LENGTH)
                segments += prefix
            }
            segments += RouteSegmentSnapshot(TARGET_RAIL, facing, 0.0, LOCAL_END)
            return RouteSnapshot("test:precision-route", 1L, topology.revision, segments)
        }
    }

    companion object {
        private val PREFIX_RAIL = UUID(0L, 101L)
        private val TARGET_RAIL = UUID(0L, 102L)
        private val CONNECTOR_RAIL = UUID(0L, 103L)
        // Tick 2002 replay values: the former BA conversion returned 490.66575622996584.
        private const val PREFIX_LENGTH = 471.91541186264385
        private const val REGION_ENTRY = PREFIX_LENGTH - 5.0
        private const val RAIL_LENGTH = 162.55741170015818
        private const val LOCAL_END = 18.750344367322043
        private const val CONNECTOR_LENGTH = 37.123456789
    }
}
