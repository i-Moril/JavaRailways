package work.art1st.javarailways.simpleato.api.ato

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.RealisticManualController
import work.art1st.javarailways.basic.control.TrainDriveDirection
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.core.testing.IsolateGlobalHalt
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.simpleato.control.ReferenceEquipmentFixture
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import java.util.concurrent.ExecutionException

class AbstractATOStallBrakingTest {
    @Test
    fun `healthy ATO and manual peers integrate real equipment EB while faulty controller stays frozen`() = withFixture { f ->
        val equipment = ReferenceEquipmentFixture()
        val train = f.train("healthy", listOf(10.0, 10.0))
        val real = HandleTrainActuator(train, equipment.profile, equipment.cars, 9.80665)
        val actuator = RecordingActuator(real)
        val healthy = f.controller(train, actuator)
        val bad = f.controller(f.train("faulty"))
        val manualTrain = f.train("manual-peer", listOf(10.0, 10.0))
        val manual = RealisticManualController(manualTrain, equipment.profile, equipment.cars, 9.80665)
        manualTrain.setControllerForTest(manual)
        f.world.onOwnerForTest { manual.applyDirection(TrainDriveDirection.FORWARD) }
        manual.setHandle("P4")
        f.physics.start()
        repeat(80) { f.step() }
        assertTrue(train.speed > 0.5, "fixture must establish real motion")
        assertTrue(manualTrain.speed > 0.5, "manual peer must establish real motion")
        bad.failValidation = true
        f.step()
        val report = requireNotNull(f.coordinator.lastStall)
        assertEquals(bad.trainId, report.trainId)
        assertEquals(0.0, bad.currentTrainFrame.speedMetersPerSecond)
        val before = train.publishedFrame
        val manualBefore = manualTrain.publishedFrame
        val distanceBefore = healthy.lifetimeTravelDistanceMeters
        val plans = healthy.plans
        val commands = healthy.commands
        val faultyPlans = bad.plans
        f.step()
        val after = train.publishedFrame
        assertEquals(PhysicsCoordinatorStatus.RUNNING, f.physics.status)
        assertTrue(after.physicsEpoch > before.physicsEpoch)
        assertEquals(VehicleControlCommand.EMERGENCY_BRAKE, actuator.commands.last())
        assertEquals("EB", real.selectedHandle)
        assertTrue(after.speedMetersPerSecond > 0.0, "EB must not replace moving equipment with zero speed")
        assertTrue(after.head.arcLengthInAbMeters > before.head.arcLengthInAbMeters)
        assertEquals(after.head.arcLengthInAbMeters - before.head.arcLengthInAbMeters,
            healthy.lifetimeTravelDistanceMeters - distanceBefore, 1e-9)
        assertFrameVelocity(train)
        assertEquals("EB", manual.handle)
        assertTrue(manualTrain.speed > 0.0, "moving manual peer must not be frozen")
        assertTrue(manualTrain.publishedFrame.head.arcLengthInAbMeters > manualBefore.head.arcLengthInAbMeters)
        assertFrameVelocity(manualTrain)
        var stopped = false
        for (tick in 0 until 400) {
            f.step()
            assertFrameVelocity(train)
            assertFrameVelocity(manualTrain)
            if (train.speed == 0.0 && manualTrain.speed == 0.0) { stopped = true; break }
        }
        assertTrue(stopped, "actual EB must eventually stop both healthy trains")
        assertTrue(manual.brakeForce > 0.0)
        assertTrue(manualTrain.cars.all { checkNotNull(manual.dynamicsDeviceStateFor(it)).pressureKpa > 0.0 })
        assertEquals(plans, healthy.plans, "Stall must not run planner")
        assertEquals(commands, healthy.commands, "Stall must not consume commands")
        assertEquals(faultyPlans, bad.plans)
        assertSame(report, f.coordinator.lastStall)
        assertTrue(real.lastDynamicsResult.state.devices.toList().all { it.pressureKpa > 0.0 })
        assertEquals(train.publishedFrame.head.arcLengthInAbMeters - before.head.arcLengthInAbMeters,
            healthy.lifetimeTravelDistanceMeters - distanceBefore, 1e-9)
        assertEquals(0, actuator.freezes, "healthy train must execute EB instead of freeze")
    }

    @ParameterizedTest
    @ValueSource(strings = ["external", "interlock", "during-plan"])
    fun `external Stall and interlock bypass normal control without inventing a faulty train`(cause: String) =
        withFixture { f ->
            val train = f.train("healthy")
            val actuator = RecordingActuator(KinematicTrainActuator(10.0, f.safety))
            val ato = f.controller(train, actuator)
            f.physics.start()
            repeat(40) { f.step() }
            val before = train.publishedFrame
            val plans = ato.plans
            val commands = ato.commands
            // A dispatcher report may name a perfectly healthy controller's train.
            when (cause) {
                "external" -> f.coordinator.stall(f.report(train.id))
                "interlock" -> f.coordinator.safetyInterlock.engage()
                else -> ato.duringPlan = { f.coordinator.stall(f.report(train.id)) }
            }
            val steps = actuator.commands.size
            f.step()
            assertEquals(steps + 1, actuator.commands.size, "integrate only once in this physical frame")
            assertEquals(VehicleControlCommand.EMERGENCY_BRAKE, actuator.commands.last())
            assertTrue(train.speed in 0.0..<before.speedMetersPerSecond)
            assertTrue(train.speed > 0.0)
            assertTrue(train.publishedFrame.head.arcLengthInAbMeters > before.head.arcLengthInAbMeters)
            assertEquals(plans + if (cause == "during-plan") 1 else 0, ato.plans)
            assertEquals(commands + if (cause == "during-plan") 1 else 0, ato.commands)
            assertEquals(0, actuator.freezes)
            if (cause == "interlock") assertNull(f.coordinator.lastStall)
        }

    @Test
    fun `physical maintenance pauses EB and resumes its equipment history afterwards`() = withFixture { f ->
        val actuator = RecordingActuator(KinematicTrainActuator(10.0, f.safety))
        val train = f.train("paused")
        f.controller(train, actuator)
        f.physics.start()
        repeat(40) { f.step() }
        f.coordinator.stall(f.report(train.id))
        val frame = train.publishedFrame
        val steps = actuator.commands.size
        IsolatedSimulationThreadMarker.run {
            f.world.withPhysicsStalled {
                assertEquals(PhysicsCoordinatorStatus.STALLED, f.physics.status)
                repeat(3) { f.step() }
                assertSame(frame, train.publishedFrame)
                assertEquals(steps, actuator.commands.size)
            }
        }
        f.step()
        assertEquals(steps + 1, actuator.commands.size)
        assertEquals(VehicleControlCommand.EMERGENCY_BRAKE, actuator.commands.last())
        assertTrue(train.publishedFrame.head.arcLengthInAbMeters > frame.head.arcLengthInAbMeters)
    }

    @Test
    fun `local failure gate belongs to current Stall report and clears across reset`() = withFixture { f ->
        val train = f.train("episode")
        val actuator = RecordingActuator(KinematicTrainActuator(10.0, f.safety))
        val ato = f.controller(train, actuator)
        f.physics.start()
        f.step()
        val first = f.report("dispatcher")
        ato.duringPlan = { f.coordinator.stall(first); error("second local failure") }
        f.step()
        assertSame(first, f.coordinator.lastStall)
        val steps = actuator.commands.size
        val plans = ato.plans
        f.step()
        assertEquals(steps, actuator.commands.size, "controller that itself failed remains frozen")
        assertEquals(plans, ato.plans)
        ato.duringPlan = {}
        f.coordinator.reset()
        // A new episode may begin before this controller sees a RUNNING tick.
        val second = f.report(train.id)
        f.coordinator.stall(second)
        f.step()
        assertSame(second, f.coordinator.lastStall)
        assertEquals(steps + 1, actuator.commands.size, "old failure gate cannot freeze the new episode")
        assertEquals(VehicleControlCommand.EMERGENCY_BRAKE, actuator.commands.last())
        f.coordinator.reset()
        f.step()
        assertEquals(plans + 1, ato.plans)
        assertTrue(train.speed > 0.0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["actuator", "movement-boundary"])
    @IsolateGlobalHalt
    fun `emergency motion failure fails the physical epoch without publishing a fake zero frame`(failure: String) =
        withFixture { f ->
            val train = f.train("failed-emergency")
            val actuator = RecordingActuator(KinematicTrainActuator(10.0, f.safety))
            val ato = f.controller(train, actuator)
            f.physics.start()
            repeat(20) { f.step() }
            val frame = train.publishedFrame
            val lifetime = ato.lifetimeTravelDistanceMeters
            f.coordinator.stall(f.report(train.id))
            if (failure == "actuator") actuator.failStep = true else ato.failBoundary = true
            assertThrows(ExecutionException::class.java) { f.step() }
            assertEquals(PhysicsCoordinatorStatus.STALLED, f.physics.status)
            assertSame(frame, train.publishedFrame)
            assertEquals(lifetime, ato.lifetimeTravelDistanceMeters)
            assertEquals(0, actuator.freezes)
        }

    private fun assertFrameVelocity(train: Train) {
        val frame = requireNotNull(train.publishedFrame)
        frame.cars.forEach { car ->
            assertEquals(frame.speedMetersPerSecond, car.state.velocityX, 1e-9)
            car.bogies.forEach { assertEquals(frame.speedMetersPerSecond, it.velocityX, 1e-9) }
        }
    }

    private class RecordingActuator(private val delegate: TrainActuator) : TrainActuator by delegate {
        val commands = mutableListOf<VehicleControlCommand>()
        var freezes = 0
        var failStep = false
        override fun step(currentSpeedMetersPerSecond: Double, command: VehicleControlCommand,
            deltaTimeSeconds: Double): ActuationStep {
            commands += command
            check(!failStep) { "forced EB actuation failure" }
            return delegate.step(currentSpeedMetersPerSecond, command, deltaTimeSeconds)
        }
        override fun freeze() { freezes++; delegate.freeze() }
    }

    private class TestATO(train: Train, world: World, actuator: TrainActuator,
        safety: AtoSafetyProfile, coordinator: AtoWorldCoordinator) : AbstractATO(train, world, actuator, safety, coordinator) {
        val trainId = train.id
        var plans = 0
        var commands = 0
        var failValidation = false
        var failBoundary = false
        var duringPlan: () -> Unit = {}
        override fun processPendingCommands() { commands++ }
        override fun plan(deltaTimeSeconds: Double): ControlDemand {
            plans++
            duringPlan()
            return ControlDemand(actuator.maximumSpeedMetersPerSecond)
        }
        override fun validateBeforeMovement() { check(!failValidation) { "forced local invariant failure" } }
        override fun constrainMovementDistance(requestedDistanceMeters: Double): Double {
            check(!failBoundary) { "forced EB movement boundary failure" }
            return requestedDistanceMeters
        }
    }

    private class Fixture(val physics: PhysicsCoordinator, val owned: TestWorldFixture) {
        val world = owned.world
        val safety = AtoSafetyProfile(serviceDecelerationMetersPerSecondSquared = 1.0,
            reactionTimeSeconds = 0.0, safetyDistanceMeters = 2.0)
        val coordinator = AtoWorldCoordinator("stall-braking", reportSink = {})
        init { TestSimpleATOWorldDataRegistry.register(world,
            BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator) }
        fun train(id: String, lengths: List<Double> = listOf(10.0)) = createTrainWithConfig(id,
            createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(1000.0)), world, lengths)
        fun controller(train: CoreTrain, actuator: TrainActuator = KinematicTrainActuator(10.0, safety)) =
            TestATO(train, world, actuator, safety, coordinator).also { train.setControllerForTest(it) }
        fun report(trainId: String) = AtoStallReport(coordinator.worldId, trainId, 1, "test", "test Stall", "test",
            null, null, null, null, emptyMap())
        fun step() { physics.stepSimulation(50.0).await() }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        PhysicsCoordinator(2, automaticFallback = false).use { physics ->
            TestWorldFixture.create(physics).use { owned ->
                physics.use {
                    try { test(Fixture(physics, owned)) }
                    finally { TestSimpleATOWorldDataRegistry.unregister(owned.world) }
                }
            }
        }
    }
}
