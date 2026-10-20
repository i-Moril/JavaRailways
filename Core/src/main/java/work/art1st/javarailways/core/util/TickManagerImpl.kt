package work.art1st.javarailways.core.util

import work.art1st.javarailways.core.api.util.tick.AsyncTickObjectMetrics
import work.art1st.javarailways.core.api.util.tick.AsyncTickPhaseMetrics
import work.art1st.javarailways.core.api.util.tick.MainThreadTickMetrics
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.api.util.tick.TickProfiled
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

class TickManagerImpl(
    private val scheduler: ScheduledExecutorService,
    private var currentTick: Long
) : TickManager, AutoCloseable {
    private val tickingObjects: MutableSet<TickingObject> = HashSet()
    private val pendingCommits = LatestTickCommitMailbox(DEFAULT_COMMIT_MAILBOX_CAPACITY)

    private val postTickTasks: ConcurrentLinkedQueue<Runnable> = ConcurrentLinkedQueue()
    private var awaitAsyncAndCommit: () -> PostTickBarrierMetrics = { PostTickBarrierMetrics.EMPTY }
    private var asyncPhasePending = false
    private var currentTickIndex: Long = 0L
    private var preTickComputeNanos: Long = 0L

    private val asyncObjectStates = ConcurrentHashMap<TickMetricKey, AsyncObjectMetricState>()

    @Volatile
    private var lastAsyncPhaseMetricsSnapshot: AsyncTickPhaseMetrics? = null

    @Volatile
    private var lastMainThreadTickMetricsSnapshot: MainThreadTickMetrics? = null

    override fun register(obj: TickingObject) {
        scheduleMainThreadPostTickTask {
            tickingObjects.add(obj)
        }
    }

    override fun unregister(obj: TickingObject) {
        scheduleMainThreadPostTickTask {
            tickingObjects.remove(obj)
            asyncObjectStates.remove(obj.metricKey())
            if (obj is Synchronizable) pendingCommits.remove(obj)
        }
    }

    override fun preTick(currentTimeMs: Long) {
        RailwaySimulationHalt.ensureRunning()
        if (asyncPhasePending) throw RailwaySimulationHalt.halt("Previous generic tick barrier was not consumed")
        asyncPhasePending = true
        currentTickIndex += 1L
        val preTickStartNanos = System.nanoTime()
        val deltaT = (currentTimeMs - currentTick).toDouble()
        currentTick = currentTimeMs
        val objects = tickingObjects.toList()
        val tickLatch = CountDownLatch(objects.size)
        val tickErrors = ConcurrentLinkedQueue<Throwable>()
        val totalObjectNanos = AtomicLong(0L)
        val commitPublishNanos = AtomicLong(0L)
        val asyncPhaseStartNanos = System.nanoTime()

        if (objects.isEmpty()) {
            preTickComputeNanos = System.nanoTime() - preTickStartNanos
            lastAsyncPhaseMetricsSnapshot = AsyncTickPhaseMetrics(
                tickIndex = currentTickIndex,
                deltaMillis = deltaT,
                objectCount = 0,
                wallTimeNanos = 0L,
                totalObjectNanos = 0L,
                errorCount = 0
            )
            awaitAsyncAndCommit = { PostTickBarrierMetrics.EMPTY }
            return
        }

        objects.forEach { obj ->
            try {
                scheduler.submit {
                    AsyncTickThreadMarker.runInAsyncTickContext {
                        val tickStartNanos = System.nanoTime()
                        try {
                            obj.tick(deltaT)
                            if (obj is Synchronizable) {
                                val publishStartNanos = System.nanoTime()
                                pendingCommits.offer(obj)
                                commitPublishNanos.addAndGet(System.nanoTime() - publishStartNanos)
                            }
                        } catch (t: Throwable) {
                            tickErrors.add(t)
                        } finally {
                            val durationNanos = System.nanoTime() - tickStartNanos
                            totalObjectNanos.addAndGet(durationNanos)
                            recordAsyncObjectMetric(obj, durationNanos)
                            tickLatch.countDown()
                        }
                    }
                }
            } catch (t: Throwable) {
                tickErrors.add(t)
                tickLatch.countDown()
            }
        }

        preTickComputeNanos = System.nanoTime() - preTickStartNanos

        awaitAsyncAndCommit = {
            val waitStartNanos = System.nanoTime()
            tickLatch.await()
            val waitNanos = System.nanoTime() - waitStartNanos
            val wallTimeNanos = System.nanoTime() - asyncPhaseStartNanos
            tickErrors.forEach { error ->
                LOGGER.log(Level.SEVERE, "Generic async tick $currentTickIndex failed", error)
                RailwaySimulationHalt.halt("Generic async tick $currentTickIndex failed", error)
            }

            lastAsyncPhaseMetricsSnapshot = AsyncTickPhaseMetrics(
                tickIndex = currentTickIndex,
                deltaMillis = deltaT,
                objectCount = objects.size,
                wallTimeNanos = wallTimeNanos,
                totalObjectNanos = totalObjectNanos.get(),
                errorCount = tickErrors.size
            )

            PostTickBarrierMetrics(waitNanos, commitPublishNanos.get())
        }
    }

    override fun postTick() {
        val phaseBreakdown = linkedMapOf<String, Long>()
        phaseBreakdown["preTick.snapshot_and_schedule"] = preTickComputeNanos

        val barrierMetrics = finishAsyncPhase()
        RailwaySimulationHalt.ensureRunning()
        phaseBreakdown["postTick.wait_async"] = barrierMetrics.waitNanos
        phaseBreakdown["postTick.publish_commit_requests"] = barrierMetrics.publishCommitRequestsNanos
        phaseBreakdown["postTick.post_tick_tasks"] = drainQueue(postTickTasks)

        val computeNanos = phaseBreakdown
            .filterKeys { it != "postTick.wait_async" }
            .values
            .sum()
        val blockedWaitNanos = barrierMetrics.waitNanos

        lastMainThreadTickMetricsSnapshot = MainThreadTickMetrics(
            tickIndex = currentTickIndex,
            computeNanos = computeNanos,
            blockedWaitAsyncNanos = blockedWaitNanos,
            totalImpactNanos = computeNanos + blockedWaitNanos,
            phaseBreakdownNanos = phaseBreakdown.toMap()
        )

        awaitAsyncAndCommit = { PostTickBarrierMetrics.EMPTY }
        preTickComputeNanos = 0L
    }

    /** Waits for the captured batch without executing queued owner or platform mutations. */
    fun finishPendingAsyncTick() {
        finishAsyncPhase()
    }

    private fun finishAsyncPhase(): PostTickBarrierMetrics {
        if (!asyncPhasePending) return PostTickBarrierMetrics.EMPTY
        val metrics = awaitAsyncAndCommit()
        asyncPhasePending = false
        awaitAsyncAndCommit = { PostTickBarrierMetrics.EMPTY }
        return metrics
    }

    override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> {
        val futureTask = FutureTask(task)
        postTickTasks.add {
            futureTask.run()
        }
        return futureTask
    }

    override fun drainPendingCommits(limit: Int): List<Synchronizable> = pendingCommits.drain(limit)

    override fun pendingCommitMetrics(): work.art1st.javarailways.core.api.util.tick.TickCommitMailboxMetrics =
        pendingCommits.metrics()

    override fun latestAsyncPhaseMetrics(): AsyncTickPhaseMetrics? = lastAsyncPhaseMetricsSnapshot

    override fun latestMainThreadTickMetrics(): MainThreadTickMetrics? = lastMainThreadTickMetricsSnapshot

    override fun asyncObjectMetrics(category: String, id: String): AsyncTickObjectMetrics? {
        return asyncObjectStates[TickMetricKey(category, id)]?.snapshot(category, id)
    }

    override fun topAsyncObjectMetrics(limit: Int): List<AsyncTickObjectMetrics> {
        if (limit <= 0) return emptyList()
        return asyncObjectStates.entries
            .mapNotNull { (key, state) -> state.snapshot(key.category, key.id) }
            .sortedWith(compareByDescending<AsyncTickObjectMetrics> { it.avg20Nanos }.thenByDescending { it.lastNanos })
            .take(limit)
    }

    private fun drainQueue(queue: ConcurrentLinkedQueue<Runnable>): Long {
        val startNanos = System.nanoTime()
        while (true) {
            val task = queue.poll() ?: break
            task.run()
        }
        return System.nanoTime() - startNanos
    }

    private fun recordAsyncObjectMetric(obj: TickingObject, durationNanos: Long) {
        val key = obj.metricKey()
        asyncObjectStates.computeIfAbsent(key) { AsyncObjectMetricState() }.record(durationNanos)
    }

    private fun TickingObject.metricKey(): TickMetricKey {
        return if (this is TickProfiled) {
            TickMetricKey(profileCategory, profileId)
        } else {
            TickMetricKey(
                category = this::class.simpleName ?: this::class.java.name,
                id = Integer.toHexString(System.identityHashCode(this))
            )
        }
    }

    private data class TickMetricKey(
        val category: String,
        val id: String
    )

    private data class PostTickBarrierMetrics(
        val waitNanos: Long,
        val publishCommitRequestsNanos: Long
    ) {
        companion object {
            val EMPTY = PostTickBarrierMetrics(0L, 0L)
        }
    }

    override fun close() {
        pendingCommits.clear()
        scheduler.shutdownNow()
    }

    /** Stops generic workers and confirms callback exit before a host releases World dependencies. */
    fun closeAndAwaitTermination(worldRuntimeId: String) {
        check(!AsyncTickThreadMarker.isAsyncTickThread()) { "Generic teardown must not wait on its own worker" }
        work.art1st.javarailways.core.api.threading.ThreadGuard.ensureMainThread(
            "TickManagerImpl.closeAndAwaitTermination")
        close()
        awaitExecutorTermination(scheduler, "World $worldRuntimeId generic tick workers", LOGGER)
        pendingCommits.clear()
    }

    companion object {
        private val LOGGER = Logger.getLogger(TickManagerImpl::class.java.name)
        private const val DEFAULT_COMMIT_MAILBOX_CAPACITY = 65_536
    }

    private class AsyncObjectMetricState {
        private val history = LongHistory(100)

        fun record(durationNanos: Long) {
            history.record(durationNanos)
        }

        fun snapshot(category: String, id: String): AsyncTickObjectMetrics? {
            val stats = history.snapshot() ?: return null
            return AsyncTickObjectMetrics(
                category = category,
                id = id,
                samples = stats.samples,
                lastNanos = stats.last,
                avg20Nanos = stats.average(20),
                avg100Nanos = stats.average(100),
                max100Nanos = stats.max(100)
            )
        }
    }

    private class LongHistory(
        private val capacity: Int
    ) {
        private val values = LongArray(capacity)
        private var size = 0
        private var nextIndex = 0

        @Synchronized
        fun record(value: Long) {
            values[nextIndex] = value
            nextIndex = (nextIndex + 1) % capacity
            if (size < capacity) {
                size += 1
            }
        }

        @Synchronized
        fun snapshot(): Snapshot? {
            if (size == 0) return null
            val copy = LongArray(size)
            for (i in 0 until size) {
                val index = (nextIndex - size + i + capacity) % capacity
                copy[i] = values[index]
            }
            return Snapshot(copy)
        }

        class Snapshot(
            private val samplesInOrder: LongArray
        ) {
            val samples: Int get() = samplesInOrder.size
            val last: Long get() = samplesInOrder.last()

            fun average(lastCount: Int): Double {
                val slice = tail(lastCount)
                return if (slice.isEmpty()) 0.0 else slice.average()
            }

            fun max(lastCount: Int): Long {
                val slice = tail(lastCount)
                return if (slice.isEmpty()) 0L else slice.maxOrNull() ?: 0L
            }

            private fun tail(lastCount: Int): LongArray {
                val size = minOf(lastCount, samplesInOrder.size)
                return samplesInOrder.copyOfRange(samplesInOrder.size - size, samplesInOrder.size)
            }
        }
    }
}
