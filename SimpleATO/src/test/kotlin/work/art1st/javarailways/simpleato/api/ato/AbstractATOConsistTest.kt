package work.art1st.javarailways.simpleato.api.ato

import work.art1st.javarailways.basic.api.ato.CarDynamicsProfile
import work.art1st.javarailways.basic.api.ato.TrainDynamicsProfile
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.setControllerForTest
import work.art1st.javarailways.core.physics.train.onOwnerForTest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.ConfigurationDynamicsActuator
import work.art1st.javarailways.basic.api.ato.PreparedConfigurationDynamicsChange
import work.art1st.javarailways.core.api.control.ControllerConsistState
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
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
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class AbstractATOConsistTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeOwnedWorldFixtures() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestSimpleATOWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `split updates actuator and safety together only after all preparation succeeds`(failInitialization: Boolean) {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        run {
            run {
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
                        safetyDistanceMeters = 17.0
                    )
                    val actuator = HandleTrainActuator(train, TrainProfile, listOf(heavy, light), 10.0)
                    val controller = TestATO(train, actuator, safety)
                    train.setControllerForTest(controller)
                    actuator.step(0.0, TrainDynamics.commandForHandle(TrainProfile, TrainProfile.powerHandles.last()), 0.1)
                    val history = actuator.lastDynamicsResult
                    val firstDevice = actuator.dynamicsDeviceStateFor(first)
                    val secondDevice = actuator.dynamicsDeviceStateFor(second)
                    assertNotNull(firstDevice)
                    assertEquals("P1", actuator.selectedHandle)

                    if (failInitialization) train.registerExtensionData(object : Plugin {
                        override var identifier = 2
                    }, FailingExtension())
                    val future = train.splitBefore(second.id, "ato-separated")
                    val owner = Thread.currentThread()
                    ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                        override fun isMainThread(): Boolean = Thread.currentThread() === owner
                    }).use { world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() } }

                    if (failInitialization) {
                        assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
                        assertSame(safety, controller.currentSafety)
                        assertSame(light, actuator.dynamicsProfileFor(second))
                        assertSame(train, second.train)
                        assertEquals(secondDevice, actuator.dynamicsDeviceStateFor(second))
                    } else {
                        future.get(5, TimeUnit.SECONDS)
                        assertEquals(safety.copy(
                            serviceDecelerationMetersPerSecondSquared = 2.0,
                            reactionTimeSeconds = HandleTrainActuator.serviceBrakeReactionTimeSeconds(
                                TrainProfile, listOf(heavy), 10.0)
                        ), controller.currentSafety)
                        assertNull(actuator.dynamicsProfileFor(second))
                        assertNull(actuator.dynamicsDeviceStateFor(second))
                    }
                    assertSame(heavy, actuator.dynamicsProfileFor(first))
                    assertEquals(firstDevice, actuator.dynamicsDeviceStateFor(first))
                    assertSame(controller, train.controller)
                    assertSame(history, actuator.lastDynamicsResult)
                    assertEquals("P1", actuator.selectedHandle)
                }
            }
        }
    }

    @Test
    fun `configuration reload installs new dynamics and derived safety without replacing controller history`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        run {
            run {
                world.physicsCoordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                    val train = createTrainWithConfig("ato-reload", rail, world, listOf(10.0))
                    val oldCar = CarProfile(10_000.0, 10_000.0)
                    val nextCar = CarProfile(20_000.0, 40_000.0)
                    val actuator = HandleTrainActuator(train, TrainProfile, listOf(oldCar), 10.0)
                    val safety = AtoSafetyProfile(safetyDistanceMeters = 17.0)
                    val controller = TestATO(train, actuator, safety)
                    train.setControllerForTest(controller)
                    actuator.step(0.0, TrainDynamics.commandForHandle(TrainProfile, TrainProfile.powerHandles.last()), 0.1)
                    val history = actuator.lastDynamicsResult
                    val device = actuator.dynamicsDeviceStateFor(train.cars.single())
                    assertNotNull(device)
                    val nextProfile = object : TrainDynamicsProfile by TrainProfile { override val maxSpeed = 15.0 }
                    val updates = controller.prepareConfigurationChange(nextProfile, listOf(nextCar))
                    assertSame(safety, controller.currentSafety)
                    assertSame(oldCar, actuator.dynamicsProfileFor(train.cars.single()))
                    assertEquals(30.0, actuator.maximumSpeedMetersPerSecond)
                    world.onOwnerForTest { train.applyControllerConfiguration(controller, updates) }
                    assertSame(controller, train.controller)
                    assertSame(history, actuator.lastDynamicsResult)
                    assertEquals(device, actuator.dynamicsDeviceStateFor(train.cars.single()))
                    assertEquals("P1", actuator.selectedHandle)
                    assertEquals(15.0, actuator.maximumSpeedMetersPerSecond)
                    assertEquals(2.0, controller.currentSafety.serviceDecelerationMetersPerSecondSquared)
                    assertEquals(HandleTrainActuator.serviceBrakeReactionTimeSeconds(nextProfile, listOf(nextCar), 10.0),
                        controller.currentSafety.reactionTimeSeconds, 1.0e-12)
                    assertEquals(17.0, controller.currentSafety.safetyDistanceMeters)
                    assertSame(nextCar, actuator.dynamicsProfileFor(train.cars.single()))
                    val installedSafety = controller.currentSafety
                    val rejectedProfile = object : TrainDynamicsProfile by nextProfile { override val maxSpeed = 12.0 }
                    assertThrows(IllegalArgumentException::class.java) {
                        controller.prepareConfigurationChange(rejectedProfile, listOf(CarProfile(20_000.0, 0.0)))
                    }
                    assertSame(installedSafety, controller.currentSafety)
                    assertEquals(15.0, actuator.maximumSpeedMetersPerSecond)
                    assertEquals("P1", actuator.selectedHandle)
                    assertSame(nextCar, actuator.dynamicsProfileFor(train.cars.single()))
                    assertEquals(device, actuator.dynamicsDeviceStateFor(train.cars.single()))
                    assertSame(history, actuator.lastDynamicsResult)
                }
            }
        }
    }

    @Test
    fun `API configuration capability prepares without installing and failures preserve all live state`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
        val train = createTrainWithConfig("api-configuration", rail, world, listOf(10.0))
        val safety = AtoSafetyProfile(safetyDistanceMeters = 17.0)
        val actuator = ApiConfigurationActuator(KinematicTrainActuator(30.0, safety))
        val controller = TestATO(train, actuator, safety)
        train.setControllerForTest(controller)
        val nextProfile = object : TrainDynamicsProfile by TrainProfile { override val maxSpeed = 15.0 }
        val nextCars = listOf(CarProfile(20_000.0, 40_000.0))

        val updates = controller.prepareConfigurationChange(nextProfile, nextCars)
        assertSame(safety, controller.currentSafety)
        assertEquals(30.0, actuator.maximumSpeedMetersPerSecond)
        assertEquals(1, actuator.preparedCount)
        world.onOwnerForTest { train.applyControllerConfiguration(controller, updates) }
        assertEquals(15.0, actuator.maximumSpeedMetersPerSecond)
        assertEquals(safety.copy(serviceDecelerationMetersPerSecondSquared = 2.0, reactionTimeSeconds = 0.4),
            controller.currentSafety)
        val installedSafety = controller.currentSafety
        val rejectedProfile = object : TrainDynamicsProfile by TrainProfile { override val maxSpeed = 12.0 }

        actuator.failAfterPreparation = true
        assertEquals("device derivation failed", assertThrows(IllegalStateException::class.java) {
            controller.prepareConfigurationChange(rejectedProfile, nextCars)
        }.message)
        assertEquals(2, actuator.preparedCount)
        assertSame(installedSafety, controller.currentSafety)
        assertEquals(15.0, actuator.maximumSpeedMetersPerSecond)

        actuator.failAfterPreparation = false
        actuator.serviceDeceleration = 0.0
        assertThrows(IllegalArgumentException::class.java) {
            controller.prepareConfigurationChange(rejectedProfile, nextCars)
        }
        assertEquals(3, actuator.preparedCount)
        assertSame(installedSafety, controller.currentSafety)
        assertEquals(15.0, actuator.maximumSpeedMetersPerSecond)
        assertSame(controller, train.controller)
    }

    @Test
    fun `actuator without optional capabilities retains empty consist updates and configuration rejection`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
        val train = createTrainWithConfig("optional-capabilities", rail, world, listOf(10.0))
        val safety = AtoSafetyProfile()
        val actuator = KinematicTrainActuator(30.0, safety)
        val controller = TestATO(train, actuator, safety)

        assertTrue(controller.prepareConsistChange(train.cars, null).isEmpty())
        assertEquals("ATO actuator does not support configuration replacement",
            assertThrows(IllegalStateException::class.java) {
                controller.prepareConfigurationChange(TrainProfile, listOf(CarProfile(20_000.0, 40_000.0)))
            }.message)
        assertSame(safety, controller.currentSafety)
        assertEquals(30.0, actuator.maximumSpeedMetersPerSecond)
    }

    /** Exercises only the API preparation seam; physical response remains the delegated test fixture. */
    private class ApiConfigurationActuator(delegate: TrainActuator) :
        TrainActuator by delegate, ConfigurationDynamicsActuator {
        private val maximumSpeed = ControllerConsistState(delegate.maximumSpeedMetersPerSecond)
        override val maximumSpeedMetersPerSecond: Double get() = maximumSpeed.value
        var serviceDeceleration = 2.0
        var failAfterPreparation = false
        var preparedCount = 0
            private set

        override fun prepareConfigurationChange(trainProfile: TrainDynamicsProfile,
            carProfiles: List<CarDynamicsProfile>): PreparedConfigurationDynamicsChange {
            val update = maximumSpeed.prepare(trainProfile.maxSpeed)
            preparedCount++
            if (failAfterPreparation) error("device derivation failed")
            return PreparedConfigurationDynamicsChange(listOf(update), serviceDeceleration, 0.4)
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
        override fun prepareLink(masterTrain: Train, removedTrain: Train, counterpart: TrainExtension?): TrainExtension = error("not used")
    }

    private object TrainProfile : TrainDynamicsProfile {
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

    private class CarProfile(override val mass: Double, override val brakeMaxForce: Double) : CarDynamicsProfile {
        override val effectiveMass = mass
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override fun motorForceAt(speed: Double) = 10_000.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }
}
