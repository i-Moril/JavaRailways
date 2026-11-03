package work.art1st.javarailways.simpleato.control

import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.basic.api.ato.VehicleHandleCapability
import kotlin.math.*

class DrivingReferenceStrategy {
    fun select(reference: NominalMotionReference, input: MotionReferenceInput,
        admissible: (VehicleControlCommand) -> Boolean = { true }): ReferenceControlDecision =
        decide(reference, input, null, admissible, false).first
}

/** Worker-owned strategy state; reset when the executor changes mission or leaves stopping. */
class StoppingReferenceStrategy {
    private var releaseTarget: String? = null
    private var previousDistance = Double.POSITIVE_INFINITY

    fun reset() { releaseTarget = null; previousDistance = Double.POSITIVE_INFINITY }

    fun select(reference: NominalMotionReference, input: MotionReferenceInput,
        stopTarget: MotionReferenceConstraint,
        admissible: (VehicleControlCommand) -> Boolean = { true }): ReferenceControlDecision {
        require(stopTarget.targetSpeedMetersPerSecond == 0.0)
        if (releaseTarget != stopTarget.id || stopTarget.distanceMeters > previousDistance + 1e-9) releaseTarget = null
        val (decision, releasing) = decide(reference, input, stopTarget, admissible, releaseTarget == stopTarget.id)
        releaseTarget = if (releasing) stopTarget.id else null
        previousDistance = stopTarget.distanceMeters
        return decision
    }
}

private fun decide(reference: NominalMotionReference, input: MotionReferenceInput,
    stop: MotionReferenceConstraint?, admissible: (VehicleControlCommand) -> Boolean,
    releaseCommitted: Boolean): Pair<ReferenceControlDecision, Boolean> {
    val state = input.state
    val caps = input.capabilities
    val ordinary = caps.handles.filter { !it.command.emergencyBrake && (stop == null || it.command.tractionRatio == 0.0) }
    require(ordinary.isNotEmpty()) { "vehicle has no ordinary control for this strategy" }
    val safe = ordinary.filter { admissible(it.command) }
    val hardFeasible = safe.isNotEmpty()
    val candidates = safe.ifEmpty { listOf(ordinary.maxBy { it.command.brakeRatio }) }
    val speed = state.speedMetersPerSecond
    val acceleration = state.accelerationMetersPerSecondSquared
    var target: MotionReferenceConstraint? = null
    var sample = ReferenceMotionSample(caps.maximumSpeedMetersPerSecond, 0.0)
    for (constraint in input.constraints) {
        val candidate = reference.at(constraint.targetSpeedMetersPerSecond, constraint.distanceMeters)
        if (candidate.speedMetersPerSecond < sample.speedMetersPerSecond) {
            sample = candidate
            target = constraint
        }
    }
    fun result(handle: VehicleHandleCapability, precision: OperatingPrecisionStatus,
        releasing: Boolean = false): Pair<ReferenceControlDecision, Boolean> =
        ReferenceControlDecision(handle.command, sample.speedMetersPerSecond,
            sample.accelerationMetersPerSecondSquared, target?.id, hardFeasible, precision) to releasing

    if (stop != null && (speed == 0.0 || stop.distanceMeters <= 0.0)) {
        val status = if (stop.distanceMeters == 0.0) OperatingPrecisionStatus.TRACKING else OperatingPrecisionStatus.TARGET_UNREACHABLE
        return result(candidates.maxBy { it.command.brakeRatio }, status)
    }

    // Accuracy estimates guide ordinary control only. Failure of an approximate nominal
    // stopping model must never be turned into a hard protection failure.
    val operatingStop = stop ?: input.constraints.asSequence()
        .filter { it.targetSpeedMetersPerSecond == 0.0 }.minByOrNull { it.distanceMeters }
    // Every candidate uses the same maximum-service-brake continuation; the strongest
    // traction candidate reaches the highest speed in the known interval, so its nominal
    // stopping distance bounds the whole ordinary catalogue. One maximum supervision
    // interval covers a cached decision before the next re-evaluation.
    val farBeyondStop = operatingStop != null && stop == null &&
        operatingStop.distanceMeters > reference.stoppingDistanceMeters(input.state.speedMetersPerSecond) &&
        operatingStop.distanceMeters > nominalStopUpperBound(input, candidates)
    val stopDistances = if (operatingStop == null || farBeyondStop) emptyMap() else candidates.associateWith {
        NominalServiceBrakeModel.stoppingDistanceMeters(input, it.command)
    }
    var precision = when {
        farBeyondStop -> OperatingPrecisionStatus.TRACKING
        stopDistances.values.any { it.isNaN() } -> OperatingPrecisionStatus.MODEL_UNAVAILABLE
        operatingStop != null && stopDistances.values.none { it.isFinite() && it <= operatingStop.distanceMeters } -> OperatingPrecisionStatus.TARGET_UNREACHABLE
        else -> OperatingPrecisionStatus.TRACKING
    }
    val nominallyReachable = if (operatingStop == null || farBeyondStop) candidates else candidates.filter {
        stopDistances.getValue(it).isNaN() || stopDistances.getValue(it) <= operatingStop.distanceMeters
    }
    var choices = nominallyReachable.ifEmpty {
        // The exact operating point may already be unattainable; execute the best HARD-safe
        // brake and report operating accuracy separately. The executor retains the hard check.
        return result(candidates.maxBy { it.command.brakeRatio }, precision)
    }

    if (stop != null) {
        val releaseRanges = choices.associateWith { NominalServiceBrakeModel.coastingDistanceMeters(input, it.command) }
        val holding = choices.filter { handle ->
            val gravityForce = caps.deviceCapabilities.sumOf { it.massKilograms } *
                caps.gravityMetersPerSecondSquared * caps.worstCurrentGrade
            val brakeForce = caps.deviceCapabilities.sumOf { min(it.brakeAdhesionForceNewtons,
                it.brakeMaxForceNewtons * handle.command.brakeRatio) }
            brakeForce + gravityForce >= 0.0
        }.minByOrNull { it.command.brakeRatio } ?: choices.maxBy { it.command.brakeRatio }
        val holdingRange = NominalServiceBrakeModel.holdingDistanceMeters(input, holding.command, holding.command)
        if (releaseCommitted && holdingRange.isFinite()) return result(holding, precision, true)
        val reachable = choices.filter { releaseRanges.getValue(it).let { range -> range.isNaN() || range >= stop.distanceMeters } }
        if (reachable.isEmpty()) {
            precision = if (releaseRanges.values.any { it.isNaN() }) OperatingPrecisionStatus.MODEL_UNAVAILABLE
                else OperatingPrecisionStatus.TARGET_UNREACHABLE
            return result(holding, precision)
        }
        // Commit a light holding demand only when another brake interval would lose the point
        // and its nominal first rest is within one current interval of the goal. Its pressure
        // must establish before downhill standstill; every later demand remains hard-checked.
        val intervalTravel = max(0.0, speed * input.deltaSeconds +
            acceleration * input.deltaSeconds * input.deltaSeconds / 2.0)
        if (holdingRange.isFinite() && holdingRange >= stop.distanceMeters &&
            holdingRange - stop.distanceMeters <= intervalTravel && reachable.size < choices.size)
            return result(holding, precision, true)
        choices = reachable
    }

    val previous = choices.firstOrNull { it.command == state.lastCommand }
    if (previous != null && caps.deviceCapabilities.size == state.devices.size &&
        caps.deviceCapabilities.indices.any { index ->
            state.devices[index].commandAgeSeconds < caps.deviceCapabilities[index].commandDelaySeconds
        }) return result(previous, precision)

    val gain = 1.0 + 7.0 * (1.0 - speed).coerceIn(0.0, 1.0)
    val feedback = if (sample.accelerationMetersPerSecondSquared == 0.0) max(gain, 1.0 / input.deltaSeconds) else gain
    val desired = (sample.accelerationMetersPerSecondSquared + feedback * (sample.speedMetersPerSecond - speed))
        .coerceIn(-reference.operatingDecelerationMetersPerSecondSquared * 1.5,
            reference.operatingAccelerationMetersPerSecondSquared)
    val desiredRatio = brakeRatioForAcceleration(caps.handles, desired)
    val deviceLag = if (caps.deviceCapabilities.isEmpty() || caps.deviceCapabilities.size != state.devices.size) 0.0
        else caps.deviceCapabilities.indices.maxOf { index ->
            val car = caps.deviceCapabilities[index]
            val device = state.devices[index]
            val electric = min(car.electricBrakeMaxForceNewtons, car.electricBrakeMaxPowerWatts / max(speed, 0.5)) *
                min(1.0, speed / car.electricBrakeFadeSpeedMetersPerSecond) * min(desiredRatio, 1.0)
            val pressure = if (car.brakeMaxForceNewtons == 0.0) 0.0 else
                max(0.0, desiredRatio - electric / car.brakeMaxForceNewtons) * car.nominalCylinderPressureKpa
            val airTime = abs(pressure - device.pressureKpa) /
                if (pressure > device.pressureKpa) car.airApplyRateKpaPerSecond else car.airReleaseRateKpaPerSecond
            val electricTime = abs(electric - device.electricBrakeForceNewtons) /
                if (electric > device.electricBrakeForceNewtons) car.electricBrakeApplyRateNewtonsPerSecond else car.electricBrakeReleaseRateNewtonsPerSecond
            car.commandDelaySeconds + maxOf(airTime, electricTime,
                device.tractionForceNewtons / car.tractionReleaseRateNewtonsPerSecond)
        }
    val jerk = if (desired < acceleration) caps.brakeJerkMetersPerSecondCubed else caps.tractionJerkMetersPerSecondCubed
    val lag = (deviceLag + if (jerk == 0.0) 0.0 else abs(desired - acceleration) / jerk) / 2.0
    val movingTime = if (acceleration < 0.0) min(lag, speed / -acceleration) else lag
    val translatedDistance = max(0.0, speed * movingTime + acceleration * movingTime * movingTime / 2.0)
    val translatedSpeed = max(0.0, speed + acceleration * movingTime)
    val translated = target?.let { reference.at(it.targetSpeedMetersPerSecond, it.distanceMeters - translatedDistance) } ?: sample
    val translatedGain = if (translated.accelerationMetersPerSecondSquared == 0.0) max(gain, 1.0 / input.deltaSeconds) else gain
    val accelerationGoal = (translated.accelerationMetersPerSecondSquared + translatedGain * (translated.speedMetersPerSecond - translatedSpeed))
        .coerceIn(-reference.operatingDecelerationMetersPerSecondSquared * 1.5,
            reference.operatingAccelerationMetersPerSecondSquared)
    // The acceleration hysteresis is a command scoring bias, never a position tolerance.
    val chosen = choices.minBy { abs(it.steadyAccelerationMetersPerSecondSquared - accelerationGoal) -
        if (it == previous) 0.025 else 0.0 }
    return result(chosen, precision)
}

/** Invert the current discrete nominal force curve, including adhesion plateaux. */
private fun brakeRatioForAcceleration(handles: List<VehicleHandleCapability>, acceleration: Double): Double {
    val curve = handles.filter { !it.command.emergencyBrake && it.command.tractionRatio == 0.0 }
        .sortedBy { it.command.brakeRatio }
    if (curve.isEmpty()) return 0.0
    if (acceleration >= curve.first().steadyAccelerationMetersPerSecondSquared) return curve.first().command.brakeRatio
    for ((a, b) in curve.zipWithNext()) if (acceleration >= b.steadyAccelerationMetersPerSecondSquared) {
        val change = a.steadyAccelerationMetersPerSecondSquared - b.steadyAccelerationMetersPerSecondSquared
        if (change <= 0.0) return a.command.brakeRatio
        return a.command.brakeRatio + (a.steadyAccelerationMetersPerSecondSquared - acceleration) /
            change * (b.command.brakeRatio - a.command.brakeRatio)
    }
    return curve.last().command.brakeRatio
}

/**
 * Conservative upper bound on the nominal stopping distance of every candidate in
 * [candidates]. All candidates share the same maximum-service-brake continuation; only the
 * known interval differs. A command with the most traction and no brake cannot leave that
 * interval slower than any other ordinary command, so one probe bounds the catalogue.
 * Returns infinity when the probe cannot establish a finite bound.
 */
internal fun nominalStopUpperBound(input: MotionReferenceInput, candidates: List<VehicleHandleCapability>): Double {
    val probe = candidates.maxByOrNull { it.command.tractionRatio - it.command.brakeRatio }
        ?: return Double.POSITIVE_INFINITY
    val distance = NominalServiceBrakeModel.stoppingDistanceMeters(input, probe.command)
    if (!distance.isFinite()) return Double.POSITIVE_INFINITY
    return distance + max(0.0, input.state.speedMetersPerSecond) * input.maximumControlDeltaSeconds + 1.0
}
