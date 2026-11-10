package work.art1st.javarailways.core.di

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

class JavaRailwaysKoinLifecycleTest {
    private class OtherPluginService

    @Test
    fun `world detach and repeated runtime release preserve shared Koin host`() {
        ensureJavaRailwaysKoinStarted()
        val host = GlobalContext.get()
        val service = OtherPluginService()
        val otherModule = module { single { service } }
        host.loadModules(listOf(otherModule))
        try {
            repeat(3) {
                val parent = javaRailwaysParentScope()
                val context = KoinWorldParentContext(parent)
                val tickManager = TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L)
                val first = CoreWorld.createEmpty(tickManager, context)
                val second = CoreWorld.createEmpty(tickManager, context)
                try {
                    val firstScope = host.getScope("world_${first.runtimeId}")
                    val secondScope = host.getScope("world_${second.runtimeId}")
                    assertSame(first, firstScope.get<CoreWorld>())
                    assertSame(second, secondScope.get<CoreWorld>())
                    assertThrows(IllegalStateException::class.java) { releaseJavaRailwaysKoinRuntime() }
                    first.releaseRuntimeContext()
                    first.releaseRuntimeContext()
                    assertTrue(firstScope.closed)
                    assertNull(host.getScopeOrNull(firstScope.id))
                    assertFalse(secondScope.closed)
                    assertSame(second, secondScope.get<CoreWorld>())
                } finally {
                    first.releaseRuntimeContext()
                    second.releaseRuntimeContext()
                    first.physicsCoordinator.close()
                    second.physicsCoordinator.close()
                    tickManager.close()
                }
                releaseJavaRailwaysKoinRuntime()
                releaseJavaRailwaysKoinRuntime()
                assertTrue(parent.closed)
                assertNull(host.getScopeOrNull(parent.id))
                assertSame(host, GlobalContext.get())
                assertSame(service, host.get<OtherPluginService>())
            }
        } finally {
            host.unloadModules(listOf(otherModule))
        }
    }
}
