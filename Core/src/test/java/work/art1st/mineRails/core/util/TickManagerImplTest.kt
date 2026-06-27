package work.art1st.mineRails.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import work.art1st.mineRails.core.api.util.tick.Synchronizable
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
}
