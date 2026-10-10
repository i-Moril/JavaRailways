package work.art1st.javarailways.core.di

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class KoinWorldParentContextTest {
    @Test
    fun `closing registration removes child scope and permits recreation`() {
        val parentScope = javaRailwaysParentScope()
        val context = KoinWorldParentContext(parentScope)
        val physics = PhysicsCoordinator(1, automaticFallback = false)
        val world = CoreWorld.createEmpty(DummyTickManager, context, physics)
        val koin = parentScope.getKoin()
        val scopeId = "world_${world.runtimeId}"
        try {
            assertSame(world, koin.getScope(scopeId).get<CoreWorld>())

            world.releaseRuntimeBinding()
            world.releaseRuntimeBinding()
            assertNull(koin.getScopeOrNull(scopeId))

            val recreated = context.attachWorld(world)
            assertSame(world, koin.getScope(scopeId).get<CoreWorld>())
            recreated.close()
            recreated.close()
            assertNull(koin.getScopeOrNull(scopeId))
        } finally {
            koin.getScopeOrNull(scopeId)?.close()
            physics.close()
        }
    }

    private object DummyTickManager : TickManager {
        override fun register(obj: TickingObject) = Unit
        override fun unregister(obj: TickingObject) = Unit
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit
        override fun <T> scheduleMainThreadAfterCommitTask(task: Callable<T>): Future<T> =
            FutureTask(task).apply { run() }

        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> =
            FutureTask(task).apply { run() }
    }
}
