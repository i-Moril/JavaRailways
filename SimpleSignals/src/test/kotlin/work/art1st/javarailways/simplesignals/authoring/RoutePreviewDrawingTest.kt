package work.art1st.javarailways.simplesignals.authoring

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.region.*
import java.util.UUID
import kotlin.math.ceil

class RoutePreviewDrawingTest {
    private val rail = UUID(0, 1)
    private fun curve(length: Double) = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
        Curve.RawPoint(Vector3d(length, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), length, Curve.SegmentType.LINE_END)))
    private fun path(length: Double = 8.0, facing: ViewDirection = ViewDirection.AB): CurrentRoutePath {
        val entry = ProtectionBoundaryDefinition("test:entry", RailPositionSnapshot(rail, facing, if (facing == ViewDirection.AB) 2.0 else length))
        val exit = ProtectionBoundaryDefinition("test:exit", RailPositionSnapshot(rail, facing.reversed(), if (facing == ViewDirection.AB) length else 2.0))
        return CurrentRoutePath(entry, exit, listOf(ProtectionRouteTraversal(rail, facing, 2.0, length)),
            exit.position.copy(facing = facing), CurrentRouteEnd.REACHED_EXIT, "connected")
    }

    @Test fun `long rendering rotates bounded batches without losing path points and always includes both endpoints`() {
        val logical = path(5000.0)
        var lookups = 0
        val drawing = RoutePreviewDrawing.prepare(logical) { lookups++; curve(5000.0) }
        assertEquals(1, lookups)
        assertTrue(drawing.batched)
        val seen = hashSetOf<PreviewParticle>()
        var offset = 0
        repeat(ceil(drawing.points.size / 497.0).toInt() + 1) {
            val batch = drawing.batch(offset)
            assertTrue(batch.size <= 512)
            assertTrue(batch.any { it.kind == PreviewParticleKind.ENTRY })
            assertTrue(batch.any { it.kind == PreviewParticleKind.REQUESTED_EXIT })
            assertTrue(batch.any { it.kind == PreviewParticleKind.ACTUAL_END })
            seen += batch
            offset = drawing.nextOffset(offset)
        }
        assertTrue(seen.containsAll(drawing.points))
        assertEquals(5000.0, logical.traversals.single().travelEndAbMeters)
        assertEquals(1, lookups) // Batching never resamples or performs lookups.
        assertThrows(UnsupportedOperationException::class.java) { (drawing.points as MutableList).clear() }
    }

    @Test fun `AB and BA chevron arms point toward their actual traversal ends`() {
        val geometry = curve(10.0)
        val forward = RoutePreviewDrawing.prepare(path()) { geometry }.points.filter { it.kind == PreviewParticleKind.DIRECTION }
        val reverse = RoutePreviewDrawing.prepare(path(facing = ViewDirection.BA)) { geometry }.points.filter { it.kind == PreviewParticleKind.DIRECTION }
        assertEquals(5.0, forward.maxOf { it.x })
        assertTrue(forward.minOf { it.x } < 5.0)
        assertEquals(5.0, reverse.minOf { it.x })
        assertTrue(reverse.maxOf { it.x } > 5.0)
    }

    @Test fun `failed prefix shows requested and actual end separately and explicitly reports missing geometry`() {
        val path = path()
        val failed = CurrentRoutePath(path.entry, path.requestedExit, path.traversals,
            path.reached.copy(arcLengthInAbMeters = 6.0), CurrentRouteEnd.WRONG_EXIT, "wrong exit")
        val drawing = RoutePreviewDrawing.prepare(failed) { curve(10.0) }
        assertFalse(drawing.connected)
        assertEquals(8.0, drawing.points.first { it.kind == PreviewParticleKind.REQUESTED_EXIT }.x)
        assertEquals(6.0, drawing.points.first { it.kind == PreviewParticleKind.ACTUAL_END }.x)
        assertTrue(drawing.points.first { it.kind == PreviewParticleKind.ACTUAL_END }.y >
            drawing.points.first { it.kind == PreviewParticleKind.REQUESTED_EXIT }.y)
        val missing = RoutePreviewDrawing.prepare(failed) { null }
        assertEquals(setOf(rail), missing.unavailableGeometry)
        assertTrue(missing.points.isEmpty())
    }
}
