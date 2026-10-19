package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.createRailForTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 直线与曲线轨道上列车匀速运动测试。
 * - 使用 PrimeCurveGenerator 生成直线和多种半径的圆。
 * - Mock TrainController 恒定速度。
 * - 不同长度车厢组合，跑若干循环，比较速度、位置与理论值。
 */
class TrainUniformMotionTest {

    private val speedTolerance = 1e-10
    private val distanceTolerance = Curve.TOLERANCE * 2
    private val positionTolerance = 5e-3
    private val tickMs = 50.0
    private val tickSeconds = tickMs * 0.001

    @Test
    fun `straight track - constant speed and distance`() {
        val length = 500.0
        val segments = PrimeCurveGenerator.buildStraight(length)
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(coordinator)
        try {
            val rail = createStraightRailFromSegments(world, segments)
            val constantSpeed = 20.0 // m/s
            val train = createTrainWithConfig("t1", rail, world, listOf(10.0, 10.0), isLoop = false)
            val controller = MockTrainController(train, constantSpeed)
            train.setControllerForTest(controller)
            val startHeadX = train.headLocation.position.x()
            val startTailX = train.tailLocation.position.x()
            val startCarXs = train.cars.map { it.position.x() }
            coordinator.start()

            repeat(100) { index ->
                val ticket = coordinator.stepSimulation(tickMs)
                val metrics = ticket.completion.get(5, TimeUnit.SECONDS)
                val expectedDistance = constantSpeed * (index + 1) * tickSeconds
                val frame = train.publishedFrame

                assertEquals(ticket.epoch, metrics.epoch)
                assertEquals(ticket.epoch, frame.physicsEpoch)
                assertEquals(constantSpeed, controller.speed, speedTolerance, "tick $index controller speed")
                assertEquals(constantSpeed, train.speed, speedTolerance, "tick $index train speed")
                assertEquals(constantSpeed, frame.speedMetersPerSecond, speedTolerance)
                assertEquals(startHeadX + expectedDistance, train.headLocation.position.x(), distanceTolerance, "tick $index head")
                assertEquals(startTailX + expectedDistance, train.tailLocation.position.x(), distanceTolerance, "tick $index tail")
                assertEquals(RailPositionSnapshot.capture(train.headLocation, ViewDirection.AB), frame.head)
                assertEquals(RailPositionSnapshot.capture(train.tailLocation, ViewDirection.AB), frame.tail)
                assertEquals(train.cars.size, frame.cars.size)
                train.cars.forEachIndexed { carIndex, car ->
                    assertEquals(startCarXs[carIndex] + expectedDistance, car.position.x(), distanceTolerance, "tick $index car $carIndex")
                    assertEquals(car.id, frame.cars[carIndex].carId)
                    assertEquals(startCarXs[carIndex] + expectedDistance, frame.cars[carIndex].state.positionX, distanceTolerance, "tick $index frame car $carIndex")
                    assertFrameVelocity(frame.cars[carIndex].state, constantSpeed, 0.0, 0.0, "tick $index car $carIndex")
                    frame.cars[carIndex].bogies.forEachIndexed { bogieIndex, bogie ->
                        assertFrameVelocity(bogie, constantSpeed, 0.0, 0.0, "tick $index car $carIndex bogie $bogieIndex")
                    }
                }
                assertFalse(train.isDerailed)
            }
        } finally {
            coordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `published velocity follows reversal and clears when stopped`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(coordinator)
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
            val train = createTrainWithConfig("velocity-direction", rail, world, listOf(10.0, 10.0), isLoop = false)
            val controller = MutableMotionController(train, 10.0)
            train.setControllerForTest(controller)
            coordinator.start()

            val startX = train.headLocation.position.x()
            coordinator.stepSimulation(tickMs).completion.get(5, TimeUnit.SECONDS)
            assertEquals(startX + 0.5, train.headLocation.position.x(), distanceTolerance)
            assertPublishedVelocity(train, 10.0)

            controller.requestedDistance = 0.0
            val instantaneousX = train.headLocation.position.x()
            val instantaneousCarPositions = train.publishedFrame.cars.map { car ->
                car.state.positionVector() to car.bogies.map(RenderableFrameSnapshot::positionVector)
            }
            coordinator.stepSimulation(tickMs).completion.get(5, TimeUnit.SECONDS)
            assertEquals(instantaneousX, train.headLocation.position.x(), distanceTolerance)
            assertPublishedVelocity(train, 10.0)
            train.publishedFrame.cars.zip(instantaneousCarPositions).forEach { (car, expected) ->
                assertVectorEquals(expected.first, car.state.positionVector(), distanceTolerance, "motion-only car geometry")
                car.bogies.zip(expected.second).forEach { (bogie, expectedPosition) ->
                    assertVectorEquals(expectedPosition, bogie.positionVector(), distanceTolerance, "motion-only bogie geometry")
                }
            }

            controller.requestedDistance = null
            controller.requestedSpeed = 0.0
            val stoppedX = train.headLocation.position.x()
            coordinator.stepSimulation(tickMs).completion.get(5, TimeUnit.SECONDS)
            assertEquals(stoppedX, train.headLocation.position.x(), distanceTolerance)
            assertPublishedVelocity(train, 0.0)

            val reversed = controller.reverse()
            IsolatedSimulationThreadMarker.run {
                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
            }
            reversed.get(5, TimeUnit.SECONDS)
            assertTrue(train.publishedFrame.headTraversal.isEmpty())
            assertPublishedVelocity(train, 0.0)

            controller.requestedSpeed = 10.0
            val reversedHeadX = train.headLocation.position.x()
            coordinator.stepSimulation(tickMs).completion.get(5, TimeUnit.SECONDS)
            assertEquals(reversedHeadX - 0.5, train.headLocation.position.x(), distanceTolerance)
            assertPublishedVelocity(train, -10.0)

            train.setControllerForTest(null)
            coordinator.stepSimulation(tickMs).completion.get(5, TimeUnit.SECONDS)
            assertPublishedVelocity(train, 0.0)
        } finally {
            coordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `curved frame velocity follows bogie tangents and rigid body rotation`() {
        val radius = 30.0
        val speed = 10.0
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(coordinator)
        try {
            val rail = world.createRailForTest(
                PrimeCurveGenerator.buildCircle(radius, segmentCount = 1, step = 0.02).single()
            )
            val train = createTrainWithConfig("curved-velocity", rail, world, listOf(10.0))
            train.setControllerForTest(MockTrainController(train, speed))
            coordinator.start()

            coordinator.stepSimulation(tickMs).completion.get(5, TimeUnit.SECONDS)

            val car = train.cars.first()
            val frame = train.publishedFrame.cars.first()
            val headFrame = frame.bogies.first()
            val tailFrame = frame.bogies.last()
            val expectedHeadVelocity = Vector3d(car.headBogie.location.tangent).mul(speed)
            assertFrameVelocity(
                headFrame,
                expectedHeadVelocity.x(),
                expectedHeadVelocity.y(),
                expectedHeadVelocity.z(),
                "curved head bogie"
            )

            val headToTail = tailFrame.positionVector().sub(headFrame.positionVector())
            val velocityDifference = tailFrame.velocityVector().sub(headFrame.velocityVector())
            val omega = Vector3d(car.motionState.angularVelocity)
            val rigidBodyDifference = Vector3d(omega).cross(headToTail)
            assertVectorEquals(velocityDifference, rigidBodyDifference, 1e-7, "omega cross chord")
            assertEquals(speed / radius, omega.length(), 2e-3, "circle angular velocity")
            assertTrue(frame.state.velocityVector().length() > speed * 0.9)

            train.cars.zip(train.publishedFrame.cars).forEachIndexed { carIndex, (liveCar, carFrame) ->
                carFrame.bogies.zip(liveCar.bogies).forEachIndexed { bogieIndex, (bogieFrame, bogie) ->
                    val velocity = bogieFrame.velocityVector()
                    val tangent = Vector3d(bogie.location.tangent)
                    assertTrue(velocity.dot(tangent) > 0.0, "car $carIndex bogie $bogieIndex must move forward")
                    assertEquals(
                        0.0,
                        Vector3d(velocity).cross(tangent).length(),
                        1e-8,
                        "car $carIndex bogie $bogieIndex velocity must follow its rail tangent"
                    )
                }
            }
        } finally {
            coordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `explicit placement clears velocity from the previous controller epoch`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(coordinator)
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
            val train = createTrainWithConfig("placement-velocity", rail, world, listOf(10.0))
            train.setControllerForTest(MockTrainController(train, 10.0))
            coordinator.start()
            coordinator.stepSimulation(tickMs).completion.get(5, TimeUnit.SECONDS)
            assertPublishedVelocity(train, 10.0)

            train.world.onOwnerForTest {
                train.setLocation(requireNotNull(train.head.location.forward(5.0)))
                train.commitMaintenancePlacement()
            }

            assertPublishedVelocity(train, 0.0)
        } finally {
            coordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @ParameterizedTest(name = "circle R={0}, {1} cars of {2}m")
    @CsvSource("30.0, 2, 10.0", "60.0, 2, 10.0", "100.0, 2, 10.0", "200.0, 2, 10.0", "500.0, 2, 10.0", "100.0, 5, 20.0")
    fun `circle - two loops preserve speed position and rigid geometry`(radius: Double, carCount: Int, carLength: Double) {
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
                    // 0.1m samples keep interpolation error below the existing 5mm position tolerance.
                    val rails = PrimeCurveGenerator.buildCircle(radius, step = 0.1).map(world::createRailForTest)
                    rails.indices.forEach { index ->
                        IsolatedSimulationThreadMarker.run { RailView.connect(rails[index].viewAB, rails[(index + 1) % rails.size].viewBA) }
                    }
                    val railsById = rails.associateBy { it.id }
                    val speed = 15.0
                    val train = createTrainWithConfig("t-circle-$radius", rails.first(), world, List(carCount) { carLength }, isLoop = true)
                    train.setControllerForTest(MockTrainController(train, speed))
                    val circumference = 2 * PI * radius
                    val ticksPerLoop = (circumference / speed / tickSeconds).toInt().coerceAtLeast(1)
                    val numTicks = 2 * ticksPerLoop + 50
                    // The fixture places the head at total car length + 10m, independent of the observed frame.
                    val startArcLength = carCount * carLength + 10.0
                    var travelledDistance = 0.0
                    coordinator.start()

                    repeat(numTicks) { index ->
                        val ticket = coordinator.stepSimulation(tickMs)
                        ticket.completion.get(5, TimeUnit.SECONDS)
                        val frame = train.publishedFrame
                        assertEquals(ticket.epoch, frame.physicsEpoch)
                        assertEquals(speed, frame.speedMetersPerSecond, speedTolerance, "tick $index speed")
                        assertFalse(frame.derailed, "tick $index derailment")
                        val angle = (startArcLength + speed * (index + 1) * tickSeconds) / radius
                        val expectedHead = Vector3d(radius * cos(angle), 0.0, radius * sin(angle))
                        val headRail = requireNotNull(railsById[frame.head.railId])
                        val actualHead = Vector3d(headRail.curve.getPointAt(frame.head.arcLengthInAbMeters).position)
                        assertVectorEquals(expectedHead, actualHead, positionTolerance, "tick $index analytic head")
                        travelledDistance += frame.headTraversal.sumOf { segment ->
                            assertEquals(ViewDirection.AB, segment.facing)
                            segment.endArcLengthInAbMeters - segment.startArcLengthInAbMeters
                        }
                        frame.cars.forEachIndexed { carIndex, car ->
                            val head = car.bogies.first().positionVector()
                            val tail = car.bogies.last().positionVector()
                            assertEquals(carLength, head.distance(tail), Curve.TOLERANCE, "tick $index car $carIndex axle distance")
                            if (carIndex > 0) {
                                val precedingTail = frame.cars[carIndex - 1].bogies.last().positionVector()
                                assertEquals(0.0, precedingTail.distance(head), Curve.TOLERANCE, "tick $index car $carIndex zero-length coupler")
                            }
                        }
                    }
                    assertEquals(speed * numTicks * tickSeconds, travelledDistance, distanceTolerance, "published traversal distance across seams")
                    assertTrue(travelledDistance > 2 * circumference, "must complete two full loops")
                }
            }
        }
    }

    private fun assertPublishedVelocity(train: CoreTrain, expectedX: Double) {
        train.publishedFrame.cars.forEachIndexed { carIndex, car ->
            assertFrameVelocity(car.state, expectedX, 0.0, 0.0, "car $carIndex")
            car.bogies.forEachIndexed { bogieIndex, bogie ->
                assertFrameVelocity(bogie, expectedX, 0.0, 0.0, "car $carIndex bogie $bogieIndex")
            }
        }
    }

    private fun assertFrameVelocity(
        frame: RenderableFrameSnapshot,
        expectedX: Double,
        expectedY: Double,
        expectedZ: Double,
        message: String
    ) {
        assertEquals(expectedX, frame.velocityX, speedTolerance, "$message velocity x")
        assertEquals(expectedY, frame.velocityY, speedTolerance, "$message velocity y")
        assertEquals(expectedZ, frame.velocityZ, speedTolerance, "$message velocity z")
    }

    private fun assertVectorEquals(expected: Vector3d, actual: Vector3d, tolerance: Double, message: String) {
        assertEquals(expected.x(), actual.x(), tolerance, "$message x")
        assertEquals(expected.y(), actual.y(), tolerance, "$message y")
        assertEquals(expected.z(), actual.z(), tolerance, "$message z")
    }

    private class MutableMotionController(
        train: CoreTrain,
        var requestedSpeed: Double,
        var requestedDistance: Double? = null
    ) : TrainController(train) {
        override fun tick(deltaT: Double) {
            val distance = requestedDistance ?: requestedSpeed * deltaT * 0.001
            speed = requestedSpeed
            moveControlledTrain(distance, requestedSpeed)
        }
    }
}
