package work.art1st.javarailways.basic.safety

/** Applies an optional manual CURVE limit without allowing it past safety or SUBGRADE. */
object RailSpeedLimitPolicy {
    fun apply(
        geometryLimits: RailSpeedLimits,
        subgradeLimitMetersPerSecond: Double,
        manualCurveLimitMetersPerSecond: Double?
    ): RailSpeedLimits {
        require(subgradeLimitMetersPerSecond.isFinite() && subgradeLimitMetersPerSecond > 0.0)
        require(
            manualCurveLimitMetersPerSecond == null ||
                manualCurveLimitMetersPerSecond.isFinite() && manualCurveLimitMetersPerSecond > 0.0
        )
        if (geometryLimits.derailSpeedMetersPerSecond == null) {
            return geometryLimits.copy(operatingLimitMetersPerSecond = subgradeLimitMetersPerSecond)
        }
        val curveLimit = manualCurveLimitMetersPerSecond
            ?: geometryLimits.operatingLimitMetersPerSecond
        return geometryLimits.copy(
            operatingLimitMetersPerSecond = minOf(
                curveLimit,
                geometryLimits.derailSpeedMetersPerSecond,
                subgradeLimitMetersPerSecond
            )
        )
    }
}
