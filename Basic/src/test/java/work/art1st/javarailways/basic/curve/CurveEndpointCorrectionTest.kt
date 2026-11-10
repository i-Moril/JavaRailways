package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy

@OptIn(Heavy::class)
class CurveEndpointCorrectionTest {
    private fun line(length: Double, slope: Double = 0.0, roll: Double = 0.0): PrimeCurve {
        val tangent = Vector3d(1.0, slope, 0.0).normalize()
        val (_, normal, _) = CurveFrames.computeFrame(tangent)
        val rolled = CurveFrames.applyRollRadians(normal, tangent, Math.toRadians(roll))
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), tangent, rolled, 0.0),
            Curve.RawPoint(Vector3d(tangent).mul(length), tangent, rolled, length)
        ))
    }

    @Test
    fun `sparse curve closes both endpoints locally and preserves middle geometry and roll`() {
        for (slope in listOf(0.0, 0.3, -0.3)) {
            val source = line(4.0, slope, 7.0)
            val targetStart = Vector3d(0.0, 0.0, 0.005)
            val targetEnd = Vector3d(source.getPointAt(4.0).position).add(0.0, 0.0, -0.004)
            val corrected = CurveEndpointCorrection.correct(source, targetStart, targetEnd,
                listOf(0.0625, 0.125, 0.5, 0.875, 0.9375))
            val curve = corrected.curve
            assertEquals(targetStart, curve.getPointAt(0.0).position)
            assertEquals(targetEnd, curve.getPointAt(curve.totalArcLength).position)
            val points = corrected.logicalBreakFractions.map { curve.getPointAt(it * curve.totalArcLength) }
            assertEquals(0.0025, points[0].position.z(), 1e-12)
            assertEquals(0.0, points[1].position.z(), 1e-12)
            assertEquals(0.0, source.getPointAt(2.0).position.distance(points[2].position), 1e-12)
            assertEquals(0.0, points[3].position.z(), 1e-12)
            assertEquals(-0.002, points[4].position.z(), 1e-12)
            for (point in listOf(curve.getPointAt(0.0), points[1], points[3], curve.getPointAt(curve.totalArcLength))) {
                assertEquals(0.0, point.tangent.distance(source.getPointAt(0.0).tangent), 1e-12)
            }
            curve.stream().forEach { point ->
                assertEquals(0.0, point.tangent.dot(point.normal), 1e-12)
                assertEquals(1.0, point.normal.length(), 1e-12)
                assertEquals(7.0, AnchorGeometry.rollDegreesFromNormal(point.tangent, point.normal), 1e-9)
            }
            assertTrue(curve.stream().count() > 20, "Sparse source must be refined inside transitions")
            val middleDistance = (corrected.logicalBreakFractions[3] - corrected.logicalBreakFractions[1]) * curve.totalArcLength
            assertEquals(3.0, middleDistance, 1e-12)
        }
    }

    @Test
    fun `reject excessive correction and short source or corrected output`() {
        val source = line(2.0)
        assertThrows(IllegalArgumentException::class.java) {
            CurveEndpointCorrection.correct(source, Vector3d(0.0, 0.0, 0.005001), Vector3d(2.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CurveEndpointCorrection.correct(line(0.999), Vector3d(), Vector3d(0.999, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CurveEndpointCorrection.correct(line(1.0), Vector3d(0.004, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
        }
        assertSame(source, CurveEndpointCorrection.correct(source, Vector3d(), Vector3d(2.0, 0.0, 0.0)).curve)
    }

    @Test
    fun `exactly one metre supports adjoining transitions and reversal`() {
        val source = line(1.0)
        val result = CurveEndpointCorrection.correct(source, Vector3d(0.0, 0.0, 0.004),
            Vector3d(1.0, 0.0, -0.004), listOf(0.5))
        val midpoint = result.curve.getPointAt(result.logicalBreakFractions.single() * result.curve.totalArcLength)
        assertEquals(0.0, Vector3d(0.5, 0.0, 0.0).distance(midpoint.position), 1e-12)
        assertEquals(Vector3d(1.0, 0.0, 0.0), midpoint.tangent)
        val reversed = CurveEndpointCorrection.correct(source.reversed, Vector3d(1.0, 0.0, -0.004),
            Vector3d(0.0, 0.0, 0.004)).curve
        assertEquals(result.curve.totalArcLength, reversed.totalArcLength, 1e-12)
        assertEquals(1.0, CurveEndpointCorrection.correct(source, Vector3d(), Vector3d(1.0, 0.0, 0.0)).curve.totalArcLength)
    }

    @Test
    fun `factory rejects a complete curve shorter than one metre`() {
        val from = Anchor("from", Vector3d(), Vector3d(1.0, 0.0, 0.0))
        val to = Anchor("to", Vector3d(0.999, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
        assertInstanceOf(RailCurveBuildResult.Failure::class.java, RailCurveFactory.build(from, to))
        assertInstanceOf(RailCurveBuildResult.Success::class.java,
            RailCurveFactory.build(from, to.withPosition(Vector3d(1.0, 0.0, 0.0))))
    }

    @Test
    fun `near duplicate transition stations and nonunit source tangents remain valid`() {
        val tangent = Vector3d(2.0, 0.0, 0.0)
        val normal = Vector3d(0.0, 1.0, 0.0)
        val source = PrimeCurve.fromPoints(listOf(0.0, Math.nextDown(0.5), 1.5, 2.0).map {
            Curve.RawPoint(Vector3d(it, 0.0, 0.0), tangent, normal, it)
        })
        val result = CurveEndpointCorrection.correct(source, Vector3d(0.0, 0.0, 0.004),
            Vector3d(2.0, 0.0, -0.004), listOf(0.25, 0.75))
        val points = result.curve.stream().toList()
        assertTrue(points.zipWithNext().all { (a, b) -> b.arcLength - a.arcLength > 1e-12 })
        assertEquals(1.0, (result.logicalBreakFractions[1] - result.logicalBreakFractions[0]) *
            result.curve.totalArcLength, 1e-12)
    }

    @Test
    fun `unchanged circular middle retains its analytic arc metric`() {
        val radius = 20.0
        val length = 20.0
        // Intentionally sparse curved source; its stored metric is the analytic arc, not chords.
        val source = PrimeCurve.fromPoints((0..4).map { index ->
            val s = index * 5.0
            val angle = s / radius
            Curve.RawPoint(Vector3d(radius * kotlin.math.sin(angle), 0.0,
                radius * (1.0 - kotlin.math.cos(angle))),
                Vector3d(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle)),
                Vector3d(0.0, 1.0, 0.0), s)
        })
        val from = Vector3d(source.getPointAt(0.0).position).add(0.0, 0.004, 0.0)
        val to = Vector3d(source.getPointAt(length).position).add(0.0, -0.004, 0.0)
        val result = CurveEndpointCorrection.correct(source, from, to, listOf(0.25, 0.75))
        assertEquals(10.0, (result.logicalBreakFractions[1] - result.logicalBreakFractions[0]) *
            result.curve.totalArcLength, 1e-12)
        result.logicalBreakFractions.forEachIndexed { index, fraction ->
            assertEquals(0.0, source.getPointAt(if (index == 0) 5.0 else 15.0).position.distance(
                result.curve.getPointAt(fraction * result.curve.totalArcLength).position), 1e-12)
        }
    }

    @Test
    fun `five millimetre limit is independent of coordinate translation`() {
        for (origin in listOf(10.0, -1000000.0)) {
            val source = PrimeCurve.fromPoints(listOf(0.0, 2.0).map { s ->
                Curve.RawPoint(Vector3d(origin + s, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                    Vector3d(0.0, 1.0, 0.0), s)
            })
            val target = Vector3d(origin + 2.0 + 0.005, 0.0, 0.0)
            val result = CurveEndpointCorrection.correct(source, Vector3d(origin, 0.0, 0.0), target)
            assertEquals(target, result.curve.getPointAt(result.curve.totalArcLength).position)
            assertThrows(IllegalArgumentException::class.java) {
                CurveEndpointCorrection.correct(source, Vector3d(origin, 0.0, 0.0),
                    Vector3d(origin + 2.0 + 0.005001, 0.0, 0.0))
            }
        }
    }

    @Test
    fun `all authoring paths enforce complete curve minimum while vertical pieces are exempt`() {
        val from = listOf(-1.0, 1.0).map { z -> Anchor("from$z", Vector3d(0.0, 0.0, z), Vector3d(1.0, 0.0, 0.0)) }
        fun parallel(length: Double) = RailCurveFactory.buildParallel(from, from.map {
            it.withPosition(Vector3d(length, 0.0, it.position.z()))
        }, RailCurveBuildOptions())
        assertInstanceOf(RailParallelCurveBuildResult.Failure::class.java, parallel(0.999))
        assertInstanceOf(RailParallelCurveBuildResult.Success::class.java, parallel(1.0))
        assertNull(StandardTurnoutFactory.build(from.first(), Vector3d(1.0, 0.0, 0.1),
            StandardTurnoutGeometry(9, 1.0, 10.0)))
        fun piece(start: Double, end: Double): PrimeCurve = PrimeCurve.fromPoints(listOf(start, end).map { x ->
            Curve.RawPoint(Vector3d(x, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0), x - start)
        })
        val rebuilt = VerticalCurveRebuilder.smooth(listOf(piece(0.0, 0.6), piece(0.6, 1.2)))
        assertEquals(2, rebuilt.size)
        assertEquals(1.2, rebuilt.sumOf { it.totalArcLength }, 1e-12)
        assertThrows(IllegalArgumentException::class.java) {
            VerticalCurveRebuilder.smooth(listOf(piece(0.0, 0.999)))
        }
    }

    @Test
    fun `five segment engineering boundaries follow local correction before partitioning`() {
        val radius = 20.0
        val angle = Math.PI / 2.0
        val length = 0.1 + radius * angle + 0.2
        val solved = ConstraintSolveResult.Success(radius, 0.1, 0.0, radius * angle,
            0.0, 0.2, angle, length, angle)
        val source = PrimeCurve.fromPoints(ConstraintSolver.generateCurve(solved, Vector3d(),
            Vector3d(1.0, 0.0, 0.0), Vector3d(20.1, 0.0, 20.2), Vector3d(0.0, 0.0, 1.0)))
        val result = CurveEndpointCorrection.correct(source, Vector3d(0.0, 0.004, 0.0),
            Vector3d(source.getPointAt(length).position).add(0.0, -0.004, 0.0),
            listOf(0.1 / length, (length - 0.2) / length))
        val parts = RailCurvePartitioner.partitionFiveSegment(result.curve, result.logicalBreakFractions)
        assertEquals(3, parts.size)
        // At source station 0.1: u=.2, w=.05792. At source distance .2 from end:
        // u=.4, w=.31744. These values are independent of the production weight helper.
        assertEquals(0.004 * (1.0 - 0.05792), parts[0].curve.getPointAt(parts[0].curve.totalArcLength).position.y(), 1e-10)
        assertEquals(-0.004 * (1.0 - 0.31744), parts[2].curve.getPointAt(0.0).position.y(), 1e-10)
    }

    @Test
    fun `invalid engineering fractions reject even without a positional correction`() {
        for (fraction in listOf(Double.NaN, -0.1, 1.1)) {
            assertThrows(IllegalArgumentException::class.java) {
                CurveEndpointCorrection.correct(line(2.0), Vector3d(), Vector3d(2.0, 0.0, 0.0), listOf(fraction))
            }
        }
    }
}
