package work.art1st.javarailways.simpleato.control

import work.art1st.javarailways.basic.api.ato.VehicleControlCapabilities
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.basic.api.ato.VehicleControlState
import kotlin.math.*

/** Positions are relative to the current physical head; zero is the actual operating goal. */
data class MotionReferenceConstraint(val id: String, val distanceMeters: Double,
    val targetSpeedMetersPerSecond: Double) {
    init {
        require(id.isNotEmpty() && distanceMeters.isFinite())
        require(targetSpeedMetersPerSecond.isFinite() && targetSpeedMetersPerSecond >= 0.0)
    }
}

data class MotionReferenceInput(val state: VehicleControlState,
    val capabilities: VehicleControlCapabilities, val constraints: List<MotionReferenceConstraint>,
    val deltaSeconds: Double, val maximumControlDeltaSeconds: Double = deltaSeconds) {
    init {
        require(deltaSeconds.isFinite() && deltaSeconds > 0.0)
        require(maximumControlDeltaSeconds.isFinite() && maximumControlDeltaSeconds >= deltaSeconds)
        require(state.speedMetersPerSecond.isFinite() && state.speedMetersPerSecond >= 0.0)
        require(state.accelerationMetersPerSecondSquared.isFinite())
        require(state.actualBrakeRatio.isFinite() && state.actualBrakeRatio >= 0.0)
    }
}

/** Operating accuracy is distinct from a hard protection judgement. */
enum class OperatingPrecisionStatus { TRACKING, TARGET_UNREACHABLE, MODEL_UNAVAILABLE }

data class ReferenceMotionSample(val speedMetersPerSecond: Double,
    val accelerationMetersPerSecondSquared: Double)

data class ReferenceControlDecision(val command: VehicleControlCommand,
    val referenceSpeedMetersPerSecond: Double, val referenceAccelerationMetersPerSecondSquared: Double,
    val targetId: String?, val ordinaryCommandAdmissible: Boolean = true,
    val precisionStatus: OperatingPrecisionStatus = OperatingPrecisionStatus.TRACKING)

/**
 * Ideal operating profile, independent of emergency braking. A terminal constant-jerk release
 * joins the ordinary constant-deceleration branch continuously. Construction prepares grade
 * variants once; a decision translates a target distance, without simulating future ticks.
 */
class NominalMotionReference private constructor(
    val operatingAccelerationMetersPerSecondSquared: Double,
    val operatingDecelerationMetersPerSecondSquared: Double,
    val stoppingReleaseJerkMetersPerSecondCubed: Double,
    private val gradeVariants: Array<NominalMotionReference?>?
) {
    constructor(acceleration: Double, deceleration: Double, releaseJerk: Double) :
        this(acceleration, deceleration, releaseJerk, null)
    init {
        require(operatingAccelerationMetersPerSecondSquared.isFinite() && operatingAccelerationMetersPerSecondSquared > 0.0)
        require(operatingDecelerationMetersPerSecondSquared.isFinite() && operatingDecelerationMetersPerSecondSquared > 0.0)
        require(stoppingReleaseJerkMetersPerSecondCubed.isFinite() && stoppingReleaseJerkMetersPerSecondCubed > 0.0)
    }
    private val releaseTime = operatingDecelerationMetersPerSecondSquared / stoppingReleaseJerkMetersPerSecondCubed
    private val releaseSpeed = stoppingReleaseJerkMetersPerSecondCubed * releaseTime * releaseTime / 2.0
    val stoppingTransitionDistanceMeters = stoppingReleaseJerkMetersPerSecondCubed * releaseTime.pow(3) / 6.0

    fun at(targetSpeedMetersPerSecond: Double, distanceMeters: Double): ReferenceMotionSample {
        val distance = max(0.0, distanceMeters)
        if (targetSpeedMetersPerSecond > 0.0) return ReferenceMotionSample(
            sqrt(targetSpeedMetersPerSecond.pow(2) + 2.0 * operatingDecelerationMetersPerSecondSquared * distance),
            if (distance == 0.0) 0.0 else -operatingDecelerationMetersPerSecondSquared)
        if (distance <= stoppingTransitionDistanceMeters) {
            val time = Math.cbrt(6.0 * distance / stoppingReleaseJerkMetersPerSecondCubed)
            return ReferenceMotionSample(stoppingReleaseJerkMetersPerSecondCubed * time * time / 2.0,
                -stoppingReleaseJerkMetersPerSecondCubed * time)
        }
        return ReferenceMotionSample(sqrt(releaseSpeed.pow(2) +
            2.0 * operatingDecelerationMetersPerSecondSquared * (distance - stoppingTransitionDistanceMeters)),
            -operatingDecelerationMetersPerSecondSquared)
    }

    fun stoppingDistanceMeters(speedMetersPerSecond: Double): Double {
        val speed = max(0.0, speedMetersPerSecond)
        return if (speed <= releaseSpeed) {
            val time = sqrt(2.0 * speed / stoppingReleaseJerkMetersPerSecondCubed)
            stoppingReleaseJerkMetersPerSecondCubed * time.pow(3) / 6.0
        } else stoppingTransitionDistanceMeters + (speed.pow(2) - releaseSpeed.pow(2)) /
            (2.0 * operatingDecelerationMetersPerSecondSquared)
    }

    fun forGrade(grade: Double): NominalMotionReference? {
        require(grade.isFinite())
        val variants = gradeVariants ?: return this
        if (grade < MIN_GRADE || grade > MAX_GRADE) return null
        return variants[floor((grade - MIN_GRADE) / GRADE_STEP + 1e-10).toInt().coerceIn(variants.indices)]
    }

    companion object {
        private const val MIN_GRADE = -0.2
        private const val MAX_GRADE = 0.2
        private const val GRADE_STEP = 0.005
        fun fromCapabilities(capabilities: VehicleControlCapabilities): NominalMotionReference {
            val nominalService = capabilities.performanceSamples.minOfOrNull {
                it.serviceDecelerationMetersPerSecondSquared
            } ?: capabilities.flatServiceDecelerationMetersPerSecondSquared
            val deceleration = min(capabilities.nominalDecelerationMetersPerSecondSquared, nominalService)
            require(deceleration > 0.0) { "vehicle has no nominal service braking capability" }
            val acceleration = capabilities.nominalAccelerationMetersPerSecondSquared
            require(acceleration.isFinite() && acceleration > 0.0) { "vehicle has no nominal operating acceleration" }
            val finiteJerks = listOf(capabilities.brakeJerkMetersPerSecondCubed,
                capabilities.tractionJerkMetersPerSecondCubed).filter { it > 0.0 }
            val releaseJerk = min(0.5, (finiteJerks.minOrNull() ?: 1.0) * 0.5)
            val gravity = capabilities.gravityMetersPerSecondSquared * capabilities.massToEffectiveMassRatio
            val variants = Array<NominalMotionReference?>(81) { index ->
                val available = deceleration + (MIN_GRADE + index * GRADE_STEP) * gravity
                if (available <= 0.0) null else NominalMotionReference(acceleration, available, releaseJerk)
            }
            return NominalMotionReference(acceleration, deceleration, releaseJerk, variants)
        }
    }
}

object ReferenceStrategyMode {
    fun stoppingTarget(reference: NominalMotionReference, input: MotionReferenceInput,
        previousTargetId: String? = null, previousDistanceMeters: Double? = null): MotionReferenceConstraint? {
        if (previousTargetId != null && previousDistanceMeters != null) {
            input.constraints.firstOrNull { it.id == previousTargetId && it.targetSpeedMetersPerSecond == 0.0 &&
                it.distanceMeters <= previousDistanceMeters + 1e-9 }?.let { return it }
        }
        if (input.state.speedMetersPerSecond > 20.0 / 3.6) return null
        val distance = reference.stoppingDistanceMeters(input.state.speedMetersPerSecond)
        val otherReferenceSpeed = minOf(input.capabilities.maximumSpeedMetersPerSecond,
            input.constraints.asSequence().filter { it.targetSpeedMetersPerSecond > 0.0 }
                .map { reference.at(it.targetSpeedMetersPerSecond, it.distanceMeters).speedMetersPerSecond }
                .minOrNull() ?: Double.POSITIVE_INFINITY)
        val brakingForZero = input.state.speedMetersPerSecond > 0.0 && input.state.lastCommand.brakeRatio > 0.0
        return input.constraints.asSequence().filter { it.targetSpeedMetersPerSecond == 0.0 }
            .filter { it.distanceMeters <= distance ||
                (brakingForZero && reference.at(0.0, it.distanceMeters).speedMetersPerSecond <= otherReferenceSpeed) ||
                (it.id == previousTargetId &&
                if (previousDistanceMeters != null) it.distanceMeters <= previousDistanceMeters + 1e-9
                else it.distanceMeters <= distance * 1.25 + input.state.speedMetersPerSecond * input.maximumControlDeltaSeconds) }
            .minByOrNull { it.distanceMeters }
    }
}
