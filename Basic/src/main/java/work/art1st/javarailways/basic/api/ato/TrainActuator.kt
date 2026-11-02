package work.art1st.javarailways.basic.api.ato

/**
 * Desired speed selected by an ATO planner.
 * @property targetSpeedMetersPerSecond Nonnegative immediate control target, in m/s.
 * @property brakingConstraints Captured physical targets for actual-candidate admission, when a route exists.
 */
data class ControlDemand(
    val targetSpeedMetersPerSecond: Double,
    val brakingConstraints: BrakingConstraints? = null
) {
    init {
        require(targetSpeedMetersPerSecond >= 0.0 && targetSpeedMetersPerSecond.isFinite()) {
            "targetSpeedMetersPerSecond must be finite and non-negative"
        }
    }
}

/**
 * One physical speed target, after the planner has applied its existing stopping margins.
 * @property distanceMeters Remaining distance to the target, in metres, with margins already applied.
 * @property targetSpeedMetersPerSecond Maximum speed at the target; zero requests a stop.
 * @property reactionTimeSeconds Explicit planning delay; the envelope avoids counting equipment response twice.
 */
data class BrakingLimit(
    val distanceMeters: Double,
    val targetSpeedMetersPerSecond: Double,
    val reactionTimeSeconds: Double = 0.0
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        require(targetSpeedMetersPerSecond.isFinite() && targetSpeedMetersPerSecond >= 0.0)
        require(reactionTimeSeconds.isFinite() && reactionTimeSeconds >= 0.0)
    }
}

/**
 * Captured route and targets used to admit the actual next handle, never a speculative route.
 * @property maximumControlDeltaSeconds Owning world's maximum physics interval, in seconds.
 */
class BrakingConstraints(
    segments: List<BrakingGradeSegment>,
    limits: List<BrakingLimit>,
    val maximumControlDeltaSeconds: Double
) {
    /** Immutable forward-route grade samples in metres and signed slope. */
    val segments: List<BrakingGradeSegment> = java.util.List.copyOf(segments)
    /** Immutable speed targets whose stopping margins have already been applied by the planner. */
    val limits: List<BrakingLimit> = java.util.List.copyOf(limits)

    init {
        require(this.segments.isNotEmpty())
        require(maximumControlDeltaSeconds.isFinite() && maximumControlDeltaSeconds > 0.0)
    }
}

/** Value-only result applied by the owning TrainController. */
data class ActuationStep(
    val speedMetersPerSecond: Double,
    val distanceMeters: Double
) {
    init {
        require(speedMetersPerSecond >= 0.0 && speedMetersPerSecond.isFinite())
        require(distanceMeters >= 0.0 && distanceMeters.isFinite())
    }
}

interface TrainActuator {
    val maximumSpeedMetersPerSecond: Double

    fun step(
        currentSpeedMetersPerSecond: Double,
        command: VehicleControlCommand,
        deltaTimeSeconds: Double
    ): ActuationStep

    fun controlState(currentSpeedMetersPerSecond: Double): VehicleControlState

    fun controlCapabilities(currentSpeedMetersPerSecond: Double): VehicleControlCapabilities

    /** Adjusts the actuator's internal braking state; motion freezing remains the controller's job. */
    fun freeze() = Unit

    /**
     * Returns the service-braking envelope for [segments].
     *
     * [segments] must be non-empty. Every actuator must provide an envelope for that route;
     * callers handle absence of a forward route before calling this method.
     * @param deltaTimeSeconds Actual current physics interval in seconds, including zero for preparation.
     * @param maximumControlDeltaSeconds Maximum interval the owning world can deliver, in seconds.
     * @throws IllegalArgumentException if [segments] is empty or timing inputs are invalid.
     */
    fun brakingEnvelope(
        segments: List<BrakingGradeSegment>,
        deltaTimeSeconds: Double,
        maximumControlDeltaSeconds: Double
    ): GradeAwareBrakingEnvelope
}
