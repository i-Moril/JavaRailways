package work.art1st.javarailways.simpleato.control

import work.art1st.javarailways.basic.api.ato.VehicleDeviceState
import work.art1st.javarailways.basic.api.ato.VehicleCarControlCapabilities
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.basic.api.ato.VehicleDeviceDynamics
import work.art1st.javarailways.simpleato.control.AffineActuatorResponse.Signal
import work.art1st.javarailways.simpleato.control.AffineActuatorResponse.Segment
import work.art1st.javarailways.simpleato.control.AffineActuatorResponse.LAST
import work.art1st.javarailways.simpleato.control.AffineActuatorResponse.combine
import work.art1st.javarailways.simpleato.control.AffineActuatorResponse.clamp
import work.art1st.javarailways.simpleato.control.AffineActuatorResponse.slew
import work.art1st.javarailways.simpleato.control.AffineActuatorResponse.constant
import work.art1st.javarailways.simpleato.control.AffineActuatorResponse.distanceToRest
import kotlin.math.*

/**
 * Ordinary operating estimate, never a safety certificate. One known current interval uses
 * Basic's shared device algebra. Subsequent response is a finite affine device-event model,
 * freezing speed-dependent electric demand and resistance. Missing state returns NaN rather
 * than substituting an aggregate-pressure vehicle. Infinity means this release never stops.
 */
object NominalServiceBrakeModel {
    fun stoppingDistanceMeters(input: MotionReferenceInput, candidate: VehicleControlCommand): Double =
        distance(input, candidate, input.capabilities.maximumServiceBrakeRatio)

    fun coastingDistanceMeters(input: MotionReferenceInput, candidate: VehicleControlCommand): Double =
        distance(input, candidate, 0.0)

    fun holdingDistanceMeters(input: MotionReferenceInput, candidate: VehicleControlCommand,
        holdingCommand: VehicleControlCommand): Double {
        require(!holdingCommand.emergencyBrake && holdingCommand.tractionRatio == 0.0)
        return distance(input, candidate, holdingCommand.brakeRatio)
    }

    private fun distance(input: MotionReferenceInput, candidate: VehicleControlCommand, followRatio: Double): Double {
        require(!candidate.emergencyBrake) { "nominal response accepts ordinary controls only" }
        val caps = input.capabilities
        if (caps.deviceCapabilities.isEmpty() || caps.deviceCapabilities.size != input.state.devices.size) return Double.NaN
        val h = input.deltaSeconds
        val mass = caps.deviceCapabilities.sumOf { it.effectiveMassKilograms }
        val coast = caps.handles.firstOrNull { it.command.tractionRatio == 0.0 && it.command.brakeRatio == 0.0 }
            ?.steadyAccelerationMetersPerSecondSquared ?: return Double.NaN
        val speed = input.state.speedMetersPerSecond
        val after = caps.deviceCapabilities.mapIndexed { i, car ->
            VehicleDeviceDynamics.advanceDeviceState(car, input.state.devices[i], candidate,
                speed, caps.maximumSpeedMetersPerSecond, h, caps.emergencyBrakeRatio)
        }
        val force = after.indices.sumOf { i ->
            val car = caps.deviceCapabilities[i]
            val device = after[i]
            min(device.tractionForceNewtons, car.motorAdhesionForceNewtons) -
                min(car.brakeAdhesionForceNewtons, device.electricBrakeForceNewtons +
                    car.brakeMaxForceNewtons * device.pressureKpa / car.nominalCylinderPressureKpa)
        }
        val target = if (speed == 0.0) max(0.0, coast + force / mass) else coast + force / mass
        val jerk = if (target < input.state.accelerationMetersPerSecondSquared)
            caps.brakeJerkMetersPerSecondCubed else caps.tractionJerkMetersPerSecondCubed
        val change = if (jerk == 0.0) Double.POSITIVE_INFINITY else jerk * h
        val acceleration = input.state.accelerationMetersPerSecondSquared +
            (target - input.state.accelerationMetersPerSecondSquared).coerceIn(-change, change)
        val nextSpeed = (speed + acceleration * h).coerceIn(0.0, caps.maximumSpeedMetersPerSecond)
        val firstDistance = (speed + nextSpeed) * h / 2.0
        if (nextSpeed == 0.0) return firstDistance
        val forces = caps.deviceCapabilities.flatMapIndexed { i, car ->
            val (traction, brake) = response(car, after[i], followRatio, speed, h)
            listOf(traction, brake)
        }
        val forceTarget = combine(forces) { values -> coast + values.withIndex().sumOf {
            (i, forceAtEvent) -> if (i % 2 == 0) forceAtEvent else -forceAtEvent
        } / mass }
        val accelerationProfile = slew(forceTarget, (nextSpeed - speed) / h,
            if (caps.tractionJerkMetersPerSecondCubed == 0.0) Double.POSITIVE_INFINITY else caps.tractionJerkMetersPerSecondCubed * h,
            if (caps.brakeJerkMetersPerSecondCubed == 0.0) Double.POSITIVE_INFINITY else caps.brakeJerkMetersPerSecondCubed * h)
        return firstDistance + distanceToRest(accelerationProfile, nextSpeed, h)
    }

    private fun response(car: VehicleCarControlCapabilities, device: VehicleDeviceState,
        ratio: Double, speed: Double, h: Double): Pair<Signal, Signal> {
        val changed = device.previousTractionRatio != 0.0 || device.previousBrakeRatio != ratio || device.previousEmergencyBrake
        val remainingDelay = if (changed) car.commandDelaySeconds else car.commandDelaySeconds - device.commandAgeSeconds - h
        val enabledTick = 1 + ceil(max(0.0, remainingDelay) / h).toLong()
        val electricTarget = min(car.electricBrakeMaxForceNewtons, car.electricBrakeMaxPowerWatts / max(speed, 0.5)) *
            min(1.0, speed / car.electricBrakeFadeSpeedMetersPerSecond) * min(ratio, 1.0)
        val electricDemand = Signal(buildList {
            if (enabledTick > 1) add(Segment(1, enabledTick - 1, 0.0))
            add(Segment(enabledTick, LAST, electricTarget))
        })
        val electric = slew(electricDemand, device.electricBrakeForceNewtons,
            car.electricBrakeApplyRateNewtonsPerSecond * h, car.electricBrakeReleaseRateNewtonsPerSecond * h)
        val pressureDemand = Signal(buildList {
            if (enabledTick > 1) add(Segment(1, enabledTick - 1, device.pressureKpa))
            for (segment in electric.segments) {
                val start = max(enabledTick, segment.start)
                if (start <= segment.end) add(if (car.brakeMaxForceNewtons == 0.0) Segment(start, segment.end, 0.0)
                    else Segment(start, segment.end,
                        (ratio - segment.at(start) / car.brakeMaxForceNewtons) * car.nominalCylinderPressureKpa,
                        -segment.slope * car.nominalCylinderPressureKpa / car.brakeMaxForceNewtons))
            }
        })
        val pressure = slew(clamp(pressureDemand, 0.0, Double.POSITIVE_INFINITY), device.pressureKpa,
            car.airApplyRateKpaPerSecond * h, car.airReleaseRateKpaPerSecond * h)
        val brake = clamp(combine(listOf(electric, pressure)) {
            it[0] + it[1] * car.brakeMaxForceNewtons / car.nominalCylinderPressureKpa
        }, 0.0, car.brakeAdhesionForceNewtons)
        val traction = clamp(slew(constant(0.0), device.tractionForceNewtons,
            car.tractionApplyRateNewtonsPerSecond * h, car.tractionReleaseRateNewtonsPerSecond * h),
            0.0, car.motorAdhesionForceNewtons)
        return traction to brake
    }
}
