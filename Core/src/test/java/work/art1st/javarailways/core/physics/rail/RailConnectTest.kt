package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.train.createTestWorld

class RailConnectTest {
    @Test
    fun `save stores connections at semantic endpoints`() {
        val world = createTestWorld()
        val first = world.createRailForTest(straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
        val second = world.createRailForTest(straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))

        CoreRail.connect(first, second)

        val firstSaved = first.save()
        val secondSaved = second.save()
        assertEquals(emptyList<String>(), firstSaved.nextRailAtA)
        assertEquals(listOf(second.id.toString()), firstSaved.nextRailAtB)
        assertNull(firstSaved.activeTrackAtA)
        assertEquals(second.id.toString(), firstSaved.activeTrackAtB)
        assertEquals(listOf(first.id.toString()), secondSaved.nextRailAtA)
        assertEquals(emptyList<String>(), secondSaved.nextRailAtB)
        assertEquals(first.id.toString(), secondSaved.activeTrackAtA)
        assertNull(secondSaved.activeTrackAtB)
    }

    @Test
    fun `connecting the same adjacent rails twice does not duplicate next rails`() {
        val world = createTestWorld()
        val first = world.createRailForTest(straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
        val second = world.createRailForTest(straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))

        val beforeConnect = world.topologyState

        CoreRail.connect(first, second)
        val afterConnect = world.topologyState
        CoreRail.connect(first, second)

        assertEquals(1, first.viewAB.nextRails.size)
        assertEquals(0, first.viewBA.nextRails.size)
        assertEquals(0, second.viewAB.nextRails.size)
        assertEquals(1, second.viewBA.nextRails.size)
        assertEquals(second.viewAB, first.viewAB.nextActiveRail)
        assertEquals(first.viewBA, second.viewBA.nextActiveRail)
        assertEquals(beforeConnect.revision + 1L, afterConnect.revision)
        assertEquals(afterConnect.revision, world.topologyRevision)
        assertSame(afterConnect, world.topologyState)
        assertEquals(1, afterConnect.edges.size)
    }

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, Vector3d(tangent), Vector3d(normal), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, Vector3d(tangent), Vector3d(normal), length, Curve.SegmentType.LINE_END)
        ))
    }
}
