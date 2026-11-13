package work.art1st.javarailways.bukkit.logic

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerPhysicsStartupTest {
    @Test
    fun `preparation waits for first actual global tick before starting physics and generic ticks`() {
        Fixture().use { fixture ->
            val initial = fixture.world()
            fixture.startup.prepare()
            fixture.startup.startWorld { initial.world.physicsCoordinator.start() }
            assertEquals(1, fixture.scheduled)
            assertFalse(initial.epoch.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, fixture.ticks)
            fixture.fireTick()
            assertTrue(initial.epoch.await(5, TimeUnit.SECONDS), "real fallback timer must run after startup")
            assertEquals(1, fixture.starts)
            assertEquals(1, fixture.ticks)
            fixture.fireTick()
            assertEquals(1, fixture.starts)
            assertEquals(1, fixture.scheduled)
            assertEquals(2, fixture.ticks)
        }
    }

    @Test
    fun `world registered before first global tick waits for the shared startup`() {
        Fixture().use { fixture ->
            fixture.startup.prepare()
            val late = fixture.world()
            fixture.startup.startWorld { late.world.physicsCoordinator.start() }
            assertFalse(late.epoch.await(100, TimeUnit.MILLISECONDS))
            fixture.fireTick()
            assertTrue(late.epoch.await(5, TimeUnit.SECONDS))
            assertEquals(1, fixture.starts)
        }
    }

    @Test
    fun `world loaded after server startup starts its own fallback timer`() {
        Fixture().use { fixture ->
            fixture.startup.prepare()
            fixture.fireTick()
            val late = fixture.world()
            fixture.startup.startWorld { late.world.physicsCoordinator.start() }
            assertTrue(late.epoch.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `stop cancels armed task and dispatched callback cannot revive physics`() {
        Fixture().use { fixture ->
            val initial = fixture.world()
            fixture.startup.prepare()
            fixture.startup.stop()
            assertEquals(1, fixture.cancelled)
            fixture.fireTick()
            fixture.startup.startWorld { initial.world.physicsCoordinator.start() }
            assertFalse(initial.epoch.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, fixture.ticks)
            assertEquals(0, fixture.starts)
            assertEquals(1, fixture.scheduled)
            assertFalse(fixture.startup.isRunning)
        }
    }

    @Test
    fun `stop after first tick cancels subsequent ticks and late world startup`() {
        Fixture().use { fixture ->
            fixture.startup.prepare()
            fixture.fireTick()
            fixture.startup.stop()
            val late = fixture.world()
            fixture.startup.startWorld { late.world.physicsCoordinator.start() }
            fixture.fireTick()
            assertFalse(late.epoch.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1, fixture.cancelled)
            assertEquals(1, fixture.starts)
            assertEquals(1, fixture.ticks)
            assertFalse(fixture.startup.isRunning)
        }
    }

    private data class TimedWorld(val world: CoreWorld, val epoch: CountDownLatch)

    private class Fixture : AutoCloseable {
        val worlds = mutableListOf<TimedWorld>()
        var scheduled = 0
        var cancelled = 0
        var starts = 0
        var ticks = 0
        private var callback: (() -> Unit)? = null
        val startup = ServerPhysicsStartup(
            scheduleTicks = { action ->
                scheduled++
                callback = action
                Proxy.newProxyInstance(ScheduledTask::class.java.classLoader, arrayOf(ScheduledTask::class.java)) { _, method, _ ->
                    if (method.name == "cancel") cancelled++ else error("Unexpected task method: ${method.name}")
                    null
                } as ScheduledTask
            },
            startWorlds = {
                starts++
                worlds.forEach { it.world.physicsCoordinator.start() }
            },
            tick = { ticks++ }
        )

        fun world(): TimedWorld {
            val world = createTestWorld(PhysicsCoordinator(1, fallbackPeriodMillis = 5L,
                mainHeartbeatTimeoutMillis = 10L, epochOverloadThresholdMillis = 1000L))
            val epoch = CountDownLatch(1)
            world.physicsCoordinator.registerRailwayEpochProcessor { epoch.countDown() }
            return TimedWorld(world, epoch).also(worlds::add)
        }

        fun fireTick() = checkNotNull(callback).invoke()

        override fun close() {
            startup.stop()
            worlds.forEach {
                work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                    try { it.world.physicsCoordinator.closeAndAwaitTermination() }
                    finally { (it.world.tickManager as AutoCloseable).close() }
                }
            }
        }
    }
}
