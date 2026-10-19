package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class TrainSlopeMotionTest {
    @Test
    fun `multi car train climbs straight slope`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try {
            createTestWorld(coordinator)
        } catch (failure: Throwable) {
            try {
                coordinator.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            run {
                coordinator.use {
                    val curve = createStraightSlope(length = 200.0, rise = 20.0)
                    val rail = world.createRailForTest(curve)
                    val train = createTrainWithConfig("slope", rail, world, listOf(10.0, 10.0, 10.0, 10.0), isLoop = false)
                    train.setControllerForTest(MockTrainController(train, 10.0))
                    val initial = train.publishedFrame
                    // The 1:10 grade gives unit tangent (10, 1, 0) / sqrt(101).
                    val expectedVelocity = Vector3d(100.0 / sqrt(101.0), 10.0 / sqrt(101.0), 0.0)
                    coordinator.start()
                    repeat(100) { index ->
                        val ticket = coordinator.stepSimulation(50.0)
                        ticket.completion.get(5, TimeUnit.SECONDS)
                        val frame = train.publishedFrame
                        val distance = 0.5 * (index + 1)
                        val expectedDelta = Vector3d(10.0 * distance / sqrt(101.0), distance / sqrt(101.0), 0.0)
                        assertEquals(ticket.epoch, frame.physicsEpoch)
                        assertFalse(frame.derailed, "tick $index derailment")
                        assertEquals(10.0, frame.speedMetersPerSecond, 1e-10, "tick $index speed")
                        // The fixture places four 10m cars with their head 50m along the rail.
                        assertEquals(50.0 + distance, frame.head.arcLengthInAbMeters, Curve.TOLERANCE, "tick $index head arc")
                        assertEquals(4, frame.cars.size)
                        frame.cars.forEachIndexed { carIndex, car ->
                            val start = initial.cars[carIndex]
                            assertEquals(2, car.bogies.size)
                            val states = listOf(car.state) + car.bogies
                            val initialStates = listOf(start.state) + start.bogies
                            states.zip(initialStates).forEachIndexed { stateIndex, (state, initialState) ->
                                val context = "tick $index car $carIndex state $stateIndex"
                                val actualDelta = state.positionVector().sub(initialState.positionVector())
                                assertEquals(0.0, actualDelta.distance(expectedDelta), Curve.TOLERANCE, "$context displacement")
                                assertEquals(0.0, state.velocityVector().distance(expectedVelocity), 1e-8, "$context velocity")
                            }
                        }
                    }
                }
            }
        }
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
