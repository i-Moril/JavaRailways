package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.station.StationCallResolverService
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.LiveWorldManagement
import work.art1st.javarailways.core.physics.WorldExecutionOwner
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionProvider
import work.art1st.javarailways.simpleato.api.station.StationCallResolverRegistry
import java.util.UUID

/** LIVE management is serialized before entering the exact world's domain owner. */
internal class DefaultStationCallResolverService : StationCallResolverService, AutoCloseable {
    private class Binding(val registry: StationCallResolverRegistry, val owner: WorldExecutionOwner) {
        var registration: Registration? = null
    }
    private class Registration(val plugin: Plugin, val handle: AutoCloseable)
    private val worlds = linkedMapOf<UUID, Binding>()
    private var closed = false

    fun registerWorld(worldId: UUID, registry: StationCallResolverRegistry, owner: WorldExecutionOwner) =
        LiveWorldManagement.run {
            check(!closed) { "StationCallResolverService is closed" }
            check(worldId !in worlds) { "Station resolver world already registered: $worldId" }
            worlds[worldId] = Binding(registry, owner)
        }

    override fun register(owner: Plugin, worldId: UUID, provider: StationCallResolutionProvider): AutoCloseable =
        LiveWorldManagement.run {
            check(!closed) { "StationCallResolverService is closed" }
            check(owner.isEnabled) { "Cannot register resolver for disabled plugin ${owner.name}" }
            val binding = checkNotNull(worlds[worldId]) { "Railway world is not registered: $worldId" }
            val registration = onOwner(binding) {
                check(binding.registration == null) { "Station resolver already registered: $worldId" }
                Registration(owner, binding.registry.register(provider)).also { binding.registration = it }
            }
            AutoCloseable {
                LiveWorldManagement.run {
                    if (worlds[worldId] === binding) onOwner(binding) {
                        if (binding.registration === registration) {
                            registration.handle.close()
                            binding.registration = null
                        }
                    }
                }
            }
        }

    override fun unregisterOwner(owner: Plugin) = LiveWorldManagement.run {
        worlds.values.forEach { binding ->
            if (binding.registration?.plugin === owner) onOwner(binding) {
                binding.registration?.handle?.close()
                binding.registration = null
            }
        }
    }

    fun removeWorld(worldId: UUID) = LiveWorldManagement.run {
        worlds[worldId]?.let { binding ->
            onOwner(binding) { binding.registration?.handle?.close() }
            worlds.remove(worldId)
        }
        Unit
    }

    override fun close() = LiveWorldManagement.run {
        closed = true
        worlds.keys.toList().forEach(::removeWorld)
    }

    private fun <T> onOwner(binding: Binding, action: () -> T): T =
        BukkitThreadGuardBridge.runInSimulationContext { binding.owner.run(action) }
}
