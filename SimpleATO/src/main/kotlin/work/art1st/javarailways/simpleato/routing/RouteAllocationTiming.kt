package work.art1st.javarailways.simpleato.routing

/** Conservative look-ahead distance at which SimpleATO should extend its current authority. */
data class RouteAllocationTiming(
    val switchSetupSeconds: Double,
    val reactionTimeSeconds: Double,
    val safetyMarginMeters: Double
) {
    init {
        require(switchSetupSeconds.isFinite() && switchSetupSeconds >= 0.0)
        require(reactionTimeSeconds.isFinite() && reactionTimeSeconds >= 0.0)
        require(safetyMarginMeters.isFinite() && safetyMarginMeters >= 0.0)
    }

    fun triggerDistanceMeters(
        approachSpeedMetersPerSecond: Double,
        serviceDecelerationMetersPerSecondSquared: Double
    ): Double {
        require(approachSpeedMetersPerSecond.isFinite() && approachSpeedMetersPerSecond >= 0.0)
        require(
            serviceDecelerationMetersPerSecondSquared.isFinite() &&
                serviceDecelerationMetersPerSecondSquared > 0.0
        )
        return approachSpeedMetersPerSecond * approachSpeedMetersPerSecond /
            (2.0 * serviceDecelerationMetersPerSecondSquared) +
            approachSpeedMetersPerSecond * (switchSetupSeconds + reactionTimeSeconds) +
            safetyMarginMeters
    }
}
