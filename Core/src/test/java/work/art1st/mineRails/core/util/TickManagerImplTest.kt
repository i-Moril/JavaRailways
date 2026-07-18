package work.art1st.mineRails.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickProfiled
import work.art1st.mineRails.core.api.util.tick.TickingObject
import java.time.Duration
import java.util.concurrent.Executors

class TickManagerImplTest {

    @Test
    fun `postTick publishes commit requests without executing platform side effects`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val manager = TickManagerImpl(scheduler, 0L)
            val first = RecordingSynchronizableTickingObject()
            val second = RecordingSynchronizableTickingObject()

            manager.register(first)
            manager.register(second)
            manager.postTick()
            assertTimeoutPreemptively(Duration.ofSeconds(1)) {
                manager.preTick(100L)
                manager.postTick()
            }

            assertEquals(1, first.tickCount)
            assertEquals(1, second.tickCount)
            assertEquals(0, first.commitCount)
            assertEquals(0, second.commitCount)

            val consumerThread = Thread.currentThread()
            manager.drainPendingCommits().forEach(Synchronizable::commit)

            assertEquals(1, first.commitCount)
            assertEquals(1, second.commitCount)
            assertSame(consumerThread, first.commitThread)
            assertSame(consumerThread, second.commitThread)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `slow commit consumer retains only one request per synchronizable object`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val manager = TickManagerImpl(scheduler, 0L)
            val obj = RecordingSynchronizableTickingObject()
            manager.register(obj)
            manager.postTick()

            repeat(3) { tick ->
                manager.preTick((tick + 1L) * 50L)
                manager.postTick()
            }

            assertEquals(3, obj.tickCount)
            assertEquals(0, obj.commitCount)
            assertEquals(1, manager.pendingCommitMetrics().size)
            assertEquals(2L, manager.pendingCommitMetrics().coalescedRequests)
            assertEquals(1, manager.drainPendingCommits().size)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `captures async object metrics and main thread tick metrics`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val manager = TickManagerImpl(scheduler, 0L)
            val obj = ProfiledSynchronizableTickingObject("train-controller", "T1")

            manager.register(obj)
            manager.postTick()
            manager.preTick(50L)
            manager.postTick()

            val asyncPhase = manager.latestAsyncPhaseMetrics()
            val objectMetrics = manager.asyncObjectMetrics("train-controller", "T1")
            val mainThreadMetrics = manager.latestMainThreadTickMetrics()

            assertNotNull(asyncPhase)
            assertEquals(1, asyncPhase!!.objectCount)
            assertTrue(asyncPhase.wallTimeNanos >= 0L)
            assertNotNull(objectMetrics)
            assertEquals("train-controller", objectMetrics!!.category)
            assertEquals("T1", objectMetrics.id)
            assertTrue(objectMetrics.lastNanos > 0L)
            assertTrue(obj.sawAsyncTickThread)
            assertNotNull(mainThreadMetrics)
            assertTrue(mainThreadMetrics!!.phaseBreakdownNanos.containsKey("preTick.snapshot_and_schedule"))
            assertTrue(mainThreadMetrics.phaseBreakdownNanos.containsKey("postTick.wait_async"))
            assertTrue(mainThreadMetrics.phaseBreakdownNanos.containsKey("postTick.publish_commit_requests"))
        } finally {
            scheduler.shutdownNow()
        }
    }

    private class RecordingSynchronizableTickingObject : TickingObject, Synchronizable {
        var tickCount = 0
            private set
        var commitCount = 0
            private set
        var commitThread: Thread? = null
            private set

        override fun tick(deltaT: Double) {
            tickCount++
        }

        override fun commit() {
            commitCount++
            commitThread = Thread.currentThread()
        }
    }

    private class ProfiledSynchronizableTickingObject(
        override val profileCategory: String,
        override val profileId: String
    ) : TickingObject, Synchronizable, TickProfiled {
        @Volatile
        var sawAsyncTickThread: Boolean = false
            private set

        override fun tick(deltaT: Double) {
            sawAsyncTickThread = ThreadGuard.isAsyncTickThread()
        }

        override fun commit() = Unit
    }
}
