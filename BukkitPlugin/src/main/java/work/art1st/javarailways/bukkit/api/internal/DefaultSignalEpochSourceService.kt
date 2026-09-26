package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import java.util.UUID

internal class DefaultSignalEpochSourceService : SignalEpochSourceService, AutoCloseable {
    private data class WorldBinding(
        val runtime: SignalEpochRuntime,
        val activeRestrictionStreams: (Set<String>) -> List<String>,
        val stallWorld: (String, Exception) -> Unit
    )

    private data class OwnerSources(
        val owner: Plugin,
        val sources: List<SignalEpochSource>
    )

    private val lock = Any()
    private val worlds = linkedMapOf<UUID, WorldBinding>()
    private val sourcesByWorld = linkedMapOf<UUID, MutableList<OwnerSources>>()
    private var closed = false

    fun registerWorld(
        worldId: UUID,
        runtime: SignalEpochRuntime,
        activeRestrictionStreams: (Set<String>) -> List<String>,
        stallWorld: (String, Exception) -> Unit
    ) = BukkitThreadGuardBridge.runInSimulationContext {
        synchronized(lock) {
            check(!closed) { "SignalEpochSourceService is closed" }
            check(worldId !in worlds) { "Signal epoch runtime already exists for world $worldId" }
            worlds[worldId] = WorldBinding(runtime, activeRestrictionStreams, stallWorld)
            sourcesByWorld[worldId] = mutableListOf()
        }
    }

    override fun replaceSources(
        pluginOwner: Plugin,
        worldId: UUID,
        sources: Collection<SignalEpochSource>
    ) = BukkitThreadGuardBridge.runInSimulationContext {
        check(pluginOwner.isEnabled) {
            "Cannot install signal sources for disabled plugin ${pluginOwner.name}"
        }
        val replacement = java.util.List.copyOf(sources)
        synchronized(lock) {
            check(!closed) { "SignalEpochSourceService is closed" }
            val world = checkNotNull(worlds[worldId]) {
                "Railway world is not registered: $worldId"
            }
            val ownerSets = checkNotNull(sourcesByWorld[worldId])
            val existingIndex = ownerSets.indexOfFirst { it.owner === pluginOwner }
            val existing = ownerSets.getOrNull(existingIndex)
            val removedIds = existing?.sources.orEmpty().mapTo(linkedSetOf()) { it.sourceId }
                .apply { removeAll(replacement.mapTo(hashSetOf()) { it.sourceId }) }
            requireWithdrawn(worldId, world, pluginOwner, removedIds)

            val candidate = ownerSets.toMutableList()
            if (existingIndex >= 0) candidate.removeAt(existingIndex)
            if (replacement.isNotEmpty()) candidate += OwnerSources(pluginOwner, replacement)
            world.runtime.replaceSources(candidate.flatMap(OwnerSources::sources))

            ownerSets.clear()
            ownerSets += candidate
        }
    }

    override fun unregisterOwner(pluginOwner: Plugin) = BukkitThreadGuardBridge.runInSimulationContext {
        check(pluginOwner.isEnabled) {
            "Cannot unregister signal sources for disabled plugin ${pluginOwner.name}"
        }
        synchronized(lock) {
            check(!closed) { "SignalEpochSourceService is closed" }
            val removals = sourcesByWorld.mapNotNull { (worldId, ownerSets) ->
                ownerSets.firstOrNull { it.owner === pluginOwner }?.let { owned ->
                    Triple(worldId, checkNotNull(worlds[worldId]), owned)
                }
            }
            removals.forEach { (worldId, world, owned) ->
                requireWithdrawn(
                    worldId,
                    world,
                    pluginOwner,
                    owned.sources.mapTo(linkedSetOf()) { it.sourceId }
                )
            }
            removals.forEach { (worldId, world, owned) ->
                val ownerSets = checkNotNull(sourcesByWorld[worldId])
                val candidate = ownerSets.filterNot { it === owned }
                world.runtime.replaceSources(candidate.flatMap(OwnerSources::sources))
                ownerSets.remove(owned)
            }
        }
    }

    fun forcedDisableOwner(pluginOwner: Plugin) = BukkitThreadGuardBridge.runInSimulationContext {
        val affected = synchronized(lock) {
            if (closed) return@runInSimulationContext
            sourcesByWorld.mapNotNull { (worldId, ownerSets) ->
                val owned = ownerSets.firstOrNull { it.owner === pluginOwner } ?: return@mapNotNull null
                val world = checkNotNull(worlds[worldId])
                val candidate = ownerSets.filterNot { it === owned }
                world.runtime.replaceSources(candidate.flatMap(OwnerSources::sources))
                ownerSets.remove(owned)
                worldId to world
            }
        }
        affected.forEach { (worldId, world) ->
            val reason = "Bukkit plugin ${pluginOwner.name} was disabled with signal sources installed in world $worldId"
            world.stallWorld(reason, IllegalStateException(reason))
        }
    }

    fun removeWorld(worldId: UUID) = BukkitThreadGuardBridge.runInSimulationContext {
        synchronized(lock) {
            val world = worlds.remove(worldId) ?: return@runInSimulationContext
            sourcesByWorld.remove(worldId)
            world.runtime.replaceSources(emptyList())
        }
    }

    override fun close() = BukkitThreadGuardBridge.runInSimulationContext {
        val snapshot = synchronized(lock) {
            if (closed) return@runInSimulationContext
            closed = true
            sourcesByWorld.clear()
            worlds.values.toList().also { worlds.clear() }
        }
        snapshot.forEach { it.runtime.replaceSources(emptyList()) }
    }

    private fun requireWithdrawn(
        worldId: UUID,
        world: WorldBinding,
        pluginOwner: Plugin,
        removedSourceIds: Set<String>
    ) {
        if (removedSourceIds.isEmpty()) return
        val active = world.activeRestrictionStreams(removedSourceIds)
        check(active.isEmpty()) {
            "Cannot remove signal sources owned by ${pluginOwner.name} from world $worldId before explicit " +
                "empty restriction snapshots are published: ${active.joinToString()}"
        }
    }
}
