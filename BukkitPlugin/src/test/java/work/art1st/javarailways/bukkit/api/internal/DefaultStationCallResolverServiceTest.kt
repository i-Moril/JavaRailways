package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.physics.WorldExecutionOwner
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionContext
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionProvider
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionSimulationFactory
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionSimulationPreparation
import work.art1st.javarailways.simpleato.api.station.StationCallResolverRegistry
import java.lang.reflect.Proxy
import java.util.UUID

class DefaultStationCallResolverServiceTest {
    @Test
    fun `disable uses exact plugin identity and stale handles cannot retire replacements`() = BukkitServiceTestSupport.withServer<Unit> {
        DefaultStationCallResolverService().use { service ->
            val worldId = UUID.randomUUID()
            val registry = StationCallResolverRegistry()
            val executionOwner = WorldExecutionOwner()
            service.registerWorld(worldId, registry, executionOwner)
            val firstOwner = plugin("stops")
            val replacementOwner = plugin("stops")
            val first = service.register(firstOwner, worldId, provider())
            assertThrows(IllegalStateException::class.java) {
                service.register(replacementOwner, worldId, provider())
            }
            service.unregisterOwner(replacementOwner)
            executionOwner.run { assertDoesNotThrow { registry.prepareSimulation() } }
            first.close()
            executionOwner.run { assertThrows(IllegalStateException::class.java) { registry.prepareSimulation() } }
            val replacement = service.register(replacementOwner, worldId, provider())
            first.close()
            executionOwner.run { assertDoesNotThrow { registry.prepareSimulation() } }
            StationCallResolverOwnerLifecycleListener(service).onPluginDisable(PluginDisableEvent(firstOwner))
            executionOwner.run { assertDoesNotThrow { registry.prepareSimulation() } }
            StationCallResolverOwnerLifecycleListener(service).onPluginDisable(PluginDisableEvent(replacementOwner))
            executionOwner.run { assertThrows(IllegalStateException::class.java) { registry.prepareSimulation() } }
            replacement.close()
        }
    }

    @Test
    fun `disabled owner and missing world reject registration without replacing defaults`() {
        DefaultStationCallResolverService().use { service ->
            val worldId = UUID.randomUUID()
            val registry = StationCallResolverRegistry()
            val executionOwner = WorldExecutionOwner()
            service.registerWorld(worldId, registry, executionOwner)
            assertThrows(IllegalStateException::class.java) {
                service.register(plugin("disabled", enabled = false), worldId, provider())
            }
            assertThrows(IllegalStateException::class.java) {
                service.register(plugin("enabled"), UUID.randomUUID(), provider())
            }
            executionOwner.run { assertDoesNotThrow { registry.prepareSimulation() } }
            assertThrows(IllegalStateException::class.java) {
                service.registerWorld(worldId, StationCallResolverRegistry(), WorldExecutionOwner())
            }
            service.register(plugin("enabled"), worldId, provider()).close()
            executionOwner.run { assertThrows(IllegalStateException::class.java) { registry.prepareSimulation() } }
        }
    }

    @Test
    fun `world unload retires policy and stale handle cannot affect a reused world ID`() {
        DefaultStationCallResolverService().use { service ->
            val worldId = UUID.randomUUID()
            val plugin = plugin("stops")
            val oldRegistry = StationCallResolverRegistry()
            val oldOwner = WorldExecutionOwner()
            service.registerWorld(worldId, oldRegistry, oldOwner)
            val old = service.register(plugin, worldId, provider())
            service.removeWorld(worldId)
            oldOwner.run { assertThrows(IllegalStateException::class.java) { oldRegistry.prepareSimulation() } }
            assertThrows(IllegalStateException::class.java) { service.register(plugin, worldId, provider()) }
            val newRegistry = StationCallResolverRegistry()
            val newOwner = WorldExecutionOwner()
            service.registerWorld(worldId, newRegistry, newOwner)
            service.register(plugin, worldId, provider())
            old.close()
            newOwner.run { assertDoesNotThrow { newRegistry.prepareSimulation() } }
            service.removeWorld(worldId)
            newOwner.run { assertThrows(IllegalStateException::class.java) { newRegistry.prepareSimulation() } }
        }
    }

    @Test
    fun `service close retires all policies and forbids new registrations`() {
        val service = DefaultStationCallResolverService()
        val owner = plugin("stops")
        val entries = List(2) { Triple(UUID.randomUUID(), StationCallResolverRegistry(), WorldExecutionOwner()) }
        val handles = entries.map { (id, registry, executionOwner) ->
            service.registerWorld(id, registry, executionOwner)
            service.register(owner, id, provider())
        }
        service.close()
        service.close()
        entries.forEach { (_, registry, executionOwner) ->
            executionOwner.run { assertThrows(IllegalStateException::class.java) { registry.prepareSimulation() } }
        }
        handles.forEach { it.close() }
        assertThrows(IllegalStateException::class.java) {
            service.registerWorld(UUID.randomUUID(), StationCallResolverRegistry(), WorldExecutionOwner())
        }
        assertThrows(IllegalStateException::class.java) { service.register(owner, entries.first().first, provider()) }
    }

    private fun provider() = object : StationCallResolutionProvider, StationCallResolutionSimulationFactory {
        override fun resolve(context: StationCallResolutionContext) =
            StationCallResolutionResult.Success(context.defaultResolution)
        override fun prepareSimulation() = StationCallResolutionSimulationPreparation {
            StationCallResolutionProvider { context -> StationCallResolutionResult.Success(context.defaultResolution) }
        }
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
