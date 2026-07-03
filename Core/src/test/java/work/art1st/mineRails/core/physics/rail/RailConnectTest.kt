package work.art1st.mineRails.core.physics.rail

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.physics.train.createTestWorld

class RailConnectTest {
    @Test
    fun `connecting the same adjacent rails twice does not duplicate next rails`() {
        val world = createTestWorld()
        val first = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val second = CoreRail(world, straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)), mutableListOf(), mutableListOf())

        CoreRail.connect(first, second)
        CoreRail.connect(first, second)

        assertEquals(1, first.viewAB.nextRails.size)
        assertEquals(0, first.viewBA.nextRails.size)
        assertEquals(0, second.viewAB.nextRails.size)
        assertEquals(1, second.viewBA.nextRails.size)
        assertEquals(second.viewAB, first.viewAB.nextActiveRail)
        assertEquals(first.viewBA, second.viewBA.nextActiveRail)
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
