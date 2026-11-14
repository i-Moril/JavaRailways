package work.art1st.javarailways.bukkit.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlatformDispatchLifecycleTest {
    private val concurrentExecutor = Executors.newFixedThreadPool(2)

    @AfterEach
    fun closeConcurrentExecutor() {
        concurrentExecutor.shutdownNow()
        assertTrue(concurrentExecutor.awaitTermination(5, TimeUnit.SECONDS), "test concurrent tasks must terminate")
    }

    @Test
    fun `retired dispatcher rejects callbacks even after a new dispatcher starts`() {
        val old = PlatformDispatchLifecycle()
        old.stopAndAwait()
        val fresh = PlatformDispatchLifecycle()
        var calls = 0
        assertFalse(old.execute { calls++ })
        assertTrue(fresh.execute { calls++ })
        assertEquals(1, calls)
    }

    @Test
    fun `stop waits for an already executing mutation`() {
        val lifecycle = PlatformDispatchLifecycle()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val action = CompletableFuture.runAsync({
            lifecycle.execute {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }, concurrentExecutor)
        val stopping: CompletableFuture<Void>
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stopping = CompletableFuture.runAsync({ lifecycle.stopAndAwait() }, concurrentExecutor)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (lifecycle.isActive() && System.nanoTime() < deadline) Thread.yield()
            assertFalse(lifecycle.isActive())
            assertFalse(stopping.isDone)
        } finally {
            release.countDown()
        }
        action.get(5, TimeUnit.SECONDS)
        stopping.get(5, TimeUnit.SECONDS)
        assertFalse(lifecycle.execute { fail("Late callback executed") })
    }

    @Test
    fun `a callback can initiate disable without waiting for itself`() {
        val lifecycle = PlatformDispatchLifecycle()
        assertTrue(lifecycle.execute { lifecycle.stopAndAwait() })
        assertFalse(lifecycle.isActive())
        lifecycle.stopAndAwait()
    }
}
