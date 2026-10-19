package work.art1st.javarailways.basic.api.ato

/** Desired speed selected by an ATO planner. */
data class ControlDemand(val targetSpeedMetersPerSecond: Double) {
    init {
        require(targetSpeedMetersPerSecond >= 0.0 && targetSpeedMetersPerSecond.isFinite()) {
            "targetSpeedMetersPerSecond must be finite and non-negative"
        }
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
        demand: ControlDemand,
        deltaTimeSeconds: Double
    ): ActuationStep

    fun freeze(): ActuationStep = ActuationStep(0.0, 0.0)

    /**
     * Returns the service-braking envelope for [segments].
     *
     * [segments] must be non-empty. Every actuator must provide an envelope for that route;
     * callers handle absence of a forward route before calling this method.
     * @throws IllegalArgumentException if [segments] is empty.
     */
    fun brakingEnvelope(segments: List<BrakingGradeSegment>): GradeAwareBrakingEnvelope
}
