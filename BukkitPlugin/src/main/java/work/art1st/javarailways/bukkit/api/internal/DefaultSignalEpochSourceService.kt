package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import java.util.UUID

internal class DefaultSignalEpochSourceService :
    SignalEpochSourceService,
    InspectionSourceService,
    AutoCloseable {
    private data class WorldBinding(
        val runtime: SignalEpochRuntime,
        val inspectionRuntime: InspectionRuntime,
        val activeRestrictionStreams: (Set<String>) -> List<String>,
        val stallWorld: (String, Exception) -> Unit
    )

    private data class OwnerSources(
        val owner: Plugin,
        val epochSources: List<SignalEpochSource>,
        val inspectionSources: List<InspectionSource> = emptyList()
    )

    private val lock = Any()
    private val worlds = linkedMapOf<UUID, WorldBinding>()
    private val sourcesByWorld = linkedMapOf<UUID, MutableList<OwnerSources>>()
    private var closed = false

    fun registerWorld(
        worldId: UUID,
        runtime: SignalEpochRuntime,
        inspectionRuntime: InspectionRuntime,
        activeRestrictionStreams: (Set<String>) -> List<String>,
        stallWorld: (String, Exception) -> Unit
    ) = BukkitThreadGuardBridge.runInSimulationContext {
        synchronized(lock) {
            check(!closed) { "SignalEpochSourceService is closed" }
            check(worldId !in worlds) { "Signal epoch runtime already exists for world $worldId" }
            worlds[worldId] = WorldBinding(runtime, inspectionRuntime, activeRestrictionStreams, stallWorld)
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
        require(replacement.isNotEmpty()) {
            "Signal source replacement cannot be empty; use InfrastructureResourceService.unregisterOwner"
        }
        synchronized(lock) {
            check(!closed) { "SignalEpochSourceService is closed" }
            val world = checkNotNull(worlds[worldId]) {
                "Railway world is not registered: $worldId"
            }
            val ownerSets = checkNotNull(sourcesByWorld[worldId])
            val existingIndex = ownerSets.indexOfFirst { it.owner === pluginOwner }
            val existing = ownerSets.getOrNull(existingIndex)
            val previousIds = existing?.allSourceIds().orEmpty()
            val replacementOwner = OwnerSources(
                pluginOwner,
                replacement,
                existing?.inspectionSources.orEmpty()
            )
            val removedIds = previousIds - replacementOwner.allSourceIds()
            requireWithdrawn(worldId, world, pluginOwner, removedIds)

            val candidate = ownerSets.toMutableList()
            if (existingIndex >= 0) candidate.removeAt(existingIndex)
            candidate += replacementOwner
            validateSourceOwnership(candidate)
            world.runtime.replaceSources(candidate.flatMap(OwnerSources::epochSources))

            ownerSets.clear()
            ownerSets += candidate
        }
    }

    override fun replaceInspectionSources(
        pluginOwner: Plugin,
        worldId: UUID,
        sources: Collection<InspectionSource>
    ) = BukkitThreadGuardBridge.runInSimulationContext {
        check(pluginOwner.isEnabled) {
            "Cannot install inspection sources for disabled plugin ${pluginOwner.name}"
        }
        val replacement = java.util.List.copyOf(sources)
        synchronized(lock) {
            check(!closed) { "InspectionSourceService is closed" }
            val world = checkNotNull(worlds[worldId]) {
                "Railway world is not registered: $worldId"
            }
            val ownerSets = checkNotNull(sourcesByWorld[worldId])
            val existingIndex = ownerSets.indexOfFirst { it.owner === pluginOwner }
            val existing = ownerSets.getOrNull(existingIndex)
            check(replacement.isNotEmpty() || existing?.epochSources?.isNotEmpty() == true) {
                "Complete signal owner removal requires InfrastructureResourceService.unregisterOwner"
            }
            val replacementOwner = OwnerSources(
                pluginOwner,
                existing?.epochSources.orEmpty(),
                replacement
            )
            val removedIds = existing?.allSourceIds().orEmpty() - replacementOwner.allSourceIds()
            requireWithdrawn(worldId, world, pluginOwner, removedIds)

            val candidate = ownerSets.toMutableList()
            if (existingIndex >= 0) candidate.removeAt(existingIndex)
            candidate += replacementOwner
            validateSourceOwnership(candidate)
            try {
                world.inspectionRuntime.replaceSources(candidate.flatMap(OwnerSources::inspectionSources))
            } catch (failure: Exception) {
                if (world.inspectionRuntime.hasActiveRoutes()) {
                    val reason = "Inspection sources owned by ${pluginOwner.name} could not be replaced " +
                        "safely in world $worldId"
                    world.stallWorld(reason, failure)
                }
                throw failure
            }

            ownerSets.clear()
            ownerSets += candidate
        }
    }

    override fun markInspectionDeclarationsDirty(
        pluginOwner: Plugin,
        worldId: UUID,
        sourceId: String,
        trainId: String,
        declarationIds: Collection<String>
    ) = BukkitThreadGuardBridge.runInSimulationContext {
        check(pluginOwner.isEnabled) {
            "Cannot dirty inspection declarations for disabled plugin ${pluginOwner.name}"
        }
        synchronized(lock) {
            check(!closed) { "InspectionSourceService is closed" }
            val world = checkNotNull(worlds[worldId]) {
                "Railway world is not registered: $worldId"
            }
            val owner = sourcesByWorld[worldId].orEmpty()
                .firstOrNull { it.owner === pluginOwner }
            check(owner?.inspectionSources?.any { it.sourceId == sourceId } == true) {
                "Inspection source $sourceId is not owned by ${pluginOwner.name} in world $worldId"
            }
            world.inspectionRuntime.markDirty(sourceId, trainId, java.util.List.copyOf(declarationIds))
        }
    }

    fun requireOwnerUnused(pluginOwner: Plugin) {
        synchronized(lock) {
            check(!closed) { "SignalEpochSourceService is closed" }
            sourcesByWorld.forEach { (worldId, ownerSets) ->
                ownerSets.firstOrNull { it.owner === pluginOwner }?.let { owned ->
                    val world = checkNotNull(worlds[worldId])
                    requireWithdrawn(
                        worldId,
                        world,
                        pluginOwner,
                        owned.allSourceIds()
                    )
                    val activeBindings = world.inspectionRuntime.activeBindings(
                        owned.inspectionSources.mapTo(linkedSetOf()) { it.sourceId }
                    )
                    if (activeBindings.isNotEmpty()) {
                        val reason = "Cannot remove inspection sources owned by ${pluginOwner.name} from world " +
                            "$worldId while route bindings remain active: ${activeBindings.joinToString()}"
                        throw IllegalStateException(reason)
                    }
                }
            }
        }
    }

    /** Removes source callbacks after the unified owner lifecycle completed all read-only checks. */
    fun removeUnusedOwner(pluginOwner: Plugin) {
        synchronized(lock) {
            check(!closed) { "SignalEpochSourceService is closed" }
            val removals = sourcesByWorld.mapNotNull { (worldId, ownerSets) ->
                val owned = ownerSets.firstOrNull { it.owner === pluginOwner }
                    ?: return@mapNotNull null
                Triple(worldId, ownerSets, owned)
            }
            removals.forEach { (worldId, _, owned) ->
                val activeBindings = checkNotNull(worlds[worldId]).inspectionRuntime.activeBindings(
                    owned.inspectionSources.mapTo(linkedSetOf()) { it.sourceId }
                )
                check(activeBindings.isEmpty()) {
                    "Inspection bindings changed while unregistering ${pluginOwner.name}: " +
                        activeBindings.joinToString()
                }
            }
            removals.forEach { (worldId, ownerSets, owned) ->
                val world = checkNotNull(worlds[worldId])
                val candidate = ownerSets.filterNot { it === owned }
                world.inspectionRuntime.replaceSources(candidate.flatMap(OwnerSources::inspectionSources))
                world.runtime.replaceSources(candidate.flatMap(OwnerSources::epochSources))
                ownerSets.remove(owned)
            }
        }
    }

    fun forcedDisableOwner(pluginOwner: Plugin) {
        synchronized(lock) {
            if (closed) return
            sourcesByWorld.forEach { (worldId, ownerSets) ->
                val owned = ownerSets.firstOrNull { it.owner === pluginOwner } ?: return@forEach
                val world = checkNotNull(worlds[worldId])
                val sourceIds = owned.allSourceIds()
                val active = try {
                    world.activeRestrictionStreams(sourceIds) +
                        world.inspectionRuntime.activeBindings(sourceIds).map { "active inspection binding: $it" }
                } catch (failure: Exception) {
                    listOf("unable to inspect retained restrictions: ${failure.message ?: failure.javaClass.name}")
                }
                if (active.isNotEmpty()) {
                    val reason = "Bukkit plugin ${pluginOwner.name} was disabled with active signal restrictions " +
                        "in world $worldId: ${active.joinToString()}"
                    world.stallWorld(reason, IllegalStateException(reason))
                }
                val candidate = ownerSets.filterNot { it === owned }
                world.runtime.replaceSources(candidate.flatMap(OwnerSources::epochSources))
                owned.inspectionSources.forEach { world.inspectionRuntime.removeSource(it.sourceId) }
                ownerSets.remove(owned)
            }
        }
    }

    fun removeWorld(worldId: UUID) = BukkitThreadGuardBridge.runInSimulationContext {
        synchronized(lock) {
            val world = worlds.remove(worldId) ?: return@runInSimulationContext
            sourcesByWorld.remove(worldId)
            world.runtime.replaceSources(emptyList())
            world.inspectionRuntime.clear()
        }
    }

    override fun close() = BukkitThreadGuardBridge.runInSimulationContext {
        val snapshot = synchronized(lock) {
            if (closed) return@runInSimulationContext
            closed = true
            sourcesByWorld.clear()
            worlds.values.toList().also { worlds.clear() }
        }
        snapshot.forEach {
            it.runtime.replaceSources(emptyList())
            it.inspectionRuntime.clear()
        }
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

    private fun OwnerSources.allSourceIds(): Set<String> = buildSet {
        epochSources.mapTo(this) { it.sourceId }
        inspectionSources.mapTo(this) { it.sourceId }
    }

    private fun validateSourceOwnership(ownerSets: Collection<OwnerSources>) {
        val ownersBySource = linkedMapOf<String, Plugin>()
        ownerSets.forEach { owned ->
            owned.allSourceIds().forEach { sourceId ->
                val previous = ownersBySource.putIfAbsent(sourceId, owned.owner)
                require(previous == null || previous === owned.owner) {
                    "signal source $sourceId is owned by more than one plugin"
                }
            }
        }
    }
}
