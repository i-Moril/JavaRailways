package work.art1st.javarailways.bukkit.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.lifecycle.GentleUnloadPreparation
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class GentleUnloadPreparationTest {
    private val logger = Logger.getAnonymousLogger()

    @Test
    fun `single worker returns first request before executing owner tasks and nested cleanup`() {
        val global = ArrayDeque<() -> Unit>()
        val owner = ArrayDeque<PlatformCleanup.Task>()
        var saved = false
        var started = 0
        val preparation = GentleUnloadPreparation(global::add, {
            started++
            PlatformCleanup.start {
                owner.add(PlatformCleanup.capture {
                    owner.add(PlatformCleanup.capture { }!!)
                }!!)
            }.completion.thenApply { saved = true; Unit }
        }, logger)
        assertFalse(preparation.request())
        assertEquals(0, started, "First PlugManX cascade must return before services retire")
        assertFalse(preparation.request())
        assertEquals(1, global.size)
        global.remove().invoke()
        assertFalse(preparation.request())
        owner.remove().run()
        assertFalse(saved)
        assertFalse(preparation.request())
        owner.remove().run()
        assertTrue(saved)
        assertTrue(preparation.request())
        assertEquals(1, started)
    }

    @Test
    fun `preparation failure remains refused and does not rerun`() {
        val global = ArrayDeque<() -> Unit>()
        val result = CompletableFuture<Unit>()
        val preparation = GentleUnloadPreparation(global::add, { result }, logger)
        assertFalse(preparation.request())
        global.remove().invoke()
        result.completeExceptionally(IllegalStateException("snapshot failed"))
        assertFalse(preparation.request())
        assertFalse(preparation.request())
        assertTrue(global.isEmpty())
    }

    @Test
    fun `disable before queued preparation prevents starting cleanup`() {
        val global = ArrayDeque<() -> Unit>()
        val preparation = GentleUnloadPreparation(global::add, { fail("Preparation started after disable") }, logger)
        assertFalse(preparation.request())
        preparation.interrupted()
        global.remove().invoke()
        assertFalse(preparation.request())
    }

    @Test
    fun `asynchronous timeout refuses unload and fences late owner callbacks`() {
        var task: PlatformCleanup.Task? = null
        var ran = false
        val operation = PlatformCleanup.start(timeoutMillis = 25) {
            task = PlatformCleanup.capture { ran = true }
        }
        assertThrows(java.util.concurrent.ExecutionException::class.java) {
            operation.completion.get(5, TimeUnit.SECONDS)
        }
        task!!.run()
        assertFalse(ran)
    }

    @Test
    fun `cancellation drains only running owner callbacks and skips queued tasks`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var running: PlatformCleanup.Task? = null
        var pending: PlatformCleanup.Task? = null
        val operation = PlatformCleanup.start {
            running = PlatformCleanup.capture {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            pending = PlatformCleanup.capture { fail("Pending callback ran after disable") }
        }
        val worker = CompletableFuture.runAsync { running!!.run() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val drained = operation.cancel()
        assertFalse(drained.isDone)
        pending!!.run()
        release.countDown()
        worker.get(5, TimeUnit.SECONDS)
        drained.get(5, TimeUnit.SECONDS)
    }
}
