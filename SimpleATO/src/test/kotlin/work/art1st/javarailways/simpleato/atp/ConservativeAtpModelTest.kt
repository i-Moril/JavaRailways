package work.art1st.javarailways.simpleato.atp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.control.*
import work.art1st.javarailways.simpleato.api.atp.*
import work.art1st.javarailways.simpleato.control.ReferenceEquipmentFixture

class ConservativeAtpModelTest {
    @Test fun `per car emergency certificate bounds actual variable step dynamics`() {
        var cases = 0
        for (jerk in listOf(0.0, .2, 2.0)) for (grade in listOf(-.035, 0.0, .035))
            for (speed in listOf(.01, .2, 2.0, 8.0)) for (pressure in listOf(0.0, .5, 1.25))
                for (variable in listOf(false, true)) for (service in listOf(false, true))
                    for (initialAcceleration in listOf(-.4, .4)) for (establishing in listOf(false, true)) {
                    val fixture = ReferenceEquipmentFixture(jerk, grade)
                    val devices = fixture.cars.mapIndexed { index, _ -> VehicleDeviceState(
                        350.0 * pressure, if (index == 0) 1200.0 else 0.0, if (pressure == 0.0) 1700.0 else 0.0,
                        if (establishing) .3 else .05, previousTractionRatio = 0.0,
                        previousBrakeRatio = if (establishing) 1.25 else .5, previousEmergencyBrake = establishing) }
                    var physical = TrainDynamics.State(speed, initialAcceleration, pressure, TrainDynamics.DeviceSnapshot.of(devices))
                    val input = fixture.input(physical, VehicleControlCommand.COAST, 1e6, .05)
                    val model = ConservativeAtpModel(input.capabilities)
                    val judgement = if (service) model.service(input.state, listOf(AtpTarget("end", -1.0, 0.0)), grade, .2)
                        else model.emergency(input.state, listOf(AtpTarget("end", -1.0, 0.0)), grade, .2)
                    val estimate = judgement.witness?.requiredDistanceMeters
                        ?: error("no estimate: $judgement")
                    var actual = 0.0
                    var restTicks = 0
                    for (tick in 0 until 10000) {
                        run {
                            val h = if (variable) listOf(.025, .2, .075, .1)[tick % 4] else .05
                            val result = TrainDynamics.step(physical, fixture.profile, fixture.carInputs,
                                if (service) VehicleControlCommand(brakeRatio = 1.0) else VehicleControlCommand.EMERGENCY_BRAKE, 9.80665, h)
                            physical = result.state; actual += result.distance
                            restTicks = if (physical.speed == 0.0) restTicks + 1 else 0
                        }
                        // Continue after first rest: a downhill train can restart while its delayed brake is building.
                        if (restTicks >= 40 && tick >= 100) break
                    }
                    assertEquals(0.0, physical.speed)
                    assertTrue(estimate + 1e-10 >= actual,
                        "underestimate $estimate < $actual jerk=$jerk grade=$grade v=$speed pressure=$pressure variable=$variable service=$service a=$initialAcceleration establishing=$establishing $judgement")
                    cases++
                }
        assertEquals(1728, cases)
    }

    @Test fun `known candidate interval plus recovery bounds actual heterogeneous dynamics`() {
        var cases = 0
        for (jerk in listOf(0.0, .2)) for (grade in listOf(-.035, 0.0, .035))
            for (speed in listOf(.01, 2.0, 8.0)) for (variable in listOf(false, true))
                for (service in listOf(false, true)) for (candidate in listOf(VehicleControlCommand.COAST,
                    VehicleControlCommand(tractionRatio = .5), VehicleControlCommand(brakeRatio = .5))) {
                    val fixture = ReferenceEquipmentFixture(jerk, grade)
                    val devices = fixture.cars.mapIndexed { index, _ -> VehicleDeviceState(
                        87.5 * (index + 1), if (index == 0) 1200.0 else 0.0, 700.0 * (index + 1),
                        .15, previousBrakeRatio = .5) }
                    var physical = TrainDynamics.State(speed, .3, .5, TrainDynamics.DeviceSnapshot.of(devices))
                    val h = if (variable) .2 else .025
                    val input = fixture.input(physical, VehicleControlCommand(brakeRatio = .5), 100.0, h)
                    val judgement = ConservativeAtpModel(input.capabilities).afterCandidate(input.state, candidate,
                        h, listOf(AtpTarget("point", -1.0, 0.0)), grade, .2, serviceRecovery = service,
                        currentCapabilities = input.capabilities)
                    val estimate = judgement.witness?.requiredDistanceMeters ?: error("no certificate: $judgement")
                    var actual = 0.0; var restTicks = 0
                    for (tick in 0 until 10000) {
                        val nextCommand = if (tick == 0) candidate else if (service)
                            VehicleControlCommand(brakeRatio = 1.0) else VehicleControlCommand.EMERGENCY_BRAKE
                        val dt = if (tick == 0) h else if (variable) listOf(.025, .2, .075, .1)[tick % 4] else .05
                        val result = TrainDynamics.step(physical, fixture.profile, fixture.carInputs, nextCommand, 9.80665, dt)
                        physical = result.state; actual += result.distance
                        restTicks = if (physical.speed == 0.0) restTicks + 1 else 0
                        if (restTicks >= 40 && tick >= 100) break
                    }
                    assertEquals(0.0, physical.speed)
                    assertTrue(estimate + 1e-10 >= actual,
                        "candidate underestimate $estimate<$actual v=$speed grade=$grade J=$jerk variable=$variable service=$service candidate=$candidate $judgement")
                    cases++
                }
        assertEquals(216, cases)
    }

    @Test fun `candidate keeps ongoing speed limits and supervises crossed future points`() {
        val fixture = ReferenceEquipmentFixture()
        val input = fixture.input(TrainDynamics.initialState(fixture.cars, 1.0), VehicleControlCommand.COAST, 50.0, .05)
        val model = ConservativeAtpModel(input.capabilities)
        val current = AtpTarget("current", 0.0, 10.0)
        assertEquals(AtpStatus.SAFE, model.afterCandidate(input.state, VehicleControlCommand.COAST,
            .05, listOf(current), 0.0, .2).status)
        assertEquals(AtpStatus.SAFE, model.afterCandidate(input.state, VehicleControlCommand.COAST,
            .05, listOf(current.copy(distanceMeters = .001)), 0.0, .2).status)
        val crossed = model.afterCandidate(input.state, VehicleControlCommand.COAST,
            .05, listOf(AtpTarget("crossed", .001, .5)), 0.0, .2)
        assertEquals(AtpStatus.INTERVENTION, crossed.status)
        assertEquals(AtpReason.CURRENT_OVERSPEED, crossed.reason)
    }

    @Test fun `nearby positive speed point does not require completing brake buildup`() {
        val fixture = ReferenceEquipmentFixture()
        val resting = fixture.input(TrainDynamics.initialState(fixture.cars, 0.0), VehicleControlCommand.COAST, 50.0, .05)
        val target = AtpTarget("creep", .0045, .2)
        val power = VehicleControlCommand(tractionRatio = 1.0)
        val model = ConservativeAtpModel(resting.capabilities)
        assertEquals(AtpStatus.SAFE, model.afterCandidate(resting.state, power, .05,
            listOf(target), 0.0, .2).status)
        val moving = resting.state.copy(speedMetersPerSecond = .19,
            accelerationMetersPerSecondSquared = 1.2, devices = resting.state.devices.map {
                it.copy(tractionForceNewtons = 1200.0, commandAgeSeconds = 1.0, previousTractionRatio = 1.0)
            })
        assertEquals(AtpStatus.INTERVENTION, model.afterCandidate(moving, power, .05,
            listOf(target), 0.0, .2).status)
    }

    @Test fun `established air brake holds at rest without restarting preparation distance`() {
        val fixture = ReferenceEquipmentFixture(.2, -.035)
        val initial = TrainDynamics.initialState(fixture.cars, 0.0, brakeScale = 1.0)
        val input = fixture.input(initial, VehicleControlCommand(brakeRatio = 1.0), 0.0, .05)
        val target = AtpTarget("hold", 0.0, 0.0)
        val model = ConservativeAtpModel(input.capabilities)
        assertEquals(AtpStatus.SAFE, model.emergency(input.state, listOf(target), -.035, .2).status)
        assertEquals(AtpStatus.SAFE, model.afterCandidate(input.state, VehicleControlCommand(brakeRatio = 1.0),
            .05, listOf(target), -.035, .2, serviceRecovery = true).status)
    }

    @Test fun `service transition reserve disappears with established pneumatic force`() {
        val fixture = ReferenceEquipmentFixture(.2, .01)
        val state = TrainDynamics.State(2.336735, -1.0980665, .5, TrainDynamics.DeviceSnapshot.of(listOf(
            VehicleDeviceState(87.805, 398.606, 0.0, .4, previousBrakeRatio = .5),
            VehicleDeviceState(175.0, 0.0, 0.0, .4, previousBrakeRatio = .5))))
        val input = fixture.input(state, VehicleControlCommand(brakeRatio = .5), 2.682209, .05)
        val model = ConservativeAtpModel(input.capabilities)
        val candidate = VehicleControlCommand(brakeRatio = 1.0)
        val partial = model.afterCandidate(input.state, candidate, .05,
            listOf(AtpTarget("authority", 2.682209, 0.0)), .01, .2, serviceRecovery = true, planningReserve = true)
        assertEquals(AtpStatus.INTERVENTION, partial.status, partial.toString())
        val established = input.state.copy(devices = input.state.devices.map {
            it.copy(pressureKpa = 350.0, electricBrakeForceNewtons = 0.0,
                previousBrakeRatio = 1.0, commandAgeSeconds = 1.0)
        })
        val available = model.service(established, listOf(AtpTarget("authority", 2.682209, 0.0)), .01, .2)
        assertEquals(AtpStatus.SAFE, available.status)
        assertTrue(available.response!!.preparationSeconds < partial.response!!.preparationSeconds)
    }

    @Test fun `candidate prefix uses this frame motor coefficient with cached constant brake model`() {
        val fixture = ReferenceEquipmentFixture(2.0)
        val state = TrainDynamics.State(2.0, 1.5, 0.0, TrainDynamics.DeviceSnapshot.of(
            fixture.cars.map { VehicleDeviceState(0.0, 0.0, 1500.0, 1.0,
                previousTractionRatio = 1.0) }))
        val input = fixture.input(state, VehicleControlCommand(tractionRatio = 1.0), 100.0, .2)
        val fresh = input.capabilities.copy(deviceCapabilities = input.capabilities.deviceCapabilities.map {
            it.copy(motorForceAtCurrentSpeedNewtons = 2000.0)
        })
        val target = listOf(AtpTarget("point", -1.0, 0.0))
        val cached = ConservativeAtpModel(input.capabilities).afterCandidate(input.state,
            VehicleControlCommand(tractionRatio = 1.0), .2, target, 0.0, .2, currentCapabilities = fresh)
        val rebuilt = ConservativeAtpModel(fresh).afterCandidate(input.state,
            VehicleControlCommand(tractionRatio = 1.0), .2, target, 0.0, .2, currentCapabilities = fresh)
        assertEquals(rebuilt.witness!!.requiredDistanceMeters, cached.witness!!.requiredDistanceMeters)
        assertEquals(AtpStatus.UNAVAILABLE, ConservativeAtpModel(input.capabilities).afterCandidate(input.state,
            VehicleControlCommand.COAST, .2, target, 0.0, .2,
            currentCapabilities = fresh.copy(configurationIdentity = Any())).status)
    }

    @Test fun `invalid device model fails explicitly without throwing or producing safe NaN`() {
        val fixture = ReferenceEquipmentFixture()
        val input = fixture.input(TrainDynamics.initialState(fixture.cars, 0.0), VehicleControlCommand.COAST, 10.0, .05)
        val invalid = input.capabilities.copy(deviceCapabilities = input.capabilities.deviceCapabilities.map {
            it.copy(airApplyRateKpaPerSecond = 0.0)
        })
        assertEquals(AtpStatus.UNAVAILABLE, ConservativeAtpModel(invalid).emergency(input.state,
            emptyList(), 0.0, .2).status)
    }

    @Test fun `discrete stop corner uses attainable force instead of unused adhesion capacity`() {
        val fixture = ReferenceEquipmentFixture(0.0, electric = false)
        val profile = object : TrainDynamics.TrainProfile by fixture.profile {
            override val adhesionCoefficient = 10.0
            override val brakeApplyTime = 0.0
            override val brakeReleaseTime = 0.0
            override fun resistanceForceAt(speed: Double) = 0.0
        }
        val car = object : TrainDynamics.CarProfile by fixture.cars.first() {
            override val mass = 1.0
            override val effectiveMass = 1.0
            override val brakeMaxForce = 1.0
            override val maximumMotorForceUpperBound = 1.0
            override val equipment = TrainDynamics.Equipment(commandDelaySeconds = 0.0)
            override fun motorForceAt(speed: Double) = 1.0
        }
        val cars = listOf(car)
        val carInputs = listOf(TrainDynamics.CarInput(car, 0.0))
        var state = TrainDynamics.initialState(cars, 1.0)
        val caps = VehicleControlCapabilityModel(profile, cars, 9.80665).snapshot(1.0, carInputs)
        val control = VehicleControlState(state.speed, state.acceleration, state.brakeScale,
            VehicleControlCommand.COAST, state.devices.toList())
        val judgement = ConservativeAtpModel(caps).emergency(control,
            listOf(AtpTarget("stop", -1.0, 0.0)), 0.0, .2)
        val estimate = judgement.witness!!.requiredDistanceMeters
        var actual = 0.0
        repeat(100) {
            val result = TrainDynamics.step(state, profile, carInputs, VehicleControlCommand.EMERGENCY_BRAKE, 9.80665, .2)
            state = result.state; actual += result.distance
        }
        assertTrue(estimate >= actual)
        assertTrue(estimate < .41, "unused 98 N adhesion must not masquerade as 98 N actual brake: $estimate")
    }

    @Test fun `missing device snapshot is explicit unavailable even without targets`() {
        val fixture = ReferenceEquipmentFixture()
        val input = fixture.input(TrainDynamics.initialState(fixture.cars, 0.0), VehicleControlCommand.COAST, 10.0, .05)
        assertEquals(AtpStatus.UNAVAILABLE, ConservativeAtpModel(input.capabilities).emergency(
            input.state.copy(devices = emptyList()), emptyList(), 0.0, .2).status)
    }

    @Test fun `grade coefficient never rounds toward weaker downhill`() {
        val table = GradeBrakeTable(1.3, 9.80665)
        for (index in 0..4000) {
            val grade = -.1 + index * .00005
            val expected = 100.0 / (2.0 * (1.3 + 9.80665 * grade))
            assertTrue(table.distance(10.0, 0.0, grade) >= expected)
        }
    }
}
