package work.art1st.javarailways.bukkit.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BukkitThreadGuardBridgeTest {
    @Test
    fun `simulation contexts are mutually exclusive`() {
        val executor = Executors.newFixedThreadPool(2)
        var active = 0
        var peak = 0
        try {
            val futures = (1..20).map {
                executor.submit {
                    BukkitThreadGuardBridge.runInSimulationContext {
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
        }

        assertEquals(1, peak)
    }

    @Test
    fun `region wait releases and restores simulation context`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            BukkitThreadGuardBridge.runInSimulationContext {
                val completed = BukkitThreadGuardBridge.runOutsideSimulationContext {
                    CompletableFuture.supplyAsync({
                        BukkitThreadGuardBridge.runInSimulationContext { true }
                    }, executor).get(5, TimeUnit.SECONDS)
                }
                assertTrue(completed)
                assertTrue(BukkitThreadGuardBridge.isMainThread())
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
