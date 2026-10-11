package work.art1st.javarailways.core.physics.rail.curve

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * 用稀疏轨道样本验证弧长查找、位置与 frame 插值，以及反向查询。
 */
class PrimeCurveTest {

    @Test
    fun `two point line segment interpolates across full length`() {
        val curve = PrimeCurve.fromPoints(
            listOf(
                Curve.RawPoint(
                    Vector3d(0.0, 0.0, 0.0),
                    Vector3d(1.0, 0.0, 0.0),
                    Vector3d(0.0, 1.0, 0.0),
                    0.0,
                    Curve.SegmentType.LINE_START
                ),
                Curve.RawPoint(
                    Vector3d(200.0, 0.0, 0.0),
                    Vector3d(1.0, 0.0, 0.0),
                    Vector3d(0.0, 1.0, 0.0),
                    200.0,
                    Curve.SegmentType.LINE_END
                )
            )
        )

        assertEquals(50.0, curve.getPointAt(50.0).position.x(), 1e-9)
        assertEquals(100.0, curve.getPointAt(100.0).position.x(), 1e-9)
        assertEquals(150.0, curve.getPointAt(150.0).position.x(), 1e-9)
        assertEquals(199.5, curve.getPointAt(199.5).position.x(), 1e-9)
    }

    @Test
    fun `sparse line preserves position and frame at boundaries and between samples`() {
        // The displacement (6, 8, 0) is exactly 10m; this is a translated 3-4-5 slope.
        val tangent = Vector3d(0.6, 0.8, 0.0)
        val normal = Vector3d(-0.8, 0.6, 0.0)
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(1.0, 2.0, 3.0), tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(7.0, 10.0, 3.0), tangent, normal, 10.0, Curve.SegmentType.LINE_END)
        ))
        val expectedPositions = listOf(
            0.0 to Vector3d(1.0, 2.0, 3.0),
            0.001 to Vector3d(1.0006, 2.0008, 3.0),
            2.5 to Vector3d(2.5, 4.0, 3.0),
            9.999 to Vector3d(6.9994, 9.9992, 3.0),
            10.0 to Vector3d(7.0, 10.0, 3.0)
        )

        assertEquals(10.0, curve.totalArcLength)
        expectedPositions.forEach { (arc, expectedPosition) ->
            val point = curve.getPointAt(arc)
            assertEquals(arc, point.arcLength, 1e-9)
            assertEquals(0.0, expectedPosition.distance(point.position), 1e-9, "position at $arc")
            assertEquals(0.0, tangent.distance(point.tangent), 1e-9, "tangent at $arc")
            assertEquals(0.0, normal.distance(point.normal), 1e-9, "normal at $arc")
            assertSame(curve, point.curve)
        }
    }

    @Test
    fun `sparse discrete frames normalize and project while the endpoint retains its raw frame`() {
        // Positions and arc lengths both advance by 5m on a straight track. Raw frames may be
        // non-unit/non-orthogonal: removing the X component leaves bank angles 0, 90, 0 degrees.
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(2.0, 0.0, 0.0), Vector3d(1.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(5.0, 0.0, 0.0), Vector3d(2.0, 0.0, 0.0), Vector3d(1.0, 0.0, 1.0), 5.0),
            Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(2.0, 0.0, 0.0), Vector3d(1.0, 1.0, 0.0), 10.0)
        ))
        val diagonal = sqrt(0.5) // 45-degree bank, independently known unit direction.
        val expectedNormals = listOf(
            0.0 to Vector3d(0.0, 1.0, 0.0),
            2.5 to Vector3d(0.0, diagonal, diagonal),
            5.0 to Vector3d(0.0, 0.0, 1.0),
            7.5 to Vector3d(0.0, diagonal, diagonal)
        )

        assertEquals(10.0, curve.totalArcLength)
        expectedNormals.forEach { (arc, expectedNormal) ->
            val point = curve.getPointAt(arc)
            assertEquals(arc, point.arcLength, 1e-9)
            assertEquals(0.0, Vector3d(arc, 0.0, 0.0).distance(point.position), 1e-9, "position at $arc")
            assertEquals(0.0, Vector3d(1.0, 0.0, 0.0).distance(point.tangent), 1e-9, "tangent at $arc")
            assertEquals(0.0, expectedNormal.distance(point.normal), 1e-9, "normal at $arc")
            assertSame(curve, point.curve)
        }

        val end = curve.getPointAt(10.0)
        assertEquals(10.0, end.arcLength, 1e-9)
        assertEquals(0.0, Vector3d(10.0, 0.0, 0.0).distance(end.position), 1e-9)
        assertEquals(0.0, Vector3d(2.0, 0.0, 0.0).distance(end.tangent), 1e-9)
        assertEquals(0.0, Vector3d(1.0, 1.0, 0.0).distance(end.normal), 1e-9)
        assertSame(curve, end.curve)
    }

    @Test
    fun `discrete tangent between orthogonal raw directions points at forty five degrees`() {
        // Raw directions are independent inputs; the stored positions and arc span both cover 5m.
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(5.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0), 5.0)
        ))

        val middle = curve.getPointAt(2.5)

        assertEquals(0.0, Vector3d(2.5, 0.0, 0.0).distance(middle.position), 1e-9)
        assertEquals(0.0, Vector3d(sqrt(0.5), 0.0, sqrt(0.5)).distance(middle.tangent), 1e-9)
        assertEquals(0.0, Vector3d(0.0, 1.0, 0.0).distance(middle.normal), 1e-9)
    }

    @Test
    @OptIn(work.art1st.javarailways.core.api.util.annotation.Heavy::class)
    fun `reversed curve nearest point reports reversed arc and tangent`() {
        val curve = PrimeCurve.fromPoints(
            listOf(
                Curve.RawPoint(
                    Vector3d(0.0, 0.0, 0.0),
                    Vector3d(1.0, 0.0, 0.0),
                    Vector3d(0.0, 1.0, 0.0),
                    0.0,
                    Curve.SegmentType.LINE_START
                ),
                Curve.RawPoint(
                    Vector3d(100.0, 0.0, 0.0),
                    Vector3d(1.0, 0.0, 0.0),
                    Vector3d(0.0, 1.0, 0.0),
                    100.0,
                    Curve.SegmentType.LINE_END
                )
            )
        ).reversed

        val point = curve.getNearestPoint(Vector3d(25.4, 0.0, 0.0))

        assertEquals(74.6, point.arcLength, 1.0e-9)
        assertEquals(25.4, point.position.x(), 1.0e-9)
        assertEquals(-1.0, point.tangent.x(), 1.0e-9)
        assertEquals(1.0, point.normal.y(), 1.0e-9)
        assertTrue(point.curve === curve)
    }
}
