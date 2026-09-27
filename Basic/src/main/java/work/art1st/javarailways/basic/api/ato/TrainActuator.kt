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

    /** Returns a braking envelope for the current forward route, or null for a test-only fallback. */
    fun brakingEnvelope(segments: List<BrakingGradeSegment>): GradeAwareBrakingEnvelope? = null
}
