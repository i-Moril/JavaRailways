package work.art1st.mineRails.bukkit.debug

data class MineRailsServerTickMetrics(
    val tickIndex: Long,
    val computeNanos: Long,
    val blockedWaitAsyncNanos: Long,
    val totalImpactNanos: Long,
    val phaseBreakdownNanos: Map<String, Long>
)
