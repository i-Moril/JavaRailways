package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.basic.safety.RailSpeedCalculator
import work.art1st.javarailways.basic.rail.RailAssemblyRole
import work.art1st.javarailways.core.api.util.annotation.Heavy

class RailCurvePartitionerTest {
    @Test
    fun `subcentimetre tangents omitted by generator do not create out of range partitions`() {
        val built = RailCurveFactory.build(
            Anchor("from", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
            Anchor("to", Vector3d(50.0, 0.0, 50.0), Vector3d(0.0, 0.0, 1.0)),
            RailCurveBuildOptions(template = RailCurveTemplateType.FIVE_SEGMENT, radius = 30.0))
        assertTrue(built.isSuccess, built.error)
        val curve = built.curve!!
        val parts = RailCurvePartitioner.partitionFiveSegment(curve, built.logicalBreakFractions)
        assertEquals(listOf(RailAssemblyRole.CURVE), parts.map { it.role })
        assertEquals(curve.totalArcLength, parts.single().curve.totalArcLength, 1e-9)
    }

    @Test
    fun `graded five segment factory maps horizontal construction joints into physical partitions`() {
        val from = Vector3d()
        val to = Vector3d(100.0, 20.0, 100.0)
        val solved = ConstraintSolver.solve(from, Vector3d(1.0, 0.0, 0.0), to,
            Vector3d(0.0, 0.0, 1.0), 30.0) as ConstraintSolveResult.Success
        val grade = 20.0 / solved.totalLength
        val built = RailCurveFactory.build(Anchor("from", from, Vector3d(1.0, grade, 0.0)),
            Anchor("to", to, Vector3d(0.0, grade, 1.0)),
            RailCurveBuildOptions(template = RailCurveTemplateType.FIVE_SEGMENT, radius = 30.0))
        assertTrue(built.isSuccess, built.error)
        val curve = built.curve!!
        assertEquals(solved.totalLength * kotlin.math.sqrt(1.0 + grade * grade), curve.totalArcLength, 1e-8)
        val parts = RailCurvePartitioner.partitionFiveSegment(curve, built.logicalBreakFractions)
        assertEquals(3, parts.size)
        val leadingEnd = parts[0].curve.getPointAt(parts[0].curve.totalArcLength).position
        val trailingStart = parts[2].curve.getPointAt(0.0).position
        assertTrue(leadingEnd.distance(Vector3d(solved.straightA, solved.straightA * grade, 0.0)) < 1e-7)
        assertTrue(trailingStart.distance(Vector3d(100.0, 20.0 - solved.straightB * grade,
            100.0 - solved.straightB)) < 1e-7)
        val varying = RailCurveFactory.build(Anchor("from", from, Vector3d(1.0, 0.0, 0.0)),
            Anchor("to", to, Vector3d(0.0, 0.0, 1.0)),
            RailCurveBuildOptions(template = RailCurveTemplateType.FIVE_SEGMENT, radius = 30.0))
        assertTrue(varying.isSuccess, varying.error)
        val varyingCurve = varying.curve!!
        val steps = 10_000
        val integratedLength = (0 until steps).sumOf { index ->
            val u = (index + 0.5) / steps
            val slope = 20.0 / solved.totalLength * 6.0 * u * (1.0 - u)
            kotlin.math.sqrt(1.0 + slope * slope) * solved.totalLength / steps
        }
        assertEquals(integratedLength, varyingCurve.totalArcLength, 5e-5)
        val varyingParts = RailCurvePartitioner.partitionFiveSegment(varyingCurve, varying.logicalBreakFractions)
        val varyingJoint = varyingParts[0].curve.getPointAt(varyingParts[0].curve.totalArcLength).position
        val u = solved.straightA / solved.totalLength
        assertEquals(solved.straightA, varyingJoint.x(), 1e-7)
        assertEquals(20.0 * (3.0 * u * u - 2.0 * u * u * u), varyingJoint.y(), 1e-7)
    }

    @Test
    @OptIn(Heavy::class)
    fun `each offset lane and nonuniform vertical rebuild keep their actual tangent joints`() {
        val offsets = listOf(-4.5, 0.0, 4.5)
        val solved = ConstraintSolver.solve(Vector3d(), Vector3d(1.0, 0.0, 0.0),
            Vector3d(100.0, 0.0, 100.0), Vector3d(0.0, 0.0, 1.0), 30.0) as ConstraintSolveResult.Success
        val built = RailCurveFactory.buildParallel(
            offsets.map { Anchor("from$it", Vector3d(0.0, 0.0, it), Vector3d(1.0, 0.0, 0.0)) },
            offsets.map { Anchor("to$it", Vector3d(100.0 - it, 0.0, 100.0), Vector3d(0.0, 0.0, 1.0)) },
            RailCurveBuildOptions(template = RailCurveTemplateType.FIVE_SEGMENT, radius = 30.0))
        assertTrue(built.isSuccess, built.error)
        assertEquals(3, built.logicalBreakFractionsByLane.distinct().size)
        built.curves!!.forEachIndexed { lane, source ->
            val fractions = built.logicalBreakFractionsByLane[lane]
            fun checkJoints(curve: PrimeCurve, boundaries: List<Double>) {
                val parts = RailCurvePartitioner.partitionFiveSegment(curve, boundaries)
                assertEquals(3, parts.size)
                val leadingEnd = parts[0].curve.getPointAt(parts[0].curve.totalArcLength).position
                val trailingStart = parts[2].curve.getPointAt(0.0).position
                assertEquals(solved.straightA, leadingEnd.x(), 1e-7)
                assertEquals(offsets[lane], leadingEnd.z(), 1e-7)
                assertEquals(100.0 - offsets[lane], trailingStart.x(), 1e-7)
                assertEquals(100.0 - solved.straightB, trailingStart.z(), 1e-7)
                assertEquals(curve.totalArcLength, parts.sumOf { it.curve.totalArcLength }, 1e-8)
                parts.zipWithNext().forEach { (a, b) -> assertTrue(
                    a.curve.getPointAt(a.curve.totalArcLength).position.distance(b.curve.getPointAt(0.0).position) < 1e-8) }
            }
            checkJoints(source, fractions)
            val horizontal = VerticalCurveRebuilder.horizontalLength(source)
            val profile = VerticalProfileSmoother.solve(listOf(0.0, horizontal), listOf(0.0, 40.0), 0.0, 0.0)
            val rebuilt = VerticalCurveRebuilder.rebuild(listOf(source), profile).single()
            val mapped = RailCurvePartitioner.remapBreakFractions(source, rebuilt, fractions)
            assertTrue(kotlin.math.abs((fractions[0] - mapped[0]) * rebuilt.totalArcLength) > 0.1)
            checkJoints(rebuilt, mapped)
        }
    }

    @Test
    fun `station remapping interpolates correspondence and rejects invalid sample mappings`() {
        assertEquals(listOf(15.0), RailCurvePartitioner.mapStations(listOf(0.0, 10.0, 20.0),
            listOf(0.0, 10.0, 30.0), listOf(12.5)))
        for ((source, target) in listOf(
            listOf(0.0, 1.0) to listOf(0.0, 1.0, 2.0),
            listOf(0.0, 0.0) to listOf(0.0, 1.0),
            listOf(0.0, 1.0) to listOf(0.0, Double.NaN)
        )) org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            RailCurvePartitioner.mapStations(source, target, listOf(0.5))
        }
    }

    @Test
    @OptIn(Heavy::class)
    fun `LINE virtual samples keep source junction correspondence through vertical rebuild`() {
        val source = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0),
                0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(40.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0),
                40.0, Curve.SegmentType.LINE_END)))
        assertTrue(source.stream().count() > 2)
        val profile = VerticalProfileSmoother.solve(listOf(0.0, 40.0), listOf(0.0, 30.0), 0.0, 0.0)
        val rebuilt = VerticalCurveRebuilder.rebuild(listOf(source), profile).single()
        val mapped = RailCurvePartitioner.remapBreakFractions(source, rebuilt, listOf(0.25, 0.75))
        val parts = RailCurvePartitioner.partitionFiveSegment(rebuilt, mapped)
        val first = parts[0].curve.getPointAt(parts[0].curve.totalArcLength).position
        val second = parts[2].curve.getPointAt(0.0).position
        assertTrue(first.distance(Vector3d(10.0, 4.6875, 0.0)) < 1e-8)
        assertTrue(second.distance(Vector3d(30.0, 25.3125, 0.0)) < 1e-8)
    }

    @Test
    fun `partitions leading curve and trailing regions without geometric gaps`() {
        val source = line(100.0)

        val parts = RailCurvePartitioner.partitionFiveSegment(source, listOf(0.2, 0.8))

        assertEquals(
            listOf(RailAssemblyRole.LEADING_TANGENT, RailAssemblyRole.CURVE, RailAssemblyRole.TRAILING_TANGENT),
            parts.map { it.role }
        )
        assertEquals(100.0, parts.sumOf { it.curve.totalArcLength }, 1.0e-9)
        parts.zipWithNext().forEach { (before, after) ->
            assertTrue(
                before.curve.getPointAt(before.curve.totalArcLength).position
                    .distance(after.curve.getPointAt(0.0).position) < 1.0e-9
            )
        }
    }

    @Test
    @OptIn(Heavy::class)
    fun `five segment tangent rails retain design speed while curve rail is limited`() {
        val built = RailCurveFactory.build(
            Anchor("A", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0)),
            Anchor("B", Vector3d(100.0, 0.0, 50.0), Vector3d(0.0, 0.0, 1.0)),
            RailCurveBuildOptions(template = RailCurveTemplateType.FIVE_SEGMENT, radius = 30.0)
        )
        assertTrue(built.isSuccess, built.error)
        val parts = RailCurvePartitioner.partitionFiveSegment(built.curve!!, built.logicalBreakFractions)
        val requested = 40.0
        val limits = parts.associate { part ->
            part.role to RailSpeedCalculator.calculate(
                part.curve,
                requested,
                allowableCantDeficiencyMeters = 0.075,
                derailLateralAccelerationMetersPerSecondSquared = 3.0
            ).operatingLimitMetersPerSecond
        }

        val tangentLimits = limits.filterKeys { it != RailAssemblyRole.CURVE }.values
        assertTrue(tangentLimits.isNotEmpty())
        assertTrue(tangentLimits.all { kotlin.math.abs(it - requested) < 1.0e-9 })
        assertTrue(limits.getValue(RailAssemblyRole.CURVE) < requested)
    }

    @Test
    fun `omits tangent parts whose boundary is at an endpoint`() {
        val source = line(100.0)

        val parts = RailCurvePartitioner.partitionFiveSegment(source, listOf(0.0, 0.75))

        assertEquals(listOf(RailAssemblyRole.CURVE, RailAssemblyRole.TRAILING_TANGENT), parts.map { it.role })
    }

    @Test
    fun `merges sub-tolerance tangent into curve rail`() {
        val source = line(100.0)
        val leadingLength = Curve.TOLERANCE / 10.0

        val parts = RailCurvePartitioner.partitionFiveSegment(
            source,
            listOf(leadingLength / source.totalArcLength, 0.75)
        )

        assertEquals(listOf(RailAssemblyRole.CURVE, RailAssemblyRole.TRAILING_TANGENT), parts.map { it.role })
        assertTrue(parts.all { it.curve.totalArcLength > Curve.TOLERANCE })
    }

    private fun line(length: Double): PrimeCurve = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
        Curve.RawPoint(Vector3d(length, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), length)
    ))
}
