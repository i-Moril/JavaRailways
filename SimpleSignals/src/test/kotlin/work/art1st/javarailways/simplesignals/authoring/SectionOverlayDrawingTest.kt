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
        assertTrue(drawing.labels.any { it.id().contains("coverage") && it.text().color() ==
            net.kyori.adventure.text.format.NamedTextColor.RED })
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

    companion object {
        internal fun curve(length: Double = 10.0): PrimeCurve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(length, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), length, Curve.SegmentType.LINE_END)))
    }
}
