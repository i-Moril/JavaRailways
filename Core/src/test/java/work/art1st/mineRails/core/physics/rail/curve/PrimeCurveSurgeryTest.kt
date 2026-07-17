package work.art1st.mineRails.core.physics.rail.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.util.annotation.Heavy

class PrimeCurveSurgeryTest {
    @OptIn(Heavy::class)
    @Test
    fun `split and concatenate preserve a straight curve without extra samples`() {
        val original = straightCurve(10.0)

        val (before, after) = original.splitAt(3.25)
        val merged = before.concatenate(after)

        assertEquals(3.25, before.totalArcLength, 1e-12)
        assertEquals(6.75, after.totalArcLength, 1e-12)
        assertEquals(original.totalArcLength, merged.totalArcLength, 1e-12)
        assertEquals(original.stream().count(), merged.stream().count())
        for (index in 0..40) {
            val arc = original.totalArcLength * index / 40.0
            assertPointsEqual(original.getPointAt(arc), merged.getPointAt(arc))
        }
    }

    @Test
    fun `split and concatenate preserve discrete sampled geometry`() {
        val original = PrimeCurve.fromPoints(
            listOf(
                rawPoint(0.0, 0.0, Vector3d(1.0, 0.1, 0.0).normalize()),
                rawPoint(4.0, 1.0, Vector3d(1.0, 0.2, 0.1).normalize()),
                rawPoint(9.0, 3.0, Vector3d(1.0, 0.3, 0.0).normalize())
            )
        )

        val (before, after) = original.splitAt(6.375)
        val merged = before.concatenate(after)

        for (index in 0..80) {
            val arc = original.totalArcLength * index / 80.0
            assertPointsEqual(original.getPointAt(arc), merged.getPointAt(arc))
        }
    }

    @Test
    fun `reversed curves can be split and concatenated in their own direction`() {
        val reversed = straightCurve(10.0).reversed

        val (before, after) = reversed.splitAt(2.5)
        val merged = before.concatenate(after)

        assertEquals(2.5, before.totalArcLength, 1e-12)
        assertEquals(7.5, after.totalArcLength, 1e-12)
        for (index in 0..20) {
            val arc = reversed.totalArcLength * index / 20.0
            assertPointsEqual(reversed.getPointAt(arc), merged.getPointAt(arc))
        }
    }

    @Test
    fun `split rejects rail endpoints`() {
        val curve = straightCurve(10.0)

        assertThrows(IllegalArgumentException::class.java) { curve.splitAt(0.0) }
        assertThrows(IllegalArgumentException::class.java) { curve.splitAt(10.0) }
    }

    private fun straightCurve(length: Double): PrimeCurve = PrimeCurve.fromPoints(
        listOf(
            Curve.RawPoint(
                Vector3d(),
                Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0),
                0.0,
                Curve.SegmentType.LINE_START
            ),
            Curve.RawPoint(
                Vector3d(length, 0.0, 0.0),
                Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0),
                length,
                Curve.SegmentType.LINE_END
            )
        )
    )

    private fun rawPoint(x: Double, y: Double, tangent: Vector3d): Curve.RawPoint = Curve.RawPoint(
        Vector3d(x, y, 0.0),
        tangent,
        Vector3d(0.0, 1.0, 0.0),
        x
    )

    private fun assertPointsEqual(expected: Curve.Point, actual: Curve.Point) {
        assertTrue(expected.position.distance(actual.position) < 1e-9)
        assertTrue(expected.tangent.distance(actual.tangent) < 1e-9)
        assertTrue(expected.normal.distance(actual.normal) < 1e-9)
    }
}
