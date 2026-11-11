package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class TrainSlopeJointMotionTest {
    @Test
    fun `multi car train moves from level rail onto connected slope`() = withWorld { world ->
        val level = world.createRailForTest(line(Vector3d(0.0, 64.0, 0.0), Vector3d(50.0, 64.0, 0.0)))
        val slope = world.createRailForTest(line(Vector3d(50.0, 64.0, 0.0), Vector3d(80.0, 70.0, 0.0)))
        world.onOwnerForTest { CoreRail.connect(level, slope) }

        val train = createTrainWithConfig("slope-joint", level, world, listOf(10.0, 10.0), isLoop = false)
        train.setControllerForTest(MockTrainController(train, 10.0))

        val coordinator = world.physicsCoordinator
        coordinator.start()
        repeat(60) { index ->
            val ticket = coordinator.stepSimulation(50.0)
            ticket.completion.get(5, TimeUnit.SECONDS)
            val frame = train.publishedFrame
            // Two 10m cars start with their head at 30m; each real step advances 0.5m.
            val distance = 30.0 + 0.5 * (index + 1)
            val onSlope = distance > 50.0
            val arc = if (onSlope) distance - 50.0 else distance
            val x = if (onSlope) 50.0 + arc * 5.0 / sqrt(26.0) else distance
            val y = if (onSlope) 64.0 + arc / sqrt(26.0) else 64.0
            assertEquals(ticket.epoch, frame.physicsEpoch)
            assertFalse(frame.derailed, "derailed at tick $index")
            assertEquals(if (onSlope) slope.id else level.id, frame.head.railId, "tick $index rail")
            assertEquals(arc, frame.head.arcLengthInAbMeters, Curve.TOLERANCE, "tick $index arc")
            assertEquals(x, train.headLocation.position.x(), Curve.TOLERANCE, "tick $index x")
            assertEquals(y, train.headLocation.position.y(), Curve.TOLERANCE, "tick $index height")
        }

        assertTrue(train.head.location.rail == slope, "head should enter slope rail")
        assertTrue(train.head.location.position.y() > 64.0, "head should climb after entering slope")
    }

    @Test
    fun `long train moves from level rail onto connected slope`() = withWorld { world ->
        val level = world.createRailForTest(line(Vector3d(0.0, 64.0, 0.0), Vector3d(120.0, 64.0, 0.0)))
        val slope = world.createRailForTest(line(Vector3d(120.0, 64.0, 0.0), Vector3d(180.0, 70.0, 0.0)))
        world.onOwnerForTest { CoreRail.connect(level, slope) }

        val train = createTrainWithConfig("long-slope-joint", level, world, List(4) { 17.8 }, isLoop = false)
        train.setControllerForTest(MockTrainController(train, 10.0))

        val coordinator = world.physicsCoordinator
        coordinator.start()
        repeat(90) { index ->
            val ticket = coordinator.stepSimulation(50.0)
            ticket.completion.get(5, TimeUnit.SECONDS)
            val frame = train.publishedFrame
            // Four 17.8m cars start with their head at 81.2m; the slope has a 1:10 grade.
            val distance = 81.2 + 0.5 * (index + 1)
            val onSlope = distance > 120.0
            val arc = if (onSlope) distance - 120.0 else distance
            val x = if (onSlope) 120.0 + arc * 10.0 / sqrt(101.0) else distance
            val y = if (onSlope) 64.0 + arc / sqrt(101.0) else 64.0
            assertEquals(ticket.epoch, frame.physicsEpoch)
            assertFalse(frame.derailed, "derailed at tick $index")
            assertEquals(if (onSlope) slope.id else level.id, frame.head.railId, "tick $index rail")
            assertEquals(arc, frame.head.arcLengthInAbMeters, Curve.TOLERANCE, "tick $index arc")
            assertEquals(x, train.headLocation.position.x(), Curve.TOLERANCE, "tick $index x")
            assertEquals(y, train.headLocation.position.y(), Curve.TOLERANCE, "tick $index height")
        }

        assertTrue(train.head.location.rail == slope, "head should enter slope rail")
        assertTrue(train.head.location.position.y() > 64.0, "head should climb after entering slope")
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try {
            createTestWorld(coordinator)
        } catch (failure: Throwable) {
            try {
                coordinator.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        (world.tickManager as TickManagerImpl).use {
            run {
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
