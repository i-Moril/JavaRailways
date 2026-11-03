package work.art1st.javarailways.simpleato.control

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.control.*
import kotlin.math.abs

class ReferenceStrategyTest {
    @Test fun `ideal release curve joins and aims at the actual point`() {
        val profile = NominalMotionReference(0.8, 0.8, 0.4)
        val join = profile.stoppingTransitionDistanceMeters
        val before = profile.at(0.0, join - 1e-8)
        val after = profile.at(0.0, join + 1e-8)
        assertEquals(before.speedMetersPerSecond, after.speedMetersPerSecond, 1e-7)
        assertEquals(before.accelerationMetersPerSecondSquared, after.accelerationMetersPerSecondSquared, 1e-7)
        assertEquals(0.0, profile.at(0.0, 0.0).speedMetersPerSecond)
        for (speed in listOf(.01, .1, 1.0, 10.0))
            assertEquals(speed, profile.at(0.0, profile.stoppingDistanceMeters(speed)).speedMetersPerSecond, 1e-9)
    }

    @Test fun `nominal infeasibility stays separate from hard feasibility`() {
        val fixture = ReferenceEquipmentFixture()
        val state = TrainDynamics.initialState(fixture.cars, 3.0)
        val input = fixture.input(state, VehicleControlCommand.COAST, .001, .05)
        val reference = NominalMotionReference.fromCapabilities(input.capabilities)
        val stop = input.constraints.single()
        val strategy = StoppingReferenceStrategy()
        val normal = strategy.select(reference, input, stop)
        assertTrue(normal.ordinaryCommandAdmissible)
        assertEquals(OperatingPrecisionStatus.TARGET_UNREACHABLE, normal.precisionStatus)
        assertEquals(1.0, normal.command.brakeRatio)
        val rejected = strategy.select(reference, input, stop) { false }
        assertFalse(rejected.ordinaryCommandAdmissible)
    }

    @Test fun `missing nominal device state cannot masquerade as a hard rejection`() {
        val fixture = ReferenceEquipmentFixture()
        val state = TrainDynamics.initialState(fixture.cars, 1.0)
        val input = fixture.input(state, VehicleControlCommand.COAST, 2.0, .05).let {
            it.copy(state = it.state.copy(devices = emptyList()))
        }
        val reference = NominalMotionReference.fromCapabilities(input.capabilities)
        val decision = StoppingReferenceStrategy().select(reference, input, input.constraints.single())
        assertTrue(decision.ordinaryCommandAdmissible)
        assertEquals(OperatingPrecisionStatus.MODEL_UNAVAILABLE, decision.precisionStatus)
        assertTrue(NominalServiceBrakeModel.stoppingDistanceMeters(input, VehicleControlCommand.COAST).isNaN())
    }

    @Test fun `moving authorization extension exits stopping interception`() {
        val fixture = ReferenceEquipmentFixture()
        val state = TrainDynamics.initialState(fixture.cars, 1.0)
        val input = fixture.input(state, VehicleControlCommand.COAST, .1, .05)
        val reference = NominalMotionReference.fromCapabilities(input.capabilities)
        assertNotNull(ReferenceStrategyMode.stoppingTarget(reference, input))
        assertNull(ReferenceStrategyMode.stoppingTarget(reference,
            input.copy(constraints = listOf(input.constraints.single().copy(distanceMeters = 100.0))), "station", .1))
        assertNull(ReferenceStrategyMode.stoppingTarget(reference,
            input.copy(state = input.state.copy(speedMetersPerSecond = 6.0))))
    }

    @Test fun `begun zero-target braking enters stopping but an unrelated speed brake does not`() {
        val fixture = ReferenceEquipmentFixture()
        val brake = TrainDynamics.commandForHandle(fixture.profile, "B3")
        val moving = fixture.input(TrainDynamics.initialState(fixture.cars, 1.0), brake, 20.0, .05)
        val reference = NominalMotionReference.fromCapabilities(moving.capabilities)
        assertNotNull(ReferenceStrategyMode.stoppingTarget(reference, moving))
        assertNull(ReferenceStrategyMode.stoppingTarget(reference, moving.copy(constraints = moving.constraints +
            MotionReferenceConstraint("current-speed",0.0,.5))))
        assertNull(ReferenceStrategyMode.stoppingTarget(reference, moving.copy(state = moving.state.copy(speedMetersPerSecond = 0.0))))
    }

    @Test fun `initial rest launches toward nearby goals and terminal rest never reapplies traction`() {
        for (distance in listOf(2.0, 10.0)) {
            val fixture = ReferenceEquipmentFixture()
            var state = TrainDynamics.initialState(fixture.cars)
            var command = VehicleControlCommand.COAST
            val reference = NominalMotionReference.fromCapabilities(fixture.input(state, command, distance, .05).capabilities)
            val driving = DrivingReferenceStrategy()
            val stopping = StoppingReferenceStrategy()
            var stopId: String? = null
            var stopDistance: Double? = null
            var position = 0.0
            var moved = false
            var enteredStop = false
            var finished = false
            assertNull(ReferenceStrategyMode.stoppingTarget(reference, fixture.input(state, command, distance, .05)))
            for (tick in 0 until 10000) {
                val input = fixture.input(state, command, distance-position, .05)
                val stop = ReferenceStrategyMode.stoppingTarget(reference, input, stopId, stopDistance)
                enteredStop = enteredStop || stop != null
                val decision = if (stop == null) driving.select(reference, input) else stopping.select(reference, input, stop)
                if (enteredStop) assertEquals(0.0, decision.command.tractionRatio)
                command = decision.command; stopId = stop?.id; stopDistance = stop?.distanceMeters
                val result = TrainDynamics.step(state, fixture.profile, fixture.carInputs, command, 9.80665, .05)
                state = result.state; position += result.distance
                moved = moved || state.speed > 0.0
                if (moved && state.speed == 0.0) {
                    assertEquals(distance, position, .05)
                    repeat(20) {
                        val heldInput = fixture.input(state, command, distance-position, .05)
                        val heldTarget = ReferenceStrategyMode.stoppingTarget(reference, heldInput, stopId, stopDistance)
                        assertNotNull(heldTarget)
                        val held = stopping.select(reference, heldInput, heldTarget!!)
                        assertEquals(0.0, held.command.tractionRatio)
                        assertTrue(held.command.brakeRatio > 0.0)
                    }
                    val extended = fixture.input(state, command, 10.0, .05)
                    assertNull(ReferenceStrategyMode.stoppingTarget(reference, extended, stopId, stopDistance))
                    finished = true; break
                }
            }
            assertTrue(finished, "nearby goal $distance never completed; x=$position state=$state")
        }
    }

    @Test fun `pending accepted demand finishes delivery but safety can interrupt`() {
        val fixture = ReferenceEquipmentFixture()
        val pending = TrainDynamics.commandForHandle(fixture.profile, "B3")
        val devices = fixture.cars.map { TrainDynamics.CarDeviceState(0.0, 0.0, 0.0, .05,
            -pending.brakeRatio, 0.0, pending.brakeRatio, false) }
        val state = TrainDynamics.State(1.0, 0.0, 0.0, TrainDynamics.DeviceSnapshot.of(devices))
        val input = fixture.input(state, pending, 80.0, .05)
        val reference = NominalMotionReference.fromCapabilities(input.capabilities)
        val strategy = DrivingReferenceStrategy()
        assertEquals(pending, strategy.select(reference, input).command)
        assertNotEquals(pending, strategy.select(reference, input) { it != pending }.command)
    }

    @Test fun `instantaneous zero-resistance equipment has unbounded coast and still parks`() {
        val fixture = ImmediateEquipmentFixture()
        val profile=fixture.profile;val car=fixture.car;val cars=fixture.cars;val model=fixture.model
        fun input(state:TrainDynamics.State,command:VehicleControlCommand,distance:Double)=fixture.input(state,command,distance)
        val cruising = TrainDynamics.initialState(listOf(car),1.0,brakeScale=0.0)
        assertTrue(NominalServiceBrakeModel.coastingDistanceMeters(input(cruising,VehicleControlCommand.COAST,20.0),VehicleControlCommand.COAST).isInfinite())
        val reference = NominalMotionReference.fromCapabilities(model.snapshot(0.0,cars))
        val driving = DrivingReferenceStrategy();val stopping = StoppingReferenceStrategy()
        var state=TrainDynamics.initialState(listOf(car),brakeScale=0.0)
        var command=VehicleControlCommand.COAST;var position=0.0
        var stopId:String?=null;var previousDistance:Double?=null;var moved=false;var stopped=false
        for(tick in 0 until 2000) {
            val request=input(state,command,20.0-position)
            val stop=ReferenceStrategyMode.stoppingTarget(reference,request,stopId,previousDistance)
            command=if(stop==null)driving.select(reference,request).command else stopping.select(reference,request,stop).command
            stopId=stop?.id;previousDistance=stop?.distanceMeters
            val result=TrainDynamics.step(state,profile,cars,command,9.80665,.05)
            state=result.state;position+=result.distance;moved=moved||state.speed>0.0
            if(moved&&state.speed==0.0) { println("INSTANT stopped tick=$tick x=$position");stopped=true;break }
        }
        assertTrue(stopped);assertEquals(20.0,position,.05)
    }

    @Test fun `positive speed targets reserve physical brake preparation`() {
        val fixture = ReferenceEquipmentFixture()
        val state = TrainDynamics.initialState(fixture.cars, 8.0)
        val input = fixture.input(state, VehicleControlCommand.COAST, 7.2467, .05)
        val reference = NominalMotionReference.fromCapabilities(input.capabilities)
        assertTrue(reference.preparationSeconds > 0.0)
        val immediate = NominalMotionReference(reference.operatingAccelerationMetersPerSecondSquared,
            reference.operatingDecelerationMetersPerSecondSquared,
            reference.stoppingReleaseJerkMetersPerSecondCubed)
        val target = 7.7505
        val distance = 7.2467
        val reserved = reference.at(target, distance)
        assertTrue(reserved.speedMetersPerSecond < immediate.at(target, distance).speedMetersPerSecond)
        assertTrue(reserved.speedMetersPerSecond < 8.2394)
        assertEquals(target, reference.at(target, 0.0).speedMetersPerSecond, 1e-9)
        assertEquals(immediate.at(0.0, distance), reference.at(0.0, distance))
    }

    @Test fun `strongest traction bounds the ordinary nominal stopping catalogue`() {
        for (speed in listOf(0.0, 1.0, 5.0, 9.0)) for (grade in listOf(-.02, 0.0, .02))
            for (pressure in listOf(0.0, .5, 1.0)) {
                val fixture = ReferenceEquipmentFixture(grade = grade)
                val state = TrainDynamics.initialState(fixture.cars, speed, brakeScale = pressure)
                val input = fixture.input(state, VehicleControlCommand.COAST, 5_000.0, .05)
                val ordinary = input.capabilities.handles.filter { !it.command.emergencyBrake }
                val bound = nominalStopUpperBound(input, ordinary)
                assertTrue(bound.isFinite(), "speed=$speed grade=$grade pressure=$pressure")
                ordinary.forEach { handle ->
                    val distance = NominalServiceBrakeModel.stoppingDistanceMeters(input, handle.command)
                    if (distance.isFinite())
                        assertTrue(distance <= bound + 1e-9,
                            "handle=${handle.handle} distance=$distance bound=$bound speed=$speed grade=$grade")
                }
            }
    }

    @Test fun `far zero target is beyond the nominal catalogue bound`() {
        val fixture = ReferenceEquipmentFixture()
        val settled = TrainDynamics.initialState(fixture.cars, 1.0)
        val state = settled.copy(devices = TrainDynamics.DeviceSnapshot.of(
            settled.devices.toList().map { it.copy(commandAgeSeconds = 1.0) }))
        val input = fixture.input(state, VehicleControlCommand.COAST, 5_000.0, .05)
        val reference = NominalMotionReference.fromCapabilities(input.capabilities)
        val ordinary = input.capabilities.handles.filter { !it.command.emergencyBrake }
        assertTrue(input.constraints.single().distanceMeters > nominalStopUpperBound(input, ordinary))
        val decision = DrivingReferenceStrategy().select(reference, input)
        assertEquals(OperatingPrecisionStatus.TRACKING, decision.precisionStatus)
        assertTrue(decision.command.tractionRatio > 0.0, "command=${decision.command}")
    }

    @Test fun `constant device event distances match the current kernel`() {
        var count = 0
        for (adhesion in listOf(.08, .4)) for ((jerk, upwardJerk) in listOf(.2 to .2, 1.0 to 1.0, .2 to 1.0, 1.0 to .2)) for (grade in listOf(-.01, 0.0, .01)) {
            val fixture = ReferenceEquipmentFixture(jerk, grade, electric = false, upwardJerk = upwardJerk, adhesion = adhesion)
            for (pressure in listOf(0.0, .4, 1.25)) for (follow in listOf(0.0, .125, 1.0)) {
                val devices = fixture.cars.mapIndexed { index, _ -> TrainDynamics.CarDeviceState(
                    350.0 * pressure, 0.0, 100.0 * (index + 1), .04, 0.0) }
                var state = TrainDynamics.State(2.0, -.3, pressure, TrainDynamics.DeviceSnapshot.of(devices))
                val candidate = TrainDynamics.commandForHandle(fixture.profile, "B3")
                val input = fixture.input(state, VehicleControlCommand.COAST, 50.0, .05)
                val estimate = NominalServiceBrakeModel.holdingDistanceMeters(input, candidate, VehicleControlCommand(brakeRatio = follow))
                var actual = 0.0
                var stopped = false
                for (tick in 0 until 10000) {
                    val command = if (tick == 0) candidate else VehicleControlCommand(brakeRatio = follow)
                    val result = TrainDynamics.step(state, fixture.profile, fixture.carInputs, command, 9.80665, .05)
                    state = result.state; actual += result.distance
                    if (state.speed == 0.0) { stopped = true; break }
                }
                if (stopped) assertEquals(actual, estimate, 1e-7) else assertTrue(estimate.isInfinite())
                count++
            }
        }
        assertEquals(216, count)
    }

    @Test fun `heterogeneous devices stop precisely without traction correction or terminal chatter`() {
        var count = 0
        for ((jerk, upwardJerk) in listOf(.2 to .2, 1.0 to 1.0, .2 to 1.0, 1.0 to .2)) for (grade in listOf(-.035, -.01, 0.0, .01, .035)) for (variable in listOf(false, true)) {
            val fixture = ReferenceEquipmentFixture(jerk, grade, upwardJerk = upwardJerk)
            val initial = TrainDynamics.initialState(fixture.cars, 1.0, brakeScale = 0.0)
            val reference = NominalMotionReference.fromCapabilities(fixture.input(initial, VehicleControlCommand.COAST, 80.0, .05).capabilities).forGrade(grade)!!
            var state = initial
            var command = VehicleControlCommand.COAST
            var position = 0.0
            var stoppingId: String? = null
            var previousStopDistance: Double? = null
            var enteredStop = false
            val driving = DrivingReferenceStrategy()
            val stopping = StoppingReferenceStrategy()
            var terminalSwitches = 0
            var tractionInStop = false
            for (tick in 0 until 10000) {
                val h = if (!variable) .05 else listOf(.025, .075, .1)[tick % 3]
                val input = fixture.input(state, command, 80.0 - position, h)
                val stop = ReferenceStrategyMode.stoppingTarget(reference, input, stoppingId, previousStopDistance)
                val decision = if (stop == null) {
                    if (stoppingId != null) stopping.reset()
                    driving.select(reference, input)
                } else stopping.select(reference, input, stop)
                stoppingId = stop?.id
                previousStopDistance = stop?.distanceMeters
                enteredStop = enteredStop || stop != null
                if (enteredStop && decision.command.tractionRatio > 0.0) tractionInStop = true
                if (command != decision.command && 80.0 - position <= 1.0) terminalSwitches++
                command = decision.command
                val result = TrainDynamics.step(state, fixture.profile, fixture.carInputs, command, 9.80665, h)
                state = result.state; position += result.distance
                if (state.speed == 0.0) {
                    println("REFERENCE grade=$grade jerk=$jerk/$upwardJerk variable=$variable error=${80-position} switches=$terminalSwitches")
                    assertEquals(80.0, position, .05)
                    assertTrue(terminalSwitches < 40)
                    break
                }
            }
            assertEquals(0.0, state.speed)
            assertFalse(tractionInStop)
            count++
        }
        assertEquals(40, count)
    }
}

internal class ReferenceEquipmentFixture(jerk: Double = 1.0, grade: Double = 0.0, electric: Boolean = true, upwardJerk: Double = jerk, adhesion: Double = .4) {
    val profile = object : TrainDynamics.TrainProfile {
        override val maxSpeed = 10.0
        override val powerHandles = (1..4).map { "P$it" }
        override val brakeHandles = (1..8).map { "B$it" } + "EB"
        override val brakeApplyTime = .4
        override val brakeReleaseTime = .6
        override val adhesionCoefficient = adhesion
        override val tractionJerk = upwardJerk
        override val brakeJerk = jerk
        override fun normalizedPowerScale(handle: String) = if (handle.startsWith("P")) handle.drop(1).toDouble() / 4 else 0.0
        override fun normalizedBrakeScale(handle: String) = when {
            handle == "EB" -> 1.25
            handle.startsWith("B") -> handle.drop(1).toDouble() / 8
            else -> 0.0
        }
        override fun resistanceForceAt(speed: Double) = 10.0
    }
    val cars = (0..1).map { index -> object : TrainDynamics.CarProfile {
        override val mass = 1000.0
        override val effectiveMass = 1000.0
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override val brakeMaxForce = 1600.0
        override val maximumMotorForceUpperBound = 1200.0
        override val electricBrakeMaxForce = if (electric && index == 0) 1000.0 else 0.0
        override val equipment = TrainDynamics.Equipment(commandDelaySeconds = if (index == 0) .1 else .2,
            electricBrakeApplyRateNewtonsPerSecond = 2000.0, electricBrakeReleaseRateNewtonsPerSecond = 1500.0,
            tractionApplyRateNewtonsPerSecond = 700.0, tractionReleaseRateNewtonsPerSecond = 1000.0)
        override fun motorForceAt(speed: Double) = 1200.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = defaultResistance(speed)
    } }
    val carInputs = cars.map { TrainDynamics.CarInput(it, grade) }
    private val model = VehicleControlCapabilityModel(profile, cars, 9.80665)
    fun input(state: TrainDynamics.State, command: VehicleControlCommand, distance: Double, h: Double) =
        MotionReferenceInput(VehicleControlState(state.speed, state.acceleration, state.brakeScale, command,
            state.devices.toList()), model.snapshot(state.speed, carInputs),
            listOf(MotionReferenceConstraint("station", distance, 0.0)), h, .2)
}

internal class ImmediateEquipmentFixture {
    val profile = object : TrainDynamics.TrainProfile {
        override val maxSpeed = 8.0
        override val powerHandles = listOf("P")
        override val brakeHandles = listOf("B", "EB")
        override val brakeApplyTime = 0.0
        override val brakeReleaseTime = 0.0
        override val adhesionCoefficient = 10.0
        override val tractionJerk = 0.0
        override val brakeJerk = 0.0
        override fun normalizedPowerScale(handle: String) = if (handle == "P") 1.0 else 0.0
        override fun normalizedBrakeScale(handle: String) = if (handle == "B" || handle == "EB") 1.0 else 0.0
        override fun resistanceForceAt(speed: Double) = 0.0
    }
    val car = object : TrainDynamics.CarProfile {
        override val mass = 1.0
        override val effectiveMass = 1.0
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override val brakeMaxForce = 1.0
        override val maximumMotorForceUpperBound = 1.0
        override val equipment = TrainDynamics.Equipment(commandDelaySeconds = 0.0,
            tractionApplyRateNewtonsPerSecond = 1e10, tractionReleaseRateNewtonsPerSecond = 1e10)
        override fun motorForceAt(speed: Double) = 1.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }
    val cars = listOf(TrainDynamics.CarInput(car, 0.0))
    val model = VehicleControlCapabilityModel(profile, listOf(car), 9.80665)
    fun input(state: TrainDynamics.State, command: VehicleControlCommand, distance: Double) =
        MotionReferenceInput(VehicleControlState(state.speed,state.acceleration,state.brakeScale,command,state.devices.toList()),
            model.snapshot(state.speed,cars),listOf(MotionReferenceConstraint("point",distance,0.0)),.05,.2)
}
