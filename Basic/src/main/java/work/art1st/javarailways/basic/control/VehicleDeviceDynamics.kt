package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.VehicleCarControlCapabilities
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import kotlin.math.max
import kotlin.math.min

/** Immutable equipment coefficients, shared by actual car profiles and captured control capabilities. */
interface VehicleDeviceEquipment {
    val nominalCylinderPressureKpa: Double
    val commandDelaySeconds: Double
    val electricBrakeMaxPowerWatts: Double
    val electricBrakeFadeSpeedMetersPerSecond: Double
    val electricBrakeApplyRateNewtonsPerSecond: Double
    val electricBrakeReleaseRateNewtonsPerSecond: Double
    val tractionApplyRateNewtonsPerSecond: Double
    val tractionReleaseRateNewtonsPerSecond: Double
    val tractionReleasePressureKpa: Double
    val electricReleaseForceThresholdNewtons: Double
}

/** Exactly one known interval of device algebra; no speed/distance integration or future-tick loop. */
object VehicleDeviceDynamics {
    fun advanceDeviceState(
        capabilities: VehicleCarControlCapabilities,
        device: TrainDynamics.CarDeviceState,
        command: VehicleControlCommand,
        speedMetersPerSecond: Double,
        maximumSpeedMetersPerSecond: Double,
        deltaSeconds: Double,
        emergencyBrakeRatio: Double
    ): TrainDynamics.CarDeviceState {
        require(deltaSeconds.isFinite() && deltaSeconds >= 0.0)
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond >= 0.0)
        require(emergencyBrakeRatio.isFinite() && emergencyBrakeRatio >= 0.0)
        if (deltaSeconds == 0.0) return device
        val effectiveSpeed = speedMetersPerSecond.coerceAtMost(maximumSpeedMetersPerSecond)
        val power = if (command.emergencyBrake) 0.0 else command.tractionRatio
        val brake = if (command.emergencyBrake) emergencyBrakeRatio else command.brakeRatio
        return advance(device.pressureKpa, device.electricBrakeForceNewtons, device.tractionForceNewtons,
            device.commandAgeSeconds, device.previousTractionRatio, device.previousBrakeRatio,
            device.previousEmergencyBrake, power, brake, command.emergencyBrake, capabilities,
            capabilities.electricBrakeMaxForceNewtons, capabilities.brakeMaxForceNewtons,
            { capabilities.motorForceAtCurrentSpeedNewtons }, capabilities.motorAdhesionForceNewtons,
            capabilities.airApplyRateKpaPerSecond, capabilities.airReleaseRateKpaPerSecond,
            effectiveSpeed, maximumSpeedMetersPerSecond, deltaSeconds) { pressure, electric, traction, age ->
            TrainDynamics.CarDeviceState(pressure, electric, traction, age,
                if (brake > 0.0) -brake else power, power, brake, command.emergencyBrake)
        }
    }

    /**
     * Raw sustained force after a constant ordinary brake demand has finished its command delay
     * and established A + k*min(E,F) >= L. Here F=B*r, A is pneumatic force, k=min(1,Ra/Re).
     * Electric release removes at most Re*h; pneumatic apply can restore k*Re*h <= Ra*h.
     * If air releases instead, A_new >= F-E_new still implies the invariant for E_new<=min(Emax,F).
     * This is not an instantaneous force claim. Before establishment, or while a retained old
     * electric force exceeds a reloaded Emax, the response owner must bound that separate phase.
     * Adhesion must be applied by the caller to the returned raw force.
     */
    fun guaranteedSustainedServiceForceNewtons(
        pneumaticMaxForceNewtons: Double,
        requestedBrakeRatio: Double,
        electricMaxForceNewtons: Double,
        pneumaticApplyTimeSeconds: Double,
        electricReleaseRateNewtonsPerSecond: Double
    ): Double {
        require(pneumaticMaxForceNewtons.isFinite() && pneumaticMaxForceNewtons >= 0.0)
        require(requestedBrakeRatio.isFinite() && requestedBrakeRatio >= 0.0)
        require(electricMaxForceNewtons.isFinite() && electricMaxForceNewtons >= 0.0)
        require(pneumaticApplyTimeSeconds.isFinite() && pneumaticApplyTimeSeconds >= 0.0)
        require(electricReleaseRateNewtonsPerSecond.isFinite() && electricReleaseRateNewtonsPerSecond > 0.0)
        val demand = pneumaticMaxForceNewtons * requestedBrakeRatio
        require(demand.isFinite())
        if (demand == 0.0) return 0.0
        val fraction = if (pneumaticApplyTimeSeconds == 0.0) 1.0 else minOf(1.0,
            (pneumaticMaxForceNewtons / pneumaticApplyTimeSeconds) / electricReleaseRateNewtonsPerSecond)
        return (demand - (1.0 - fraction) * minOf(electricMaxForceNewtons, demand)).coerceAtLeast(0.0)
    }

    /** Inlined sink lets actual physics write its owned arrays without allocating per-car state objects. */
    internal inline fun <T> advance(
        pressure: Double, electric: Double, traction: Double, commandAge: Double,
        previousPower: Double, previousBrake: Double, previousEmergency: Boolean,
        power: Double, brake: Double, emergency: Boolean, equipment: VehicleDeviceEquipment,
        electricMaxForce: Double, pneumaticMaxForce: Double, motorForce: () -> Double, motorAdhesion: Double,
        airApplyRate: Double, airReleaseRate: Double, speed: Double, maximumSpeed: Double, delta: Double,
        emit: (pressure: Double, electric: Double, traction: Double, commandAge: Double) -> T
    ): T {
        if (delta == 0.0) return emit(pressure, electric, traction, commandAge)
        val changed = power != previousPower || brake != previousBrake || emergency != previousEmergency
        val age = if (changed) 0.0 else commandAge + delta
        val enabled = age >= equipment.commandDelaySeconds
        val electricTarget = if (enabled && brake > 0.0 && !emergency) {
            min(electricMaxForce, equipment.electricBrakeMaxPowerWatts / max(speed, 0.5)) *
                min(1.0, speed / equipment.electricBrakeFadeSpeedMetersPerSecond) * min(brake, 1.0)
        } else 0.0
        val electricRate = if (electricTarget > electric) equipment.electricBrakeApplyRateNewtonsPerSecond
            else equipment.electricBrakeReleaseRateNewtonsPerSecond
        val newElectric = electric + (electricTarget - electric).coerceIn(-electricRate * delta, electricRate * delta)
        val pressureTarget = if (enabled) {
            if (pneumaticMaxForce > 0.0) ((pneumaticMaxForce * brake - newElectric).coerceAtLeast(0.0) /
                pneumaticMaxForce) * equipment.nominalCylinderPressureKpa else 0.0
        } else pressure
        val airRate = if (pressureTarget > pressure) airApplyRate else airReleaseRate
        val newPressure = if (airRate == Double.POSITIVE_INFINITY) pressureTarget else
            pressure + (pressureTarget - pressure).coerceIn(-airRate * delta, airRate * delta)
        val targetTraction = if (enabled && power > 0.0 && speed < maximumSpeed &&
            newPressure <= equipment.tractionReleasePressureKpa &&
            newElectric <= equipment.electricReleaseForceThresholdNewtons)
            (motorForce() * power).coerceIn(0.0, motorAdhesion) else 0.0
        val tractionRate = if (targetTraction > traction) equipment.tractionApplyRateNewtonsPerSecond
            else equipment.tractionReleaseRateNewtonsPerSecond
        val newTraction = traction + (targetTraction - traction).coerceIn(-tractionRate * delta, tractionRate * delta)
        return emit(newPressure, newElectric, newTraction, age)
    }
}
