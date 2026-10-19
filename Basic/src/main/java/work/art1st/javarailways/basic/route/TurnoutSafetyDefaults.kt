package work.art1st.javarailways.basic.route

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
