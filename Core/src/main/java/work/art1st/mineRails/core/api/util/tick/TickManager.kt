package work.art1st.mineRails.core.api.util.tick

import java.util.concurrent.Callable
import java.util.concurrent.Future

interface TickManager {
    fun register(obj: TickingObject)
    fun unregister(obj: TickingObject)
    fun preTick(currentTimeMs: Long)
    fun postTick()
    fun <T> scheduleMainThreadAfterCommitTask(task: Callable<T>): Future<T>
    fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T>

    /** Drains platform commit requests without executing their side effects on the simulation path. */
    fun drainPendingCommits(limit: Int = Int.MAX_VALUE): List<Synchronizable> = emptyList()

    fun pendingCommitMetrics(): TickCommitMailboxMetrics = TickCommitMailboxMetrics(
        size = 0,
        capacity = 0,
        peakSize = 0,
        coalescedRequests = 0L,
        droppedCapacityRequests = 0L
    )

    fun latestAsyncPhaseMetrics(): AsyncTickPhaseMetrics? = null
    fun latestMainThreadTickMetrics(): MainThreadTickMetrics? = null
    fun asyncObjectMetrics(category: String, id: String): AsyncTickObjectMetrics? = null
    fun topAsyncObjectMetrics(limit: Int): List<AsyncTickObjectMetrics> = emptyList()
}