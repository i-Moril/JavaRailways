package work.art1st.mineRails.core.api.util.tick

data class AsyncTickObjectMetrics(
    val category: String,
    val id: String,
    val samples: Int,
    val lastNanos: Long,
    val avg20Nanos: Double,
    val avg100Nanos: Double,
    val max100Nanos: Long
)

data class AsyncTickPhaseMetrics(
    val tickIndex: Long,
    val deltaMillis: Double,
    val objectCount: Int,
    val wallTimeNanos: Long,
    val totalObjectNanos: Long,
    val errorCount: Int
)

data class MainThreadTickMetrics(
    val tickIndex: Long,
    val computeNanos: Long,
    val blockedWaitAsyncNanos: Long,
    val totalImpactNanos: Long,
    val phaseBreakdownNanos: Map<String, Long>
)

interface TickProfiled {
    val profileCategory: String
    val profileId: String
}
