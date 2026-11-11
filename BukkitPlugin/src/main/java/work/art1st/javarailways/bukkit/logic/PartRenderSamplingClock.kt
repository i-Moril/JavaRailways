package work.art1st.javarailways.bukkit.logic

/** World-owner sampling: ordinary clocks are 2Hz; operational mutations wake a sample immediately. */
internal class PartRenderSamplingClock {
    private var lastBucket: Long? = null
    private var lastDisplayRevision: Long? = null

    fun sample(tick: Long, displayRevision: Long): Boolean {
        require(tick >= 0L && displayRevision >= 0L)
        val bucket = tick / 10L
        val due = bucket != lastBucket || displayRevision != lastDisplayRevision
        lastBucket = bucket
        lastDisplayRevision = displayRevision
        return due
    }
}
