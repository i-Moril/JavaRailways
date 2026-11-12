package work.art1st.javarailways.bukkit.logic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.tick.PhysicsStalledException
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PhysicsFailureClassificationTest {
    @Test
    fun `failed overload wake remains local when its owner observes failure after manual recovery`() {
        val clock = AtomicLong(1_000_000_000L)
        val coordinator = PhysicsCoordinator(1, nanoTime = { clock.addAndGet(2_000_000L) },
            automaticFallback = false, epochOverloadThresholdMillis = 1L, overloadsBeforeStall = 1)
        val world = createTestWorld(coordinator)
        try {
            coordinator.start()
            val failed = coordinator.wakeFromMainThread()
            val oldFailure = assertThrows(ExecutionException::class.java) {
                java.util.concurrent.CompletableFuture.supplyAsync { failed.await() }.get(5, TimeUnit.SECONDS)
            }
            val oldRejectedWake = assertThrows(PhysicsStalledException::class.java) {
                coordinator.wakeFromMainThread()
            }
            assertTrue(coordinator.resetStall().get(5, TimeUnit.SECONDS))
            assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
            assertTrue(isPhysicsOverloadFailure(oldFailure), "late owner must not turn an old overload into global halt")
            assertTrue(isPhysicsOverloadFailure(oldRejectedWake))
            assertFalse(isPhysicsOverloadFailure(ExecutionException(PhysicsStalledException("invariant failed"))))
        } finally {
            work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                try { coordinator.closeAndAwaitTermination() }
                finally { (world.tickManager as AutoCloseable).close() }
            }
        }
    }
}
