package work.art1st.mineRails.basic.safety

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.curve.Anchor
import work.art1st.mineRails.basic.curve.StandardRailCurve
import work.art1st.mineRails.basic.curve.StandardTurnout
import work.art1st.mineRails.basic.curve.StandardTurnoutFactory
import work.art1st.mineRails.basic.curve.SuperelevationConfig
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.util.annotation.Heavy
import kotlin.math.atan2
import kotlin.math.sqrt

@OptIn(Heavy::class)
class RailSpeedCalculatorTest {
    @Test
    fun `uncanted curve has separate operating and derail speeds`() {
        val curve = requireNotNull(StandardTurnoutFactory.build(
            Anchor("A", Vector3d(), Vector3d(1.0, 0.0, 0.0)),
            Vector3d(1.0, 0.0, -0.2),
            StandardTurnout.N9
        )).curve

        val limits = RailSpeedCalculator.calculate(curve, 100.0, 0.075, 3.0)
        val expectedOperating = sqrt(
            RailSpeedCalculator.GRAVITY_METERS_PER_SECOND_SQUARED * 0.075 /
                RailSpeedCalculator.STANDARD_GAUGE_METERS * StandardTurnout.N9.radiusMeters
        )
        val expectedDerail = sqrt(3.0 * StandardTurnout.N9.radiusMeters)

        assertEquals(expectedOperating, limits.operatingLimitMetersPerSecond, 0.02)
        assertEquals(expectedDerail, limits.derailSpeedMetersPerSecond!!, 0.05)
        assertTrue(limits.derailSpeedMetersPerSecond > limits.operatingLimitMetersPerSecond)
    }

    @Test
    fun `capped cant reduces requested operating limit`() {
        val requestedKmH = 80.0
        val balanceKmH = requestedKmH - 10.0
        val source = StandardRailCurve(
            300.0,
            20.0,
            50.0,
            SuperelevationConfig(maxSuperelevation = 0.15, designSpeedKmH = balanceKmH)
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
