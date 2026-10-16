package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
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
        val twice = SectionAuthoringActions.deleteRegionBoundary(definitions(listOf(once)), point(8.0)).patch.upsertRegions.single()
        assertEquals(region.regionId, twice.regionId)
        assertEquals(region.boundaries, twice.boundaries)
        assertTrue(twice.boundaryDraft!!.isEmpty())
        assertEquals(2, twice.revision)
    }

    @Test fun `pending first cut is persisted and closed second cut consumes pending markers`() {
        val open = SectionAuthoringActions.placeRegionBoundary(topology, definitions(), point(2.0), 100.0, emptyMap()) { "test:first" }
        assertTrue(open.patch.upsertRegions.isEmpty())
        assertEquals(listOf(first), open.patch.pendingProtectionBoundaries)
        var id = 0
        val closed = SectionAuthoringActions.placeRegionBoundary(topology,
            definitions(pending = open.patch.pendingProtectionBoundaries!!), point(8.0, ViewDirection.BA), 100.0, emptyMap()) { "test:new${id++}" }
        assertEquals(2, closed.patch.upsertRegions.single().boundaries.size)
        assertEquals(emptyList<ProtectionBoundaryDefinition>(), closed.patch.pendingProtectionBoundaries)
    }

    @Test fun `repair snaps to removed accepted cut and restores its ID while keeping region and tables`() {
        val draft = ProtectionRegionDefinition(region.regionId, 1, region.boundaries, listOf(last))
        val repaired = SectionAuthoringActions.placeRegionBoundary(topology, definitions(listOf(draft)),
            point(2.4), 100.0, interior) { error("Repair must preserve every ID") }.patch
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
        val edit = SectionAuthoringActions.placeRegionBoundary(topology, definitions(listOf(draft)), point(3.0), 100.0, interior) { "test:replacement" }
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
            SectionAuthoringActions.placeRegionBoundary(topology, definitions(listOf(regionA, regionB)), point(9.0, ViewDirection.BA), 100.0, footprints)
        }
    }

    @Test fun `new blocks cannot overlap existing interiors but may share a boundary`() {
        val defs = definitions(blocks = listOf(block("a", 1.0, 5.0)))
        assertThrows(IllegalArgumentException::class.java) { SectionAuthoringActions.createBlock(defs, block("overlap", 4.0, 9.0)) }
        assertEquals("test:next", SectionAuthoringActions.createBlock(defs, block("next", 5.0, 9.0)).patch.upsertFixedBlocks.single().blockId)
    }

    @Test fun `block creation keeps storage identity out of player confirmation`() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val edit = SectionAuthoringActions.createBlock(definitions(), block(id, 2.0, 8.0))
        assertEquals("test:$id", edit.patch.upsertFixedBlocks.single().blockId)
        assertEquals("Created bidirectional block: block", edit.message)
        assertEquals("Created bidirectional block: test:platform", SectionAuthoringActions.createBlock(
            definitions(), block("platform", 2.0, 8.0)).message)
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

    @Test fun `a pending cut outside an established region does not steal its ownership through the outside face`() {
        val edit = SectionAuthoringActions.placeRegionBoundary(topology, definitions(regions = listOf(region)),
            point(0.8), 100.0, interior) { "test:outside" }
        assertTrue(edit.patch.upsertRegions.isEmpty())
        assertEquals(listOf("test:outside"), edit.patch.pendingProtectionBoundaries!!.map { it.boundaryId })
    }

    private fun signal(name: String, blockId: String) =
        FixedBlockSignalDefinition("test:$name-signal", 0, blockId, 0.0, "automatic:block")
}
