package work.art1st.javarailways.simplesignals.authoring

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
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

    @Test fun `canonical tangent arrows reverse in space and bidirectional endpoints show both`() {
        fun drawing(facing: ViewDirection, both: Boolean) = SectionOverlayDrawing.prepare(
            definitions(block(facing = facing, both = both)), emptySet(), emptySet()) { curve(10.0) }
        val forward = drawing(ViewDirection.AB, false)
        val reverse = drawing(ViewDirection.BA, false)
        fun arrows(value: SectionOverlayDrawing, x: Double) = value.points.filter {
            it.kind == PreviewParticleKind.DIRECTION && kotlin.math.abs(it.x - x) < 1.0
        }
        assertTrue(arrows(forward, 2.0).all { it.x <= 2.0 })
        assertTrue(arrows(reverse, 2.0).all { it.x >= 2.0 })
        val both = drawing(ViewDirection.AB, true)
        assertTrue(arrows(both, 2.0).any { it.x < 2.0 })
        assertTrue(arrows(both, 2.0).any { it.x > 2.0 })
        assertEquals(setOf(2.0, 8.0), forward.points.filter { it.kind == PreviewParticleKind.OVERLAY_BOUNDARY }.map { it.x }.toSet())
        assertTrue(forward.points.filter { it.kind == PreviewParticleKind.PATH }.all { it.x in 2.0..8.0 })
    }

    @Test fun `draft replaces effective markers pending state is explicit and overlapping labels retain identity`() {
        val old = listOf(cut("old", 2.0), cut("exit", 8.0, ViewDirection.BA))
        val draft = listOf(cut("draft", 3.0))
        val region = ProtectionRegionDefinition("test:region", 0, old, draft)
        val definitions = SimpleSignalsWorldDefinitions(world, 0, listOf(region), emptyList(),
            listOf(block(lower = 3.0)), pendingProtectionBoundaries = listOf(cut("pending", 3.0)))
        var lookups = 0
        val drawing = SectionOverlayDrawing.prepare(definitions, emptySet(), emptySet(),
            mapOf("test:region" to listOf(ProtectionRegionInteriorSlice(rail, 2.0, 8.0)))) { lookups++; curve(10.0) }
        assertEquals(1, lookups)
        assertFalse(drawing.labels.any { it.id().endsWith("test:old") || it.id().endsWith("test:exit") })
        assertTrue(drawing.labels.single { it.id() == "region/test:region/test:draft" }.text().toString().contains("draft"))
        assertTrue(drawing.labels.single { it.id() == "pending/test:pending" }.text().toString().contains("pending"))
        val stacked = drawing.labels.filter { it.x() == 3.0 && !it.id().contains("coverage") }
        assertEquals(3, stacked.size)
        assertEquals(3, stacked.map { it.y() }.distinct().size)
        assertEquals(drawing.labels.size, drawing.labels.map { it.id() }.distinct().size)
        assertTrue(drawing.points.any { it.kind == PreviewParticleKind.WARNING })
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
        assertEquals(1, drawing.labels.size) // Only the valid start has a meaningful pose.
        assertTrue(drawing.labels.single().text().toString().contains("unavailable"))
        assertFalse(drawing.points.any { it.kind == PreviewParticleKind.PATH })
    }

    @Test fun `long block body stays identifiable within64m and many nearby boundaries rotate under512`() {
        val long = SectionOverlayDrawing.prepare(definitions(block(upper = 1000.0)), emptySet(), emptySet()) { curve(1000.0) }
        val near = long.nearby(500.0, 0.0, 0.0)
        assertTrue(near.labels.isNotEmpty())
        assertTrue(near.labels.all { it.id().contains("coverage") && it.text().toString().contains("test:block") })
        assertTrue(near.points.all { (it.x - 500.0) * (it.x - 500.0) + it.y * it.y + it.z * it.z <= 4096.0 })
        val crowded = SectionOverlayDrawing.prepare(definitions(*(1..100).map { block("test:b$it") }.toTypedArray()),
            emptySet(), emptySet()) { curve(10.0) }
        val drawing = RoutePreviewDrawing(crowded.points, true)
        val seen = hashSetOf<PreviewParticle>()
        var offset = 0
        repeat(crowded.points.size / 512 + 2) {
            val batch = drawing.batch(offset)
            assertTrue(batch.size <= 512)
            seen += batch
            offset = drawing.nextOffset(offset)
        }
        assertTrue(seen.containsAll(crowded.points))
    }

    companion object {
        internal fun curve(length: Double = 10.0): PrimeCurve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(length, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), length, Curve.SegmentType.LINE_END)))
    }
}
