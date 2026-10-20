package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature
import work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultSimulationFeatureServiceTest {
    @Test
    fun `registration close disable and service close preserve exact owner identity`() = BukkitServiceTestSupport.withServer<Unit> {
        val service = DefaultSimulationFeatureService()
        val firstOwner = plugin("SimpleSignals")
        val replacementOwner = plugin("SimpleSignals")
        val first = service.register(firstOwner) { prepared("simplesignals") }
        assertEquals(listOf("simplesignals"), service.prepare(UUID.randomUUID()).map { it.id })
        assertThrows(IllegalStateException::class.java) {
            service.register(replacementOwner) { prepared("simplesignals") }
        }
        service.unregisterOwner(replacementOwner)
        assertEquals(1, service.prepare(UUID.randomUUID()).size)
        first.close()
        val replacement = service.register(replacementOwner) { prepared("simplesignals") }
        first.close()
        assertEquals(1, service.prepare(UUID.randomUUID()).size)
        SimulationFeatureOwnerLifecycleListener(service).onPluginDisable(PluginDisableEvent(replacementOwner))
        assertTrue(service.prepare(UUID.randomUUID()).isEmpty())
        replacement.close()
        service.close()
        service.close()
        assertThrows(IllegalStateException::class.java) { service.prepare(UUID.randomUUID()) }
        assertThrows(IllegalStateException::class.java) { service.register(firstOwner) { prepared("simplesignals") } }
    }

    @Test
    fun `disabled owner mismatched ID and preparer failure fail explicitly`() {
        val service = DefaultSimulationFeatureService()
        assertThrows(IllegalStateException::class.java) {
            service.register(plugin("disabled", false)) { prepared("disabled") }
        }
        service.register(plugin("one")) { prepared("other") }.use {
            assertThrows(IllegalArgumentException::class.java) { service.prepare(UUID.randomUUID()) }
        }
        val failure = IllegalArgumentException("rules unavailable")
        service.register(plugin("two")) { throw failure }.use {
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { service.prepare(UUID.randomUUID()) })
        }
        service.close()
    }

    @Test
    fun `blocked preparation releases shared directory and another source can prepare`() {
        val service = DefaultSimulationFeatureService()
        val worldA = UUID.randomUUID()
        val worldB = UUID.randomUUID()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val owner = plugin("signals")
        service.register(owner) { worldId ->
            if (worldId == worldA) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            prepared("signals")
        }
        try {
            val pending = executor.submit<List<PreparedSimulationFeature>> { service.prepare(worldA) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val other = executor.submit<List<PreparedSimulationFeature>> { service.prepare(worldB) }
            assertEquals("signals", other.get(10, TimeUnit.SECONDS).single().id)
            val directory = executor.submit { service.unregisterOwner(owner); service.close() }
            directory.get(10, TimeUnit.SECONDS)
            assertFalse(pending.isDone)
            release.countDown()
            assertEquals("signals", pending.get(10, TimeUnit.SECONDS).single().id,
                "already captured detached preparation is independent of later registration cleanup")
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            service.close()
        }
    }

    private fun prepared(id: String) = object : PreparedSimulationFeature {
        override val id = id
        override fun install(target: SimulationDomainTarget) = AutoCloseable {}
    }

    private fun plugin(name: String, enabled: Boolean = true): Plugin {
        lateinit var instance: Plugin
        instance = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { _, method, args ->
            when (method.name) {
                "getName", "toString" -> name
                "isEnabled" -> enabled
                "equals" -> instance === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                else -> null
            }
        } as Plugin
        return instance
    }
}
