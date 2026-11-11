package work.art1st.javarailways.bukkit.api.internal

import work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.LiveWorldManagement
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.WorldExecutionOwner
import java.util.concurrent.ConcurrentHashMap
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.routing.RoutePlanningRestrictionRuntime
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import java.util.UUID

internal class DefaultSignalEpochSourceService :
    SignalEpochSourceService,
    InspectionSourceService,
    RoutePlanningRestrictionSourceService,
    AutoCloseable {
    private data class WorldBinding(
        val runtime: SignalEpochRuntime,
        val inspectionRuntime: InspectionRuntime,
        val routePlanningRuntime: RoutePlanningRestrictionRuntime,
        val activeRestrictionStreams: (Set<String>) -> List<String>,
        val stallWorld: (String, Exception) -> Unit,
        val owner: WorldExecutionOwner,
        val physicalWorld: CoreWorld?
    )

    private data class OwnerSources(
        val owner: Plugin,
        val epochSources: List<SignalEpochSource>,
        val inspectionSources: List<InspectionSource> = emptyList(),
        val routePlanningSources: List<RoutePlanningRestrictionBinding> = emptyList()
    )

    private val lock = Any()
    private val worlds = ConcurrentHashMap<UUID, WorldBinding>()
    private val sourcesByWorld = ConcurrentHashMap<UUID, List<OwnerSources>>()
    private var closed = false
    private val atomicCommitInProgress = ThreadLocal.withInitial { false }

    fun registerWorld(
        worldId: UUID,
        runtime: SignalEpochRuntime,
        inspectionRuntime: InspectionRuntime,
        activeRestrictionStreams: (Set<String>) -> List<String>,
        routePlanningRuntime: RoutePlanningRestrictionRuntime = RoutePlanningRestrictionRuntime(),
        physicalWorld: CoreWorld? = null,
        stallWorld: (String, Exception) -> Unit
    ) = LiveWorldManagement.run { BukkitThreadGuardBridge.runInSimulationContext {
        synchronized(lock) {
            requireNoAtomicCommitReentry()
            RailwaySimulationHalt.ensureRunning()
            check(!closed) { "SignalEpochSourceService is closed" }
            check(!worlds.containsKey(worldId)) { "Signal epoch runtime already exists for world $worldId" }
            worlds[worldId] = WorldBinding(
                runtime,
                inspectionRuntime,
                routePlanningRuntime,
                activeRestrictionStreams,
                stallWorld,
                physicalWorld?.executionOwner ?: WorldExecutionOwner(),
                physicalWorld
            )
            sourcesByWorld[worldId] = emptyList()
        }
    } }

    override fun replaceSources(
        pluginOwner: Plugin,
        worldId: UUID,
        sources: Collection<SignalEpochSource>
    ) = replaceSourcesAtomically(pluginOwner, worldId, sources) {}

    override fun replaceSourcesAtomically(
        pluginOwner: Plugin,
        worldId: UUID,
        sources: Collection<SignalEpochSource>,
        commitAction: Runnable
    ) = withWorld(worldId) {
        RailwaySimulationHalt.ensureRunning()
        check(pluginOwner.isEnabled) {
            "Cannot install signal sources for disabled plugin ${pluginOwner.name}"
        }
        val replacement = java.util.List.copyOf(sources)
        run {
            check(!closed) { "SignalEpochSourceService is closed" }
            val world = checkNotNull(worlds[worldId]) {
                "Railway world is not registered: $worldId"
            }
            val ownerSets = checkNotNull(sourcesByWorld[worldId])
            val existingIndex = ownerSets.indexOfFirst { it.owner === pluginOwner }
            val existing = ownerSets.getOrNull(existingIndex)
            val previousIds = existing?.restrictionSourceIds().orEmpty()
            val replacementOwner = OwnerSources(
                pluginOwner,
                replacement,
                existing?.inspectionSources.orEmpty(),
                existing?.routePlanningSources.orEmpty()
            )
            val removedIds = previousIds - replacementOwner.restrictionSourceIds()
            requireWithdrawn(worldId, world, pluginOwner, removedIds)

            val candidate = ownerSets.toMutableList()
            if (existingIndex >= 0) candidate.removeAt(existingIndex)
            if (!replacementOwner.isEmpty()) candidate += replacementOwner
            validateSourceOwnership(candidate)
            val committedOwnerSets = candidate.toMutableList()
            atomicCommitInProgress.set(true)
            try {
                world.runtime.replaceSourcesAtomically(
                    candidate.flatMap(OwnerSources::epochSources)
                ) {
                    // A halt during candidate preparation must not commit a previously admitted edit.
                    RailwaySimulationHalt.ensureRunning()
                    commitAction.run()
                }
            } finally {
                atomicCommitInProgress.remove()
            }

            sourcesByWorld[worldId] = committedOwnerSets
        }
    }

    override fun replaceInspectionSources(
        pluginOwner: Plugin,
        worldId: UUID,
        sources: Collection<InspectionSource>
    ) = withWorld(worldId) {
        RailwaySimulationHalt.ensureRunning()
        check(pluginOwner.isEnabled) {
            "Cannot install inspection sources for disabled plugin ${pluginOwner.name}"
        }
        val replacement = java.util.List.copyOf(sources)
        run {
            check(!closed) { "InspectionSourceService is closed" }
            val world = checkNotNull(worlds[worldId]) {
                "Railway world is not registered: $worldId"
            }
            val ownerSets = checkNotNull(sourcesByWorld[worldId])
            val existingIndex = ownerSets.indexOfFirst { it.owner === pluginOwner }
            val existing = ownerSets.getOrNull(existingIndex)
            check(
                replacement.isNotEmpty() ||
                    existing?.epochSources?.isNotEmpty() == true ||
                    existing?.routePlanningSources?.isNotEmpty() == true
            ) {
                "Complete signal owner removal requires InfrastructureResourceService.unregisterOwner"
            }
            val replacementOwner = OwnerSources(
                pluginOwner,
                existing?.epochSources.orEmpty(),
                replacement,
                existing?.routePlanningSources.orEmpty()
            )
            val removedIds = existing?.restrictionSourceIds().orEmpty() -
                replacementOwner.restrictionSourceIds()
            requireWithdrawn(worldId, world, pluginOwner, removedIds)

            val candidate = ownerSets.toMutableList()
            if (existingIndex >= 0) candidate.removeAt(existingIndex)
            candidate += replacementOwner
            validateSourceOwnership(candidate)
            RailwaySimulationHalt.ensureRunning()
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

            sourcesByWorld[worldId] = candidate
        }
    }

    override fun replaceRoutePlanningSources(
        pluginOwner: Plugin,
        worldId: UUID,
        sources: Collection<RoutePlanningRestrictionBinding>
    ) = withWorld(worldId) {
        RailwaySimulationHalt.ensureRunning()
        check(pluginOwner.isEnabled) {
            "Cannot install route-planning sources for disabled plugin ${pluginOwner.name}"
        }
        val replacement = java.util.List.copyOf(sources)
        run {
            check(!closed) { "RoutePlanningRestrictionSourceService is closed" }
            val world = checkNotNull(worlds[worldId]) {
                "Railway world is not registered: $worldId"
            }
            val ownerSets = checkNotNull(sourcesByWorld[worldId])
            val existingIndex = ownerSets.indexOfFirst { it.owner === pluginOwner }
            val existing = ownerSets.getOrNull(existingIndex)
            check(
                replacement.isNotEmpty() ||
                    existing?.epochSources?.isNotEmpty() == true ||
                    existing?.inspectionSources?.isNotEmpty() == true
            ) {
                "Complete signal owner removal requires InfrastructureResourceService.unregisterOwner"
            }
            val replacementOwner = OwnerSources(
                pluginOwner,
                existing?.epochSources.orEmpty(),
                existing?.inspectionSources.orEmpty(),
                replacement
            )
            val candidate = ownerSets.toMutableList()
            if (existingIndex >= 0) candidate.removeAt(existingIndex)
            candidate += replacementOwner
            validateSourceOwnership(candidate)
            RailwaySimulationHalt.ensureRunning()
            world.routePlanningRuntime.replaceSources(
                candidate.flatMap(OwnerSources::routePlanningSources)
            )

            sourcesByWorld[worldId] = candidate
        }
    }

    override fun retireSources(
        pluginOwner: Plugin,
        worldId: UUID,
        expectedEpochSources: Collection<SignalEpochSource>,
        expectedInspectionSources: Collection<InspectionSource>,
        expectedRoutePlanningSources: Collection<RoutePlanningRestrictionBinding>
    ) {
        requireNoAtomicCommitReentry()
        val world = worlds[worldId] ?: return
        BukkitThreadGuardBridge.runInSimulationContext {
            world.owner.run {
                if (worlds[worldId] !== world) return@run
                val ownerSets = sourcesByWorld[worldId] ?: return@run
                val owned = ownerSets.firstOrNull { it.owner === pluginOwner } ?: return@run
                val epochs = owned.epochSources.filter { source -> expectedEpochSources.any { it === source } }
                val inspections = owned.inspectionSources.filter { source -> expectedInspectionSources.any { it === source } }
                val planning = owned.routePlanningSources.filter { source -> expectedRoutePlanningSources.any { it === source } }
                if (epochs.isEmpty() && inspections.isEmpty() && planning.isEmpty()) return@run

                var failure: Throwable? = null
                fun attempt(action: () -> Unit): Boolean = try {
                    action()
                    true
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
                    false
                }
                val removedRestrictionIds = (epochs.map { it.sourceId } + inspections.map { it.sourceId }).toSet()
                var active = emptyList<String>()
                val inspected = removedRestrictionIds.isEmpty() || attempt {
                    active = world.activeRestrictionStreams(removedRestrictionIds) +
                        world.inspectionRuntime.activeBindings(inspections.mapTo(linkedSetOf()) { it.sourceId })
                            .map { "active inspection binding: $it" }
                }
                val safetyCleared = if (!inspected || active.isNotEmpty()) {
                    val reason = "Retiring failed signal installation owned by ${pluginOwner.name} in world " +
                        "$worldId with active or unavailable restrictions: ${active.joinToString()}"
                    attempt { world.stallWorld(reason, IllegalStateException(reason, failure)) }
                } else true

                // Each successful category is reflected separately, so a failed retirement can be
                // retried without restoring callbacks already removed by this attempt.
                var remaining = owned
                if (safetyCleared && epochs.isNotEmpty()) attempt {
                    val epochSources = owned.epochSources.filterNot { source -> epochs.any { it === source } }
                    world.runtime.replaceSources(ownerSets.flatMap { if (it === owned) epochSources else it.epochSources })
                    remaining = remaining.copy(epochSources = epochSources)
                }
                if (safetyCleared) inspections.forEach { source -> attempt {
                    // Planned replacement rejects active bindings; failure retirement stalls above
                    // and uses the runtime's exact-source teardown instead of rebinding routes.
                    world.inspectionRuntime.removeSource(source.sourceId)
                    remaining = remaining.copy(inspectionSources = remaining.inspectionSources.filterNot { it === source })
                } }
                if (planning.isNotEmpty()) attempt {
                    val routePlanningSources = owned.routePlanningSources.filterNot { source -> planning.any { it === source } }
                    world.routePlanningRuntime.replaceSources(
                        ownerSets.flatMap { if (it === owned) routePlanningSources else it.routePlanningSources }
                    )
                    remaining = remaining.copy(routePlanningSources = routePlanningSources)
                }
                sourcesByWorld[worldId] = ownerSets.mapNotNull {
                    if (it !== owned) it else remaining.takeUnless { it.isEmpty() }
                }
                failure?.let { throw it }
            }
        }
    }

    fun requireOwnerUnused(pluginOwner: Plugin) {
        synchronized(lock) {
            requireNoAtomicCommitReentry()
            check(!closed) { "SignalEpochSourceService is closed" }
            sourcesByWorld.forEach { (worldId, ownerSets) ->
                ownerSets.firstOrNull { it.owner === pluginOwner }?.let { owned ->
                    val world = checkNotNull(worlds[worldId])
                    requireWithdrawn(
                        worldId,
                        world,
                        pluginOwner,
                        owned.restrictionSourceIds()
                    )
                    val activeBindings = world.inspectionRuntime.activeBindings(
                        owned.inspectionSourceIds()
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
            requireNoAtomicCommitReentry()
            check(!closed) { "SignalEpochSourceService is closed" }
            val removals = sourcesByWorld.mapNotNull { (worldId, ownerSets) ->
                val owned = ownerSets.firstOrNull { it.owner === pluginOwner }
                    ?: return@mapNotNull null
                Triple(worldId, ownerSets, owned)
            }
            removals.forEach { (worldId, _, owned) ->
                val activeBindings = checkNotNull(worlds[worldId]).inspectionRuntime.activeBindings(
                    owned.inspectionSourceIds()
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
                world.routePlanningRuntime.replaceSources(
                    candidate.flatMap(OwnerSources::routePlanningSources)
                )
                sourcesByWorld[worldId] = candidate
            }
        }
    }

    fun forcedDisableOwner(pluginOwner: Plugin) {
        synchronized(lock) {
            requireNoAtomicCommitReentry()
            if (closed) return
            sourcesByWorld.forEach { (worldId, ownerSets) ->
                val owned = ownerSets.firstOrNull { it.owner === pluginOwner } ?: return@forEach
                val world = checkNotNull(worlds[worldId])
                val restrictionSourceIds = owned.restrictionSourceIds()
                val active = try {
                    world.activeRestrictionStreams(restrictionSourceIds) +
                        world.inspectionRuntime.activeBindings(owned.inspectionSourceIds())
                            .map { "active inspection binding: $it" }
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
                world.routePlanningRuntime.replaceSources(
                    candidate.flatMap(OwnerSources::routePlanningSources)
                )
                sourcesByWorld[worldId] = candidate
            }
        }
    }

    fun removeWorld(worldId: UUID) = withWorld(worldId) runInSimulationContext@ {
        synchronized(lock) {
            val world = worlds.remove(worldId) ?: return@runInSimulationContext
            sourcesByWorld.remove(worldId)
            world.runtime.replaceSources(emptyList())
            world.inspectionRuntime.clear()
            world.routePlanningRuntime.replaceSources(emptyList())
        }
    }

    override fun close() = LiveWorldManagement.run {
        WorldExecutionOwner.runTogether(executionOwners()) {
            BukkitThreadGuardBridge.runInSimulationContext {
                val snapshot = synchronized(lock) {
                    requireNoAtomicCommitReentry()
                    if (closed) return@runInSimulationContext
                    closed = true
                    sourcesByWorld.clear()
                    worlds.values.toList().also { worlds.clear() }
                }
                snapshot.forEach {
                    it.runtime.replaceSources(emptyList())
                    it.inspectionRuntime.clear()
                    it.routePlanningRuntime.replaceSources(emptyList())
                }
            }
        }
    }

    fun executionOwners(): List<WorldExecutionOwner> = worlds.values.map { it.owner }

    fun executionWorlds(): List<CoreWorld> = worlds.values.mapNotNull { it.physicalWorld }

    fun requiredDomainFeatures(worldId: UUID): Set<String> =
        sourcesByWorld[worldId].orEmpty().mapTo(linkedSetOf()) { it.owner.name.lowercase() }

    private fun <T> withWorld(worldId: UUID, action: () -> T): T {
        requireNoAtomicCommitReentry()
        return BukkitThreadGuardBridge.runInSimulationContext {
            val owner = worlds[worldId]?.owner
            if (owner == null) action() else owner.run(action)
        }
    }

    private fun requireNoAtomicCommitReentry() {
        check(!atomicCommitInProgress.get()) {
            "SignalEpochSourceService cannot be re-entered from an atomic source commit callback"
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

    private fun OwnerSources.restrictionSourceIds(): Set<String> = buildSet {
        epochSources.mapTo(this) { it.sourceId }
        inspectionSources.mapTo(this) { it.sourceId }
    }

    private fun OwnerSources.inspectionSourceIds(): Set<String> =
        inspectionSources.mapTo(linkedSetOf()) { it.sourceId }

    private fun OwnerSources.allSourceIds(): Set<String> = buildSet {
        addAll(restrictionSourceIds())
        routePlanningSources.mapTo(this) { it.sourceId }
    }

    private fun OwnerSources.isEmpty(): Boolean =
        epochSources.isEmpty() && inspectionSources.isEmpty() && routePlanningSources.isEmpty()

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
