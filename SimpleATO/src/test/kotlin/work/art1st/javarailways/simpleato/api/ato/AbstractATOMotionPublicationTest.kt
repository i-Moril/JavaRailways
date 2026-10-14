package work.art1st.javarailways.simpleato.api.ato

import work.art1st.javarailways.core.physics.train.setControllerForTest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class AbstractATOMotionPublicationTest {
    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0,
        stopToleranceMeters = 0.25
    )

    @Test
    fun `actuation publishes frame velocity and stall freeze clears it`() {
        val physics = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(physics)
        val atoCoordinator = AtoWorldCoordinator("abstract-ato-motion", reportSink = {})
        TestSimpleATOWorldDataRegistry.register(
            world,
            BasicWorldData(safetyInterlock = atoCoordinator.safetyInterlock),
            atoCoordinator
        )
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
            val train = createTrainWithConfig("abstract-ato-motion", rail, world, listOf(10.0))
            val ato = TestATO(
                train,
                world,
                KinematicTrainActuator(10.0, profile),
                profile,
                atoCoordinator
            )
            train.setControllerForTest(ato)
            val startX = train.headLocation.position.x()
            physics.start()

            physics.stepSimulation(50.0).await()

            assertEquals(startX + 0.00125, train.headLocation.position.x(), 1.0e-9)
            assertEquals(0.05, train.publishedFrame.speedMetersPerSecond, 1.0e-12)
            assertFrameVelocity(train, 0.05)

            ato.failValidation = true
            physics.stepSimulation(50.0).await()

            assertTrue(atoCoordinator.isStalled)
            assertEquals(0.0, train.publishedFrame.speedMetersPerSecond, 0.0)
            assertFrameVelocity(train, 0.0)
        } finally {
            physics.close()
            (world.tickManager as? AutoCloseable)?.close()
            TestSimpleATOWorldDataRegistry.unregister(world)
        }
    }

    private fun assertFrameVelocity(
        train: work.art1st.javarailways.core.physics.train.CoreTrain,
        expectedX: Double
    ) {
        train.publishedFrame.cars.single().let { car ->
            assertEquals(expectedX, car.state.velocityX, 1.0e-12)
            car.bogies.forEach { bogie ->
                assertEquals(expectedX, bogie.velocityX, 1.0e-12)
            }
        }
    }

    private class TestATO(
        train: Train,
        world: World,
        actuator: KinematicTrainActuator,
        safetyProfile: AtoSafetyProfile,
        atoCoordinator: AtoWorldCoordinator
    ) : AbstractATO(train, world, actuator, safetyProfile, atoCoordinator) {
        var failValidation: Boolean = false

        override fun plan(deltaTimeSeconds: Double): ControlDemand =
            ControlDemand(actuator.maximumSpeedMetersPerSecond)

        override fun validateBeforeMovement() {
            if (failValidation) error("forced invariant failure")
        }
    }
}
