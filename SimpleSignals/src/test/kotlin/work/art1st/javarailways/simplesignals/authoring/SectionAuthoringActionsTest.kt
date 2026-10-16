package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.signal.*
import java.util.UUID
import work.art1st.javarailways.simplesignals.testing.reconfigured

class SectionAuthoringActionsTest {
    private val world = UUID(0, 30)
    private val rail = UUID(0, 1)
    private val otherRail = UUID(0, 2)
    private val first = boundary("first", 2.0, ViewDirection.AB)
    private val last = boundary("last", 8.0, ViewDirection.BA)
    private val region = ProtectionRegionDefinition("test:region", 0, listOf(first, last))
    private val topology = lineTopologyState(1, rail)
    private val interior = mapOf(region.regionId to listOf(ProtectionRegionInteriorSlice(rail, 2.0, 8.0)))
    private fun point(arc: Double, facing: ViewDirection = ViewDirection.AB) = RailPositionSnapshot(rail, facing, arc)
    private fun boundary(name: String, arc: Double, facing: ViewDirection) = ProtectionBoundaryDefinition("test:$name", point(arc, facing))
    private fun block(name: String, from: Double, to: Double) = FixedBlockDefinition("test:$name", 0,
        listOf(FixedBlockTraversal(rail, ViewDirection.AB, from, to)), true)
    private fun definitions(regions: List<ProtectionRegionDefinition> = emptyList(), blocks: List<FixedBlockDefinition> = emptyList(),
        pending: List<ProtectionBoundaryDefinition> = emptyList()) = SimpleSignalsWorldDefinitions(world, 0, regions, emptyList(), blocks,
        pendingProtectionBoundaries = pending)

    @Test fun `snap shares same rail coordinates but never jumps to another nearby rail`() {
        val defs = definitions(regions = listOf(region), blocks = listOf(block("block", 8.0, 10.0)))
        assertEquals(8.0, SectionAuthoringActions.snap(defs, point(8.4)).arcLengthInAbMeters)
        val other = RailPositionSnapshot(otherRail, ViewDirection.BA, 8.4)
        assertEquals(other, SectionAuthoringActions.snap(defs, other))
        assertEquals(ViewDirection.BA, SectionAuthoringActions.snap(defs, point(8.4, ViewDirection.BA)).facing)
    }

    @Test fun `old block boundary is selectable for continuation without a separate mode`() {
        val defs = definitions(blocks = listOf(block("block", 2.0, 8.0)))
        assertEquals(8.0, SectionAuthoringActions.selectBlockEnd(defs, point(8.3)).arcLengthInAbMeters)
        assertThrows(IllegalArgumentException::class.java) { SectionAuthoringActions.selectBlockEnd(defs, point(5.0)) }
    }

    @Test fun `boundary deletion removes both adjacent blocks with owned signals and repeaters only`() {
        val blocks = listOf(block("a", 1.0, 4.0), block("b", 4.0, 7.0), block("c", 7.0, 9.0))
        val signals = blocks.map { signal(it.blockId.removePrefix("test:"), it.blockId) }
        val model = DeviceModelDefinition("test:head", ModelPlacement.Ground(ModelPose.IDENTITY))
        val attached = signals.map { AttachedSignalDefinition(it.signalId + "-attached", 0, it.signalId, model) }
        val defs = SimpleSignalsWorldDefinitions(world, 0, emptyList(), emptyList(), blocks, signals, attachedSignals = attached)
        val patch = SectionAuthoringActions.deleteBlock(defs, point(4.2), true).patch
        assertEquals(setOf("test:a", "test:b"), patch.removeFixedBlockIds.toSet())
        assertEquals(setOf("test:a-signal", "test:b-signal"), patch.removeFixedBlockSignalIds.toSet())
        assertEquals(setOf("test:a-signal-attached", "test:b-signal-attached"), patch.removeAttachedSignalIds.toSet())
        assertTrue(patch.removeRegionIds.isEmpty())
        assertEquals(listOf("test:b"), SectionAuthoringActions.deleteBlock(defs, point(5.0), false).patch.removeFixedBlockIds)
    }

    @Test fun `overlapped region cut requires deleting the block boundary first`() {
        val defs = definitions(regions = listOf(region), blocks = listOf(block("a", 8.0, 10.0)))
        assertThrows(IllegalArgumentException::class.java) { SectionAuthoringActions.deleteRegionBoundary(defs, point(8.2)) }
        val deleted = SectionAuthoringActions.deleteBlock(defs, point(8.0), true).patch
        assertTrue(deleted.upsertRegions.isEmpty())
        assertTrue(deleted.removeRegionIds.isEmpty())
        val unified = SectionAuthoringActions.deleteSection(defs, point(8.0), true, topology).patch
        assertEquals(listOf("test:a"), unified.removeFixedBlockIds)
        assertTrue(unified.upsertRegions.isEmpty())
    }

    @Test fun `deleting bidirectional block removes both primary signals and all their displays only`() {
        val shared = block("shared", 2.0, 8.0)
        val other = block("other", 8.0, 10.0)
        val forward = signal("forward", shared.blockId)
        val reverse = signal("reverse", shared.blockId).reconfigured(entryDirection = FixedBlockEntryDirection.REVERSE)
        val unrelated = signal("unrelated", other.blockId)
        val model = DeviceModelDefinition("test:head", ModelPlacement.Ground(ModelPose.IDENTITY))
        val attachments = listOf(forward, reverse, unrelated).flatMap { primary -> (1..2).map {
            AttachedSignalDefinition(primary.signalId + "/repeat-$it", 0, primary.signalId, model)
        } }
        val defs = SimpleSignalsWorldDefinitions(world, 0, emptyList(), emptyList(), listOf(shared, other),
            listOf(forward, reverse, unrelated), attachedSignals = attachments)
        val patch = SectionAuthoringActions.deleteBlock(defs, point(5.0), false).patch
        assertEquals(listOf(shared.blockId), patch.removeFixedBlockIds)
        assertEquals(setOf(forward.signalId, reverse.signalId), patch.removeFixedBlockSignalIds.toSet())
        assertEquals(attachments.filter { it.primarySignalId != unrelated.signalId }.map { it.signalId }.toSet(),
            patch.removeAttachedSignalIds.toSet())
    }

    @Test fun `deleting all region markers preserves accepted identity and original complete cuts`() {
        val once = SectionAuthoringActions.deleteRegionBoundary(definitions(listOf(region)), point(2.0)).patch.upsertRegions.single()
        assertEquals(region.boundaries, once.boundaries)
        assertEquals(listOf(last), once.boundaryDraft)
        val twice = SectionAuthoringActions.deleteRegionBoundary(definitions(listOf(once)), point(8.0, ViewDirection.BA)).patch.upsertRegions.single()
        assertEquals(region.regionId, twice.regionId)
        assertEquals(region.boundaries, twice.boundaries)
        assertTrue(twice.boundaryDraft!!.isEmpty())
        assertEquals(2, twice.revision)
    }

    @Test fun `deletion at a shared protection border refuses ambiguous ownership without direction guessing`() {
        val neighbour = ProtectionRegionDefinition("test:neighbour", 0,
            listOf(boundary("neighbour-in", 8.0, ViewDirection.AB), boundary("neighbour-out", 9.0, ViewDirection.BA)))
        val defs = definitions(regions = listOf(region, neighbour))
        val forward = SectionAuthoringActions.deleteSection(defs, point(8.0), false, topology)
        val reverse = SectionAuthoringActions.deleteSection(defs, point(8.0, ViewDirection.BA), false, topology)
        assertTrue(forward.patch.upsertRegions.isEmpty())
        assertTrue(reverse.patch.upsertRegions.isEmpty())
        assertEquals(forward.text, reverse.text)
    }

    private val directions: (Set<UUID>) -> Map<UUID, ViewDirection?> = { rails -> rails.associateWith { null } }

    @Test fun `pending first cut is persisted and plain closure creates a block without a region`() {
        val open = SectionAuthoringActions.placeSectionBoundary(topology, definitions(), point(2.0), 100.0, emptyMap(), directions) { "test:first" }
        assertTrue(open.patch.upsertRegions.isEmpty())
        assertEquals(listOf(first), open.patch.pendingProtectionBoundaries)
        var id = 0
        val closed = SectionAuthoringActions.placeSectionBoundary(topology,
            definitions(pending = open.patch.pendingProtectionBoundaries!!), point(8.0, ViewDirection.BA), 100.0, emptyMap(), directions) { "test:new${id++}" }
        assertTrue(closed.patch.upsertRegions.isEmpty())
        assertEquals(listOf(FixedBlockTraversal(rail, ViewDirection.BA, 2.0, 8.0)), closed.patch.upsertFixedBlocks.single().traversals)
        assertEquals(emptyList<ProtectionBoundaryDefinition>(), closed.patch.pendingProtectionBoundaries)
    }

    @Test fun `repair snaps to removed accepted cut and restores its ID while keeping region and tables`() {
        val draft = ProtectionRegionDefinition(region.regionId, 1, region.boundaries, listOf(last))
        val repaired = SectionAuthoringActions.placeSectionBoundary(topology, definitions(listOf(draft)),
            point(2.4), 100.0, interior, directions) { error("Repair must preserve every ID") }.patch
        val restored = repaired.upsertRegions.single()
        assertEquals(region.regionId, restored.regionId)
        assertEquals(region.boundaries.toSet(), restored.boundaries.toSet())
        assertNull(restored.boundaryDraft)
        assertTrue(repaired.removeRouteTableIds.isEmpty())
        assertTrue(repaired.upsertRouteTables.isEmpty())
        assertTrue(repaired.removeProtectionSignalIds.isEmpty())
    }

    @Test fun `zero-marker region is found through retained interior when first replacement cut is placed`() {
        val draft = ProtectionRegionDefinition(region.regionId, 1, region.boundaries, emptyList())
        val edit = SectionAuthoringActions.placeSectionBoundary(topology, definitions(listOf(draft)), point(3.0), 100.0, interior, directions) { "test:replacement" }
        val changed = edit.patch.upsertRegions.single()
        assertEquals(region.regionId, changed.regionId)
        assertEquals(listOf("test:replacement"), changed.boundaryDraft!!.map { it.boundaryId })
        assertEquals(region.boundaries, changed.boundaries)
    }

    @Test fun `closed component cannot merge two established owners`() {
        val regionA = ProtectionRegionDefinition("test:a", 1, listOf(boundary("a1", 1.0, ViewDirection.AB), boundary("a2", 4.0, ViewDirection.BA)),
            listOf(boundary("a1", 1.0, ViewDirection.AB)))
        val regionB = ProtectionRegionDefinition("test:b", 1, listOf(boundary("b1", 6.0, ViewDirection.AB), boundary("b2", 9.0, ViewDirection.BA)), emptyList())
        val footprints = mapOf("test:a" to listOf(ProtectionRegionInteriorSlice(rail, 1.0, 4.0)),
            "test:b" to listOf(ProtectionRegionInteriorSlice(rail, 6.0, 9.0)))
        assertThrows(IllegalArgumentException::class.java) {
            SectionAuthoringActions.placeSectionBoundary(topology, definitions(listOf(regionA, regionB)), point(9.0, ViewDirection.BA), 100.0, footprints, directions)
        }
    }

    @Test fun `new blocks cannot overlap existing interiors but may share a boundary`() {
        val defs = definitions(blocks = listOf(block("a", 1.0, 5.0)))
        assertThrows(IllegalArgumentException::class.java) { SectionAuthoringActions.createBlock(defs, block("overlap", 4.0, 9.0)) }
        assertEquals("test:next", SectionAuthoringActions.createBlock(defs, block("next", 5.0, 9.0)).patch.upsertFixedBlocks.single().blockId)
    }

    @Test fun `automatic block closes at the nearest cut and keeps an adjacent block border`() {
        val old = block("old", 0.0, 2.0)
        var id = 0
        val edit = SectionAuthoringActions.placeSectionBoundary(topology,
            definitions(blocks = listOf(old)), point(8.0, ViewDirection.BA), 1.0, emptyMap(), directions) { "test:auto${id++}" }
        assertEquals(listOf(FixedBlockTraversal(rail, ViewDirection.BA, 2.0, 8.0)), edit.patch.upsertFixedBlocks.single().traversals)
        assertTrue(edit.patch.removeFixedBlockIds.isEmpty())
        val unoriented = SectionAuthoringActions.placeSectionBoundary(topology,
            definitions(pending = listOf(first.copy(position = point(2.0, ViewDirection.BA)))),
            point(8.0, ViewDirection.BA), 100.0, emptyMap(), directions) { "test:wrong${id++}" }
        assertEquals(edit.patch.upsertFixedBlocks.single().traversals, unoriented.patch.upsertFixedBlocks.single().traversals)
        assertTrue(unoriented.patch.pendingProtectionBoundaries!!.isEmpty())
    }

    @Test fun `block creation keeps storage identity out of player confirmation`() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val edit = SectionAuthoringActions.createBlock(definitions(), block(id, 2.0, 8.0))
        assertEquals("test:$id", edit.patch.upsertFixedBlocks.single().blockId)
        assertEquals("Created bidirectional block: block", edit.text.render(java.util.Locale.US))
        assertEquals("Created bidirectional block: test:platform", SectionAuthoringActions.createBlock(
            definitions(), block("platform", 2.0, 8.0)).text.render(java.util.Locale.US))
    }

    @Test fun `ordinary joint has one block boundary identity across its two rail coordinates`() {
        val topology = lineTopologyState(1, rail, otherRail)
        val a = block("a", 2.0, 10.0)
        val b = FixedBlockDefinition("test:b", 0, listOf(FixedBlockTraversal(otherRail, ViewDirection.AB, 0.0, 8.0)), true)
        val defs = definitions(blocks = listOf(a, b))
        val target = RailPositionSnapshot(otherRail, ViewDirection.AB, 0.0)
        assertEquals(setOf("test:a", "test:b"), SectionAuthoringActions.deleteBlock(defs, target, true, topology).patch.removeFixedBlockIds.toSet())
        assertEquals(point(10.0, ViewDirection.BA), SectionAuthoringActions.selectBlockEnd(definitions(blocks = listOf(a)), target, topology))
    }

    @Test fun `adjacent blocks can be created from either rail coordinate of their ordinary joint`() {
        val joined = lineTopologyState(1, rail, otherRail)
        var id = 0
        val old = SectionAuthoringActions.placeSectionBoundary(joined, definitions(pending = listOf(first)),
            point(10.0), 1.0, emptyMap(), directions) { "test:joint-${id++}" }.patch.upsertFixedBlocks.single()
        val nextEnd = ProtectionBoundaryDefinition("test:next-end", RailPositionSnapshot(otherRail, ViewDirection.AB, 8.0))
        val next = SectionAuthoringActions.placeSectionBoundary(joined,
            definitions(blocks = listOf(old), pending = listOf(nextEnd)), RailPositionSnapshot(otherRail, ViewDirection.AB, 0.0),
            1.0, emptyMap(), directions) { "test:joint-${id++}" }.patch
        assertEquals(listOf(FixedBlockTraversal(otherRail, ViewDirection.AB, 0.0, 8.0)), next.upsertFixedBlocks.single().traversals)
        assertTrue(next.removeFixedBlockIds.isEmpty())
        assertTrue(next.upsertRegions.isEmpty())
        assertTrue(next.pendingProtectionBoundaries!!.isEmpty())
    }

    @Test fun `a new block outside an established region reuses its border without stealing ownership`() {
        var id = 0
        val edit = SectionAuthoringActions.placeSectionBoundary(topology, definitions(regions = listOf(region)),
            point(0.8), 100.0, interior, directions) { "test:outside${id++}" }
        assertTrue(edit.patch.upsertRegions.isEmpty())
        assertEquals(listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.8, 2.0)), edit.patch.upsertFixedBlocks.single().traversals)
        assertTrue(edit.patch.pendingProtectionBoundaries!!.isEmpty())
    }

    @Test fun `four cuts with the same stored facing enclose a scissors crossover`() {
        val rails = (1L..8L).map { UUID(0, it) }
        val (a, b, c, d) = rails.take(4)
        val (straightA, straightB, crossA, crossB) = rails.drop(4)
        fun port(rail: UUID, end: RailEndpoint) = RailEndpointRef(rail, end)
        val connections = listOf(a to straightA, a to crossA, b to straightB, b to crossB).map {
            port(it.first, RailEndpoint.B) to port(it.second, RailEndpoint.A)
        } + listOf(straightA to c, crossB to c, straightB to d, crossA to d).map {
            port(it.first, RailEndpoint.B) to port(it.second, RailEndpoint.A)
        }
        val adjacency = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
        connections.forEach { (from, to) ->
            adjacency.getOrPut(from, ::linkedSetOf) += to
            adjacency.getOrPut(to, ::linkedSetOf) += from
        }
        val topology = buildTestTopology(1, rails.map { TestTopologyRail(it, 10.0) }, adjacency,
            atGradeCrossings = listOf(AtGradeRailCrossing(RailCrossingPoint(crossA, 5.0), RailCrossingPoint(crossB, 5.0))))
        val cuts = listOf(a, b, c, d).map { ProtectionBoundaryDefinition("test:cut-${it.leastSignificantBits}",
            RailPositionSnapshot(it, ViewDirection.BA, 5.0)) }
        fun create(facing: ViewDirection): ProtectionRegionDefinition {
            var id = 0
            val edit = SectionAuthoringActions.placeSectionBoundary(topology, definitions(pending = cuts.take(3)),
                cuts.last().position.copy(facing = facing), 100.0, emptyMap(), directions) { "test:created-${id++}" }
            assertTrue(edit.patch.pendingProtectionBoundaries!!.isEmpty())
            return edit.patch.upsertRegions.single()
        }
        val forward = create(ViewDirection.AB)
        assertEquals(mapOf(a to ViewDirection.AB, b to ViewDirection.AB, c to ViewDirection.BA, d to ViewDirection.BA),
            forward.boundaries.associate { it.position.railId to it.insideFacing })
        assertEquals(forward, create(ViewDirection.BA))
        assertTrue(ProtectionRegionGraphResolver.resolve(forward, ProtectionRegionTopology.direct(topology)) is ProtectionRegionGraphResolution.Resolved)
        // Existing failed drafts can be completed by clicking the interior without replacing cuts.
        val confirmed = SectionAuthoringActions.confirmSection(topology, definitions(pending = cuts),
            RailPositionSnapshot(crossA, ViewDirection.BA, 4.0), 100.0, emptyMap(), directions) { "test:confirmed" }
        assertEquals(cuts.map { it.boundaryId }.toSet(), confirmed.patch.upsertRegions.single().boundaries.map { it.boundaryId }.toSet())
    }

    @Test fun `two closed loop components require an interior click instead of arbitrary direction`() {
        val aEnd = RailEndpointRef(rail, RailEndpoint.B)
        val aStart = RailEndpointRef(rail, RailEndpoint.A)
        val bEnd = RailEndpointRef(otherRail, RailEndpoint.B)
        val bStart = RailEndpointRef(otherRail, RailEndpoint.A)
        val loop = buildTestTopology(1, listOf(TestTopologyRail(rail, 10.0), TestTopologyRail(otherRail, 10.0)),
            mapOf(aEnd to setOf(bStart), bStart to setOf(aEnd), bEnd to setOf(aStart), aStart to setOf(bEnd)))
        var id = 0
        val pending = SectionAuthoringActions.placeSectionBoundary(loop, definitions(pending = listOf(first)),
            point(8.0), 1.0, emptyMap(), directions) { "test:loop-${id++}" }
        assertTrue(pending.patch.upsertFixedBlocks.isEmpty())
        assertEquals(2, pending.patch.pendingProtectionBoundaries!!.size)
        val defs = definitions(pending = pending.patch.pendingProtectionBoundaries)
        val middle = SectionAuthoringActions.confirmSection(loop, defs, point(5.0), 1.0, emptyMap(), directions) { "test:middle" }
        assertEquals(6.0, middle.patch.upsertFixedBlocks.single().traversals.sumOf { it.lengthMeters })
        val outside = SectionAuthoringActions.confirmSection(loop, defs, point(9.0, ViewDirection.BA), 1.0, emptyMap(), directions) { "test:outside" }
        assertEquals(14.0, outside.patch.upsertFixedBlocks.single().traversals.sumOf { it.lengthMeters })
        assertTrue(outside.patch.pendingProtectionBoundaries!!.isEmpty())
    }

    private fun signal(name: String, blockId: String) =
        FixedBlockSignalDefinition("test:$name-signal", 0, blockId, 0.0, "automatic:block")
}
