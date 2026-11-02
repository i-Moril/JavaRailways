package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.BrakingResponse
import kotlin.math.max
import kotlin.math.min

/**
 * Upper trajectory after one actual command, independent of subsequent control-step partitioning.
 *
 * Equipment targets are bounded analytically. A decreasing force upper bound and its brake-jerk
 * closure bound the discrete kernel's endpoint acceleration. Integration uses left endpoint upper
 * accelerations, so each integration rectangle is conservative; it is not a simulation at a guessed
 * future tick rate. The only cadence assumption is the owner's explicit maximum control interval.
 */
internal object ServiceBrakingResponse {
    private const val INTEGRATION_INTERVAL_SECONDS = 0.01
    private const val MAX_BOUND_DURATION_SECONDS = 120.0

    private data class DeviceBound(
        val peakTraction: Double,
        val poweredSeconds: Double,
        val releaseRate: Double,
        val tractionAdhesion: Double,
        val minimumAirForce: Double,
        val finalAirForce: Double,
        val airReadySeconds: Double
    ) {
        fun tractionAt(seconds: Double): Double = min(
            tractionAdhesion,
            (peakTraction - releaseRate * (seconds - poweredSeconds).coerceAtLeast(0.0))
                .coerceAtLeast(0.0)
        )
    }

    fun bound(
        preview: TrainDynamics.PreviewSession,
        firstHandle: String,
        subsequentHandles: List<String>,
        maximumControlDeltaSeconds: Double
    ): BrakingResponse {
        require(maximumControlDeltaSeconds.isFinite() && maximumControlDeltaSeconds > 0.0)
        require(subsequentHandles.isNotEmpty())
        val first = preview.trial(firstHandle)
        val start = first.state
        val profile = preview.profile
        val serviceScale = profile.normalizedBrakeScale(subsequentHandles.last())
        val mass = TrainDynamics.totalEffectiveMass(preview.cars.map { it.control })
        val travelSeconds = subsequentHandles.size * maximumControlDeltaSeconds
        val bounds = preview.cars.mapIndexed { index, input ->
            val car = input.control
            val equipment = car.equipment
            val device = start.devices
            var previousDemand = device.previous[index]
            var poweredSteps = 0
            var mayRelease = false
            for ((handleIndex, handle) in subsequentHandles.withIndex()) {
                val brake = profile.normalizedBrakeScale(handle)
                val power = profile.normalizedPowerScale(handle)
                val demand = if (brake > 0.0) -brake else power
                // Changed commands with a positive delay hold their cylinder target and cut power.
                // Equal-demand aliases and zero-delay equipment may act immediately.
                val mayEnable = equipment.commandDelaySeconds == 0.0 || demand == previousDemand
                if (mayEnable && power > 0.0) poweredSteps = handleIndex + 1
                if (mayEnable && brake < serviceScale) mayRelease = true
                previousDemand = demand
            }
            val poweredSeconds = poweredSteps * maximumControlDeltaSeconds
            val adhesion = car.mass * car.motorAdhesionMassRatio * preview.gravity * profile.adhesionCoefficient
            val initialTraction = device.traction[index]
            val peak = max(initialTraction, min(adhesion,
                initialTraction + equipment.tractionApplyRateNewtonsPerSecond * poweredSeconds))
            val maximumElectric = max(device.electric[index], car.electricBrakeMaxForce)
            val finalRawAir = (car.brakeMaxForce * serviceScale - car.electricBrakeMaxForce).coerceAtLeast(0.0)
            val transientRawAir = (car.brakeMaxForce * serviceScale - maximumElectric).coerceAtLeast(0.0)
            val pressure = if (!mayRelease) device.pressure[index] else if (profile.brakeReleaseTime <= 0.0) 0.0
                else (device.pressure[index] - equipment.nominalCylinderPressureKpa /
                    profile.brakeReleaseTime * travelSeconds).coerceAtLeast(0.0)
            val minimumRawAir = min(car.brakeMaxForce * pressure / equipment.nominalCylinderPressureKpa,
                transientRawAir)
            val brakeAdhesion = car.mass * car.brakeAdhesionMassRatio * preview.gravity * profile.adhesionCoefficient
            val excessElectricSeconds = (maximumElectric - car.electricBrakeMaxForce) /
                equipment.electricBrakeReleaseRateNewtonsPerSecond
            val airReady = travelSeconds + equipment.commandDelaySeconds + maximumControlDeltaSeconds +
                excessElectricSeconds + profile.brakeApplyTime.coerceAtLeast(0.0) * serviceScale + maximumControlDeltaSeconds
            DeviceBound(peak, poweredSeconds, equipment.tractionReleaseRateNewtonsPerSecond, adhesion,
                min(minimumRawAir, brakeAdhesion), min(finalRawAir, brakeAdhesion), airReady)
        }
        val gradeForce = preview.cars.sumOf {
            it.control.mass * preview.gravity * it.grade.coerceIn(-1.0, 1.0)
        }
        val guaranteedDeceleration = (bounds.sumOf { it.finalAirForce } + gradeForce) / mass
        if (!guaranteedDeceleration.isFinite() || guaranteedDeceleration <= 0.0) {
            return BrakingResponse(0.0, 0.0, 0.0, false)
        }
        fun forceAcceleration(seconds: Double): Double = (bounds.sumOf {
            it.tractionAt(seconds) - if (seconds >= it.airReadySeconds) it.finalAirForce else it.minimumAirForce
        } - gradeForce) / mass

        // A discrete endpoint can create a new acceleration maximum only by rising toward its
        // force target. That maximum is bounded both by the cumulative traction-jerk ramp and
        // the non-increasing force bound at that endpoint. Later force cannot create a higher
        // maximum. Starting at least at zero also covers acceleration resets at a clamped stop.
        val risingStart = start.motion.acceleration.coerceAtLeast(0.0)
        val initialForce = forceAcceleration(0.0)
        val accelerationPeak = if (profile.tractionJerk <= 0.0 || initialForce <= risingStart) {
            max(risingStart, initialForce)
        } else {
            var lower = 0.0
            var upper = (initialForce - risingStart) / profile.tractionJerk
            repeat(64) {
                val middle = (lower + upper) * 0.5
                if (risingStart + profile.tractionJerk * middle < forceAcceleration(middle)) {
                    lower = middle
                } else {
                    upper = middle
                }
            }
            // The upper bracket remains on or after the crossing even if the air bound jumps.
            risingStart + profile.tractionJerk * upper
        }
        if (!accelerationPeak.isFinite()) return BrakingResponse(0.0, 0.0, 0.0, false)

        var elapsed = 0.0
        var speed = start.motion.speed
        var peakSpeed = max(preview.seed.motion.speed, speed)
        var distance = first.distance
        var acceleration = accelerationPeak
        val ready = bounds.maxOf { max(it.airReadySeconds,
            it.poweredSeconds + it.peakTraction / it.releaseRate) }
        while (elapsed < MAX_BOUND_DURATION_SECONDS) {
            // Once this upper trajectory stops under a nonpositive, decreasing force bound, the
            // real trajectory cannot restart. Its final clamped tick is covered below.
            if ((speed == 0.0 && acceleration <= 0.0 && forceAcceleration(elapsed) <= 0.0) ||
                (elapsed >= ready && acceleration <= -guaranteedDeceleration)) {
                val clampReservation = peakSpeed * maximumControlDeltaSeconds * 0.5
                return BrakingResponse(distance + clampReservation,
                    (peakSpeed - preview.seed.motion.speed).coerceAtLeast(0.0), elapsed + preview.delta, true)
            }
            val interval = min(INTEGRATION_INTERVAL_SECONDS, MAX_BOUND_DURATION_SECONDS - elapsed)
            val nextSpeed = (speed + acceleration * interval).coerceAtLeast(0.0)
            // Constant left acceleration bounds the continuous jerk closure over this interval.
            // For a zero crossing, use the full rectangle rather than crediting early stopping.
            distance += max(speed, nextSpeed) * interval
            speed = nextSpeed
            peakSpeed = max(peakSpeed, speed)
            val force = forceAcceleration(elapsed)
            acceleration = min(accelerationPeak, if (profile.brakeJerk <= 0.0) force
                else max(acceleration - profile.brakeJerk * interval, force))
            elapsed += interval
        }
        return BrakingResponse(0.0, 0.0, 0.0, false)
    }
}
