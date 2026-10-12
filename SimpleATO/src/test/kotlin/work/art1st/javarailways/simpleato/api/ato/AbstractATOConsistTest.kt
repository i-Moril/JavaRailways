package work.art1st.javarailways.simpleato.api.ato

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainExtension
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.api.util.plugin.Plugin
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class AbstractATOConsistTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `split updates actuator and safety together only after all preparation succeeds`(failInitialization: Boolean) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                    val train = createTrainWithConfig("ato-consist", rail, world, listOf(10.0, 10.0))
                    val first = train.cars[0]
                    val second = train.cars[1]
                    val heavy = CarProfile(20_000.0, 40_000.0)
                    val light = CarProfile(10_000.0, 10_000.0)
                    val safety = AtoSafetyProfile(
                        accelerationMetersPerSecondSquared = 3.0,
                        serviceDecelerationMetersPerSecondSquared = 5.0 / 3.0,
                        reactionTimeSeconds = 7.0,
                        safetyDistanceMeters = 17.0,
                        stopToleranceMeters = 0.07
                    )
                    val actuator = HandleTrainActuator(train, TrainProfile, listOf(heavy, light), 10.0)
                    val controller = TestATO(train, actuator, safety)
                    train.setController(controller)
                    actuator.step(0.0, ControlDemand(30.0), 0.1)
                    val history = actuator.lastDynamicsResult
                    assertEquals("P1", actuator.selectedHandle)

                    if (failInitialization) train.registerExtensionData(object : Plugin {
                        override var identifier = 2
                    }, FailingExtension())
                    val future = train.splitBefore(second.id, "ato-separated")
                    val owner = Thread.currentThread()
                    ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                        override fun isMainThread(): Boolean = Thread.currentThread() === owner
                    }).use { world.tickManager.postTick() }

                    if (failInitialization) {
                        assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
                        assertSame(safety, controller.currentSafety)
                        assertSame(light, actuator.dynamicsProfileFor(second))
                        assertSame(train, second.train)
                    } else {
                        future.get(5, TimeUnit.SECONDS)
                        assertEquals(safety.copy(
                            serviceDecelerationMetersPerSecondSquared = 2.0,
                            reactionTimeSeconds = 1.5
                        ), controller.currentSafety)
                        assertNull(actuator.dynamicsProfileFor(second))
                    }
                    assertSame(heavy, actuator.dynamicsProfileFor(first))
                    assertSame(controller, train.controller)
                    assertSame(history, actuator.lastDynamicsResult)
                    assertEquals("P1", actuator.selectedHandle)
                }
            }
        }
    }

    private class TestATO(train: Train, actuator: TrainActuator, safety: AtoSafetyProfile) :
        AbstractATO(train, train.world, actuator, safety, AtoWorldCoordinator("ato-consist", reportSink = {})) {
        val currentSafety: AtoSafetyProfile get() = safetyProfile
        override fun plan(deltaTimeSeconds: Double) = ControlDemand(0.0)
    }

    private class FailingExtension(private val fail: Boolean = false) : TrainExtension {
        override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
            if (fail) error("new host initialization failure")
        }
        override fun save(): Pair<String, SerializableExtensionData>? = null
        override fun splitBefore(carId: String, newId: String): TrainExtension = FailingExtension(true)
        override fun linkWith(otherTrain: Train) = Unit
    }

    private object TrainProfile : TrainDynamics.TrainProfile {
        override val maxSpeed = 30.0
        override val powerHandles = listOf("P1")
        override val brakeHandles = listOf("B1", "EB")
        override val brakeApplyTime = 0.3
        override val brakeReleaseTime = 0.0
        override val adhesionCoefficient = 1.0
        override val tractionJerk = 100.0
        override val brakeJerk = 2.0
        override fun normalizedPowerScale(handle: String) = if (handle == "P1") 1.0 else 0.0
        override fun normalizedBrakeScale(handle: String) = if (handle in brakeHandles) 1.0 else 0.0
        override fun resistanceForceAt(speed: Double) = 0.0
    }

    private class CarProfile(override val mass: Double, override val brakeMaxForce: Double) : TrainDynamics.CarProfile {
        override val effectiveMass = mass
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override fun motorForceAt(speed: Double) = 10_000.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }
}
