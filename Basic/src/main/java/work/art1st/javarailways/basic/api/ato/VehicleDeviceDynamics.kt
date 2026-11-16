package work.art1st.javarailways.basic.api.ato

import kotlin.math.max
import kotlin.math.min

/**
 * Pure device algebra shared by first-party physics and control modules; this is not an
 * independently supported third-party extension API. Calls neither mutate their inputs nor
 * access a live train, and perform no speed/distance integration or future-tick loop.
 */
object VehicleDeviceDynamics {
    /**
     * Advances exactly one known interval using the supplied per-car capabilities and device state
     * from the same captured consist. Speeds are in m/s, duration in s and brake strength is a ratio.
     * The supplied speed is held fixed, capped at the maximum speed; motor force is the captured
     * capability value. This does not predict speed changes over a future braking trajectory.
     *
     * @return a new device value, or the exact input [device] when [deltaSeconds] is zero.
     * @throws IllegalArgumentException if duration, either speed or emergency ratio is negative
     * or non-finite, including for a zero-duration call.
     */
    fun advanceDeviceState(
        capabilities: VehicleCarControlCapabilities,
        device: VehicleDeviceState,
        command: VehicleControlCommand,
        speedMetersPerSecond: Double,
        maximumSpeedMetersPerSecond: Double,
        deltaSeconds: Double,
        emergencyBrakeRatio: Double
    ): VehicleDeviceState {
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
            VehicleDeviceState(pressure, electric, traction, age,
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
     *
     * @return the sustained raw force floor in N; zero demand returns zero.
     * @throws IllegalArgumentException if force, ratio or apply time is negative/non-finite,
     * release rate is non-positive/non-finite, or the requested pneumatic force overflows.
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
