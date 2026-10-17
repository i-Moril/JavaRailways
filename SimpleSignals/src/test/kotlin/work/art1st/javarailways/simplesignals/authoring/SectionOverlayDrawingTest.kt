package work.art1st.javarailways.simplesignals.authoring

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerGlyph
import work.art1st.javarailways.bukkit.api.editor.EditorTextAppearance
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.*
import java.util.UUID

class SectionOverlayDrawingTest {
    private val world = UUID(0, 1)
    private val rail = UUID(0, 2)
    private fun block(id: String = "test:block", facing: ViewDirection = ViewDirection.AB, both: Boolean = false,
        lower: Double = 2.0, upper: Double = 8.0) = FixedBlockDefinition(id, 0,
        listOf(FixedBlockTraversal(rail, facing, lower, upper)), both)
    private fun definitions(vararg blocks: FixedBlockDefinition) = SimpleSignalsWorldDefinitions(world, 0,
        emptyList(), emptyList(), blocks.toList())
    private fun cut(id: String, ab: Double, direction: ViewDirection = ViewDirection.AB) =
        ProtectionBoundaryDefinition("test:$id", RailPositionSnapshot(rail, direction, ab))

    @Test fun `physical names and world coordinates localize on block region and pending overlays`() {
        val position = RailCutPosition(rail, 2.0)
        val region = ProtectionRegionDefinition("test:region", 0, listOf(cut("entry", 2.0), cut("exit", 8.0, ViewDirection.BA)))
        val defs = SimpleSignalsWorldDefinitions(world, 1, listOf(region), emptyList(), listOf(block()),
            pendingProtectionBoundaries = listOf(cut("pending", 2.0).cut()), boundaryNames =
                listOf(work.art1st.javarailways.simplesignals.persistence.BoundaryName(position, "<red>西端</red>")))
        val names = BoundaryNames(defs, lineTopologyState(1, rail)) { curve(10.0) }
        val drawing = SectionOverlayDrawing.prepare(defs, emptySet(), emptySet(), boundaryName = names::label) { curve(10.0) }
        val labels = drawing.localizedLabels(java.util.Locale.SIMPLIFIED_CHINESE)
        listOf("block/test:block/start", "region/test:region/test:entry", "pending/test:pending").forEach { id ->
            val caption = labels.single { it.id() == id }.text().toString()
            assertTrue(caption.contains("<red>西端</red>"), caption)
            assertTrue(caption.contains("2.0"), caption)
        }
        assertTrue(labels.single { it.id() == "block/test:block/end" }.text().toString().contains("未命名边界"))
    }

    @Test fun `section overlays contain boundary markers and one continuous line without arrows or interior captions`() {
        fun drawing(facing: ViewDirection, both: Boolean) = SectionOverlayDrawing.prepare(
            definitions(block(facing = facing, both = both)), emptySet(), emptySet()) { curve(10.0) }
        val forward = drawing(ViewDirection.AB, false)
        val reverse = drawing(ViewDirection.BA, false)
        val both = drawing(ViewDirection.AB, true)
        fun spans(value: SectionOverlayDrawing) = value.labels.filter { it.id().contains("coverage") }
        assertEquals(spans(forward), spans(reverse))
        assertEquals(spans(forward), spans(both))
        assertEquals(6, spans(forward).size)
        listOf(forward, reverse, both).forEach { value ->
            assertFalse(value.labels.any {
                (it.text() as? net.kyori.adventure.text.TextComponent)?.content() == EditorMarkerGlyph.DIRECTION.text()
            })
            assertFalse(value.labels.any { it.id().contains("direction") })
            assertTrue(value.labels.filter { it.appearance().mode() == EditorTextAppearance.Mode.READABLE_TEXT }
                .all { it.x() == 2.0 || it.x() == 8.0 })
        }
        assertEquals(setOf(2.0, 8.0), forward.labels.filter { it.id().endsWith("/boundary") }.map { it.x() }.toSet())
        assertTrue(forward.labels.filter { it.id().contains("coverage") }.all { it.x() in 2.0..8.0 })
        val short = SectionOverlayDrawing.prepare(definitions(block(lower = 2.0, upper = 2.2)),
            emptySet(), emptySet()) { curve(10.0) }
        val coverage = short.labels.single { it.id() == "block/test:block/0/coverage/0" }
        assertEquals(2.1, coverage.x(), 1e-12)
        assertTrue(coverage.appearance().scaleX() > 0.0)
        assertTrue(coverage.appearance().scaleY() > 0.0)
    }

    @Test fun `draft replaces effective markers pending state is explicit and overlapping labels retain identity`() {
        val old = listOf(cut("old", 2.0), cut("exit", 8.0, ViewDirection.BA))
        val draft = listOf(cut("draft", 3.0).cut())
        val region = ProtectionRegionDefinition("test:region", 0, old, draft)
        val definitions = SimpleSignalsWorldDefinitions(world, 0, listOf(region), emptyList(),
            listOf(block(lower = 3.0)), pendingProtectionBoundaries = listOf(cut("pending", 3.0).cut()))
        var lookups = 0
        val drawing = SectionOverlayDrawing.prepare(definitions, emptySet(), emptySet(),
            mapOf("test:region" to listOf(ProtectionRegionInteriorSlice(rail, 2.0, 8.0)))) { lookups++; curve(10.0) }
        assertEquals(1, lookups)
        assertFalse(drawing.labels.any { it.id().endsWith("test:old") || it.id().endsWith("test:exit") })
        assertTrue(drawing.labels.single { it.id() == "region/test:region/test:draft" }.text().toString().contains("draft"))
        assertTrue(drawing.labels.single { it.id() == "pending/test:pending" }.text().toString().contains("pending"))
        val stacked = drawing.labels.filter { it.x() == 3.0 && it.appearance().mode() ==
            work.art1st.javarailways.bukkit.api.editor.EditorTextAppearance.Mode.READABLE_TEXT }
        assertEquals(3, stacked.size)
        assertEquals(3, stacked.map { it.y() }.distinct().size)
        assertEquals(drawing.labels.size, drawing.labels.map { it.id() }.distinct().size)
        assertFalse(drawing.labels.any { it.id().startsWith("region/") && it.id().contains("coverage") })
    }

    @Test fun `shortened and missing curves omit invalid objects and label valid neighbours unavailable`() {
        val missingRail = UUID(0, 3)
        val broken = FixedBlockDefinition("test:broken", 0, listOf(
            FixedBlockTraversal(rail, ViewDirection.AB, 2.0, 8.0),
            FixedBlockTraversal(missingRail, ViewDirection.AB, 0.0, 5.0)))
        val drawing = SectionOverlayDrawing.prepare(definitions(broken), emptySet(), emptySet()) {
            if (it == rail) curve(6.0) else null
        }
        assertEquals(setOf(rail, missingRail), drawing.unavailableGeometry)
        assertEquals(2, drawing.labels.size) // Only the valid start has a meaningful pose: name and boundary.
        assertTrue(drawing.labels.single { it.id() == "block/test:broken/start" }.text().toString().contains("unavailable"))
        assertFalse(drawing.labels.any { it.id().contains("coverage") })
    }

    @Test fun `generated identities stay internal while labels retain authored names and boundary states`() {
        val generated = "simplesignals:12345678-1234-1234-1234-123456789abc"
        val boundary = ProtectionBoundaryDefinition(generated, RailPositionSnapshot(rail, ViewDirection.AB, 2.0))
        val region = ProtectionRegionDefinition(generated, 0,
            listOf(boundary, cut("named-exit", 8.0, ViewDirection.BA)))
        val defs = SimpleSignalsWorldDefinitions(world, 0, listOf(region), emptyList(), listOf(block(generated)),
            pendingProtectionBoundaries = listOf(boundary.cut()))
        val drawing = SectionOverlayDrawing.prepare(defs, emptySet(), emptySet()) { curve(10.0) }
        assertTrue(drawing.labels.any { it.id().contains(generated) })
        assertTrue(drawing.labels.none { it.text().toString().contains("12345678") })
        assertTrue(drawing.labels.any { it.text().toString().contains("Block boundary") })
        assertTrue(drawing.labels.any { it.text().toString().contains("test:named-exit") })
        assertTrue(drawing.labels.any { it.text().toString().contains("pending") })
    }

    @Test fun `long block filters continuous coverage nearby without repeated interior text`() {
        val long = SectionOverlayDrawing.prepare(definitions(block(upper = 150.0)), emptySet(), emptySet()) { curve(150.0) }
        val near = long.nearby(75.0, 0.0, 0.0)
        assertTrue(near.labels.isNotEmpty())
        assertTrue(near.labels.all { it.id().contains("coverage") })
        assertTrue(near.labels.none { it.appearance().mode() == EditorTextAppearance.Mode.READABLE_TEXT })
        assertTrue(near.labels.all { (it.x() - 75.0) * (it.x() - 75.0) + it.y() * it.y() + it.z() * it.z() <= 4096.0 })
    }

    @Test fun `adjacent blocks share one boundary caption and unavailable status wins`() {
        val drawing = SectionOverlayDrawing.prepare(definitions(block("test:first", lower = 1.0, upper = 5.0),
            block("test:second", lower = 5.0, upper = 9.0)), setOf("test:second"), emptySet()) { curve() }
        val shared = drawing.labels.filter { it.x() == 5.0 }
        assertEquals(2, shared.size)
        assertEquals("Block boundary · unavailable", (shared.single {
            it.appearance().mode() == EditorTextAppearance.Mode.READABLE_TEXT
        }.text() as net.kyori.adventure.text.TextComponent).content())
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.RED,
            shared.single { it.id().endsWith("/boundary") }.text().color())
    }

    @Test fun `region lines union shared and reverse saved routes and hide paths using a removed cut`() {
        val branchA = UUID(0, 3)
        val branchB = UUID(0, 4)
        val entry = cut("entry", 2.0)
        fun exit(id: String, railId: UUID) = ProtectionBoundaryDefinition("test:$id", RailPositionSnapshot(railId, ViewDirection.BA, 8.0))
        val exitA = exit("exit-a", branchA)
        val exitB = exit("exit-b", branchB)
        val region = ProtectionRegionDefinition("test:region", 0, listOf(entry, exitA, exitB))
        fun route(id: String, exit: ProtectionBoundaryDefinition) = ProtectionRouteDefinition("test:$id",
            entry.boundaryId, exit.boundaryId, listOf(ProtectionRouteTraversal(rail, ViewDirection.AB, 2.0, 10.0),
                ProtectionRouteTraversal(exit.position.railId, ViewDirection.AB, 0.0, 8.0)))
        val first = route("a", exitA)
        val reverse = ProtectionRouteDefinition("test:reverse-a", exitA.boundaryId, entry.boundaryId,
            first.traversals.asReversed().map { it.copy(facing = ViewDirection.BA) })
        val table = ProtectionRouteTableDefinition("test:table", 0, region.regionId, 0, listOf(first, reverse, route("b", exitB)))
        val interior = mapOf(region.regionId to listOf(ProtectionRegionInteriorSlice(rail, 2.0, 10.0),
            ProtectionRegionInteriorSlice(branchA, 0.0, 8.0), ProtectionRegionInteriorSlice(branchB, 0.0, 8.0)))
        fun draw(definition: ProtectionRegionDefinition) = SectionOverlayDrawing.prepare(
            SimpleSignalsWorldDefinitions(world, 0, listOf(definition), listOf(table)), emptySet(), emptySet(), interior) { curve() }
        val complete = draw(region).labels.filter { it.id().contains("coverage") }
        assertEquals(24, complete.size, "The common stem and reverse route must not duplicate the line")
        val draft = ProtectionRegionDefinition(region.regionId, 1, region.boundaries, listOf(entry.cut(), exitA.cut()))
        val drawing = draw(draft)
        val retained = drawing.labels.filter { it.id().contains("coverage") }
        assertEquals(16, retained.size, "Only the stem and surviving branch remain")
        assertTrue(retained.all { it.text().color() == net.kyori.adventure.text.format.NamedTextColor.RED })
        assertEquals(2, drawing.labels.count { it.id().endsWith("/boundary") })
    }

    @Test fun `regions with no eligible saved routes retain markers but never fall back to whole interior`() {
        val entry = cut("entry", 2.0)
        val exit = cut("exit", 8.0, ViewDirection.BA)
        val region = ProtectionRegionDefinition("test:region", 0, listOf(entry, exit))
        val route = ProtectionRouteDefinition("test:route", entry.boundaryId, exit.boundaryId,
            listOf(ProtectionRouteTraversal(rail, ViewDirection.AB, 2.0, 8.0)))
        val table = ProtectionRouteTableDefinition("test:table", 0, region.regionId, 0, listOf(route))
        fun draw(definition: ProtectionRegionDefinition, tables: List<ProtectionRouteTableDefinition>) = SectionOverlayDrawing.prepare(
            SimpleSignalsWorldDefinitions(world, 0, listOf(definition), tables), emptySet(), emptySet(),
            mapOf(region.regionId to listOf(ProtectionRegionInteriorSlice(rail, 2.0, 8.0)))) { curve() }
        val withoutTable = draw(region, emptyList())
        assertFalse(withoutTable.labels.any { it.id().contains("coverage") })
        assertEquals(2, withoutTable.labels.count { it.id().endsWith("/boundary") })
        val moved = ProtectionRegionDefinition(region.regionId, 1, region.boundaries,
            listOf(entry.cut(), exit.cut().copy(position = RailCutPosition(rail, 7.0))))
        val drawing = draw(moved, listOf(table))
        assertFalse(drawing.labels.any { it.id().contains("coverage") }, "A retained ID at a changed position is not an accepted exit")
        assertEquals(setOf(2.0, 7.0), drawing.labels.filter { it.id().endsWith("/boundary") }.map { it.x() }.toSet())
        val emptyDraft = ProtectionRegionDefinition(region.regionId, 1, region.boundaries, emptyList())
        assertTrue(draw(emptyDraft, listOf(table)).labels.isEmpty())
    }

    companion object {
        internal fun curve(length: Double = 10.0): PrimeCurve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(length, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), length, Curve.SegmentType.LINE_END)))
    }
}
