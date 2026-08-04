package work.art1st.javarailways.core.physics

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.util.IsolatedMainThreadMarker
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.util.ThreadGuardPlatformBridge
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class CoreWorldThreadGuardTest {
    @Test
    fun `isolated main marker is scoped to the current simulation call`() {
        assertFalse(ThreadGuard.isMainThread())
        IsolatedMainThreadMarker.run { assertTrue(ThreadGuard.isMainThread()) }
        assertFalse(ThreadGuard.isMainThread())
    }

    @Test
    fun `getNearestRail fails on installed main thread`() {
        val mainThread = Thread.currentThread()
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === mainThread
        })
        try {
            val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext)
            assertThrows(IllegalStateException::class.java) {
                world.getNearestRail(Vector3d())
            }
        } finally {
            ThreadGuard.clearPlatformBridge()
        }
    }

    private object DummyTickManager : TickManager {
        override fun register(obj: work.art1st.javarailways.core.api.util.tick.TickingObject) = Unit
        override fun unregister(obj: work.art1st.javarailways.core.api.util.tick.TickingObject) = Unit
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit
        override fun <T> scheduleMainThreadAfterCommitTask(task: Callable<T>): Future<T> = FutureTask(task).apply { run() }
        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> = FutureTask(task).apply { run() }
    }
}
