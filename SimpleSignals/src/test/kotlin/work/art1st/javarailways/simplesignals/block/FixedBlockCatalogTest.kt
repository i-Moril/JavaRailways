package work.art1st.javarailways.simplesignals.block

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.region.topologyState
import java.util.UUID

class FixedBlockCatalogTest {
    private val first = rail(1)
    private val second = rail(2)

    @Test
    fun `authored blocks reject even a straight turnout while protection route geometry accepts it`() {
        val branch = rail(3)
        val topology = topologyState(7L, listOf(first, second, branch), listOf(
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(first, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)))
        val crossing = block("test:turnout", 1, listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 10.0),
            FixedBlockTraversal(second, ViewDirection.AB, 0.0, 8.0)))
        val rejected = FixedBlockCatalog().publish(topology, 1, listOf(crossing))
        assertEquals(FixedBlockCatalogStatus.REJECTED, rejected.status)
        assertEquals(FixedBlockValidationCode.TURNOUT_TRAVERSAL, rejected.diagnostics.single().code)
        assertEquals(FixedBlockCatalogStatus.PUBLISHED,
            FixedBlockCatalog(allowTurnoutGeometry = true).publish(topology, 1, listOf(crossing)).status)
    }

    @Test
    fun `every compilation derives immutable reverse path without duplicating occupancy or index`() {
        val topology = lineTopologyState(7L, first, second)
        val traversals = listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 10.0),
            FixedBlockTraversal(second, ViewDirection.AB, 0.0, 10.0 + Curve.TOLERANCE / 2))
        val catalog = FixedBlockCatalog()
        val twoWay = FixedBlockDefinition("test:two-way", 1L, traversals)
        assertEquals(FixedBlockCatalogStatus.PUBLISHED, catalog.publish(topology, 1L, listOf(twoWay)).status)
        val snapshot = catalog.snapshot(topology)
        val compiled = snapshot.blocksById.getValue(twoWay.blockId)
        assertEquals(listOf(FixedBlockTraversal(second, ViewDirection.BA, 0.0, 10.0),
            FixedBlockTraversal(first, ViewDirection.BA, 2.0, 10.0)), compiled.reverseTraversals)
        assertEquals(18.0, compiled.lengthMeters)
        assertEquals(1, snapshot.blocksById.size)
        assertEquals(setOf(twoWay.blockId), snapshot.blockIdsByRail.getValue(first))
        assertEquals(setOf(twoWay.blockId), snapshot.blockIdsByRail.getValue(second))
        assertEquals(listOf(RailInterval(first, 2.0, 10.0), RailInterval(second, 0.0, 10.0)),
            compiled.occupancyArea.intervals)
        assertEquals(listOf(RailObjectRef("test", "two-way/entry"), RailObjectRef("test", "two-way/exit")),
            compiled.occupancyArea.boundaries.map { it.boundaryId })
        assertEquals(listOf(first, second), compiled.entryTraversals.map { it.railId })
        assertThrows(UnsupportedOperationException::class.java) {
            (compiled.reverseTraversals as MutableList<FixedBlockTraversal>).clear()
        }
        assertEquals(FixedBlockCatalogStatus.REJECTED, catalog.publish(topology, 2L,
            listOf(FixedBlockDefinition(twoWay.blockId, 1L, traversals.dropLast(1)))).status)
        assertSame(snapshot, catalog.snapshot(topology))
    }

    @Test
    fun `multi rail AB and BA blocks compile length boundaries and occupancy geometry`() {
        val topology = lineTopologyState(7L, first, second)
        val catalog = FixedBlockCatalog()
        val ab = block(
            "test:ab",
            1L,
            listOf(
                FixedBlockTraversal(first, ViewDirection.AB, 2.0, 10.0),
                FixedBlockTraversal(second, ViewDirection.AB, 0.0, 8.0)
            )
        )
        val ba = block(
            "test:ba",
            1L,
            listOf(
                FixedBlockTraversal(second, ViewDirection.BA, 0.0, 10.0),
                FixedBlockTraversal(first, ViewDirection.BA, 2.0, 10.0)
            )
        )

        assertEquals(FixedBlockCatalogStatus.PUBLISHED, catalog.publish(topology, 1L, listOf(ab, ba)).status)
        val snapshot = catalog.snapshot(topology)

        val compiledAb = snapshot.blocksById.getValue(ab.blockId)
        assertEquals(16.0, compiledAb.lengthMeters)
        assertEquals(RailObjectRef("test", "ab"), compiledAb.occupancyArea.areaId)
        assertEquals(
            listOf(RailInterval(first, 2.0, 10.0), RailInterval(second, 0.0, 8.0)),
            compiledAb.occupancyArea.intervals
        )
        assertEquals(ViewDirection.AB, compiledAb.occupancyArea.boundaries[0].inwardPosition.facing)
        assertEquals(2.0, compiledAb.occupancyArea.boundaries[0].inwardPosition.arcLengthInAbMeters)
        assertEquals(ViewDirection.BA, compiledAb.occupancyArea.boundaries[1].inwardPosition.facing)
        assertEquals(8.0, compiledAb.occupancyArea.boundaries[1].inwardPosition.arcLengthInAbMeters)

        val compiledBa = snapshot.blocksById.getValue(ba.blockId)
        assertEquals(18.0, compiledBa.lengthMeters)
        assertEquals(second, compiledBa.occupancyArea.boundaries[0].inwardPosition.railId)
        assertEquals(ViewDirection.BA, compiledBa.occupancyArea.boundaries[0].inwardPosition.facing)
        assertEquals(first, compiledBa.occupancyArea.boundaries[1].inwardPosition.railId)
        assertEquals(ViewDirection.AB, compiledBa.occupancyArea.boundaries[1].inwardPosition.facing)
    }

    @Test
    fun `shared protection geometry continuity follows legal turnout transitions rather than node membership`() {
        val entry = rail(10)
        val selected = rail(11)
        val other = rail(12)
        val topology = topologyState(
            revision = 8L,
            railIds = listOf(entry, selected, other),
            connections = listOf(
                endpoint(entry, RailEndpoint.B) to endpoint(selected, RailEndpoint.A),
                endpoint(selected, RailEndpoint.A) to endpoint(other, RailEndpoint.A)
            )
        )
        val catalog = FixedBlockCatalog(allowTurnoutGeometry = true)
        val illegal = block(
            "test:illegal",
            1L,
            listOf(
                FixedBlockTraversal(entry, ViewDirection.AB, 0.0, 10.0),
                FixedBlockTraversal(other, ViewDirection.AB, 0.0, 10.0)
            )
        )

        val result = catalog.publish(topology, 1L, listOf(illegal))

        assertEquals(FixedBlockCatalogStatus.REJECTED, result.status)
        assertEquals(FixedBlockValidationCode.DISCONTINUOUS_PATH, result.diagnostics.single().code)
    }

    @Test
    fun `duplicate stale and invalid revision publications preserve the previous snapshot`() {
        val topology = lineTopologyState(9L, first, second)
        val catalog = FixedBlockCatalog()
        val initial = block(
            "test:block",
            2L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 1.0, 9.0))
        )
        assertEquals(FixedBlockCatalogStatus.PUBLISHED, catalog.publish(topology, 3L, listOf(initial)).status)
        val retained = catalog.snapshot(topology)

        val staleDefinitions = catalog.publish(topology, 2L, listOf(initial))
        assertEquals(FixedBlockCatalogStatus.REJECTED, staleDefinitions.status)
        assertEquals(
            FixedBlockValidationCode.INVALID_DEFINITION_REVISION,
            staleDefinitions.diagnostics.single().code
        )
        assertSame(retained, catalog.snapshot(topology))

        val duplicate = catalog.publish(topology, 4L, listOf(initial, initial))
        assertEquals(FixedBlockCatalogStatus.REJECTED, duplicate.status)
        assertEquals(FixedBlockValidationCode.DUPLICATE_BLOCK_ID, duplicate.diagnostics.single().code)
        assertSame(retained, catalog.snapshot(topology))

        val rollback = FixedBlockDefinition(initial.blockId, 1L, initial.traversals)
        val rollbackResult = catalog.publish(topology, 4L, listOf(rollback))
        assertEquals(FixedBlockCatalogStatus.REJECTED, rollbackResult.status)
        assertEquals(FixedBlockValidationCode.INVALID_BLOCK_REVISION, rollbackResult.diagnostics.single().code)
        assertSame(retained, catalog.snapshot(topology))

        val otherRoot = lineTopologyState(9L, first, second)
        assertThrows(IllegalStateException::class.java) { catalog.snapshot(otherRoot) }
        assertSame(retained, catalog.snapshot(topology))
    }

    @Test
    fun `rail length tolerance snaps compiled geometry and larger overflow preserves publication`() {
        val topology = lineTopologyState(10L, first)
        val catalog = FixedBlockCatalog()
        val tolerated = block(
            "test:tolerated",
            1L,
            listOf(
                FixedBlockTraversal(
                    first,
                    ViewDirection.AB,
                    2.0,
                    10.0 + Curve.TOLERANCE / 2.0
                )
            )
        )

        assertEquals(
            FixedBlockCatalogStatus.PUBLISHED,
            catalog.publish(topology, 1L, listOf(tolerated)).status
        )
        val retained = catalog.snapshot(topology)
        val compiled = retained.blocksById.getValue(tolerated.blockId)
        assertEquals(8.0, compiled.lengthMeters)
        assertEquals(10.0, compiled.traversals.single().upperAbMeters)
        assertEquals(10.0, compiled.occupancyArea.intervals.single().maxArcLengthInAbMeters)
        assertEquals(
            10.0,
            compiled.occupancyArea.boundaries.last().inwardPosition.arcLengthInAbMeters
        )

        val outside = block(
            tolerated.blockId,
            2L,
            listOf(
                FixedBlockTraversal(
                    first,
                    ViewDirection.AB,
                    2.0,
                    10.0 + Curve.TOLERANCE * 2.0
                )
            )
        )
        val rejected = catalog.publish(topology, 2L, listOf(outside))

        assertEquals(FixedBlockCatalogStatus.REJECTED, rejected.status)
        assertEquals(FixedBlockValidationCode.TRAVERSAL_OUTSIDE_RAIL, rejected.diagnostics.single().code)
        assertSame(retained, catalog.snapshot(topology))
    }

    @Test
    fun `rail reverse index finds only route local candidates once`() {
        val third = rail(3)
        val topology = lineTopologyState(10L, first, second, third)
        val catalog = FixedBlockCatalog()
        val firstBlock = block(
            "test:first",
            1L,
            listOf(
                FixedBlockTraversal(first, ViewDirection.AB, 0.0, 10.0),
                FixedBlockTraversal(second, ViewDirection.AB, 0.0, 5.0)
            )
        )
        val secondBlock = block(
            "test:second",
            1L,
            listOf(FixedBlockTraversal(third, ViewDirection.AB, 0.0, 10.0))
        )
        assertEquals(
            FixedBlockCatalogStatus.PUBLISHED,
            catalog.publish(topology, 1L, listOf(firstBlock, secondBlock)).status
        )
        val route = RouteSnapshot(
            "test:route",
            1L,
            topology.revision,
            listOf(
                RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0),
                RouteSegmentSnapshot(second, ViewDirection.BA, 0.0, 10.0)
            )
        )

        val snapshot = catalog.snapshot(topology)
        assertEquals(setOf("test:first"), snapshot.blockIdsByRail.getValue(second))
        assertEquals(listOf("test:first"), snapshot.candidates(route).map { it.definition.blockId })
        assertTrue(snapshot.blockIdsByRail[third]!!.contains("test:second"))
    }

    private fun block(
        id: String,
        revision: Long,
        traversals: Collection<FixedBlockTraversal>
    ) = FixedBlockDefinition(id, revision, traversals)

    private fun rail(index: Long): UUID = UUID(0L, index)
}
