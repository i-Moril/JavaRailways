package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.physics.rail.CoreRail

class TrainSlopeMotionTest {
    @Test
    fun `multi car train climbs straight slope`() {
        val world = createTestWorld()
        val curve = createStraightSlope(length = 200.0, rise = 20.0)
        val rail = CoreRail(world, curve, mutableListOf(), mutableListOf())
        val train = createTrainWithConfig("slope", rail, world, listOf(10.0, 10.0, 10.0, 10.0), isLoop = false)
        train.setController(MockTrainController(train, world, 10.0))

        val startX = train.head.location.position.x()
        val startY = train.head.location.position.y()
        repeat(100) {
            train.controller!!.tick(50.0)
            train.commit()
            assertFalse(train.isDerailed, "derailed at tick $it")
        }
        assertTrue(train.head.location.position.x() > startX + 40.0, "head should move forward")
        assertTrue(train.head.location.position.y() > startY + 4.0, "head should climb")
    }

    private fun createStraightSlope(length: Double, rise: Double): PrimeCurve {
        val start = Vector3d(0.0, 64.0, 0.0)
        val end = Vector3d(length, 64.0 + rise, 0.0)
        val dist = start.distance(end)
        val tan = Vector3d(end).sub(start).normalize()
        val up = Vector3d(0.0, 1.0, 0.0)
        val normal = Vector3d(up).sub(Vector3d(tan).mul(up.dot(tan))).normalize()
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(start, Vector3d(tan), Vector3d(normal), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(end, Vector3d(tan), Vector3d(normal), dist, Curve.SegmentType.LINE_END),
        ))
    }
}
