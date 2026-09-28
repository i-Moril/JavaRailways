package work.art1st.javarailways.basic.route

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

/** Immutable timetable-owned route after Basic has validated its physical traversal. */
data class PlannedRoute(
    val routeId: String,
    val revision: Long,
    val expandedRoute: ExpandedRoute
) {
    init {
        require(routeId.isNotBlank())
        require(revision >= 0L)
    }
}

data class TurnoutSafetyDefaults(
    val protectionDistanceMeters: Double = DEFAULT_PROTECTION_DISTANCE_METERS,
    val lockingDistanceMeters: Double = DEFAULT_LOCKING_DISTANCE_METERS,
    val decisionLeadTicks: Int = DEFAULT_DECISION_LEAD_TICKS,
    val retryTicks: Int = DEFAULT_RETRY_TICKS
) {
    init {
        require(protectionDistanceMeters.isFinite() && protectionDistanceMeters >= 0.0)
        require(lockingDistanceMeters.isFinite() && lockingDistanceMeters >= 0.0)
        require(lockingDistanceMeters <= protectionDistanceMeters)
        require(decisionLeadTicks > 0)
        require(retryTicks > 0)
    }

    companion object {
        const val DEFAULT_PROTECTION_DISTANCE_METERS = 20.0
        const val DEFAULT_LOCKING_DISTANCE_METERS = 5.0
        const val DEFAULT_DECISION_LEAD_TICKS = 5
        const val DEFAULT_RETRY_TICKS = 5
    }
}
