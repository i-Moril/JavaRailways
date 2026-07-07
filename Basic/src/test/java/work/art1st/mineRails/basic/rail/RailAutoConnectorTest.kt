package work.art1st.mineRails.basic.rail

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.train.createTestWorld
import kotlin.math.cos
import kotlin.math.sin

class RailAutoConnectorTest {
    @Test
    fun `auto connector rejects sharp endpoint branch`() {
        val world = createTestWorld()
        val approach = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val side = CoreRail(world, straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(10.0, 0.0, 10.0)), mutableListOf(), mutableListOf())

        val result = RailAutoConnector.connectIfAllowed(approach, side)

        assertEquals(RailAutoConnector.Outcome.ANGLE_TOO_SHARP, result.outcome)
        assertTrue((result.angleDegrees ?: 0.0) > 80.0)
        assertEquals(0, approach.viewAB.nextRails.size)
        assertEquals(0, side.viewBA.nextRails.size)
    }

    @Test
    fun `auto connector allows thirty degree endpoint transition`() {
        val world = createTestWorld()
        val approach = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val turnout = CoreRail(world, angledCurveFrom(Vector3d(10.0, 0.0, 0.0), 30.0), mutableListOf(), mutableListOf())

        val result = RailAutoConnector.connectIfAllowed(approach, turnout)

        assertEquals(RailAutoConnector.Outcome.CONNECTED, result.outcome)
        assertTrue((result.angleDegrees ?: 180.0) <= 30.000001)
        assertSame(turnout.viewAB, approach.viewAB.nextActiveRail)
        assertSame(approach.viewBA, turnout.viewBA.nextActiveRail)
    }

    @Test
    fun `created rail can select smoothest active branch after unordered auto connects`() {
        val world = createTestWorld()
        val created = CoreRail(world, straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val branch = CoreRail(world, curveEndingAt(Vector3d(10.0, 0.0, 0.0), 200.0), mutableListOf(), mutableListOf())
        val straight = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())

        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(created, branch).outcome)
        assertSame(branch.viewBA, created.viewBA.nextActiveRail)
        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(created, straight).outcome)
        assertSame(branch.viewBA, created.viewBA.nextActiveRail)

        assertTrue(RailAutoConnector.selectSmoothestActiveRail(created.viewBA))

        assertEquals(2, created.viewBA.nextRails.size)
        assertSame(straight.viewBA, created.viewBA.nextActiveRail)
    }

    @Test
    fun `auto connector preserves existing active rail when adding another legal branch`() {
        val world = createTestWorld()
        val approach = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val straight = CoreRail(world, straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val turnout = CoreRail(world, angledCurveFrom(Vector3d(10.0, 0.0, 0.0), 20.0), mutableListOf(), mutableListOf())

        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(approach, straight).outcome)
        assertSame(straight.viewAB, approach.viewAB.nextActiveRail)

        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(approach, turnout).outcome)

        assertEquals(2, approach.viewAB.nextRails.size)
        assertTrue(approach.viewAB.nextRails.contains(straight.viewAB))
        assertTrue(approach.viewAB.nextRails.contains(turnout.viewAB))
        assertSame(straight.viewAB, approach.viewAB.nextActiveRail)
    }

    private fun angledCurveFrom(from: Vector3d, angleDegrees: Double, length: Double = 10.0): PrimeCurve {
        val radians = Math.toRadians(angleDegrees)
        val to = Vector3d(
            from.x + cos(radians) * length,
            from.y,
            from.z + sin(radians) * length
        )
        return straightCurve(from, to)
    }

    private fun curveEndingAt(to: Vector3d, awayAngleDegrees: Double, length: Double = 10.0): PrimeCurve {
        val radians = Math.toRadians(awayAngleDegrees)
        val from = Vector3d(
            to.x + cos(radians) * length,
            to.y,
            to.z + sin(radians) * length
        )
        return straightCurve(from, to)
    }

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(normal), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(to), Vector3d(tangent), Vector3d(normal), length, Curve.SegmentType.LINE_END)
        ))
    }
}
