package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.util.TickManagerImpl

class TrainSlopeJointMotionTest {
    @Test
    fun `multi car train moves from level rail onto connected slope`() = withWorld { world ->
        val level = world.createRailForTest(line(Vector3d(0.0, 64.0, 0.0), Vector3d(50.0, 64.0, 0.0)))
        val slope = world.createRailForTest(line(Vector3d(50.0, 64.0, 0.0), Vector3d(80.0, 70.0, 0.0)))
        CoreRail.connect(level, slope)

        val train = createTrainWithConfig("slope-joint", level, world, listOf(10.0, 10.0), isLoop = false)
        train.setControllerForTest(MockTrainController(train, world, 10.0))

        repeat(60) {
            train.controller!!.tick(50.0)
            train.commit()
            assertFalse(train.isDerailed, "derailed at tick $it")
        }

        assertTrue(train.head.location.rail == slope, "head should enter slope rail")
        assertTrue(train.head.location.position.y() > 64.0, "head should climb after entering slope")
    }

    @Test
    fun `long train moves from level rail onto connected slope`() = withWorld { world ->
        val level = world.createRailForTest(line(Vector3d(0.0, 64.0, 0.0), Vector3d(120.0, 64.0, 0.0)))
        val slope = world.createRailForTest(line(Vector3d(120.0, 64.0, 0.0), Vector3d(180.0, 70.0, 0.0)))
        CoreRail.connect(level, slope)

        val train = createTrainWithConfig("long-slope-joint", level, world, List(4) { 17.8 }, isLoop = false)
        train.setControllerForTest(MockTrainController(train, world, 10.0))

        repeat(90) {
            train.controller!!.tick(50.0)
            train.commit()
            assertFalse(train.isDerailed, "derailed at tick $it")
        }

        assertTrue(train.head.location.rail == slope, "head should enter slope rail")
        assertTrue(train.head.location.position.y() > 64.0, "head should climb after entering slope")
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as TickManagerImpl).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use { block(world) }
            }
        }
    }

    private fun line(start: Vector3d, end: Vector3d): PrimeCurve {
        val length = start.distance(end)
        val tangent = Vector3d(end).sub(start).normalize()
        val up = Vector3d(0.0, 1.0, 0.0)
        val normal = Vector3d(up).sub(Vector3d(tangent).mul(up.dot(tangent))).normalize()
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(start), tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(end), tangent, normal, length, Curve.SegmentType.LINE_END),
        ))
    }
}
