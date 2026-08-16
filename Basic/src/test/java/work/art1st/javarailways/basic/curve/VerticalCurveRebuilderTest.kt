package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy

class VerticalCurveRebuilderTest {
    @Test
    fun `rebuilds adjacent curves with a shared crest tangent and true arc length`() {
        val source = listOf(
            line(0.0, 0.0, 10.0, 5.0),
            line(10.0, 5.0, 20.0, 0.0)
        )
        val profile = VerticalProfileSmoother.solve(
            stations = listOf(0.0, 10.0, 20.0),
            elevations = listOf(0.0, 5.0, 0.0),
            startGrade = 0.0,
            endGrade = 0.0
        )

        val rebuilt = VerticalCurveRebuilder.rebuild(source, profile)

        val leftEnd = rebuilt[0].getPointAt(rebuilt[0].totalArcLength)
        val rightStart = rebuilt[1].getPointAt(0.0)
        assertEquals(5.0, leftEnd.position.y(), 1.0e-9)
        assertEquals(1.0, leftEnd.tangent.dot(rightStart.tangent), 1.0e-9)
        assertEquals(0.0, leftEnd.tangent.y(), 1.0e-9)
        assertTrue(rebuilt.sumOf { it.totalArcLength } > 20.0)
    }

    @Test
    @OptIn(Heavy::class)
    fun `automatic smoothing keeps an adjacent horizontal rail level`() {
        val source = listOf(
            line(0.0, 3.0, 10.0, 3.0),
            line(10.0, 3.0, 20.0, 8.0)
        )

        val rebuilt = VerticalCurveRebuilder.smooth(source)

        rebuilt[0].stream().forEach { point ->
            assertEquals(3.0, point.position.y(), 1.0e-12)
            assertEquals(0.0, point.tangent.y(), 1.0e-12)
        }
        assertEquals(0.0, rebuilt[1].getPointAt(0.0).tangent.y(), 1.0e-12)
    }

    @Test
    fun `reversed copy remains reversed after persistence`() {
        val source = line(0.0, 1.0, 10.0, 2.0)

        val reversed = VerticalCurveRebuilder.reversedCopy(source)
        val restored = reversed.save().load(Unit)

        assertEquals(10.0, restored.getPointAt(0.0).position.x(), 1.0e-12)
        assertEquals(0.0, restored.getPointAt(restored.totalArcLength).position.x(), 1.0e-12)
        assertTrue(restored.getPointAt(0.0).tangent.x() < 0.0)
    }

    @Test
    @OptIn(Heavy::class)
    fun `rebuild preserves horizontal geometry and level profile`() {
        val source = listOf(line(0.0, 4.0, 10.0, 4.0))
        val profile = VerticalProfileSmoother.solve(
            stations = listOf(0.0, 10.0),
            elevations = listOf(4.0, 4.0),
            startGrade = 0.0,
            endGrade = 0.0,
            horizontalSegments = setOf(0)
        )

        val rebuilt = VerticalCurveRebuilder.rebuild(source, profile).single()
        rebuilt.stream().forEach { point ->
            assertEquals(4.0, point.position.y(), 1.0e-12)
            assertEquals(0.0, point.tangent.y(), 1.0e-12)
            assertEquals(0.0, point.position.z(), 1.0e-12)
        }
    }

    private fun line(x0: Double, y0: Double, x1: Double, y1: Double): PrimeCurve {
        val length = kotlin.math.hypot(x1 - x0, y1 - y0)
        val tangent = Vector3d(x1 - x0, y1 - y0, 0.0).normalize()
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(x0, y0, 0.0), tangent, Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d((x0 + x1) / 2.0, (y0 + y1) / 2.0, 0.0), tangent, Vector3d(0.0, 1.0, 0.0), length / 2.0),
            Curve.RawPoint(Vector3d(x1, y1, 0.0), tangent, Vector3d(0.0, 1.0, 0.0), length)
        ))
    }
}
