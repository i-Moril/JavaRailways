package work.art1st.javarailways.bukkit.logic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WorldTickRunnerRegistryTest {
    @Test
    fun `coalesced wake crossing a snapshot boundary captures once without replaying missed periods`() {
        val interval = WorldSnapshotInterval(1200L)
        val captured = mutableListOf<Long>()
        listOf(1199L, 1201L, 1202L, 6001L, 6002L).forEach { tick ->
            interval.captureIfDue(tick) { captured += tick }
        }
        assertEquals(listOf(1201L, 6001L), captured)
    }

    @Test
    fun `failed capture leaves the same interval eligible for the next wake`() {
        val interval = WorldSnapshotInterval(1200L)
        assertThrows(IllegalStateException::class.java) {
            interval.captureIfDue(1201L) { error("capture failed") }
        }
        var captures = 0
        interval.captureIfDue(1202L) { captures += 1 }
        interval.captureIfDue(1203L) { error("completed interval must not capture twice") }
        assertEquals(1, captures)
    }

    @Test
    fun `retired directory does not retain an unloaded core world`() {
        val registry = WorldTickRunnerRegistry<work.art1st.javarailways.core.physics.CoreWorld>()
        try {
            val reference = retireWorld(registry)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reference.get() != null && System.nanoTime() < deadline) {
                System.gc()
                Thread.yield()
            }
            assertNull(reference.get(), "a retired weak key must not retain the World graph")
        } finally {
            registry.close()
        }
    }

    private fun retireWorld(registry: WorldTickRunnerRegistry<work.art1st.javarailways.core.physics.CoreWorld>):
        java.lang.ref.WeakReference<work.art1st.javarailways.core.physics.CoreWorld> {
        val world = work.art1st.javarailways.core.physics.train.createTestWorld(
            work.art1st.javarailways.core.physics.tick.PhysicsCoordinator(1, automaticFallback = false))
        registry.offer(world, WorldTickWake(1L, 50L)) { WorldTickRunner("weak-retire", {}, { throw it }) }
        registry.retire(world)
        world.physicsCoordinator.close()
        world.releaseRuntimeBinding()
        (world.tickManager as AutoCloseable).close()
        return java.lang.ref.WeakReference(world)
    }

    @Test
    fun `retire racing an earlier offer prevents later old offers from recreating a runner`() {
        val registry = WorldTickRunnerRegistry<Any>()
        val key = Any()
        val executor = Executors.newFixedThreadPool(2)
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val creatingThread = AtomicReference<Thread>()
        val retiringThread = AtomicReference<Thread>()
        val creates = AtomicInteger()
        try {
            val offered = executor.submit {
                creatingThread.set(Thread.currentThread())
                registry.offer(key, WorldTickWake(1L, 50L)) {
                    creates.incrementAndGet()
                    factoryEntered.countDown()
                    check(releaseFactory.await(5, TimeUnit.SECONDS))
                    WorldTickRunner("test", {}, { throw it })
                }
            }
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))
            val retired = executor.submit {
                retiringThread.set(Thread.currentThread())
                registry.retire(key)
            }
            val bean = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (retiringThread.get()?.let { bean.getThreadInfo(it.threadId())?.lockOwnerId } != creatingThread.get().threadId() &&
                System.nanoTime() < deadline) Thread.yield()
            assertEquals(creatingThread.get().threadId(), bean.getThreadInfo(retiringThread.get().threadId())?.lockOwnerId)
            releaseFactory.countDown()
            offered.get(5, TimeUnit.SECONDS)
            retired.get(5, TimeUnit.SECONDS)
            registry.offer(key, WorldTickWake(2L, 100L)) { error("retired runner must not be recreated") }
            registry.close()
            registry.offer(Any(), WorldTickWake(3L, 150L)) { error("stopped directory must not create a runner") }
            assertEquals(1, creates.get())
        } finally {
            releaseFactory.countDown()
            executor.shutdownNow()
            registry.close()
        }
    }
}
