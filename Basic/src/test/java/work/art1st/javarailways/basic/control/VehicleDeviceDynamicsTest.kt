package work.art1st.javarailways.basic.control

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand

class VehicleDeviceDynamicsTest {
    private val profile = object : TrainDynamics.TrainProfile {
        override val maxSpeed = 30.0
        override val powerHandles = listOf("P1")
        override val brakeHandles = listOf("B1", "EB")
        override val brakeApplyTime = 1.2
        override val brakeReleaseTime = 1.8
        override val adhesionCoefficient = 0.25
        override val tractionJerk = 0.8
        override val brakeJerk = 0.8
        override fun normalizedPowerScale(handle: String) = if (handle == "P1") 1.0 else 0.0
        override fun normalizedBrakeScale(handle: String) = when (handle) { "B1" -> 1.0; "EB" -> 1.25; else -> 0.0 }
        override fun resistanceForceAt(speed: Double) = 300.0
    }
    private val car = object : TrainDynamics.CarProfile {
        override val mass = 32000.0
        override val effectiveMass = 34560.0
        override val motorAdhesionMassRatio = 1.0
        override val brakeMaxForce = 45000.0
        override val brakeAdhesionMassRatio = 1.0
        override val electricBrakeMaxForce = 35000.0
        override val equipment = TrainDynamics.Equipment()
        override val maximumMotorForceUpperBound = 50000.0
        override fun motorForceAt(speed: Double) = minOf(50000.0, 500000.0 / maxOf(speed, 0.5))
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = defaultResistance(speed)
    }

    @Test
    fun `known interval device algebra exactly equals physical kernel for all control phases`() {
        val model = VehicleControlCapabilityModel(profile, listOf(car), 9.80665)
        val inputs = listOf(TrainDynamics.CarInput(car))
        var state = TrainDynamics.initialState(listOf(car), 5.0)
        val random = java.util.Random(7301)
        val commands = listOf(VehicleControlCommand(tractionRatio = 1.0), VehicleControlCommand.COAST,
            VehicleControlCommand(brakeRatio = 0.5), VehicleControlCommand(brakeRatio = 1.0),
            VehicleControlCommand(tractionRatio = 0.25, brakeRatio = 0.4), VehicleControlCommand.EMERGENCY_BRAKE)
        repeat(600) { tick ->
            val command = commands[(tick / 30).coerceAtMost(commands.lastIndex)]
            val h = listOf(0.01, 0.05, 0.2)[random.nextInt(3)]
            val cap = model.snapshot(state.speed, inputs)
            val projected = VehicleDeviceDynamics.advanceDeviceState(cap.deviceCapabilities.single(),
                state.devices[0], command, state.speed, profile.maxSpeed, h, cap.emergencyBrakeRatio)
            val result = TrainDynamics.step(state, profile, inputs, command, 9.80665, h)
            assertEquals(result.state.devices[0], projected, "device mismatch tick=$tick h=$h command=$command")
            assertEquals(state.devices[0], VehicleDeviceDynamics.advanceDeviceState(cap.deviceCapabilities.single(),
                state.devices[0], command, state.speed, profile.maxSpeed, 0.0, cap.emergencyBrakeRatio))
            state = result.state
        }
    }

    @Test
    fun `device update matches physical speed clamping after a lower configured maximum`() {
        val inputs = listOf(TrainDynamics.CarInput(car))
        val state = TrainDynamics.initialState(listOf(car), profile.maxSpeed + 5.0)
        val cap = VehicleControlCapabilityModel(profile, listOf(car), 9.80665).snapshot(state.speed, inputs)
        val command = VehicleControlCommand(tractionRatio = 1.0, brakeRatio = 0.8)
        val projected = VehicleDeviceDynamics.advanceDeviceState(cap.deviceCapabilities.single(), state.devices[0],
            command, state.speed, profile.maxSpeed, 0.2, cap.emergencyBrakeRatio)
        val actual = TrainDynamics.step(state, profile, inputs, command, 9.80665, 0.2)
        assertEquals(actual.state.devices[0], projected)
    }

    @Test
    fun `zero interval returns identical snapshot even with instantaneous pneumatic rates`() {
        val instant = object : TrainDynamics.TrainProfile by profile {
            override val brakeApplyTime = 0.0
            override val brakeReleaseTime = 0.0
        }
        val inputs = listOf(TrainDynamics.CarInput(car))
        val cap = VehicleControlCapabilityModel(instant, listOf(car), 9.80665).snapshot(0.0, inputs)
        val state = TrainDynamics.initialState(listOf(car)).devices[0]
        assertSame(state, VehicleDeviceDynamics.advanceDeviceState(cap.deviceCapabilities.single(), state,
            VehicleControlCommand.EMERGENCY_BRAKE, 0.0, instant.maxSpeed, 0.0, cap.emergencyBrakeRatio))
    }
    @Test
    fun `blended service invariant survives real kernel variable intervals and electric fade`() {
        // Deliberately strong electric brake and slow air make the old F-Emax floor zero.
        for (ratio in listOf(0.35, 0.8, 1.0)) {
            val equipment = TrainDynamics.Equipment(commandDelaySeconds = 0.12,
                electricBrakeApplyRateNewtonsPerSecond = 120000.0,
                electricBrakeReleaseRateNewtonsPerSecond = 90000.0)
            val selectedCar = object : TrainDynamics.CarProfile by car {
                override val electricBrakeMaxForce = 80000.0
                override val equipment = equipment
            }
            val inputs = listOf(TrainDynamics.CarInput(selectedCar))
            val force = selectedCar.brakeMaxForce * ratio
            val airRate = selectedCar.brakeMaxForce / profile.brakeApplyTime
            val k = minOf(1.0, airRate / equipment.electricBrakeReleaseRateNewtonsPerSecond)
            val floor = VehicleDeviceDynamics.guaranteedSustainedServiceForceNewtons(
                selectedCar.brakeMaxForce, ratio, selectedCar.electricBrakeMaxForce,
                profile.brakeApplyTime, equipment.electricBrakeReleaseRateNewtonsPerSecond)
            assertTrue(floor > 0.0)
            val command = VehicleControlCommand(brakeRatio = ratio)
            var state = TrainDynamics.initialState(listOf(selectedCar), 20.0)
            var established = false
            repeat(1200) { tick ->
                // Arbitrary observed speed traces test the invariant, not simulated journey accuracy.
                val speed = listOf(25.0, 9.0, 1.5, 0.1, 0.0, 4.0)[(tick / 7) % 6]
                val h = listOf(0.01, 0.05, 0.19, 0.31)[tick % 4]
                val result = TrainDynamics.step(state.copy(speed = speed), profile, inputs, command, 9.80665, h)
                val device = result.state.devices[0]
                val air = selectedCar.brakeMaxForce * device.pressureKpa / equipment.nominalCylinderPressureKpa
                val barrier = air + k * minOf(device.electricBrakeForceNewtons, force)
                if (device.commandAgeSeconds >= equipment.commandDelaySeconds && barrier >= floor - 1e-8)
                    established = true
                if (established) {
                    assertTrue(barrier >= floor - 1e-8, "ratio=$ratio tick=$tick barrier=$barrier floor=$floor")
                    assertTrue(result.brakeForce >= floor - 1e-8)
                }
                state = result.state
            }
            assertTrue(established)
        }
    }

    @Test
    fun `service floor establishment waits for retained electric force above reloaded maximum`() {
        val ratio = 0.8
        val floor = VehicleDeviceDynamics.guaranteedSustainedServiceForceNewtons(car.brakeMaxForce,
            ratio, car.electricBrakeMaxForce, profile.brakeApplyTime,
            car.equipment.electricBrakeReleaseRateNewtonsPerSecond)
        val command = VehicleControlCommand(brakeRatio = ratio)
        val retained = TrainDynamics.CarDeviceState(electricBrakeForceNewtons = 150000.0,
            commandAgeSeconds = 1.0, previousDemand = -ratio)
        var state = TrainDynamics.State(speed = 5.0, devices = TrainDynamics.DeviceSnapshot.of(listOf(retained)))
        assertTrue(retained.electricBrakeForceNewtons > car.electricBrakeMaxForce)
        repeat(200) {
            state = TrainDynamics.step(state.copy(speed = 0.2), profile,
                listOf(TrainDynamics.CarInput(car)), command, 9.80665, 0.05).state
        }
        val device = state.devices[0]
        assertTrue(device.electricBrakeForceNewtons <= car.electricBrakeMaxForce)
        val actualRaw = car.brakeMaxForce * device.pressureKpa / car.equipment.nominalCylinderPressureKpa +
            device.electricBrakeForceNewtons
        assertTrue(actualRaw >= floor - 1e-8)
    }

}
