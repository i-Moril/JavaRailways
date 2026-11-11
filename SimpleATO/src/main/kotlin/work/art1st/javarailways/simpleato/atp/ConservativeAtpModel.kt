package work.art1st.javarailways.simpleato.atp

import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.control.VehicleDeviceDynamics
import work.art1st.javarailways.simpleato.api.atp.*
import kotlin.math.*

/** Immutable consist model. Runtime queries contain bounded per-car algebra, never future tick integration. */
class ConservativeAtpModel(val capabilities: VehicleControlCapabilities) {
    private val cars = capabilities.deviceCapabilities
    private val mass = cars.sumOf { it.effectiveMassKilograms }
    private val gravity = capabilities.gravityMetersPerSecondSquared * cars.sumOf { it.massKilograms } / mass
    private val emergencyFlat = cars.sumOf {
        min(it.brakeMaxForceNewtons * capabilities.emergencyBrakeRatio, it.brakeAdhesionForceNewtons)
    } / mass
    private val serviceForces = cars.map { car ->
        runCatching { VehicleDeviceDynamics.guaranteedSustainedServiceForceNewtons(car.brakeMaxForceNewtons,
            capabilities.maximumServiceBrakeRatio, car.electricBrakeMaxForceNewtons,
            if (car.airApplyRateKpaPerSecond.isInfinite()) 0.0 else
                car.nominalCylinderPressureKpa / car.airApplyRateKpaPerSecond,
            car.electricBrakeReleaseRateNewtonsPerSecond) }.getOrDefault(Double.NaN)
    }
    private val serviceFlat = cars.indices.sumOf { min(serviceForces[it], cars[it].brakeAdhesionForceNewtons) } / mass
    private val emergencyTable = GradeBrakeTable(emergencyFlat, gravity)
    private val serviceTable = GradeBrakeTable(serviceFlat, gravity)
    private val invalid = when {
        cars.isEmpty() -> "missing per-car capabilities"
        cars.any { car ->
            listOf(car.massKilograms, car.effectiveMassKilograms, car.nominalCylinderPressureKpa,
                car.electricBrakeFadeSpeedMetersPerSecond, car.electricBrakeApplyRateNewtonsPerSecond,
                car.electricBrakeReleaseRateNewtonsPerSecond, car.tractionApplyRateNewtonsPerSecond,
                car.tractionReleaseRateNewtonsPerSecond).any { !it.isFinite() || it <= 0.0 } ||
                listOf(car.brakeMaxForceNewtons, car.electricBrakeMaxForceNewtons, car.motorAdhesionForceNewtons,
                    car.brakeAdhesionForceNewtons, car.commandDelaySeconds, car.electricBrakeMaxPowerWatts,
                    car.tractionReleasePressureKpa, car.electricReleaseForceThresholdNewtons,
                    car.motorForceAtCurrentSpeedNewtons).any { !it.isFinite() || it < 0.0 } ||
                listOf(car.airApplyRateKpaPerSecond, car.airReleaseRateKpaPerSecond).any { it.isNaN() || it <= 0.0 }
        } -> "invalid per-car capability"
        !capabilities.maximumServiceBrakeRatio.isFinite() || capabilities.maximumServiceBrakeRatio <= 0.0 ||
            !capabilities.emergencyBrakeRatio.isFinite() || capabilities.emergencyBrakeRatio <= 0.0 -> "invalid brake demands"
        !mass.isFinite() || mass <= 0.0 -> "invalid effective mass"
        !capabilities.maximumSpeedMetersPerSecond.isFinite() || capabilities.maximumSpeedMetersPerSecond <= 0.0 -> "invalid speed domain"
        !emergencyFlat.isFinite() || emergencyFlat <= 0.0 -> "no emergency pneumatic guarantee"
        !gravity.isFinite() || gravity < 0.0 -> "invalid gravity"
        !capabilities.brakeJerkMetersPerSecondCubed.isFinite() || capabilities.brakeJerkMetersPerSecondCubed < 0.0 ||
            !capabilities.tractionJerkMetersPerSecondCubed.isFinite() || capabilities.tractionJerkMetersPerSecondCubed < 0.0 -> "invalid jerk"
        else -> null
    }

    fun emergency(state: VehicleControlState, targets: List<AtpTarget>, grade: Double,
        maximumDeltaSeconds: Double): AtpJudgement = check(state, targets, grade, maximumDeltaSeconds, false, false)

    /** Sustained maximum ordinary service is a recovery certificate, never an ATP actuation. */
    fun service(state: VehicleControlState, targets: List<AtpTarget>, grade: Double,
        maximumDeltaSeconds: Double): AtpJudgement = check(state, targets, grade, maximumDeltaSeconds, true, false)

    /** Exactly the known current actuation interval, followed by an immediate emergency command. */
    fun afterCandidate(state: VehicleControlState, command: VehicleControlCommand, deltaSeconds: Double,
        targets: List<AtpTarget>, grade: Double, maximumDeltaSeconds: Double,
        serviceRecovery: Boolean = false,
        currentCapabilities: VehicleControlCapabilities = capabilities, planningReserve: Boolean = false): AtpJudgement {
        validation(state, grade, maximumDeltaSeconds)?.let { return it }
        if (currentCapabilities.configurationIdentity !== capabilities.configurationIdentity ||
            currentCapabilities.deviceCapabilities.size != cars.size)
            return unavailable(AtpReason.INVALID_MODEL, "stale candidate capability snapshot")
        val maximumPower = capabilities.handles.maxOfOrNull { it.command.tractionRatio } ?: 0.0
        if (!command.emergencyBrake && (command.tractionRatio > maximumPower ||
                command.brakeRatio > capabilities.maximumServiceBrakeRatio))
            return unavailable(AtpReason.OUTSIDE_MODEL_DOMAIN, "candidate exceeds declared physical demand range")
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0.0 || deltaSeconds > maximumDeltaSeconds)
            return unavailable(AtpReason.INVALID_STATE, "invalid known interval")
        val prefix = prefix(state, command, deltaSeconds, grade, currentCapabilities.deviceCapabilities)
        // The prefix state has already been validated through its source state and the shared
        // device algebra. Shift target distances in place instead of allocating a candidate
        // list and copying every target for each handle.
        return check(prefix.first, targets, grade, maximumDeltaSeconds, serviceRecovery,
            planningReserve, distanceShift = prefix.second, stateValidated = true,
            prefixPeakSpeed = max(state.speedMetersPerSecond, prefix.first.speedMetersPerSecond))
    }

    private fun validation(state: VehicleControlState, grade: Double, maximumDelta: Double): AtpJudgement? {
        invalid?.let { return unavailable(AtpReason.INVALID_MODEL, it) }
        if (!grade.isFinite() || grade !in -1.0..1.0 || !maximumDelta.isFinite() || maximumDelta <= 0.0)
            return unavailable(AtpReason.OUTSIDE_MODEL_DOMAIN, "invalid grade or supervision interval")
        if (!state.speedMetersPerSecond.isFinite() || state.speedMetersPerSecond < 0.0 ||
            !state.accelerationMetersPerSecondSquared.isFinite() || !state.actualBrakeRatio.isFinite() || state.actualBrakeRatio < 0.0 || state.devices.size != cars.size)
            return unavailable(AtpReason.INVALID_STATE, "invalid motion or missing ordered devices")
        if (state.speedMetersPerSecond > capabilities.maximumSpeedMetersPerSecond)
            return unavailable(AtpReason.OUTSIDE_MODEL_DOMAIN, "speed exceeds compiled domain")
        if (state.devices.any { device ->
                !device.pressureKpa.isFinite() || device.pressureKpa < 0.0 ||
                    !device.electricBrakeForceNewtons.isFinite() || device.electricBrakeForceNewtons < 0.0 ||
                    !device.tractionForceNewtons.isFinite() || device.tractionForceNewtons < 0.0 ||
                    !device.commandAgeSeconds.isFinite() || device.commandAgeSeconds < 0.0 ||
                    !device.previousTractionRatio.isFinite() || device.previousTractionRatio < 0.0 ||
                    !device.previousBrakeRatio.isFinite() || device.previousBrakeRatio < 0.0
            })
            return unavailable(AtpReason.INVALID_STATE, "invalid device state")
        return null
    }

    private fun check(state: VehicleControlState, targets: List<AtpTarget>, grade: Double,
        maximumDelta: Double, service: Boolean, planningReserve: Boolean,
        distanceShift: Double = 0.0, stateValidated: Boolean = false,
        prefixPeakSpeed: Double = Double.NEGATIVE_INFINITY): AtpJudgement {
        if (!stateValidated) validation(state, grade, maximumDelta)?.let { return it }
        if (targets.any { it.id.isEmpty() || !it.distanceMeters.isFinite() ||
                !it.targetSpeedMetersPerSecond.isFinite() || it.targetSpeedMetersPerSecond < 0.0 })
            return unavailable(AtpReason.INVALID_STATE, "invalid hard target")
        val response = response(state, grade, maximumDelta, service, planningReserve)
        if (!response.distanceMeters.isFinite() || response.distanceMeters < 0.0 ||
            !response.endSpeedMetersPerSecond.isFinite() || response.endSpeedMetersPerSecond < 0.0 ||
            !response.peakSpeedMetersPerSecond.isFinite() || response.peakSpeedMetersPerSecond < 0.0 ||
            !response.preparationSeconds.isFinite() || response.preparationSeconds < 0.0)
            return unavailable(AtpReason.INVALID_MODEL, "non-finite analytic response")
        val table = if (service) serviceTable else emergencyTable
        for (target in targets) {
            val available = target.distanceMeters - distanceShift
            val currentLimit = available <= 0.0 && target.targetSpeedMetersPerSecond > 0.0
            if (currentLimit) {
                val peak = maxOf(response.peakSpeedMetersPerSecond, prefixPeakSpeed)
                if (peak > target.targetSpeedMetersPerSecond)
                    return AtpJudgement(AtpStatus.INTERVENTION, AtpReason.CURRENT_OVERSPEED,
                        "response peak exceeds current hard speed limit", AtpWitness(target,
                            if (distanceShift > 0.0) distanceShift else 0.0,
                            if (distanceShift > 0.0) target.distanceMeters else 0.0, peak), response)
                continue
            }
            // A positive speed point constrains speed, not completion of device preparation.
            // If even the complete response peak is below it, there is no braking distance to reserve.
            if (target.targetSpeedMetersPerSecond > 0.0 &&
                response.peakSpeedMetersPerSecond <= target.targetSpeedMetersPerSecond) continue
            val distance = table.distance(response.endSpeedMetersPerSecond,
                target.targetSpeedMetersPerSecond, grade)
            if (!distance.isFinite()) return AtpJudgement(AtpStatus.UNAVAILABLE,
                AtpReason.NO_GUARANTEED_BRAKING, "grade defeats guaranteed braking", response = response)
            // The kernel clamps the final speed to zero, then uses trapezoidal distance for the
            // whole interval. The largest positive-part corner quadrature error is b*h²/8.
            val terminalRounding = if (target.targetSpeedMetersPerSecond == 0.0 && response.endSpeedMetersPerSecond > 0.0)
                up(maximumPhysicalDeceleration(state, grade) * maximumDelta * maximumDelta / 8.0) else 0.0
            val terminalDistance = up(distance + terminalRounding)
            val required = if (response.distanceMeters == 0.0 && terminalDistance == 0.0) 0.0 else
                up(response.distanceMeters + terminalDistance)
            if (required > available) return AtpJudgement(AtpStatus.INTERVENTION,
                AtpReason.INSUFFICIENT_DISTANCE, if (service) "ordinary recovery unavailable" else "emergency stopping boundary",
                AtpWitness(target, up(required + distanceShift), target.distanceMeters,
                    response.peakSpeedMetersPerSecond), response)
        }
        return AtpJudgement(AtpStatus.SAFE, AtpReason.CLEAR, response = response)
    }

    private fun prefix(state: VehicleControlState, command: VehicleControlCommand, h: Double,
        grade: Double, currentCars: List<VehicleCarControlCapabilities>): Pair<VehicleControlState, Double> {
        val devices = cars.indices.map { index -> VehicleDeviceDynamics.advanceDeviceState(currentCars[index],
            state.devices[index], command, state.speedMetersPerSecond, capabilities.maximumSpeedMetersPerSecond,
            h, capabilities.emergencyBrakeRatio) }
        val net = cars.indices.sumOf { index ->
            val car = cars[index]; val device = devices[index]
            min(device.tractionForceNewtons, car.motorAdhesionForceNewtons) -
                min(device.electricBrakeForceNewtons + car.brakeMaxForceNewtons *
                    device.pressureKpa / car.nominalCylinderPressureKpa, car.brakeAdhesionForceNewtons)
        } / mass - gravity * grade
        var target = net
        if (state.speedMetersPerSecond == 0.0 && target < 0.0) target = 0.0
        val difference = target - state.accelerationMetersPerSecondSquared
        val jerk = if (difference > 0.0) capabilities.tractionJerkMetersPerSecondCubed else capabilities.brakeJerkMetersPerSecondCubed
        val acceleration = if (abs(difference) < 1e-9 || jerk == 0.0) target else
            state.accelerationMetersPerSecondSquared + difference.coerceIn(-jerk * h, jerk * h)
        val speed = (state.speedMetersPerSecond + acceleration * h).coerceIn(0.0, capabilities.maximumSpeedMetersPerSecond)
        val distance = if (speed == 0.0 && state.speedMetersPerSecond == 0.0) 0.0 else
            up((state.speedMetersPerSecond + speed) * h * 0.5)
        return state.copy(speedMetersPerSecond = speed,
            accelerationMetersPerSecondSquared = (speed - state.speedMetersPerSecond) / h,
            devices = devices, lastCommand = command) to distance
    }

    private fun maximumPhysicalDeceleration(state: VehicleControlState, grade: Double): Double {
        val maximumRatio = max(capabilities.emergencyBrakeRatio, capabilities.maximumServiceBrakeRatio)
        val force = cars.indices.sumOf { index ->
            val car = cars[index]; val device = state.devices[index]
            val pneumatic = max(car.brakeMaxForceNewtons * maximumRatio,
                car.brakeMaxForceNewtons * device.pressureKpa / car.nominalCylinderPressureKpa)
            val electric = max(car.electricBrakeMaxForceNewtons, device.electricBrakeForceNewtons)
            min(car.brakeAdhesionForceNewtons, pneumatic + electric)
        }
        return max(max(0.0, -state.accelerationMetersPerSecondSquared), max(0.0, force / mass + gravity * grade))
    }

    private fun response(state: VehicleControlState, grade: Double, h: Double, service: Boolean, planningReserve: Boolean): AtpResponse {
        val command = if (service) VehicleControlCommand(brakeRatio = capabilities.maximumServiceBrakeRatio) else VehicleControlCommand.EMERGENCY_BRAKE
        val ratio = if (service) capabilities.maximumServiceBrakeRatio else capabilities.emergencyBrakeRatio
        var release = 0.0; var tractionRelease = 0.0; var preparation = 0.0
        var retained = 0.0; var initialTraction = 0.0
        for (index in cars.indices) {
            val car = cars[index]; val device = state.devices[index]
            val unchanged = device.previousTractionRatio == 0.0 && device.previousBrakeRatio == ratio &&
                device.previousEmergencyBrake == command.emergencyBrake
            val delay = if (unchanged) max(0.0, car.commandDelaySeconds - device.commandAgeSeconds) else
                if (car.commandDelaySeconds == 0.0) 0.0 else car.commandDelaySeconds + h
            val electricLimit = if (service) car.electricBrakeMaxForceNewtons else 0.0
            val electricSeconds = max(0.0, device.electricBrakeForceNewtons - electricLimit) /
                car.electricBrakeReleaseRateNewtonsPerSecond
            val ready = max(delay, electricSeconds)
            release = max(release, ready)
            initialTraction += device.tractionForceNewtons
            tractionRelease = max(tractionRelease, device.tractionForceNewtons / car.tractionReleaseRateNewtonsPerSecond)
            val maxElectric = if (service) max(device.electricBrakeForceNewtons, car.electricBrakeMaxForceNewtons) else device.electricBrakeForceNewtons
            val pressureFloor = min(device.pressureKpa,
                if (car.brakeMaxForceNewtons == 0.0) 0.0 else max(0.0, ratio - maxElectric / car.brakeMaxForceNewtons) * car.nominalCylinderPressureKpa)
            val actualAir = if (car.brakeMaxForceNewtons == 0.0) 0.0 else car.brakeMaxForceNewtons * pressureFloor / car.nominalCylinderPressureKpa
            val guarantee = if (service) serviceForces[index] else min(car.brakeMaxForceNewtons * ratio, car.brakeAdhesionForceNewtons)
            val air = min(actualAir, guarantee)
            retained += min(air, car.brakeAdhesionForceNewtons)
            val forceRate = if (car.brakeMaxForceNewtons == 0.0) 0.0 else car.brakeMaxForceNewtons * car.airApplyRateKpaPerSecond / car.nominalCylinderPressureKpa
            val build = if (guarantee <= air) 0.0 else (guarantee - air) / forceRate
            // Operating reserve for ONE future service→EB command reset. It is not an
            // extra physical EB delay. Existing pneumatic force discharges the reserve;
            // a fully established air brake therefore never restarts a moving deadline.
            val ebAir = if (car.brakeMaxForceNewtons == 0.0) 0.0 else min(
                car.brakeMaxForceNewtons * device.pressureKpa / car.nominalCylinderPressureKpa,
                max(0.0, car.brakeMaxForceNewtons * capabilities.emergencyBrakeRatio - device.electricBrakeForceNewtons))
            val ebForce = min(car.brakeMaxForceNewtons * capabilities.emergencyBrakeRatio, car.brakeAdhesionForceNewtons)
            val ebRelease = device.electricBrakeForceNewtons / car.electricBrakeReleaseRateNewtonsPerSecond
            val ebBuild = if (ebForce <= ebAir) 0.0 else (ebForce - ebAir) / forceRate
            val operatingReserve = if (service && planningReserve && ebAir < guarantee)
                max(if (car.commandDelaySeconds == 0.0) 0.0 else car.commandDelaySeconds + h, ebRelease) + ebBuild else 0.0
            release = max(release, ready + operatingReserve)
            preparation = max(preparation, ready + build + operatingReserve)
        }
        val flat = if (service) serviceFlat else emergencyFlat
        val coast = -retained / mass - gravity * grade
        val cursor = MotionUpper(state.speedMetersPerSecond, state.accelerationMetersPerSecondSquared,
            capabilities.maximumSpeedMetersPerSecond, capabilities.brakeJerkMetersPerSecondCubed,
            capabilities.tractionJerkMetersPerSecondCubed, h,
            maximumPhysicalDeceleration(state, grade))
        // Residual traction decays concurrently. A straight chord is above the sum of clipped ramps.
        val traction = initialTraction / mass
        if (tractionRelease > 0.0) cursor.toward(coast + traction, coast, tractionRelease)
        val elapsed = tractionRelease
        if (release > elapsed) cursor.toward(coast, coast, release - elapsed)
        val buildStart = max(release, elapsed)
        val full = -flat - gravity * grade
        if (preparation > buildStart) cursor.toward(coast, full, preparation - buildStart)
        cursor.settle(full)
        return AtpResponse(cursor.distance, cursor.speed, cursor.peak,
            max(preparation, tractionRelease) + cursor.settleSeconds, flat)
    }

    private class MotionUpper(var speed: Double, var acceleration: Double, val maximum: Double,
        val downJerk: Double, val upJerk: Double, val maxStep: Double, val maximumDeceleration: Double) {
        var distance = 0.0; var peak = speed; var settleSeconds = 0.0
        fun toward(start: Double, end: Double, seconds: Double) {
            if (seconds <= 0.0) return
            val slope = (end - start) / seconds
            if (acceleration < start) {
                if (upJerk == 0.0) acceleration = start else {
                    // Physical force is sampled at interval end: one maximum-step lead bounds its staircase.
                    acceleration = min(start, acceleration + upJerk * maxStep)
                    val catch = (start - acceleration) / (upJerk - slope)
                    val part = min(seconds, max(0.0, catch))
                    integrate(upJerk, part)
                    if (part >= seconds) return
                    acceleration = start + slope * part
                    integrate(if (downJerk > 0.0) max(slope, -downJerk) else slope, seconds - part)
                    return
                }
            }
            if (downJerk == 0.0) { acceleration = start; integrate(slope, seconds); return }
            val difference = acceleration - start
            val catch = if (downJerk + slope > 0.0) difference / (downJerk + slope) else Double.POSITIVE_INFINITY
            val part = min(seconds, max(0.0, catch))
            integrate(-downJerk, part)
            if (part < seconds) { acceleration = start + slope * part; integrate(slope, seconds - part) }
        }
        fun settle(target: Double) {
            if (acceleration <= target) { if (acceleration < target) toward(target, target, maxStep); return }
            if (downJerk == 0.0) { acceleration = target; return }
            val time = (acceleration - target) / downJerk
            settleSeconds = time
            integrate(-downJerk, time)
            acceleration = max(acceleration, target)
        }
        private fun integrate(jerk: Double, seconds: Double) {
            if (seconds <= 0.0) return
            // Splitting at a=0 makes speed monotone, and handles first rest without permanent-stop assumptions.
            val zero = if (jerk != 0.0) -acceleration / jerk else Double.POSITIVE_INFINITY
            if (zero > 0.0 && zero < seconds) { integrate(jerk, zero); integrate(jerk, seconds - zero); return }
            val endA = acceleration + jerk * seconds
            val endV = speed + acceleration * seconds + jerk * seconds * seconds * 0.5
            if (endV < 0.0) {
                val stop = if (speed == 0.0) 0.0 else if (jerk == 0.0) -speed / acceleration else {
                    val root = sqrt(max(0.0, acceleration * acceleration - 2.0 * jerk * speed))
                    2.0 * speed / (-acceleration + root)
                }
                val corner = min(speed * maxStep * 0.5,
                    max(maximumDeceleration, max(abs(acceleration), abs(endA))) * maxStep * maxStep / 8.0)
                distance = up(distance + max(0.0, speed * stop + acceleration * stop * stop * 0.5 + jerk * stop * stop * stop / 6.0) + corner)
                speed = 0.0; acceleration = 0.0
                // The kernel stores zero actual acceleration once speed clips to zero. During
                // an upward-jerk response this can restart motion before brake establishment;
                // retain that reset rather than continuing the old negative-acceleration curve.
                if (jerk > 0.0 && seconds > stop) integrate(jerk, seconds - stop)
            } else {
                distance = up(distance + max(0.0, speed * seconds + acceleration * seconds * seconds * 0.5 + jerk * seconds * seconds * seconds / 6.0))
                speed = min(maximum, up(endV)); acceleration = endA
                if (endV > maximum) acceleration = max(0.0, acceleration)
            }
            peak = max(peak, speed)
        }
    }
    companion object {
        private fun up(value: Double): Double = if (value == 0.0) 0.0 else Math.nextUp(value)
        private fun unavailable(reason: AtpReason, detail: String) = AtpJudgement(AtpStatus.UNAVAILABLE, reason, detail)
    }
}
