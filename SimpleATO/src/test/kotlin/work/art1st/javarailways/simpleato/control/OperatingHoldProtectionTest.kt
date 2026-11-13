package work.art1st.javarailways.simpleato.control

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.simpleato.api.atp.AtpReason
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.constraint.*

class OperatingHoldProtectionTest {
    private val owner = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "dispatcher")

    private fun frame(grade: Double, authority: Double = .3041646857212754,
        holding: Boolean = true, stopDistance: Double = 20.0): TrainControlFrame = controlFrame(
        mapOf(owner to buildList {
            if (holding) add(StopConstraint(ConstraintKey("external", "hold"), 0.0,
                stoppingMarginMeters = 0.0, operatingTarget = true))
            else add(StopConstraint(ConstraintKey("mission", "stop"), stopDistance,
                stoppingMarginMeters = 0.0, operatingTarget = true))
            add(MovementAuthorityConstraint(ConstraintKey("mission", "authority"), authority, .3))
        }), AtoSafetyProfile(), grade)

    @Test fun `released equipment holds on a near flat route without consuming station authority margin`() {
        val fixture = ReferenceEquipmentFixture()
        val grade = -3.552713678801713E-14
        val frame = frame(grade)
        assertEquals(.3041646857212754, frame.protectionTargets.single().distanceMeters)
        val events = mutableListOf<AtpControlEvent>()
        val executor = VehicleControlExecutor(events::add)
        var state = TrainDynamics.initialState(fixture.cars, 0.0, brakeScale = 0.0)
        var command = VehicleControlCommand.COAST
        var position = 0.0
        repeat(40) {
            val input = fixture.input(state, command, 0.0, .05).copy(constraints = frame.referenceTargets)
            command = executor.choose(input, frame.protectionTargets, grade, VehicleExecutionMode.SIMULATION)
            assertFalse(command.emergencyBrake)
            assertEquals(0.0, command.tractionRatio)
            assertTrue(command.brakeRatio > 0.0)
            val result = TrainDynamics.step(state, fixture.profile, fixture.carInputs, command, 9.80665, .05)
            state = result.state
            position += result.distance
            assertEquals(0.0, state.speed)
        }
        assertEquals(0.0, position)
        val held = fixture.input(state, command, 0.0, .05)
        assertTrue(serviceHoldingBrakeEstablished(held.state, held.capabilities))
        assertNull(executor.intervention)
        assertTrue(events.isEmpty())

        // The same executor must release its ordinary stop latch after the keyed hold is removed.
        val departure = frame(0.0, authority = 20.3, holding = false)
        var appliedPower = false
        repeat(120) {
            val input = fixture.input(state, command, 20.0 - position, .05).copy(
                constraints = departure.referenceTargets.map { it.copy(distanceMeters = it.distanceMeters - position) })
            command = executor.choose(input,
                departure.protectionTargets.map { it.copy(distanceMeters = it.distanceMeters - position) },
                0.0, VehicleExecutionMode.SIMULATION)
            appliedPower = appliedPower || command.tractionRatio > 0.0
            val result = TrainDynamics.step(state, fixture.profile, fixture.carInputs, command, 9.80665, .05)
            state = result.state
            position += result.distance
        }
        assertTrue(appliedPower)
        assertTrue(position > 0.0)
        assertTrue(events.isEmpty())
    }

    @Test fun `operating hold maintains established pneumatic braking on a real downhill`() {
        val grade = -.01
        val fixture = ReferenceEquipmentFixture(grade = grade)
        val frame = frame(grade)
        val executor = VehicleControlExecutor()
        var state = TrainDynamics.initialState(fixture.cars, 0.0, brakeScale = 1.0)
        var command = VehicleControlCommand(brakeRatio = 1.0, handle = "B8")
        repeat(80) {
            val input = fixture.input(state, command, 0.0, .05).copy(constraints = frame.referenceTargets)
            command = executor.choose(input, frame.protectionTargets, grade, VehicleExecutionMode.SIMULATION)
            assertFalse(command.emergencyBrake)
            assertEquals(0.0, command.tractionRatio)
            val result = TrainDynamics.step(state, fixture.profile, fixture.carInputs, command, 9.80665, .05)
            assertEquals(0.0, result.distance)
            state = result.state
            assertEquals(0.0, state.speed)
            val held = fixture.input(state, command, 0.0, .05)
            assertTrue(serviceHoldingBrakeEstablished(held.state, held.capabilities))
        }
        assertNull(executor.intervention)
    }

    @Test fun `operating hold does not hide a passed physical authority`() {
        val fixture = ReferenceEquipmentFixture()
        val frame = frame(0.0, authority = -.01)
        val input = fixture.input(TrainDynamics.initialState(fixture.cars, 0.0),
            VehicleControlCommand.COAST, 0.0, .05).copy(constraints = frame.referenceTargets)
        val executor = VehicleControlExecutor()
        val command = executor.choose(input, frame.protectionTargets, 0.0)
        assertTrue(command.emergencyBrake)
        val incident = requireNotNull(executor.intervention)
        assertEquals(AtpReason.INSUFFICIENT_DISTANCE, incident.judgement.reason)
        assertEquals(frame.protectionTargets.single(), incident.judgement.witness?.target)
        assertEquals(-.01, incident.judgement.witness?.availableDistanceMeters)
    }

    @Test fun `explicit zero speed and fail closed holds retain hard protection`() {
        val fixture = ReferenceEquipmentFixture()
        val grade = -3.552713678801713E-14
        for (hardHold in listOf(
            CurrentSpeedLimitConstraint(ConstraintKey("external", "zero-speed"), 0.0),
            DepartureHoldConstraint(ConstraintKey("signal", "fail-closed"))
        )) {
            val frame = controlFrame(mapOf(owner to listOf(hardHold)), AtoSafetyProfile(), grade)
            assertEquals(0.0, frame.protectionTargets.single().distanceMeters)
            val input = fixture.input(TrainDynamics.initialState(fixture.cars, 0.0, brakeScale = 0.0),
                VehicleControlCommand.COAST, 0.0, .05).copy(constraints = frame.referenceTargets)
            val executor = VehicleControlExecutor()
            assertTrue(executor.choose(input, frame.protectionTargets, grade).emergencyBrake)
            assertEquals(AtpReason.INSUFFICIENT_DISTANCE, executor.intervention?.judgement?.reason)
        }
    }
}
