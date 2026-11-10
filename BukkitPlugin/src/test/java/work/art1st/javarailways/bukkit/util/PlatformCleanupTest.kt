package work.art1st.javarailways.bukkit.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class PlatformCleanupTest {
    @Test
    fun `stopped Folia cleanup releases local state without accessing any native owner`() {
        fun <T> unused(type: Class<T>): T = type.cast(Proxy.newProxyInstance(
            type.classLoader, arrayOf(type)
        ) { _, method, _ -> error("Native access during stopped-region cleanup: ${method.name}") })
        val dispatcher = PaperPlatformDispatcher(unused(Plugin::class.java))
        val entity = unused(Entity::class.java)
        var released = false
        PlatformCleanup.runAndAwait(skipOwnerTasks = true) {
            released = true
            dispatcher.runAt(Location(null, 0.0, 0.0, 0.0)) { fail("Owner action ran") }
            assertFalse(dispatcher.runForEntity(
                entity, { fail("Entity action ran") }, { fail("Retired callback ran") }
            ))
            assertNull(dispatcher.runForEntityLater(
                entity, 1L, { fail("Delayed action ran") }, { fail("Delayed retired callback ran") }
            ))
        }
        assertTrue(released)
        assertFalse(PlatformCleanup.skipsOwnerTasks())
        PlatformCleanup.runAndAwait {
            assertFalse(PlatformCleanup.skipsOwnerTasks(), "Hot-disable cleanup must retain owner work")
            PlatformCleanup.capture { released = false }!!.run()
        }
        assertFalse(released)
    }

    @Test
    fun `wait includes owner tasks submitted by another owner task`() {
        val submitted = CountDownLatch(1)
        val tasks = java.util.concurrent.LinkedBlockingQueue<PlatformCleanup.Task>()
        val calls = AtomicInteger()
        val finished = CompletableFuture.runAsync {
            PlatformCleanup.runAndAwait {
                tasks.add(PlatformCleanup.capture {
                    calls.incrementAndGet()
                    tasks.add(PlatformCleanup.capture { calls.incrementAndGet() }!!)
                }!!)
                submitted.countDown()
            }
        }
        assertTrue(submitted.await(5, TimeUnit.SECONDS))
        tasks.poll(5, TimeUnit.SECONDS)!!.run()
        assertEquals(1, calls.get())
        assertFalse(finished.isDone, "The nested native entity removal is still pending")
        tasks.poll(5, TimeUnit.SECONDS)!!.run()
        finished.get(5, TimeUnit.SECONDS)
        assertEquals(2, calls.get())
    }

    @Test
    fun `retirement completes cleanup without running an entity mutation`() {
        val calls = AtomicInteger()
        PlatformCleanup.runAndAwait {
            val task = PlatformCleanup.capture { calls.incrementAndGet() }!!
            task.retired()
            task.run()
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `owner failures are reported to the shutdown caller`() {
        val failure = IllegalStateException("native removal failed")
        val result = assertThrows(IllegalStateException::class.java) {
            PlatformCleanup.runAndAwait { PlatformCleanup.capture { throw failure }!!.run() }
        }
        assertSame(failure, result.suppressed.single())
    }

    @Test
    fun `scheduling failure cannot leave the barrier pending`() {
        val failure = IllegalArgumentException("scheduler unavailable")
        val result = assertThrows(IllegalStateException::class.java) {
            PlatformCleanup.runAndAwait { PlatformCleanup.capture {}!!.failed(failure) }
        }
        assertSame(failure, result.suppressed.single())
    }

    @Test
    fun `a timed out cleanup cannot mutate a later enable generation`() {
        lateinit var pending: PlatformCleanup.Task
        val calls = AtomicInteger()
        assertThrows(TimeoutException::class.java) {
            PlatformCleanup.runAndAwait(timeoutMillis = 1) {
                pending = PlatformCleanup.capture { calls.incrementAndGet() }!!
            }
        }
        pending.run()
        assertEquals(0, calls.get())
        // A later independent cleanup has no inherited timed-out state.
        PlatformCleanup.runAndAwait { PlatformCleanup.capture { calls.incrementAndGet() }!!.run() }
        assertEquals(1, calls.get())
    }
}
