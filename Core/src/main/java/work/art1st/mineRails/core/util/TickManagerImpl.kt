package work.art1st.mineRails.core.util

import work.art1st.mineRails.core.api.util.tick.AsyncTickObjectMetrics
import work.art1st.mineRails.core.api.util.tick.AsyncTickPhaseMetrics
import work.art1st.mineRails.core.api.util.tick.MainThreadTickMetrics
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickManager
import work.art1st.mineRails.core.api.util.tick.TickProfiled
import work.art1st.mineRails.core.api.util.tick.TickingObject
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

class TickManagerImpl(
    private val scheduler: ScheduledExecutorService,
    private var currentTick: Long
) : TickManager {
    private val tickingObjects: MutableSet<TickingObject> = HashSet()
    private val tickingSynchronizableObjects: MutableSet<Synchronizable> = HashSet()

    private val afterCommitTasks: ConcurrentLinkedQueue<Runnable> = ConcurrentLinkedQueue()
    private val postTickTasks: ConcurrentLinkedQueue<Runnable> = ConcurrentLinkedQueue()
    private var awaitAsyncAndCommit: () -> PostTickBarrierMetrics = { PostTickBarrierMetrics.EMPTY }
    private var currentTickIndex: Long = 0L
    private var preTickComputeNanos: Long = 0L

    private val asyncObjectStates = ConcurrentHashMap<TickMetricKey, AsyncObjectMetricState>()

    @Volatile
    private var lastAsyncPhaseMetricsSnapshot: AsyncTickPhaseMetrics? = null

    @Volatile
    private var lastMainThreadTickMetricsSnapshot: MainThreadTickMetrics? = null

    override fun register(obj: TickingObject) {
        scheduleMainThreadPostTickTask {
            if (obj is Synchronizable) {
                tickingSynchronizableObjects.add(obj)
            }
            tickingObjects.add(obj)
        }
    }

    override fun unregister(obj: TickingObject) {
        scheduleMainThreadPostTickTask {
            if (obj is Synchronizable) {
                tickingSynchronizableObjects.remove(obj)
            }
            tickingObjects.remove(obj)
        }
    }

    override fun preTick(currentTimeMs: Long) {
        currentTickIndex += 1L
        val preTickStartNanos = System.nanoTime()
        val deltaT = (currentTimeMs - currentTick).toDouble()
        currentTick = currentTimeMs
        val objects = tickingObjects.toList()
        val synchronizableObjects = tickingSynchronizableObjects.toList()
        val tickLatch = CountDownLatch(objects.size)
        val tickErrors = ConcurrentLinkedQueue<Throwable>()
        val successfulSynchronizableObjects = ConcurrentHashMap.newKeySet<Synchronizable>()
        val totalObjectNanos = AtomicLong(0L)
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
                                successfulSynchronizableObjects.add(obj)
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
            tickErrors.forEach { it.printStackTrace() }

            val commitStartNanos = System.nanoTime()
            synchronizableObjects.forEach {
                if (successfulSynchronizableObjects.contains(it)) {
                    try {
                        it.commit()
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
            }
            val commitNanos = System.nanoTime() - commitStartNanos

            lastAsyncPhaseMetricsSnapshot = AsyncTickPhaseMetrics(
                tickIndex = currentTickIndex,
                deltaMillis = deltaT,
                objectCount = objects.size,
                wallTimeNanos = wallTimeNanos,
                totalObjectNanos = totalObjectNanos.get(),
                errorCount = tickErrors.size
            )

            PostTickBarrierMetrics(waitNanos, commitNanos)
        }
    }

    override fun postTick() {
        val phaseBreakdown = linkedMapOf<String, Long>()
        phaseBreakdown["preTick.snapshot_and_schedule"] = preTickComputeNanos

        val barrierMetrics = awaitAsyncAndCommit()
        phaseBreakdown["postTick.wait_async"] = barrierMetrics.waitNanos
        phaseBreakdown["postTick.commit"] = barrierMetrics.commitNanos
        phaseBreakdown["postTick.after_commit_tasks"] = drainQueue(afterCommitTasks)
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

    override fun <T> scheduleMainThreadAfterCommitTask(task: Callable<T>): Future<T> {
        val futureTask = FutureTask(task)
        afterCommitTasks.add {
            futureTask.run()
        }
        return futureTask
    }

    override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> {
        val futureTask = FutureTask(task)
        postTickTasks.add {
            futureTask.run()
        }
        return futureTask
    }

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
        val commitNanos: Long
    ) {
        companion object {
            val EMPTY = PostTickBarrierMetrics(0L, 0L)
        }
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
