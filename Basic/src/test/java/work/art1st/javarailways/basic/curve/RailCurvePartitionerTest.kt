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
