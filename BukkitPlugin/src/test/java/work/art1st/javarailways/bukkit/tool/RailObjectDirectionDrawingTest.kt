package work.art1st.javarailways.bukkit.tool

import org.bukkit.World
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerGlyph
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerLabels
import work.art1st.javarailways.bukkit.api.editor.EditorTextAppearance
import work.art1st.javarailways.bukkit.api.editor.EditorTextHandle
import work.art1st.javarailways.bukkit.api.editor.EditorTextLabel
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy

class RailObjectDirectionDrawingTest {
    @Test
    fun `operating arrows and terminal boundary follow the directed rail including slope`() {
        val rail = rail(slope = 0.5)
        val drawing = RailObjectDirectionDrawing()
        for (direction in ViewDirection.entries) {
            val labels = drawing.prepare(listOf(RailObjectDirectionSource(rail, direction)), Vector3d())
            val expected = Vector3d(1.0, 0.5, 0.0).normalize()
                .mul(if (direction == ViewDirection.AB) 1.0 else -1.0)
            val arrows = labels.filter { it.id().contains(":arrow:") }
            assertTrue(arrows.isNotEmpty())
            arrows.forEach { label ->
                assertEquals(EditorMarkerLabels.FONT, label.text().font())
                assertEquals(EditorTextAppearance.Mode.FIXED_GLYPH, label.appearance().mode())
                val pose = label.appearance().transform()
                val actual = Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW())
                    .transform(Vector3d(0.0, 1.0, 0.0))
                assertTrue(actual.distance(expected) < 1e-9)
            }
            val boundary = labels.single { it.id().endsWith(":end") }
            val endpoint = rail.getView(direction).endPoint
            assertEquals(endpoint.position.x() + endpoint.normal.x() * 0.08, boundary.x(), 1e-9)
            assertEquals(endpoint.position.y() + endpoint.normal.y() * 0.08, boundary.y(), 1e-9)
            assertEquals(EditorMarkerGlyph.BOUNDARY.text(), (boundary.text() as net.kyori.adventure.text.TextComponent).content())
        }
    }

    @Test
    fun `approach arrows stay on arrival side in both directions`() {
        val rail = rail()
        val drawing = RailObjectDirectionDrawing()
        for (direction in ViewDirection.entries) {
            val labels = drawing.prepare(
                listOf(RailObjectDirectionSource(rail, direction, "station", 50.0)), Vector3d(50.0, 0.0, 0.0)
            )
            val arrows = labels.filter { it.id().contains(":arrow:") }
            val expectedRange = if (direction == ViewDirection.AB) 26.0..50.0 else 50.0..74.0
            assertTrue(arrows.isNotEmpty())
            assertTrue(arrows.all { it.x() in expectedRange })
            assertEquals(50.0, labels.single { it.id().endsWith(":end") }.x(), 1e-9)
        }
    }

    @Test
    fun `movement filters prepared values without recreating geometry and edits invalidate it`() {
        val rail = rail()
        val source = listOf(RailObjectDirectionSource(rail, ViewDirection.AB))
        val drawing = RailObjectDirectionDrawing()
        val first = drawing.prepare(source, Vector3d())
        val moved = drawing.prepare(source.toList(), Vector3d(10.0, 0.0, 0.0))
        first.zip(moved).forEach { (before, after) -> assertSame(before, after) }
        assertTrue(drawing.prepare(source, Vector3d(1000.0, 0.0, 0.0)).isEmpty())
        val returned = drawing.prepare(source, Vector3d())
        assertSame(first.first(), returned.first(), "out-of-range filtering does not discard cached geometry")
        val reversed = drawing.prepare(listOf(source.single().copy(direction = ViewDirection.BA)), Vector3d())
        assertNotEquals(first.first().appearance(), reversed.first().appearance())
        assertTrue(drawing.prepare(emptyList(), Vector3d()).isEmpty(), "removed restriction clears the scene")
    }

    @Test
    fun `session mode and exact world changes retire old labels and late work cannot revive them`() {
        val world = world()
        val handle = RecordingHandle()
        val session = RailObjectWand.DirectionOverlaySession(world, RailObjectWandMode.DIRECTION_RESTRICTION, handle)
        assertTrue(session.matches(world, RailObjectWandMode.DIRECTION_RESTRICTION))
        assertFalse(session.matches(world, RailObjectWandMode.WAY_POINT))
        assertFalse(session.matches(world(), RailObjectWandMode.DIRECTION_RESTRICTION))
        val labels = listOf(EditorTextLabel("arrow", 0.0, 0.0, 0.0, net.kyori.adventure.text.Component.text("arrow")))
        session.publish(labels)
        session.publish(emptyList())
        assertEquals(listOf(0L, 1L), handle.revisions)
        session.close()
        session.publish(labels)
        session.close()
        assertEquals(1, handle.closes)
        assertEquals(listOf(0L, 1L), handle.revisions)
        assertFalse(session.matches(world, RailObjectWandMode.DIRECTION_RESTRICTION))
    }

    private fun rail(slope: Double = 0.0): CoreRail {
        val tangent = Vector3d(1.0, slope, 0.0).normalize()
        val normal = Vector3d(-slope, 1.0, 0.0).normalize()
        return createTestWorld().createRailForTest(PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(100.0, 100.0 * slope, 0.0), tangent, normal,
                100.0 * kotlin.math.sqrt(1.0 + slope * slope), Curve.SegmentType.LINE_END)
        )))
    }

    private fun world(): World = Proxy.newProxyInstance(
        World::class.java.classLoader, arrayOf(World::class.java)
    ) { _, _, _ -> null } as World

    private class RecordingHandle : EditorTextHandle {
        val revisions = mutableListOf<Long>()
        var closes = 0
        override fun submit(revision: Long, labels: List<EditorTextLabel>) { revisions += revision }
        override fun close() { closes++ }
    }

    companion object {
        @JvmStatic @BeforeAll fun bootstrap() = ModelRuntimeTestSupport.bootstrap()
    }
}
