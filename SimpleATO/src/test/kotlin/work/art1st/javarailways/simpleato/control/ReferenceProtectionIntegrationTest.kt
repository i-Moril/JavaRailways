package work.art1st.javarailways.simpleato.control

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.simpleato.api.atp.AtpTarget
import kotlin.math.abs

/** Actual per-car motion plus the independent protection/executor; no synthetic brake provider. */
class ReferenceProtectionIntegrationTest {
    @Test fun `ordinary heterogeneous parking remains hard safe`() {
        var count = 0
        for ((brakeJerk, tractionJerk) in listOf(.2 to .2, 1.0 to 1.0, .2 to 1.0, 1.0 to .2))
            for (grade in listOf(-.01, 0.0, .01)) for (variable in listOf(false, true)) {
            run(fixture = ReferenceEquipmentFixture(brakeJerk, grade, upwardJerk = tractionJerk),
                grade = grade, distance = 80.0, variable = variable, initialSpeed = 1.0)
            count++
        }
        assertEquals(24, count)
    }

    @Test fun `multiple nonzero speed targets are respected before ordinary parking`() {
        for (variable in listOf(false,true)) run(ReferenceEquipmentFixture(),0.0,80.0,variable,
            initialSpeed=1.0,speedChanges=true)
    }

    @Test fun `held equipment launches toward nearby targets and remains held after arrival`() {
        for (distance in listOf(2.0, 10.0)) run(ReferenceEquipmentFixture(), 0.0, distance,
            variable = false, initialSpeed = 0.0)
    }

    @Test fun `immediate ideal equipment parks at an exact hard boundary`() {
        val fixture=ImmediateEquipmentFixture()
        val executor=VehicleControlExecutor()
        var state=TrainDynamics.initialState(listOf(fixture.car),brakeScale=0.0)
        var command=VehicleControlCommand.COAST;var position=0.0;var moved=false;var stopped=false
        for(tick in 0 until 2000) {
            val request=fixture.input(state,command,20.0-position)
            val hard=listOf(AtpTarget("physical-end",20.0-position,0.0))

            command=executor.choose(request,hard,0.0,VehicleExecutionMode.SIMULATION)
            val result=TrainDynamics.step(state,fixture.profile,fixture.cars,command,9.80665,.05)
            state=result.state;position+=result.distance;moved=moved||state.speed>0.0
            if(moved&&state.speed==0.0){println("IMMEDIATE_PROTECTED tick=$tick x=$position decision=${executor.lastDecision}");stopped=true;break}
        }
        assertTrue(stopped);assertEquals(20.0,position,.05);assertNull(executor.intervention)
        val rest=position
        repeat(20) {
            val request=fixture.input(state,command,20.0-position)
            command=executor.choose(request,listOf(AtpTarget("physical-end",20.0-position,0.0)),0.0,VehicleExecutionMode.SIMULATION)
            assertEquals(0.0,command.tractionRatio)
            val result=TrainDynamics.step(state,fixture.profile,fixture.cars,command,9.80665,.05)
            state=result.state;position+=result.distance
        }
        assertEquals(rest,position,1e-9)
    }

    private fun run(fixture: ReferenceEquipmentFixture, grade: Double, distance: Double,
        variable: Boolean, initialSpeed: Double, speedChanges: Boolean = false) {
        val events = mutableListOf<AtpControlEvent>()
        val executor = VehicleControlExecutor(events::add)
        var state = TrainDynamics.initialState(fixture.cars, initialSpeed,
            brakeScale = if (initialSpeed == 0.0) 1.0 else 0.0)
        var command = VehicleControlCommand.COAST
        var position = 0.0
        var moved = initialSpeed > 0.0
        var finished = false
        var terminalSwitches = 0
        for (tick in 0 until 10000) {
            val h = if (!variable) .05 else listOf(.025, .075, .1)[tick % 3]
            val limits = if(speedChanges) listOf(
                MotionReferenceConstraint("first-speed",maxOf(0.0,20.0-position),2.0),
                MotionReferenceConstraint("second-speed",maxOf(0.0,50.0-position),1.0)) else emptyList()
            val input = fixture.input(state, command, distance-position, h).let { it.copy(constraints=it.constraints+limits) }
            val targets = listOf(AtpTarget("speed", 0.0, fixture.profile.maxSpeed),
                AtpTarget("authority", distance+.3-position, 0.0)) + limits.map { AtpTarget(it.id,it.distanceMeters,it.targetSpeedMetersPerSecond) }
            val next = try { executor.choose(input, targets, grade, VehicleExecutionMode.SIMULATION) }
            catch (failure: AtpSimulationAbort) {
                val model = work.art1st.javarailways.simpleato.atp.ConservativeAtpModel(input.capabilities)
                val strongest = input.capabilities.handles.filter { !it.command.emergencyBrake }.maxBy { it.command.brakeRatio }.command
                println("PROTECTION_FAILURE tick=$tick x=$position state=${input.state}")
                println("IMMEDIATE="+model.emergency(input.state,targets,grade,input.maximumControlDeltaSeconds))
                println("CANDIDATE_EB="+model.afterCandidate(input.state,strongest,h,targets,grade,input.maximumControlDeltaSeconds))
                println("CANDIDATE_SERVICE="+model.afterCandidate(input.state,strongest,h,targets,grade,input.maximumControlDeltaSeconds,serviceRecovery=true))
                throw failure
            }
            if (next != command && distance-position <= 1.0) terminalSwitches++
            command = next
            val result = TrainDynamics.step(state, fixture.profile, fixture.carInputs, command, 9.80665, h)
            state = result.state; position += result.distance
            if(speedChanges && position>=20.0) assertTrue(state.speed<=2.0+1e-9,"first speed crossing: $state")
            if(speedChanges && position>=50.0) assertTrue(state.speed<=1.0+1e-9,"second speed crossing: $state")
            moved = moved || state.speed > 0.0
            if (moved && state.speed == 0.0) {
                println("PROTECTED grade=$grade variable=$variable distance=$distance error=${distance-position} switches=$terminalSwitches")
                assertEquals(distance, position, .05)
                assertTrue(terminalSwitches < 40)
                val rest = position
                repeat(20) {
                    val held = fixture.input(state, command, distance-position, .05)
                    command = executor.choose(held, listOf(AtpTarget("authority",distance+.3-position,0.0)),
                        grade, VehicleExecutionMode.SIMULATION)
                    assertEquals(0.0, command.tractionRatio)
                    val stationary = TrainDynamics.step(state,fixture.profile,fixture.carInputs,command,9.80665,.05)
                    state=stationary.state;position+=stationary.distance
                }
                assertEquals(rest, position, 1e-9)
                finished=true;break
            }
        }
        assertTrue(finished,"never arrived distance=$distance x=$position state=$state")
        assertTrue(events.isEmpty(), events.toString())
        assertNull(executor.intervention)
        assertTrue(abs(distance-position)<=.05)
    }
}
