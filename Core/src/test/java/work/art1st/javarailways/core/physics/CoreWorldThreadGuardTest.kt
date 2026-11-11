package work.art1st.javarailways.core.physics

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class CoreWorldThreadGuardTest {
    @Test
    fun `isolated main marker is scoped to the current simulation call`() {
        assertFalse(ThreadGuard.isMainThread())
        IsolatedSimulationThreadMarker.run { assertTrue(ThreadGuard.isMainThread()) }
        assertFalse(ThreadGuard.isMainThread())
    }

    @Test
    fun `getNearestRail fails on installed main thread`() {
        val mainThread = Thread.currentThread()
        val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === mainThread
        })
        try {
            PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
                val world = CoreWorld.createEmpty(DummyTickManager, coordinator)
                assertThrows(IllegalStateException::class.java) {
                    world.getNearestRail(Vector3d())
                }
            }
        } finally {
            registration.close()
        }
    }

    private object DummyTickManager : TickManager {
        override fun register(obj: work.art1st.javarailways.core.api.util.tick.TickingObject) = Unit
        override fun unregister(obj: work.art1st.javarailways.core.api.util.tick.TickingObject) = Unit
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit
        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> = FutureTask(task).apply { run() }
    }
}
