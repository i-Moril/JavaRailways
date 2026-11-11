package work.art1st.javarailways.simpleato.api.ato

import work.art1st.javarailways.core.physics.train.setControllerForTest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.util.AsyncTickTestSupport
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
        safetyDistanceMeters = 2.0
    )

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `actuation publishes frame velocity and stall freeze clears it`(explicitCommit: Boolean) {
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
            val initial = ato.initialTrainFrame
            physics.start()
            fun step() {
                if (explicitCommit) {
                    AsyncTickTestSupport.run { ato.tick(50.0) }
                    ato.commit()
                } else physics.stepSimulation(50.0).await()
            }

            step()

            assertEquals(startX + 0.00125, train.headLocation.position.x(), 1.0e-9)
            assertEquals(0.05, train.publishedFrame.speedMetersPerSecond, 1.0e-12)
            assertSame(train.publishedFrame, ato.currentTrainFrame)
            assertEquals(train.publishedFrame.head, ato.currentTrainFrame.head)
            assertEquals(train.publishedFrame.tail, ato.currentTrainFrame.tail)
            assertTrue(ato.currentTrainFrame.bodyTraversal.isNotEmpty())
            val moving = train.publishedFrame
            assertFrameVelocity(train, 0.05)

            ato.failValidation = true
            step()

            assertTrue(atoCoordinator.isStalled)
            assertEquals(0.0, train.publishedFrame.speedMetersPerSecond, 0.0)
            assertSame(train.publishedFrame, ato.currentTrainFrame)
            assertSame(initial, atoCoordinator.lastStall!!.initialTrain)
            assertSame(moving, atoCoordinator.lastStall!!.currentTrain)
            assertEquals(0.05, moving.speedMetersPerSecond)
            val json = AtoStallReportJson.encode(atoCoordinator.lastStall!!)
            assertTrue(json.contains("\"revision\": 2"))
            assertTrue(json.contains("\"revision\": 0"))
            assertTrue(!json.contains("bodyTraversal") && !atoCoordinator.lastStall!!.format().contains("RenderableFrame"))
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
