package work.art1st.javarailways.basic.safety

import work.art1st.javarailways.basic.curve.RailCurveBuildResult
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.curve.Anchor
import work.art1st.javarailways.basic.curve.RailCurveBuildOptions
import work.art1st.javarailways.basic.curve.RailCurveFactory
import work.art1st.javarailways.basic.curve.RailCurveTemplateType
import work.art1st.javarailways.basic.curve.StandardRailCurve
import work.art1st.javarailways.basic.curve.StandardTurnoutGeometry
import work.art1st.javarailways.basic.curve.StandardTurnoutFactory
import work.art1st.javarailways.basic.curve.SuperelevationConfig
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import kotlin.math.atan2
import kotlin.math.sqrt

@OptIn(Heavy::class)
class RailSpeedCalculatorTest {
    @Test
    fun `uncanted curve has separate operating and derail speeds`() {
        val turnout = StandardTurnoutGeometry(9, 180.0, 30.0)
        val curve = requireNotNull(StandardTurnoutFactory.build(
            Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
            Vector3d(1.0, 0.0, -0.2),
            turnout
        ))

        val limits = RailSpeedCalculator.calculate(curve, 100.0, 0.075, 3.0)
        val expectedOperating = sqrt(
            RailSpeedCalculator.GRAVITY_METERS_PER_SECOND_SQUARED * 0.075 /
                RailSpeedCalculator.STANDARD_GAUGE_METERS * turnout.radiusMeters
        )
        val expectedDerail = sqrt(3.0 * turnout.radiusMeters)

        assertEquals(expectedOperating, limits.operatingLimitMetersPerSecond, 0.02)
        assertEquals(expectedDerail, limits.derailSpeedMetersPerSecond!!, 0.05)
        assertTrue(limits.derailSpeedMetersPerSecond > limits.operatingLimitMetersPerSecond)
    }

    @Test
    fun `speed limits use configured gauge and gravity`() {
        val turnout = StandardTurnoutGeometry(9, 180.0, 30.0)
        val curve = requireNotNull(StandardTurnoutFactory.build(
            Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
            Vector3d(1.0, 0.0, -0.2),
            turnout
        ))

        val limits = RailSpeedCalculator.calculate(
            curve,
            requestedLimitMetersPerSecond = 100.0,
            allowableCantDeficiencyMeters = 0.1,
            derailLateralAccelerationMetersPerSecondSquared = 3.0,
            physics = RailSpeedPhysics(gaugeMeters = 2.0, gravityMetersPerSecondSquared = 5.0)
        )

        assertEquals(sqrt(5.0 * 0.1 / 2.0 * turnout.radiusMeters),
            limits.operatingLimitMetersPerSecond, 0.02)
    }

    @Test
    fun `capped cant reduces requested operating limit`() {
        val requestedKmH = 80.0
        val balanceKmH = requestedKmH - 10.0
        val source = StandardRailCurve(
            300.0,
            20.0,
            50.0,
            SuperelevationConfig(
                maxSuperelevation = 0.15,
                designSpeedKmH = balanceKmH,
                maxCantGradient = 1.0
            )
        )
        val curve = PrimeCurve.fromPoints(source.generatePoints(0.1).map { point ->
            val cantAngle = atan2(source.superelevationAt(point.arcLength), RailSpeedCalculator.STANDARD_GAUGE_METERS)
            val tangent = Vector3d(point.tangent).normalize()
            val normal = Vector3d(0.0, 1.0, 0.0).rotateAxis(cantAngle, tangent.x(), tangent.y(), tangent.z())
            Curve.RawPoint(point.position, tangent, normal, point.arcLength)
        })

        assertEquals(0.15, source.superelevationAt(30.0), 1.0e-9)
        val limits = RailSpeedCalculator.calculate(curve, requestedKmH / 3.6, 0.075, 3.0)

        assertTrue(limits.operatingLimitMetersPerSecond < requestedKmH / 3.6)
        assertEquals(77.4, limits.operatingLimitMetersPerSecond * 3.6, 0.3)
    }

    @Test
    fun `cant raises the operating limit of an offset s curve`() {
        fun build(cantMeters: Double?): PrimeCurve {
            val result = RailCurveFactory.build(
                Anchor("A", Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0)),
                Anchor("B", Vector3d(90.0, 0.0, 6.0), Vector3d(1.0, 0.0, 0.0)),
                RailCurveBuildOptions(
                    template = RailCurveTemplateType.S_CURVE,
                    superelevation = cantMeters?.let {
                        SuperelevationConfig(maxSuperelevation = it, autoCalculate = false)
                    }
                )
            )
            val resultSuccess = assertInstanceOf(RailCurveBuildResult.Success::class.java, result)
            return resultSuccess.curve
        }

        val uncanted = RailSpeedCalculator.calculate(build(null), 100.0, 0.075, 3.0)
        val canted = RailSpeedCalculator.calculate(build(0.15), 100.0, 0.075, 3.0)

        assertTrue(
            canted.operatingLimitMetersPerSecond > uncanted.operatingLimitMetersPerSecond + 5.0 / 3.6,
            "uncanted=${uncanted.operatingLimitMetersPerSecond * 3.6}, " +
                "canted=${canted.operatingLimitMetersPerSecond * 3.6}"
        )
    }

    @Test
    fun `manual curve limit is independent from automatic operating limit but capped by safety and subgrade`() {
        val geometry = RailSpeedLimits(
            operatingLimitMetersPerSecond = 12.0,
            derailSpeedMetersPerSecond = 24.0
        )

        assertEquals(20.0, RailSpeedLimitPolicy.apply(geometry, 30.0, 20.0).operatingLimitMetersPerSecond)
        assertEquals(22.0, RailSpeedLimitPolicy.apply(geometry, 22.0, 30.0).operatingLimitMetersPerSecond)
        assertEquals(24.0, RailSpeedLimitPolicy.apply(geometry, 30.0, 30.0).operatingLimitMetersPerSecond)
        assertEquals(12.0, RailSpeedLimitPolicy.apply(geometry, 30.0, null).operatingLimitMetersPerSecond)
    }

    @Test
    fun `straight geometry keeps requested limit and has no curve derail speed`() {
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(100.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 100.0)
        ))

        val limits = RailSpeedCalculator.calculate(curve, 30.0, 0.075, 3.0)

        assertEquals(30.0, limits.operatingLimitMetersPerSecond)
        assertNull(limits.derailSpeedMetersPerSecond)
    }
}
