package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.DynamicsProfileSource
import work.art1st.javarailways.basic.api.ato.CarDynamicsProfile
import work.art1st.javarailways.basic.api.ato.TrainDynamicsProfile
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.basic.api.ato.VehicleControlState
import work.art1st.javarailways.basic.api.ato.VehicleControlCapabilities
import work.art1st.javarailways.basic.api.ato.VehicleCommandSupervisor
import org.junit.jupiter.api.Assertions.assertInstanceOf
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.setControllerForTest
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.Train
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import kotlin.math.sqrt

class TrainDynamicsTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeOwnedWorldFixtures() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestBasicWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }

    private val trainProfile = TestTrainProfile()
    private val carProfile = TestCarProfile()

    @Test
    fun `invalid car masses are rejected instead of repaired in dynamics and braking`() {
        for (mass in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            for (invalid in listOf(TestCarProfile(mass = mass), TestCarProfile(effectiveMass = mass))) {
                assertThrows(IllegalArgumentException::class.java) {
                    TrainDynamics.step(TrainDynamics.State(), trainProfile,
                        listOf(TrainDynamics.CarInput(invalid)), "P2", 10.0, 1.0)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    TrainDynamics.flatServiceDeceleration(trainProfile, listOf(invalid), 10.0)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    GradeAwareBrakingEnvelope.fromDynamics(trainProfile, listOf(invalid), 10.0,
                        listOf(BrakingGradeSegment(10.0, 0.0)))
                }
            }
        }
    }

    @Test
    fun `positive effective mass below one kilogram is used exactly`() {
        val light = TestCarProfile(mass = 0.5, motorForce = 1.0, brakeMaxForce = 1.0)
        val result = TrainDynamics.step(TrainDynamics.State(), trainProfile,
            listOf(TrainDynamics.CarInput(light)), "P2", 10.0, 0.1)
        assertEquals(2.0, result.state.acceleration, 1.0e-12)
        assertEquals(2.0, TrainDynamics.flatServiceDeceleration(trainProfile, listOf(light), 10.0), 1.0e-12)
        val envelope = GradeAwareBrakingEnvelope.fromDynamics(trainProfile, listOf(light), 10.0,
            listOf(BrakingGradeSegment(10.0, 0.0)))
        assertEquals(kotlin.math.sqrt(40.0), envelope.permittedSpeedMetersPerSecond(0.0, 10.0), 1.0e-12)
    }

    @Test
    fun `manual direction requests commit FIFO and failures retain effective display direction`() {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            val world = createTestWorld(coordinator)
            TestBasicWorldDataRegistry.register(world, BasicWorldData())
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                val train = createTrainWithConfig("manual-direction", rail, world, listOf(10.0))
                val controller = RealisticManualController(
                    train, trainProfile, listOf(carProfile), 9.80665,
                    initialSpeedMetersPerSecond = 10.0
                )
                train.setControllerForTest(controller)
                world.onOwnerForTest { controller.applyDirection(TrainDriveDirection.FORWARD) }
                coordinator.start()
                coordinator.stepSimulation(50.0).await()
                val displaySpeed = controller.displaySpeed
                assertTrue(displaySpeed > 0.0)
                val observed = mutableListOf<Pair<TrainDriveDirection, Boolean>>()
                val reverse = controller.setDirection(TrainDriveDirection.REVERSE)
                reverse.thenRun { observed += controller.direction to train.isReversed }
                val neutral = controller.setDirection(TrainDriveDirection.NEUTRAL)
                neutral.thenRun { observed += controller.direction to train.isReversed }
                val forward = controller.setDirection(TrainDriveDirection.FORWARD)
                forward.thenRun { observed += controller.direction to train.isReversed }
                assertEquals(TrainDriveDirection.FORWARD, controller.direction)
                assertEquals(displaySpeed, controller.displaySpeed)
                world.onOwnerForTest { coordinator.drainBetweenFramesOperations() }
                listOf(reverse, neutral, forward).forEach { it.get(5, TimeUnit.SECONDS) }
                assertEquals(listOf(
                    TrainDriveDirection.REVERSE to true,
                    TrainDriveDirection.NEUTRAL to true,
                    TrainDriveDirection.FORWARD to false
                ), observed)
                assertEquals(displaySpeed, controller.displaySpeed)

                val rejected = controller.setDirection(TrainDriveDirection.REVERSE)
                train.setControllerForTest(null)
                val beforeRejected = train.publishedFrame
                world.onOwnerForTest { coordinator.drainBetweenFramesOperations() }
                assertThrows(ExecutionException::class.java) { rejected.get(5, TimeUnit.SECONDS) }
                assertSame(beforeRejected, train.publishedFrame)
                assertEquals(TrainDriveDirection.FORWARD, controller.direction)
                assertEquals(displaySpeed, controller.displaySpeed)
                coordinator.beginShutdown()
                assertTrue(controller.setDirection(TrainDriveDirection.REVERSE).isCompletedExceptionally)
                assertEquals(TrainDriveDirection.FORWARD, controller.direction)
            } finally {
                coordinator.close()
                TestBasicWorldDataRegistry.unregister(world)
                (world.tickManager as AutoCloseable).close()
            }
        }
    }

    @Test
    fun `prepared union uses counterpart exact bindings before resolving unknown cars`() = withDynamicsTrain { train ->
        val first = train.cars[0]
        val second = train.cars[1]
        val incoming = ObservedCar(first, 0.0)
        val own = TestCarProfile(mass = 20_000.0)
        val other = TestCarProfile(mass = 30_000.0)
        val resolved = TestCarProfile(mass = 40_000.0)
        var resolutions = 0
        val executor = TrainDynamicsExecutor(train, trainProfile, listOf(own, own), 10.0,
            profileResolver = { car -> assertSame(incoming, car); resolutions++; resolved })
        val source = object : DynamicsProfileSource {
            override fun dynamicsProfileFor(car: Car): CarDynamicsProfile? = if (car === incoming) other else null
        }

        val union = executor.prepareConsistChange(listOf(incoming, second, first), source)
        assertEquals(listOf(other, own, own), union.carProfiles)
        assertEquals(0, resolutions)
        assertEquals(listOf(own, own), executor.carProfiles, "prepare must not install")
        assertEquals(listOf(resolved, own), executor.prepareConsistChange(listOf(incoming, first), null).carProfiles)
        assertEquals(1, resolutions)
        assertEquals(listOf(own, own), executor.carProfiles)
    }

    @Test
    fun `manual split retains controller handle and partitions exact car bindings`() = withDynamicsTrain { train ->
        val original = train.original as work.art1st.javarailways.core.physics.train.CoreTrain
        val first = original.cars[0]
        val second = original.cars[1]
        val heavy = TestCarProfile(mass = 20_000.0)
        val light = TestCarProfile(mass = 10_000.0)
        val controller = RealisticManualController(original, trainProfile, listOf(heavy, light), 10.0)
        controller.setHandle("P2")
        val firstDevice=controller.dynamicsDeviceStateFor(first)
        val secondDevice=controller.dynamicsDeviceStateFor(second)
        assertTrue(requireNotNull(firstDevice).pressureKpa>0.0)
        original.setControllerForTest(controller)
        val future = original.splitBefore(second.id, "manual-subset")
        original.world.onOwnerForTest { original.world.physicsCoordinator.drainBetweenFramesOperations() }
        val separated = future.get(5, TimeUnit.SECONDS)

        assertSame(controller, original.controller)
        assertEquals("P2", controller.handle)
        assertSame(heavy, controller.dynamicsProfileFor(first))
        assertEquals(firstDevice,controller.dynamicsDeviceStateFor(first))
        assertEquals(null,controller.dynamicsDeviceStateFor(second))
        assertTrue(requireNotNull(secondDevice).pressureKpa>0.0)
        assertEquals(null, controller.dynamicsProfileFor(second))
        assertSame(separated, second.train)
    }

    @Test
    fun `controllerless real split retains partial devices for new manual and automatic owners`() = withDynamicsTrain { fixture ->
        val train = fixture.original as work.art1st.javarailways.core.physics.train.CoreTrain
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(train.world, data)
        val profile = object : TrainDynamicsProfile by trainProfile {
            override val brakeApplyTime = 1.2
            override val brakeReleaseTime = 1.8
        }
        val car = object : CarDynamicsProfile by carProfile {
            override val equipment = TrainDynamics.Equipment()
        }
        val controller = RealisticManualController(train, profile, listOf(car, car), 10.0)
        train.setControllerForTest(controller)
        train.world.onOwnerForTest { controller.applyDirection(TrainDriveDirection.FORWARD) }
        controller.setHandle("P2")
        repeat(8) { controller.tick(50.0) }
        val separatedCar = train.cars.last()
        val actual = requireNotNull(controller.dynamicsDeviceStateFor(separatedCar))
        assertTrue(actual.pressureKpa > 0.0 && actual.pressureKpa < car.equipment.nominalCylinderPressureKpa)
        assertEquals(0.0, train.speed)
        val future = train.splitBefore(separatedCar.id, "controllerless-device-suffix")
        train.world.onOwnerForTest { train.world.physicsCoordinator.drainBetweenFramesOperations() }
        val separated = future.get(5, TimeUnit.SECONDS)
        assertEquals(null, data.trainDynamicsHistory.latest(separated))
        val manual = RealisticManualController(separated, profile, listOf(car), 10.0)
        val automatic = HandleTrainActuator(separated, profile, listOf(car), 10.0)
        assertEquals(actual, manual.dynamicsDeviceStateFor(separatedCar))
        assertEquals(actual, automatic.dynamicsDeviceStateFor(separatedCar))
        assertEquals(null, data.trainDynamicsHistory.latest(separated), "constructing candidates is read-only")
        val impostor = object : Car by separatedCar {}
        assertEquals(null, data.trainDynamicsHistory.deviceForCar(impostor), "same ID cannot inherit physical state")
    }

    @Test
    fun `executor keeps profiles with car instances through reorder and subset`() = withDynamicsTrain { train ->
        val heavyCar = ObservedCar(train.cars[0], 0.1)
        val lightCar = ObservedCar(train.cars[1], -0.05)
        train.currentCars = listOf(heavyCar, lightCar)
        val heavy = TestCarProfile(mass = 20_000.0, brakeMaxForce = 40_000.0)
        val light = TestCarProfile(mass = 10_000.0, brakeMaxForce = 5_000.0)
        val executor = TrainDynamicsExecutor(train, trainProfile, listOf(heavy, light), 10.0)
        val initial=executor.state

        assertEquals(15_000.0, executor.preview(10.0, "N", 0.1).gradeForce, 1.0e-9)
        train.currentCars = listOf(lightCar, heavyCar)
        assertSame(light, executor.carProfiles[0])
        assertSame(heavy, executor.carProfiles[1])
        assertEquals(15_000.0, executor.preview(10.0, "N", 0.1).gradeForce, 1.0e-9)

        // Location/operating direction is sampled again, not captured with the profile.
        lightCar.grade = 0.2
        train.currentCars = listOf(lightCar)
        assertEquals(listOf(light), executor.carProfiles)
        val subset = executor.preview(10.0, "B2", 0.1)
        assertEquals(20_000.0, subset.gradeForce, 1.0e-9)
        assertEquals(5_000.0, subset.brakeForce, 1.0e-9)
        assertSame(initial, executor.state)
    }

    @Test
    fun `executor rejects unknown instance with reused id without committing state`() = withDynamicsTrain { train ->
        val executor = TrainDynamicsExecutor(train, trainProfile, listOf(carProfile, carProfile), 10.0)
        executor.step(10.0, "P2", 0.1)
        val state = executor.state
        val lastResult = executor.lastResult
        val replacement = ObservedCar(train.cars[0], 0.1)
        assertEquals(train.cars[0].id, replacement.id)
        train.currentCars = listOf(replacement)

        assertThrows(IllegalStateException::class.java) { executor.preview(10.0, "B2", 0.1) }
        assertThrows(IllegalStateException::class.java) { executor.step(10.0, "B2", 0.1) }
        assertThrows(IllegalStateException::class.java) { executor.carProfiles }
        assertSame(state, executor.state)
        assertSame(lastResult, executor.lastResult)
        assertThrows(IllegalStateException::class.java) { executor.prepareConsistChange(train.currentCars, null) }
        assertSame(state, executor.state)
        assertSame(lastResult, executor.lastResult)
    }

    @Test
    fun `manual configuration replacement is prepared without mutation and rejects stale owner`() = withDynamicsTrain { wrapper ->
        val train = wrapper.original as work.art1st.javarailways.core.physics.train.CoreTrain
        val controller = RealisticManualController(train, trainProfile, listOf(carProfile, carProfile), 10.0)
        train.setControllerForTest(controller)
        controller.setHandle("P2")
        val replacement = TestCarProfile(mass = 20_000.0)
        val nextProfile = object : TrainDynamicsProfile by trainProfile {
            override val powerHandles = listOf("P2", "P3")
        }
        val originalDevices=train.cars.map { controller.dynamicsDeviceStateFor(it) }
        val updates = controller.prepareConfigurationChange(nextProfile, listOf(replacement, replacement))
        assertEquals(originalDevices,train.cars.map { controller.dynamicsDeviceStateFor(it) })
        assertSame(carProfile, controller.dynamicsProfileFor(train.cars.first()))
        assertTrue(!controller.setHandle("P3"))
        train.world.onOwnerForTest { train.applyControllerConfiguration(controller, updates) }
        assertSame(controller, train.controller)
        assertEquals(originalDevices,train.cars.map { controller.dynamicsDeviceStateFor(it) })
        assertSame(replacement, controller.dynamicsProfileFor(train.cars.first()))
        assertEquals("P2", controller.handle)
        assertTrue(controller.setHandle("P3"))
        assertThrows(IllegalArgumentException::class.java) {
            controller.prepareConfigurationChange(trainProfile, listOf(carProfile, carProfile))
        }
        train.setControllerForTest(null)
        train.world.onOwnerForTest {
            assertThrows(IllegalStateException::class.java) { train.applyControllerConfiguration(controller, updates) }
        }
        assertSame(replacement, controller.dynamicsProfileFor(train.cars.first()))
    }

    @Test
    fun `head uphill does not attribute uphill braking work to the downhill tail`() {
        withDynamicsTrain { train ->
            train.currentCars = listOf(ObservedCar(train.cars[0], 0.02), ObservedCar(train.cars[1], -0.02))
            val actuator = HandleTrainActuator(train, trainProfile, List(2) { carProfile }, 9.80665,
                initialSpeedMetersPerSecond = 5.0)
            val headUphill = actuator.brakingEnvelope(listOf(BrakingGradeSegment(100.0, 0.02)), 0.05, 0.2)
            val allDownhill = actuator.brakingEnvelope(listOf(BrakingGradeSegment(100.0, -0.02)), 0.05, 0.2)
            assertEquals(allDownhill.permittedSpeedMetersPerSecond(0.0, 80.0, 5.0),
                headUphill.permittedSpeedMetersPerSecond(0.0, 80.0, 5.0), 0.0)
        }
    }

    private fun withDynamicsTrain(action: (MutableConsist) -> Unit) {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        run {
            run {
                world.physicsCoordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                    action(MutableConsist(createTrainWithConfig("profile-identity", rail, world, listOf(10.0, 10.0))))
                }
            }
        }
    }

    /** Unit boundary: vary the current consist without exercising Core's structural transaction. */
    private class MutableConsist(val original: Train) : Train by original {
        var currentCars: List<Car> = original.cars
        override val cars: List<Car> get() = currentCars
    }

    private class ObservedCar(car: Car, var grade: Double) : Car by car {
        override val operatingDirection: Vector3dc get() = Vector3d(sqrt(1.0 - grade * grade), grade, 0.0)
    }

    @Test
    fun `configured gravity scales grade force without changing flat traction`() {
        val uphill = listOf(TrainDynamics.CarInput(carProfile, grade = 0.05))
        val flat = listOf(TrainDynamics.CarInput(carProfile, grade = 0.0))

        val lowGravityUphill = TrainDynamics.step(
            TrainDynamics.State(), trainProfile, uphill, "P2", 5.0, 1.0
        )
        val highGravityUphill = TrainDynamics.step(
            TrainDynamics.State(), trainProfile, uphill, "P2", 10.0, 1.0
        )
        val lowGravityFlat = TrainDynamics.step(
            TrainDynamics.State(), trainProfile, flat, "P2", 5.0, 1.0
        )
        val highGravityFlat = TrainDynamics.step(
            TrainDynamics.State(), trainProfile, flat, "P2", 10.0, 1.0
        )

        assertEquals(lowGravityUphill.gradeForce * 2.0, highGravityUphill.gradeForce, 1.0e-9)
        assertEquals(lowGravityFlat.tractionForce, highGravityFlat.tractionForce, 1.0e-9)
    }

    @Test
    fun `additional trailer mass lowers acceleration`() {
        val motor = TestCarProfile(mass = 40_000.0, motorForce = 80_000.0)
        val trailer = TestCarProfile(mass = 35_000.0, motorForce = 0.0)

        val motorOnly = TrainDynamics.step(
            TrainDynamics.State(), trainProfile, listOf(TrainDynamics.CarInput(motor)),
            "P2", 9.80665, 1.0
        )
        val withTrailer = TrainDynamics.step(
            TrainDynamics.State(), trainProfile,
            listOf(TrainDynamics.CarInput(motor), TrainDynamics.CarInput(trailer)),
            "P2", 9.80665, 1.0
        )

        assertTrue(motorOnly.state.acceleration > withTrailer.state.acceleration)
    }

    @Test
    fun `additional motor car raises acceleration despite extra mass`() {
        val motor = TestCarProfile(mass = 40_000.0, motorForce = 80_000.0)
        val trailer = TestCarProfile(mass = 35_000.0, motorForce = 0.0)

        val oneMotor = TrainDynamics.step(
            TrainDynamics.State(), trainProfile,
            listOf(TrainDynamics.CarInput(motor), TrainDynamics.CarInput(trailer)),
            "P2", 9.80665, 1.0
        )
        val twoMotors = TrainDynamics.step(
            TrainDynamics.State(), trainProfile,
            listOf(
                TrainDynamics.CarInput(motor),
                TrainDynamics.CarInput(motor),
                TrainDynamics.CarInput(trailer)
            ),
            "P2", 9.80665, 1.0
        )

        assertTrue(twoMotors.state.acceleration > oneMotor.state.acceleration)
    }

    @Test
    fun `brake force builds over configured apply time`() {
        val delayedBrakeProfile = TestTrainProfile(brakeApplyTime = 1.2)
        val first = TrainDynamics.step(
            TrainDynamics.State(speed = 10.0), delayedBrakeProfile,
            listOf(TrainDynamics.CarInput(carProfile)), "B2", 9.80665, 0.1
        )
        val later = TrainDynamics.step(
            first.state, delayedBrakeProfile,
            listOf(TrainDynamics.CarInput(carProfile)), "B2", 9.80665, 1.0
        )

        assertTrue(first.state.brakeScale in 0.0..1.0)
        assertTrue(later.state.brakeScale > first.state.brakeScale)
        assertTrue(later.brakeForce > first.brakeForce)
    }

    @Test
    fun `coasting loses speed uphill and gains speed downhill relative to level`() {
        fun acceleration(grade: Double) = TrainDynamics.step(
            TrainDynamics.State(speed = 10.0),
            trainProfile,
            listOf(TrainDynamics.CarInput(carProfile, grade)),
            "N",
            9.80665,
            1.0
        ).state.acceleration

        assertTrue(acceleration(0.05) < acceleration(0.0))
        assertTrue(acceleration(-0.05) > acceleration(0.0))
    }

    @Test
    fun `grade-aware envelope permits less speed downhill and more uphill`() {
        fun envelope(grade: Double) = GradeAwareBrakingEnvelope.fromDynamics(
            trainProfile,
            listOf(carProfile),
            9.80665,
            listOf(BrakingGradeSegment(100.0, grade))
        )

        val downhill = envelope(-0.05).permittedSpeedMetersPerSecond(0.0, 100.0)
        val level = envelope(0.0).permittedSpeedMetersPerSecond(0.0, 100.0)
        val uphill = envelope(0.05).permittedSpeedMetersPerSecond(0.0, 100.0)

        assertTrue(downhill < level)
        assertTrue(level < uphill)
    }

    @Test
    fun `grade-aware envelope fails closed before unbrakeable downhill`() {
        val weakBrakeCar = TestCarProfile(brakeMaxForce = 1_000.0)
        val envelope = GradeAwareBrakingEnvelope.fromDynamics(
            trainProfile,
            listOf(weakBrakeCar),
            9.80665,
            listOf(
                BrakingGradeSegment(10.0, 0.0),
                BrakingGradeSegment(10.0, -0.2)
            )
        )

        assertTrue(envelope.permittedSpeedMetersPerSecond(0.0, 5.0) > 0.0)
        assertEquals(0.0, envelope.permittedSpeedMetersPerSecond(0.0, 15.0), 0.0)
    }

    @Test
    fun `braking envelope includes positive acceleration unwind distance and gained speed`() {
        val profile = TestTrainProfile(brakeJerk = 2.0)
        val segments = listOf(BrakingGradeSegment(100.0, -0.02))
        fun envelope(acceleration: Double, selectedProfile: TestTrainProfile = profile) =
            GradeAwareBrakingEnvelope.fromDynamics(selectedProfile, listOf(carProfile), 9.80665,
                segments, acceleration)
        val speed = 4.0
        val reactionSeconds = 0.2
        val baseline = envelope(0.0)
        assertTrue(envelope(1.2).permittedSpeedMetersPerSecond(0.0,10.0,speed,reactionSeconds)<
            baseline.permittedSpeedMetersPerSecond(0.0,10.0,speed,reactionSeconds))
        assertEquals(baseline.permittedSpeedMetersPerSecond(0.0,10.0,speed,reactionSeconds),
            envelope(-1.2).permittedSpeedMetersPerSecond(0.0,10.0,speed,reactionSeconds),0.0)
        assertEquals(0.0,envelope(1.2).permittedSpeedMetersPerSecond(0.0,1.0,speed,reactionSeconds))
        val unbrakeable = GradeAwareBrakingEnvelope.fromDynamics(profile, listOf(carProfile), 9.80665,
            listOf(BrakingGradeSegment(10.0, 0.0), BrakingGradeSegment(100.0, -1.0)), 1.2)
        assertEquals(0.0, unbrakeable.permittedSpeedMetersPerSecond(0.0, 12.0, speed, reactionSeconds),
            "reserving unwind distance must not hide an unbrakeable segment inside the target range")
        val noJerk = TestTrainProfile(brakeJerk = 0.0)
        assertTrue(envelope(1.2,noJerk).permittedSpeedMetersPerSecond(0.0,10.0,speed,reactionSeconds)<=
            envelope(0.0,noJerk).permittedSpeedMetersPerSecond(0.0,10.0,speed,reactionSeconds))
    }

    @Test
    fun `service reaction covers equipment response and has no handle travel delay`() {
        val profile = TestTrainProfile(brakeApplyTime = 1.2, brakeJerk = 2.0)
        val reaction = HandleTrainActuator.serviceBrakeReactionTimeSeconds(
            profile,
            listOf(carProfile),
            9.80665,
            controlStepSeconds = 0.05,
            planningMarginSeconds = 0.1
        )
        val serviceDeceleration = TrainDynamics.flatServiceDeceleration(
            profile,
            listOf(carProfile),
            9.80665
        )

        val adhesionAcceleration = 9.80665 * profile.adhesionCoefficient
        val cutout = carProfile.mass * 9.80665 * profile.adhesionCoefficient /
            carProfile.equipment.tractionReleaseRateNewtonsPerSecond
        assertEquals(0.1 + 0.05 + cutout + 1.2 + (adhesionAcceleration + serviceDeceleration) / 2.0,
            reaction, 1e-12)
        val extraNotches = object : TrainDynamicsProfile by profile {
            override val powerHandles = profile.powerHandles + "P1"
        }
        assertEquals(reaction, HandleTrainActuator.serviceBrakeReactionTimeSeconds(extraNotches,
            listOf(carProfile), 9.80665, 0.05, 0.1))
        assertTrue(reaction.isFinite())
    }

    @Test
    fun `handle actuator executes explicitly selected notches and matches direct dynamics`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(200.0)
        )
        val train = createTrainWithConfig("handle-actuator", rail, world, listOf(10.0))
        val actuator = HandleTrainActuator(
            train,
            trainProfile,
            listOf(carProfile),
            9.80665
        )

        assertThrows(IllegalArgumentException::class.java) { actuator.brakingEnvelope(emptyList(), 0.05, 0.2) }
        val envelope = actuator.brakingEnvelope(listOf(BrakingGradeSegment(100.0, 0.0)), 0.05, 0.2)
        assertEquals(100.0, envelope.totalDistanceMeters)

        val first = actuator.step(0.0, TrainDynamics.commandForHandle(trainProfile, "P1"), 0.1)
        val expected = TrainDynamics.step(
            TrainDynamics.State(),
            trainProfile,
            listOf(TrainDynamics.CarInput(carProfile, train.cars.single().operatingDirection.y())),
            "P1",
            9.80665,
            0.1
        )

        assertEquals("P1", actuator.selectedHandle)
        assertEquals(expected.state.speed, first.speedMetersPerSecond, 1.0e-12)
        assertEquals(expected.distance, first.distanceMeters, 1.0e-12)

        actuator.step(first.speedMetersPerSecond, TrainDynamics.commandForHandle(trainProfile, "P2"), 0.1)
        assertEquals("P2", actuator.selectedHandle)
    }

    @Test
    fun `world safety interlock forces the manual controller to emergency brake`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(200.0)
        )
        val train = createTrainWithConfig("manual-interlock", rail, world, listOf(10.0))
        val basicData = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, basicData)
        val controller = RealisticManualController(
            train,
            trainProfile,
            listOf(carProfile),
            9.80665
        )
        train.setControllerForTest(controller)
        world.onOwnerForTest { controller.applyDirection(TrainDriveDirection.FORWARD) }
        controller.setHandle("P2")

        try {
            basicData.safetyInterlock.engage()
            controller.tick(50.0)

            assertEquals("EB", controller.handle)
        } finally {
            TestBasicWorldDataRegistry.unregister(world)
        }
    }

    @Test
    fun `manual controller publishes dynamics speed with the same epoch distance`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(coordinator)
        val basicData = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, basicData)
        try {
            val rail = createStraightRailFromSegments(
                world,
                PrimeCurveGenerator.buildStraight(200.0)
            )
            val train = createTrainWithConfig("manual-frame-motion", rail, world, listOf(10.0))
            val controller = RealisticManualController(
                train,
                trainProfile,
                listOf(carProfile),
                9.80665
            )
            train.setControllerForTest(controller)
            world.onOwnerForTest { controller.applyDirection(TrainDriveDirection.FORWARD) }
            controller.setHandle("P2")
            val startX = train.headLocation.position.x()
            coordinator.start()

            coordinator.stepSimulation(50.0).await()

            val expected = TrainDynamics.step(
                TrainDynamics.State(),
                trainProfile,
                listOf(TrainDynamics.CarInput(carProfile)),
                "P2",
                9.80665,
                0.05
            )
            assertEquals(startX + expected.distance, train.headLocation.position.x(), 1.0e-9)
            assertEquals(expected.state.speed, train.publishedFrame.speedMetersPerSecond, 1.0e-12)
            train.publishedFrame.cars.single().let { car ->
                assertEquals(expected.state.speed, car.state.velocityX, 1.0e-12)
                car.bogies.forEach { bogie ->
                    assertEquals(expected.state.speed, bogie.velocityX, 1.0e-12)
                }
            }
        } finally {
            coordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
            TestBasicWorldDataRegistry.unregister(world)
        }
    }

    private class TestTrainProfile(
        override val brakeApplyTime: Double = 0.0,
        override val brakeJerk: Double = 100.0
    ) : TrainDynamicsProfile {
        override val maxSpeed = 30.0
        override val powerHandles = listOf("P1", "P2")
        override val brakeHandles = listOf("B1", "B2", "EB")
        override val brakeReleaseTime = 0.0
        override val adhesionCoefficient = 1.0
        override val tractionJerk = 100.0
        override fun normalizedPowerScale(handle: String): Double = when (handle) {
            "P1" -> 0.5
            "P2" -> 1.0
            else -> 0.0
        }
        override fun normalizedBrakeScale(handle: String): Double = when (handle) {
            "B1" -> 0.5
            "B2" -> 1.0
            "EB" -> 1.25
            else -> 0.0
        }
        override fun resistanceForceAt(speed: Double) = 0.0
    }

    @Test
    fun `explicit commands retain real device release and pressure hold semantics`() = withDynamicsTrain { train ->
        val delayedProfile = object : TrainDynamicsProfile by trainProfile {
            override val brakeApplyTime = 1.2
            override val brakeReleaseTime = 1.8
            override val tractionJerk = 0.8
            override val brakeJerk = 0.8
        }
        val delayedCar = object : CarDynamicsProfile by carProfile {
            override val equipment = TrainDynamics.Equipment()
        }
        val actuator = HandleTrainActuator(train, delayedProfile, listOf(delayedCar, delayedCar), 9.80665)
        val initial = actuator.controlState(0.0).devices
        assertTrue(initial.all { it.pressureKpa > 300.0 })
        var speed = 0.0
        repeat(20) {
            speed = actuator.step(speed, VehicleControlCommand(tractionRatio = 1.0, handle = "P2"), 0.05).speedMetersPerSecond
            assertEquals(0.0, actuator.lastDynamicsResult.tractionForce)
        }
        repeat(200) { speed = actuator.step(speed, VehicleControlCommand(tractionRatio = 1.0, handle = "P2"), 0.05).speedMetersPerSecond }
        assertTrue(speed > 0.0)
        repeat(1000) { speed = actuator.step(speed, VehicleControlCommand(brakeRatio = 1.0, handle = "B2"), 0.05).speedMetersPerSecond }
        assertEquals(0.0, speed)
        assertTrue(actuator.controlState(speed).devices.all { it.pressureKpa > 300.0 })
        assertTrue(initial.all { it.pressureKpa > 300.0 }, "old device snapshots stay immutable")
    }

    @Test
    fun `explicit device source preserves prepared physical cars without controller lookup`() = withDynamicsTrain { train ->
        val profile = object : TrainDynamicsProfile by trainProfile {
            override val brakeApplyTime = 1.2
            override val brakeReleaseTime = 1.8
        }
        val car = object : CarDynamicsProfile by carProfile {
            override val equipment = TrainDynamics.Equipment()
        }
        val previous = TrainDynamicsExecutor(train, profile, listOf(car, car), 9.80665, 5.0)
        var speed = 5.0
        repeat(8) { speed = previous.step(speed, VehicleControlCommand(brakeRatio = 0.5), 0.05).state.speed }
        val replacement = TrainDynamicsExecutor(train, profile, listOf(car, car), 9.80665, speed,
            initialDeviceSource = previous)
        assertEquals(previous.state.devices, replacement.state.devices)
        assertTrue(replacement.state.devices[0].pressureKpa > 0.0)
        assertTrue(replacement.state.devices[0].pressureKpa < car.equipment.nominalCylinderPressureKpa)
    }

    @Test
    fun `real per-car devices follow exact identities through reordered controller handoff`() = withDynamicsTrain { train ->
        TestBasicWorldDataRegistry.register(train.world, BasicWorldData())
        val profile = object : TrainDynamicsProfile by trainProfile {
            override val brakeApplyTime = 1.0
            override val brakeReleaseTime = 1.5
            override val brakeJerk = 0.8
        }
        val firstCarProfile = object : CarDynamicsProfile by carProfile {
            override val electricBrakeMaxForce = 3000.0
            override val equipment = TrainDynamics.Equipment(nominalCylinderPressureKpa = 300.0,
                commandDelaySeconds = 0.1)
        }
        val secondCarProfile = object : CarDynamicsProfile by carProfile {
            override val electricBrakeMaxForce = 1000.0
            override val equipment = TrainDynamics.Equipment(nominalCylinderPressureKpa = 400.0,
                commandDelaySeconds = 0.1)
        }
        val originalCars = train.cars.toList()
        val first = TrainDynamicsExecutor(train, profile, listOf(firstCarProfile, secondCarProfile), 10.0, 5.0)
        var speed = 5.0
        repeat(8) { speed = first.step(speed, VehicleControlCommand(brakeRatio = 0.5), 0.05).state.speed }
        val before = first.state.devices
        assertTrue(before[0].pressureKpa > 0.0 && before[1].pressureKpa > 0.0)
        assertTrue(before[0].electricBrakeForceNewtons > before[1].electricBrakeForceNewtons)
        train.currentCars = originalCars.asReversed()
        val committed = first.step(speed, VehicleControlCommand(brakeRatio = 0.5), 0.05)
        val second = TrainDynamicsExecutor(train, profile, listOf(secondCarProfile, firstCarProfile), 10.0, committed.state.speed)
        originalCars.forEach { car -> assertEquals(first.dynamicsDeviceStateFor(car), second.dynamicsDeviceStateFor(car)) }
        assertEquals(first.state.devices, second.state.devices)
        assertEquals(before[0].previousBrakeRatio, second.dynamicsDeviceStateFor(originalCars[0])!!.previousBrakeRatio)
        val retained = second.state.devices.toList()
        second.preview(committed.state.speed, VehicleControlCommand.EMERGENCY_BRAKE, 0.05)
        assertEquals(retained, second.state.devices.toList(), "preview cannot mutate transferred physical devices")
        assertEquals(300.0, firstCarProfile.equipment.nominalCylinderPressureKpa)
    }

    @Test
    fun `numeric split controls share dynamics and emergency suppresses traction`() {
        val inputs = listOf(TrainDynamics.CarInput(carProfile))
        val initial = TrainDynamics.State(speed = 10.0)
        val split = VehicleControlCommand(tractionRatio = 0.5, brakeRatio = 0.25)
        val result = TrainDynamics.step(initial, trainProfile, inputs, split, 10.0, 0.1)
        assertEquals(0.0, result.tractionForce, "pneumatic interlock blocks traction while split brake is applied")
        assertEquals(5000.0, result.brakeForce)
        val released = TrainDynamics.step(result.state, trainProfile, inputs,
            VehicleControlCommand(tractionRatio = 0.5), 10.0, 0.1)
        assertEquals(5000.0, released.tractionForce)
        val emergency = TrainDynamics.step(initial, trainProfile, inputs,
            split.copy(emergencyBrake = true), 10.0, 0.1)
        assertEquals(0.0, emergency.tractionForce)
        assertEquals(25000.0, emergency.brakeForce)
        assertEquals(TrainDynamics.step(initial, trainProfile, inputs, "P1", 10.0, 0.1),
            TrainDynamics.step(initial, trainProfile, inputs,
                TrainDynamics.commandForHandle(trainProfile, "P1"), 10.0, 0.1))
    }

    @Test
    fun `numeric traction respects authored physical range including ratios above one`() {
        val profile = object : TrainDynamicsProfile by trainProfile {
            override fun normalizedPowerScale(handle: String) = if (handle == "P2") 1.5 else
                trainProfile.normalizedPowerScale(handle)
        }
        val inputs = listOf(TrainDynamics.CarInput(carProfile))
        val result = TrainDynamics.step(TrainDynamics.State(), profile, inputs,
            VehicleControlCommand(tractionRatio = 1.5), 10.0, 0.1)
        assertEquals(15000.0, result.tractionForce)
        val capability = VehicleControlCapabilityModel(profile, listOf(carProfile), 10.0)
            .snapshot(0.0, inputs)
        assertEquals(1.5, capability.performanceSamples.first().maximumTractionAccelerationMetersPerSecondSquared)
        assertThrows(IllegalArgumentException::class.java) {
            TrainDynamics.step(TrainDynamics.State(), profile, inputs,
                VehicleControlCommand(tractionRatio = 1.5001), 10.0, 0.1)
        }
    }

    @Test
    fun `proven motor force ceiling tightens bound while unknown curves retain adhesion fallback`() = withDynamicsTrain { train ->
        val known = HandleTrainActuator(train, trainProfile, listOf(carProfile, carProfile), 10.0)
        assertEquals(1.0, known.controlCapabilities(0.0).maximumTractionAccelerationMetersPerSecondSquared)
        val arbitrary = object : CarDynamicsProfile by carProfile {
            override val maximumMotorForceUpperBound: Double? get() = null
            override fun motorForceAt(speed: Double) = if (speed > 0.12 && speed < 0.13) 1000000.0 else 1.0
        }
        val unknown = HandleTrainActuator(train, trainProfile, listOf(arbitrary, arbitrary), 10.0)
        assertEquals(10.0, unknown.controlCapabilities(0.0).maximumTractionAccelerationMetersPerSecondSquared)
        assertTrue(unknown.controlCapabilities(0.125).handles.last().steadyAccelerationMetersPerSecondSquared > 1.0)
    }

    @Test
    fun `capability snapshots reuse performance table and preserve physical brake state`() = withDynamicsTrain { train ->
        val actuator = HandleTrainActuator(train, trainProfile, listOf(carProfile, carProfile), 10.0)
        val before = actuator.controlCapabilities(0.0)
        actuator.step(10.0, VehicleControlCommand(brakeRatio = 0.5), 0.1)
        val after = actuator.controlCapabilities(9.0)
        assertSame(before.configurationIdentity, after.configurationIdentity)
        assertSame(before.performanceSamples, after.performanceSamples)
        assertEquals(0.5, actuator.controlState(9.0).actualBrakeRatio)
        assertEquals(0.5, actuator.controlState(9.0).lastCommand.brakeRatio)
        assertEquals(0.0, before.performanceSamples.first().speedMetersPerSecond)
        assertEquals(trainProfile.maxSpeed, before.performanceSamples.last().speedMetersPerSecond)
    }

    @Test
    fun `world physical history survives replacement and unused controller preparation`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
        val train = createTrainWithConfig("physical-handoff", rail, world, listOf(10.0))
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val profile = TestTrainProfile(brakeApplyTime = 1.0, brakeJerk = 0.5)
        val original = TrainDynamicsExecutor(train, profile, listOf(carProfile), 10.0, 10.0)
        val command = VehicleControlCommand(brakeRatio = 1.0, handle = "B2")
        val result = original.step(10.0, command, 0.1)
        assertTrue(result.state.brakeScale > 0.0)
        assertTrue(result.state.brakeScale < 1.0)
        assertTrue(result.state.acceleration < 0.0)
        // An uninstalled candidate with a deliberately different initial speed cannot erase history.
        TrainDynamicsExecutor(train, profile, listOf(carProfile), 10.0, 0.0)
        val replacement = TrainDynamicsExecutor(train, profile, listOf(carProfile), 10.0, result.state.speed)
        assertEquals(original.state, replacement.state)
        assertEquals(command, replacement.lastCommand)
        val expected = TrainDynamics.step(result.state, profile,
            listOf(TrainDynamics.CarInput(carProfile, train.cars.single().operatingDirection.y())),
            VehicleControlCommand.COAST, 10.0, 0.1)
        assertEquals(expected, replacement.step(result.state.speed, VehicleControlCommand.COAST, 0.1))
        val history = data.trainDynamicsHistory.read(train, expected.state.speed)!!
        assertEquals(expected.state, history.state)
        // Same textual ID does not grant a different physical train access to the stored state.
        val differentIdentity = object : Train by train {}
        assertEquals(null, data.trainDynamicsHistory.read(differentIdentity, expected.state.speed))
        data.trainDynamicsHistory.remove(differentIdentity)
        assertEquals(history, data.trainDynamicsHistory.read(train, expected.state.speed))
        data.trainDynamicsHistory.remove(train)
        assertEquals(null, data.trainDynamicsHistory.read(train, expected.state.speed))
    }

    @Test
    fun `published formation stop clears residual acceleration while preserving actual brake buildup`() {
        PhysicsCoordinator(1, automaticFallback = false).use { physics ->
            val world = TestWorldFixture.create(physics).also(worldFixtures::add).world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
            val train = createTrainWithConfig("published-stop-handoff", rail, world, listOf(10.0))
            val data = BasicWorldData()
            TestBasicWorldDataRegistry.register(world, data)
            val profile = TestTrainProfile(brakeApplyTime = 1.0, brakeJerk = 0.5)
            val retained = TrainDynamicsExecutor(train, profile, listOf(carProfile), 10.0, 10.0)
            val physical = TrainDynamicsExecutor(train, profile, listOf(carProfile), 10.0, 10.0)
            val controller = object : work.art1st.javarailways.core.api.control.TrainController(train) {
                var publishExternalStop = false
                init { speed = 10.0 }
                override fun tick(deltaT: Double) {
                    if (publishExternalStop) {
                        // A controller-owned stop publication without another device integration,
                        // matching the history discontinuity produced by a structural stop.
                        speed = 0.0
                        moveControlledTrain(0.0, 0.0)
                    } else {
                        val step = physical.step(activeSpeed, VehicleControlCommand(brakeRatio = 1.0, handle = "B2"),
                            deltaT * 0.001)
                        speed = step.state.speed
                        moveControlledTrain(step.distance, step.state.speed)
                    }
                }
            }
            train.setControllerForTest(controller)
            physics.start()
            physics.stepSimulation(50.0).await()
            assertTrue(train.publishedFrame.speedMetersPerSecond > 0.0)
            val history = data.trainDynamicsHistory.latest(train)!!
            assertTrue(history.state.brakeScale > 0.0 && history.state.brakeScale < 1.0)
            world.onOwnerForTest { controller.publishExternalStop = true }
            physics.stepSimulation(50.0).await()
            assertEquals(0.0, train.publishedFrame.speedMetersPerSecond)
            val hold = world.onOwnerForTest {
                assertInstanceOf(work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Accepted::class.java,
                    world.restoreFormationMovementHold(train.id, train.cars.map { it.id })).hold
            }
            physics.stepSimulation(50.0).await()
            assertEquals(0.0, train.publishedFrame.speedMetersPerSecond)
            val stopped = retained.controlState(0.0)
            assertEquals(0.0, stopped.accelerationMetersPerSecondSquared)
            assertEquals(history.state.brakeScale, stopped.actualBrakeRatio)
            val replacement = RealisticManualController(train, profile, listOf(carProfile), 10.0, 0.0)
            assertEquals(0.0, replacement.acceleration)
            assertEquals(history.state.brakeScale, replacement.brakeScale)
            assertSame(history, data.trainDynamicsHistory.latest(train), "read-only stop adaptation must not publish fake force")
            world.onOwnerForTest { assertTrue(world.releaseFormationMovementHold(hold)) }
        }
    }

    @Test
    fun `retained original actuator resumes latest manual physical state rather than its old history`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
        val train = createTrainWithConfig("return-to-original-ato", rail, world, listOf(10.0))
        TestBasicWorldDataRegistry.register(world, BasicWorldData())
        val profile = TestTrainProfile(brakeApplyTime = 1.0, brakeJerk = 0.5)
        val originalAto = HandleTrainActuator(train, profile, listOf(carProfile), 10.0, 10.0)
        val aResult = originalAto.step(10.0, VehicleControlCommand(tractionRatio = 0.5, handle = "P1"), 0.1)
        val manualPhysics = TrainDynamicsExecutor(train, profile, listOf(carProfile), 10.0, aResult.speedMetersPerSecond)
        val manualBrake = VehicleControlCommand(brakeRatio = 1.0, handle = "B2")
        var bResult = manualPhysics.step(aResult.speedMetersPerSecond, manualBrake, 0.1)
        repeat(3) { bResult = manualPhysics.step(bResult.state.speed, manualBrake, 0.1) }
        val resumed = originalAto.controlState(bResult.state.speed)
        assertEquals(bResult.state.brakeScale, resumed.actualBrakeRatio)
        assertEquals(bResult.state.acceleration, resumed.accelerationMetersPerSecondSquared)
        assertEquals(manualBrake, resumed.lastCommand)
        val next = VehicleControlCommand.COAST
        val expected = TrainDynamics.step(bResult.state, profile,
            listOf(TrainDynamics.CarInput(carProfile, train.cars.single().operatingDirection.y())), next, 10.0, 0.1)
        val actual = originalAto.step(bResult.state.speed, next, 0.1)
        assertEquals(expected.state, originalAto.lastDynamicsResult.state)
        assertEquals(expected.distance, actual.distanceMeters)
        // Reusing B through step() directly also sees A's new publication without a prior state query.
        val expectedB = TrainDynamics.step(expected.state, profile,
            listOf(TrainDynamics.CarInput(carProfile, train.cars.single().operatingDirection.y())), manualBrake, 10.0, 0.1)
        assertEquals(expectedB, manualPhysics.step(expected.state.speed, manualBrake, 0.1))
    }

    @Test
    fun `prepared replacement refreshes latest device state on first active use`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
        val train = createTrainWithConfig("late-device-handoff", rail, world, listOf(10.0))
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val profile = TestTrainProfile(brakeApplyTime = 1.0, brakeJerk = 0.5)
        val previous = TrainDynamicsExecutor(train, profile, listOf(carProfile), 10.0, 10.0)
        val command = VehicleControlCommand(brakeRatio = 1.0, handle = "B2")
        val first = previous.step(10.0, command, 0.1)
        val prepared = TrainDynamicsExecutor(train, profile, listOf(carProfile), 10.0, first.state.speed)
        val preparedDirectStep = TrainDynamicsExecutor(train, profile, listOf(carProfile), 10.0, first.state.speed)
        var latest = first
        repeat(3) { latest = previous.step(latest.state.speed, command, 0.1) }
        val activeState = prepared.controlState(latest.state.speed)
        assertEquals(latest.state.acceleration, activeState.accelerationMetersPerSecondSquared)
        assertEquals(latest.state.brakeScale, activeState.actualBrakeRatio)
        assertEquals(command, activeState.lastCommand)
        val expected = TrainDynamics.step(latest.state, profile,
            listOf(TrainDynamics.CarInput(carProfile, train.cars.single().operatingDirection.y())),
            VehicleControlCommand.COAST, 10.0, 0.1)
        assertEquals(expected, preparedDirectStep.step(latest.state.speed, VehicleControlCommand.COAST, 0.1))
        // freeze() changes a diagnostic handle, not the physically established brake state.
        val automatic = HandleTrainActuator(train, profile, listOf(carProfile), 10.0, expected.state.speed)
        val stored = data.trainDynamicsHistory.read(train, expected.state.speed)
        automatic.freeze()
        assertEquals(stored, data.trainDynamicsHistory.read(train, expected.state.speed))
    }

    @Test
    fun `manual replacement inherits actual brake and acceleration before its first tick`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
        val train = createTrainWithConfig("manual-device-handoff", rail, world, listOf(10.0))
        TestBasicWorldDataRegistry.register(world, BasicWorldData())
        val profile = TestTrainProfile(brakeApplyTime = 1.0, brakeJerk = 0.5)
        val automatic = HandleTrainActuator(train, profile, listOf(carProfile), 10.0, 10.0)
        val result = automatic.step(10.0, VehicleControlCommand(brakeRatio = 1.0, handle = "B2"), 0.1)
        val manual = RealisticManualController(train, profile, listOf(carProfile), 10.0, result.speedMetersPerSecond)
        assertEquals(automatic.controlState(result.speedMetersPerSecond).actualBrakeRatio, manual.brakeScale)
        assertEquals(automatic.controlState(result.speedMetersPerSecond).accelerationMetersPerSecondSquared, manual.acceleration)
    }

    @Test
    fun `manual control passes player command and physical state through world supervisor`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
        val train = createTrainWithConfig("manual-supervision", rail, world, listOf(10.0))
        val data = BasicWorldData()
        var supervised = false
        data.commandSupervisor = object : VehicleCommandSupervisor {
            override fun supervise(train: Train, state: VehicleControlState,
                capabilities: VehicleControlCapabilities, command: VehicleControlCommand,
                deltaTimeSeconds: Double): VehicleControlCommand {
                assertEquals("P2", command.handle)
                assertEquals(1.0, state.actualBrakeRatio, "a newly stopped physical train starts with held air")
                assertTrue(state.devices.single().pressureKpa > 300.0)
                assertEquals(0.05, deltaTimeSeconds)
                assertTrue(capabilities.performanceSamples.isNotEmpty())
                supervised = true
                return VehicleControlCommand.EMERGENCY_BRAKE
            }
            override fun isAcknowledgementRequired(train: Train) = true
            override fun acknowledge(train: Train) = false
        }
        TestBasicWorldDataRegistry.register(world, data)
        val controller = RealisticManualController(train, trainProfile, listOf(carProfile), 10.0)
        train.setControllerForTest(controller)
        world.onOwnerForTest { controller.applyDirection(TrainDriveDirection.FORWARD) }
        controller.setHandle("P2")
        controller.tick(0.0)
        assertTrue(!supervised)
        assertEquals(null, data.trainDynamicsHistory.read(train, 0.0))
        controller.tick(50.0)
        assertTrue(supervised)
        assertTrue(controller.isAtpAcknowledgementRequired)
        assertEquals(0.0, controller.tractionForce)
        assertTrue(controller.brakeForce > 0.0)
        assertEquals("P2", controller.handle, "supervision must not overwrite player input")
    }

    private class TestCarProfile(
        override val mass: Double = 10_000.0,
        private val motorForce: Double = 10_000.0,
        override val brakeMaxForce: Double = 20_000.0,
        override val effectiveMass: Double = mass
    ) : CarDynamicsProfile {
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        // These force/grade arithmetic fixtures deliberately isolate device delays. Real equipment
        // response, held-air startup and fade are exercised by TrainEquipmentDynamicsTest.
        override val equipment = TrainDynamics.Equipment(commandDelaySeconds=0.0,
            electricBrakeApplyRateNewtonsPerSecond=1e12,electricBrakeReleaseRateNewtonsPerSecond=1e12,
            tractionApplyRateNewtonsPerSecond=1e12,tractionReleaseRateNewtonsPerSecond=1e12)
        override val maximumMotorForceUpperBound = motorForce
        override fun motorForceAt(speed: Double) = motorForce
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }
}
