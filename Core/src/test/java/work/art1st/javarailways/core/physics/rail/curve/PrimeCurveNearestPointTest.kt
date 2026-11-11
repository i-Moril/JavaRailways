package work.art1st.javarailways.core.physics.rail.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.CurveException
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy

@OptIn(Heavy::class)
class PrimeCurveNearestPointTest {
    @Test
    fun `two point line projects to a fractional interior position`() {
        val curve = straightLine()

        val point = curve.getNearestPoint(Vector3d(4.4, 3.0, -2.0))

        assertLocation(point, 4.4, 4.4, 0.0, 0.0)
        assertEquals(0.0, point.tangent.distance(Vector3d(1.0, 0.0, 0.0)), 1e-9)
        assertEquals(0.0, point.normal.distance(Vector3d(0.0, 1.0, 0.0)), 1e-9)
        assertSame(curve, point.curve)
    }

    @Test
    fun `nearest projection clamps to either line endpoint`() {
        val curve = straightLine()

        assertLocation(curve.getNearestPoint(Vector3d(-2.0, 1.0, 0.0)), 0.0, 0.0, 0.0, 0.0)
        assertLocation(curve.getNearestPoint(Vector3d(12.0, 1.0, 0.0)), 10.0, 10.0, 0.0, 0.0)
    }

    @Test
    fun `sparse discrete curve projects onto the closest three dimensional segment`() {
        val normal = Vector3d(-0.8, 0.6, 0.0)
        val curve = PrimeCurve.fromPoints(
            listOf(
                Curve.RawPoint(Vector3d(), Vector3d(0.6, 0.8, 0.0), normal, 0.0),
                Curve.RawPoint(Vector3d(3.0, 4.0, 0.0), Vector3d(0.0, 0.0, 1.0), normal, 5.0),
                Curve.RawPoint(Vector3d(3.0, 4.0, 5.0), Vector3d(0.0, 0.0, 1.0), normal, 10.0)
            )
        )

        val point = curve.getNearestPoint(Vector3d(4.0, 5.0, 2.4))

        assertLocation(point, 7.4, 3.0, 4.0, 2.4)
        assertEquals(0.0, point.tangent.distance(Vector3d(0.0, 0.0, 1.0)), 1e-9)
        assertEquals(0.0, point.normal.distance(normal), 1e-9)
        assertSame(curve, point.curve)
    }

    @Test
    fun `zero length geometric segment remains a candidate and permits later projection`() {
        val curve = PrimeCurve.fromPoints(
            listOf(
                straightPoint(0.0, 0.0),
                straightPoint(0.0, 1.0),
                straightPoint(10.0, 11.0)
            )
        )

        assertLocation(curve.getNearestPoint(Vector3d(0.0, 1.0, 0.0)), 0.0, 0.0, 0.0, 0.0)
        assertLocation(curve.getNearestPoint(Vector3d(4.4, 1.0, 0.0)), 5.4, 4.4, 0.0, 0.0)
    }

    @Test
    fun `position lookup accepts exact and within tolerance interior positions`() {
        val curve = straightLine()

        assertLocation(curve.getPointAt(Vector3d(4.4, 0.0, 0.0)), 4.4, 4.4, 0.0, 0.0)
        assertLocation(curve.getPointAt(Vector3d(4.4, 0.004, 0.0)), 4.4, 4.4, 0.0, 0.0)
    }

    @Test
    fun `position lookup rejects outside tolerance with the diagnostic message`() {
        val curve = straightLine()

        val failure = assertThrows(CurveException::class.java) {
            curve.getPointAt(Vector3d(4.4, 0.006, 0.0))
        }

        assertEquals("Point not in curve.", failure.message)
    }

    private fun straightLine(): PrimeCurve = PrimeCurve.fromPoints(
        listOf(
            straightPoint(0.0, 0.0, Curve.SegmentType.LINE_START),
            straightPoint(10.0, 10.0, Curve.SegmentType.LINE_END)
        )
    )

    private fun straightPoint(
        x: Double,
        arcLength: Double,
        segmentType: Curve.SegmentType = Curve.SegmentType.DISCRETE
    ) = Curve.RawPoint(
        Vector3d(x, 0.0, 0.0),
        Vector3d(1.0, 0.0, 0.0),
        Vector3d(0.0, 1.0, 0.0),
        arcLength,
        segmentType
    )

    private fun assertLocation(point: Curve.Point, arcLength: Double, x: Double, y: Double, z: Double) {
        assertEquals(arcLength, point.arcLength, 1e-9)
        assertEquals(x, point.position.x(), 1e-9)
        assertEquals(y, point.position.y(), 1e-9)
        assertEquals(z, point.position.z(), 1e-9)
    }
}
