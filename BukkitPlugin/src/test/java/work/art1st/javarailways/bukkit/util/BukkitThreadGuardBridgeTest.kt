package work.art1st.javarailways.bukkit.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator

class BukkitThreadGuardBridgeTest {
    @Test
    fun `same world simulation contexts are mutually exclusive`() {
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val executor = Executors.newFixedThreadPool(2)
        var active = 0
        var peak = 0
        try {
            val futures = (1..20).map {
                executor.submit {
                    BukkitThreadGuardBridge.runInSimulationContext(world) {
                        active += 1
                        peak = maxOf(peak, active)
                        Thread.yield()
                        active -= 1
                    }
                }
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            close(world)
        }

        assertEquals(1, peak)
    }

    @Test
    fun `region wait releases and restores simulation context`() {
        val executor = Executors.newSingleThreadExecutor()
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        try {
            BukkitThreadGuardBridge.runInSimulationContext(world) {
                val completed = BukkitThreadGuardBridge.runOutsideSimulationContext {
                    CompletableFuture.supplyAsync({
                        BukkitThreadGuardBridge.runInSimulationContext(world) { true }
                    }, executor).get(5, TimeUnit.SECONDS)
                }
                assertTrue(completed)
                assertTrue(BukkitThreadGuardBridge.isMainThread())
            }
        } finally {
            executor.shutdownNow()
            close(world)
        }
    }

    @Test
    fun `another world progresses while first world owner is paused`() {
        val first = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val second = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val executor = Executors.newSingleThreadExecutor()
        try {
            BukkitThreadGuardBridge.runInSimulationContext(first) {
                assertTrue(executor.submit<Boolean> {
                    BukkitThreadGuardBridge.runInSimulationContext(second) { true }
                }.get(5, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            close(first)
            close(second)
        }
    }

    private fun close(world: CoreWorld) {
        world.physicsCoordinator.close()
        (world.tickManager as AutoCloseable).close()
    }
}
