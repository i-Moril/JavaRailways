package work.art1st.javarailways.simpleato.control

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.simpleato.api.atp.*

class VehicleControlExecutorTest {
    private fun input(): MotionReferenceInput {
        val fixture = ReferenceEquipmentFixture()
        return fixture.input(TrainDynamics.initialState(fixture.cars, 0.0),
            VehicleControlCommand.COAST, 500.0, .05)
    }
    @Test fun `automatic fresh safe departure releases a stopped intervention`() {
        // Constrain the operating candidate catalogue to a power demand so the release test
        // exercises the formerly stuck safe-P branch, rather than passing via a coast tie.
        val input = input().let { it.copy(capabilities = it.capabilities.copy(
            handles = listOf(it.capabilities.handles.last { handle -> handle.command.tractionRatio > 0.0 }))) }
        val executor = VehicleControlExecutor()
        executor.choose(input, emptyList(), 0.0, inputUnavailableReason = "lost route")
        assertNotNull(executor.intervention)
        val chosen = executor.choose(input, emptyList(), 0.0)
        assertNull(executor.intervention)
        assertFalse(chosen.emergencyBrake)
        assertTrue(chosen.tractionRatio > 0.0)
    }
    @Test fun `manual acknowledgement is queued and survives automatic takeover`() {
        val input = input()
        val executor = VehicleControlExecutor()
        executor.choose(input, emptyList(), 0.0, VehicleExecutionMode.MANUAL,
            VehicleControlCommand.COAST, inputUnavailableReason = "lost route")
        assertTrue(executor.choose(input, emptyList(), 0.0).emergencyBrake)
        assertNotNull(executor.intervention)
        assertTrue(executor.acknowledgeManual())
        assertNotNull(executor.intervention)
        executor.choose(input, emptyList(), 0.0)
        assertNull(executor.intervention)
    }
    @Test fun `unsafe fresh input consumes acknowledgement without clearing latch`() {
        val input = input()
        val executor = VehicleControlExecutor()
        executor.choose(input, emptyList(), 0.0, VehicleExecutionMode.MANUAL,
            inputUnavailableReason = "lost route")
        assertTrue(executor.acknowledgeManual())
        executor.choose(input, emptyList(), 0.0, inputUnavailableReason = "still lost")
        assertNotNull(executor.intervention)
        executor.choose(input, emptyList(), 0.0)
        assertNotNull(executor.intervention)
    }
    @Test fun `planning reserve exhaustion selects only hard safe brake or coast without ATP`() {
        val fixture = ReferenceEquipmentFixture(.2, -.01, upwardJerk = 1.0)
        val command = VehicleControlCommand(brakeRatio = 1.0, handle = "B8")
        val physical = TrainDynamics.State(4.50232, -.97943, 1.0, TrainDynamics.DeviceSnapshot.of(listOf(
            TrainDynamics.CarDeviceState(131.25, 1000.0, 0.0, .45, previousBrakeRatio = 1.0),
            TrainDynamics.CarDeviceState(350.0, 0.0, 0.0, .45, previousBrakeRatio = 1.0))))
        val input = fixture.input(physical, command, 8.88436, .05)
        val targets = listOf(AtpTarget("speed", 0.0, 10.0), AtpTarget("authority", 9.18436, 0.0))
        val events = mutableListOf<AtpControlEvent>()
        val executor = VehicleControlExecutor { events.add(it) }
        val chosen = executor.choose(input, targets, -.01, VehicleExecutionMode.SIMULATION)
        assertFalse(executor.planningReserveAvailable)
        assertEquals(0.0, chosen.tractionRatio)
        assertFalse(chosen.emergencyBrake)
        assertTrue(executor.lastDecision!!.ordinaryCommandAdmissible)
        assertNull(executor.intervention)
        assertTrue(events.isEmpty())
    }

    @Test fun `cruise ordinary decision is cached but hard supervision remains fresh`() {
        val input = input()
        val executor = VehicleControlExecutor()
        executor.choose(input, emptyList(), 0.0)
        val first = executor.lastDecision
        repeat(3) {
            executor.choose(input, emptyList(), 0.0)
            assertSame(first, executor.lastDecision)
        }
        executor.choose(input, emptyList(), 0.0)
        assertNotSame(first, executor.lastDecision)
        val held = executor.lastDecision
        executor.choose(input, emptyList(), 0.0, inputUnavailableReason = "fresh route failure")
        assertSame(held, executor.lastDecision)
        assertEquals(AtpReason.INPUT_UNAVAILABLE, executor.intervention!!.judgement.reason)
    }

    @Test fun `far hard targets are recognized as outside the protection horizon`() {
        val fixture = ReferenceEquipmentFixture()
        val state = TrainDynamics.initialState(fixture.cars, 5.0)
        val far = fixture.input(state, VehicleControlCommand.COAST, 5_000.0, .05)
        val model = work.art1st.javarailways.simpleato.atp.ConservativeAtpModel(far.capabilities)
        val farTarget = listOf(AtpTarget("stop", 5_000.0, 0.0))
        val safe = model.emergency(far.state, farTarget, 0.0, far.maximumControlDeltaSeconds)
        assertEquals(AtpStatus.SAFE, safe.status)
        assertTrue(hardTargetsBeyondProtection(far, farTarget, safe))
        val near = far.copy(constraints = listOf(MotionReferenceConstraint("stop", 1.0, 0.0)))
        assertFalse(hardTargetsBeyondProtection(near, listOf(AtpTarget("stop", 1.0, 0.0)), safe))
    }

    @Test fun `relaxed far-target selection still commits only a hard-safe command`() {
        val fixture = ReferenceEquipmentFixture()
        val settled = TrainDynamics.initialState(fixture.cars)
        val state = settled.copy(devices = TrainDynamics.DeviceSnapshot.of(
            settled.devices.toList().map { it.copy(commandAgeSeconds = 1.0) }))
        val input = fixture.input(state, VehicleControlCommand.COAST, 20.0, .05)
        val targets = listOf(AtpTarget("stop", 20.0, 0.0))
        val executor = VehicleControlExecutor()
        val chosen = executor.choose(input, targets, 0.0)
        assertNull(executor.intervention)
        assertFalse(chosen.emergencyBrake)
        assertTrue(executor.lastDecision!!.ordinaryCommandAdmissible)
    }

    @Test fun `simulation cannot continue an existing intervention`() {
        val input = input()
        val executor = VehicleControlExecutor()
        executor.choose(input, emptyList(), 0.0, inputUnavailableReason = "lost route")
        assertThrows(AtpSimulationAbort::class.java) {
            executor.choose(input, emptyList(), 0.0, VehicleExecutionMode.SIMULATION)
        }
    }
}
