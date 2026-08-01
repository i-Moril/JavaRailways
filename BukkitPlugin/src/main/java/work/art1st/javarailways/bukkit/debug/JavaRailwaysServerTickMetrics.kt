package work.art1st.javarailways.bukkit.debug

data class JavaRailwaysServerTickMetrics(
    val tickIndex: Long,
    val computeNanos: Long,
    val blockedWaitAsyncNanos: Long,
    val totalImpactNanos: Long,
    val phaseBreakdownNanos: Map<String, Long>
)
