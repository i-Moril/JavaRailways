package work.art1st.javarailways.basic.api.ato

/** Fixed Phase B motion limits. All values use SI units. */
data class AtoSafetyProfile(
    val accelerationMetersPerSecondSquared: Double = 1.0,
    val serviceDecelerationMetersPerSecondSquared: Double = 1.0,
    val reactionTimeSeconds: Double = 0.1,
    val safetyDistanceMeters: Double = 5.0,
    val stopToleranceMeters: Double = 0.1
) {
    init {
        require(accelerationMetersPerSecondSquared > 0.0 && accelerationMetersPerSecondSquared.isFinite())
        require(serviceDecelerationMetersPerSecondSquared > 0.0 && serviceDecelerationMetersPerSecondSquared.isFinite())
        require(reactionTimeSeconds >= 0.0 && reactionTimeSeconds.isFinite())
        require(safetyDistanceMeters >= 0.0 && safetyDistanceMeters.isFinite())
        require(stopToleranceMeters >= 0.0 && stopToleranceMeters.isFinite())
    }
}
