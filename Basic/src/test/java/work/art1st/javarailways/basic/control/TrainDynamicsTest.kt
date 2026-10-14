package work.art1st.javarailways.basic.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.ControlDemand
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
    private val trainProfile = TestTrainProfile()
    private val carProfile = TestCarProfile()

    @Test
    fun `manual direction requests commit FIFO and failures retain effective display direction`() {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            val world = createTestWorld(coordinator)
            TestBasicWorldDataRegistry.register(world, BasicWorldData())
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                val train = createTrainWithConfig("manual-direction", rail, world, listOf(10.0))
                val controller = RealisticManualController(
                    train, world, trainProfile, listOf(carProfile), 9.80665,
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
                world.releaseRuntimeBinding()
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
            override fun dynamicsProfileFor(car: Car): TrainDynamics.CarProfile? = if (car === incoming) other else null
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
        val controller = RealisticManualController(original, original.world, trainProfile, listOf(heavy, light), 10.0)
        controller.setHandle("P2")
        original.setControllerForTest(controller)
        val future = original.splitBefore(second.id, "manual-subset")
        original.world.onOwnerForTest { original.world.physicsCoordinator.drainBetweenFramesOperations() }
        val separated = future.get(5, TimeUnit.SECONDS)

        assertSame(controller, original.controller)
        assertEquals("P2", controller.handle)
        assertSame(heavy, controller.dynamicsProfileFor(first))
        assertEquals(null, controller.dynamicsProfileFor(second))
        assertSame(separated, second.train)
    }

    @Test
    fun `executor keeps profiles with car instances through reorder and subset`() = withDynamicsTrain { train ->
        val heavyCar = ObservedCar(train.cars[0], 0.1)
        val lightCar = ObservedCar(train.cars[1], -0.05)
        train.currentCars = listOf(heavyCar, lightCar)
        val heavy = TestCarProfile(mass = 20_000.0, brakeMaxForce = 40_000.0)
        val light = TestCarProfile(mass = 10_000.0, brakeMaxForce = 5_000.0)
        val executor = TrainDynamicsExecutor(train, trainProfile, listOf(heavy, light), 10.0)

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
        assertEquals(TrainDynamics.State(), executor.state)
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

    private fun withDynamicsTrain(action: (MutableConsist) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
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
        val releaseSeconds = 1.2 / profile.brakeJerk
        val speedGain = 1.2 * releaseSeconds / 2.0
        val extraDistance = speed * releaseSeconds + 1.2 * releaseSeconds * releaseSeconds / 3.0 +
            speedGain * reactionSeconds
        val baseline = envelope(0.0)
        assertEquals(
            (baseline.permittedSpeedMetersPerSecond(0.0, 10.0 - speed * reactionSeconds - extraDistance) - speedGain)
                .coerceAtLeast(0.0),
            envelope(1.2).permittedSpeedMetersPerSecond(0.0, 10.0, speed, reactionSeconds), 1.0e-12)
        assertEquals(baseline.permittedSpeedMetersPerSecond(0.0, 10.0, speed, reactionSeconds),
            envelope(-1.2).permittedSpeedMetersPerSecond(0.0, 10.0, speed, reactionSeconds), 0.0)
        assertEquals(0.0, envelope(1.2).permittedSpeedMetersPerSecond(0.0, 1.0, speed, reactionSeconds))
        val unbrakeable = GradeAwareBrakingEnvelope.fromDynamics(profile, listOf(carProfile), 9.80665,
            listOf(BrakingGradeSegment(10.0, 0.0), BrakingGradeSegment(100.0, -1.0)), 1.2)
        assertEquals(0.0, unbrakeable.permittedSpeedMetersPerSecond(0.0, 12.0, speed, reactionSeconds),
            "reserving unwind distance must not hide an unbrakeable segment inside the target range")
        val noJerk = TestTrainProfile(brakeJerk = 0.0)
        assertEquals(envelope(0.0, noJerk).permittedSpeedMetersPerSecond(0.0, 10.0, speed, reactionSeconds),
            envelope(1.2, noJerk).permittedSpeedMetersPerSecond(0.0, 10.0, speed, reactionSeconds), 0.0)
    }

    @Test
    fun `service brake reaction covers progressive handles apply time and jerk`() {
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

        assertEquals(0.1 + 4 * 0.05 + 1.2 + serviceDeceleration / 2.0, reaction, 1.0e-12)
    }

    @Test
    fun `handle actuator progresses through configured notches and matches direct dynamics`() {
        val world = createTestWorld()
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

        val first = actuator.step(0.0, ControlDemand(trainProfile.maxSpeed), 0.1)
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

        actuator.step(first.speedMetersPerSecond, ControlDemand(trainProfile.maxSpeed), 0.1)
        assertEquals("P2", actuator.selectedHandle)
    }

    @Test
    fun `world safety interlock forces the manual controller to emergency brake`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(200.0)
        )
        val train = createTrainWithConfig("manual-interlock", rail, world, listOf(10.0))
        val basicData = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, basicData)
        val controller = RealisticManualController(
            train,
            world,
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
                world,
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
    ) : TrainDynamics.TrainProfile {
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

    private class TestCarProfile(
        override val mass: Double = 10_000.0,
        private val motorForce: Double = 10_000.0,
        override val brakeMaxForce: Double = 20_000.0
    ) : TrainDynamics.CarProfile {
        override val effectiveMass = mass
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override fun motorForceAt(speed: Double) = motorForce
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }
}
