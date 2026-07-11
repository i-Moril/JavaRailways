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
import java.util.concurrent.atomic.AtomicReference

class TickManagerImplTest {

    @Test
    fun `single thread scheduler commits multiple synchronizable objects on postTick thread`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val manager = TickManagerImpl(scheduler, 0L)
            val first = RecordingSynchronizableTickingObject()
            val second = RecordingSynchronizableTickingObject()

            manager.register(first)
            manager.register(second)
            manager.preTick(50L)
            manager.postTick()

            val postTickThread = AtomicReference<Thread>()
            assertTimeoutPreemptively(Duration.ofSeconds(1)) {
                postTickThread.set(Thread.currentThread())
                manager.preTick(100L)
                manager.postTick()
            }

            assertEquals(1, first.tickCount)
            assertEquals(1, second.tickCount)
            assertEquals(1, first.commitCount)
            assertEquals(1, second.commitCount)
            assertSame(postTickThread.get(), first.commitThread)
            assertSame(postTickThread.get(), second.commitThread)
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
            assertTrue(mainThreadMetrics.phaseBreakdownNanos.containsKey("postTick.commit"))
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
