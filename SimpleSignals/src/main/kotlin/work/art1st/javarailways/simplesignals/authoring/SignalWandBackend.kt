package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionAuthoringStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signalpack.AvailableSignalType
import work.art1st.javarailways.simplesignals.signalpack.SignalTypeCatalogSnapshot
import java.util.UUID

internal interface SignalWandCapture { val worldId: UUID }

internal data class SignalWandOutcome(val message: String)

internal data class SignalWandChoice(val binding: SignalWandBinding, val label: String)

/** Worker-facing boundary. Catalog data exposed to commands is immutable intent, not edit authority. */
internal interface SignalWandBackend {
    fun availableSignalTypes(): List<AvailableSignalType>?
    fun capture(worldId: UUID, signalType: String): SignalWandCapture
    fun current(capture: SignalWandCapture): Boolean
    fun resolveEntrance(
        capture: SignalWandCapture,
        controlKind: SignalWandControlKind,
        mode: SignalWandMode,
        point: RailPositionSnapshot
    ): List<SignalWandChoice>
    fun place(
        capture: SignalWandCapture,
        binding: SignalWandBinding,
        point: RailPositionSnapshot,
        active: () -> Boolean
    ): SignalWandOutcome
    fun delete(
        capture: SignalWandCapture,
        binding: SignalWandBinding,
        active: () -> Boolean
    ): SignalWandOutcome
}

internal class RuntimeSignalWandBackend(
    private val manager: SimpleSignalsWorldRuntimeManager,
    private val infrastructure: InfrastructureResourceService,
    private val newSignalId: () -> String = { "simplesignals:${UUID.randomUUID()}" }
) : SignalWandBackend {
    private data class Capture(
        override val worldId: UUID,
        val signalType: String,
        val catalog: SignalTypeCatalogSnapshot,
        val runtime: SimpleSignalsWorldRuntime,
        val publication: SimpleSignalsRuntimePublication,
        val topology: TopologyState
    ) : SignalWandCapture

    override fun availableSignalTypes(): List<AvailableSignalType>? =
        manager.signalTypeCatalogSnapshot()?.signalTypes

    override fun capture(worldId: UUID, signalType: String): SignalWandCapture {
        val catalog = manager.signalTypeCatalogSnapshot()
            ?: throw IllegalStateException("Signal type catalog is not ready")
        require(catalog.contains(signalType)) { "Unknown signal type: $signalType" }
        val runtime = manager.authoringRuntime(worldId)
            ?: throw IllegalStateException("SimpleSignals world is unavailable")
        return Capture(worldId, signalType, catalog, runtime, runtime.authoringPublication(),
            infrastructure.currentTopology(worldId))
    }

    override fun current(capture: SignalWandCapture): Boolean {
        val captured = capture as Capture
        return manager.currentSignalTypeCatalog(captured.catalog) &&
            manager.authoringRuntime(captured.worldId) === captured.runtime &&
            captured.runtime.authoringPublication() === captured.publication &&
            infrastructure.currentTopology(captured.worldId) === captured.topology
    }

    override fun resolveEntrance(
        capture: SignalWandCapture,
        controlKind: SignalWandControlKind,
        mode: SignalWandMode,
        point: RailPositionSnapshot
    ): List<SignalWandChoice> {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired; select again" }
        return when (controlKind) {
            SignalWandControlKind.FIXED_BLOCK -> {
                val resolved = FixedBlockSignalEntranceResolver.resolve(
                    captured.publication.fixedBlocks.snapshot(captured.topology),
                    captured.publication.definitions.fixedBlockSignals,
                    point
                )
                if (mode == SignalWandMode.DELETION && resolved.signal == null) {
                    throw IllegalArgumentException("This fixed-block entrance has no primary signal to delete")
                }
                listOf(SignalWandChoice(
                    FixedBlockSignalWandBinding(captured.worldId, resolved.blockId, resolved.entryDirection),
                    "${resolved.blockId} ${resolved.entryDirection}"
                ))
            }
            SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
            SignalWandControlKind.PROTECTION_FIXED_ROUTE -> protectionChoices(captured, controlKind, mode, point)
        }
    }

    override fun place(
        capture: SignalWandCapture,
        binding: SignalWandBinding,
        point: RailPositionSnapshot,
        active: () -> Boolean
    ): SignalWandOutcome {
        val captured = capture as Capture
        fun selectionCurrent(): Boolean = active() && current(captured)
        require(selectionCurrent()) { "Signal selection expired; select again" }
        require(binding.worldId == captured.worldId) { "The bound entrance belongs to another world" }
        val publication = captured.publication
        val placement = ModelPlacement.RailAttached(point)
        val patch: SimpleSignalsDefinitionPatch
        val replacementId: String
        val created: Boolean
        when (binding) {
            is FixedBlockSignalWandBinding -> {
                val resolved = FixedBlockSignalEntranceResolver.resolve(
                    publication.fixedBlocks.snapshot(captured.topology),
                    publication.definitions.fixedBlockSignals,
                    binding
                )
                val replacement = resolved.signal?.let { existing ->
                    existing.copy(revision = nextRevision(existing.revision),
                        signalType = captured.signalType, placement = placement)
                } ?: FixedBlockSignalDefinition(
                    uniqueSignalId(publication), 0L, resolved.blockId,
                    DEFAULT_INSPECTION_LEAD_TIME_SECONDS, captured.signalType,
                    resolved.entryDirection, placement
                )
                patch = SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(replacement))
                replacementId = replacement.signalId
                created = resolved.signal == null
            }
            is ProtectionCurrentEntrySignalWandBinding -> {
                val existing = validateProtectionBinding(publication, binding,
                    ProtectionSignalKind.CURRENT_ENTRY, requireUsableNewRoute = false)
                val replacement = existing?.copy(revision = nextRevision(existing.revision),
                    signalType = captured.signalType, placement = placement)
                    ?: ProtectionSignalDefinition(uniqueSignalId(publication), 0L,
                        ProtectionSignalKind.CURRENT_ENTRY, binding.entryBoundaryId,
                        DEFAULT_INSPECTION_LEAD_TIME_SECONDS, captured.signalType, placement)
                patch = SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(replacement))
                replacementId = replacement.signalId
                created = existing == null
            }
            is ProtectionFixedRouteSignalWandBinding -> {
                val existing = validateProtectionBinding(publication, binding,
                    ProtectionSignalKind.FIXED_ROUTE, requireUsableNewRoute = true)
                val replacement = existing?.copy(revision = nextRevision(existing.revision),
                    signalType = captured.signalType, placement = placement)
                    ?: ProtectionSignalDefinition(uniqueSignalId(publication), 0L,
                        ProtectionSignalKind.FIXED_ROUTE, binding.routeId,
                        DEFAULT_INSPECTION_LEAD_TIME_SECONDS, captured.signalType, placement)
                patch = SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(replacement))
                replacementId = replacement.signalId
                created = existing == null
            }
        }
        require(selectionCurrent()) { "Signal selection expired before submission" }
        val result = manager.applyDefinitionPatch(
            captured.worldId,
            publication.definitions.definitionRevision,
            patch,
            captured.runtime,
            captured.topology,
            ::selectionCurrent
        )
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED) {
            val verb = if (created) "Created" else "Updated"
            SignalWandOutcome("$verb signal $replacementId as ${captured.signalType}; " +
                "definition revision ${result.definitions!!.definitionRevision}")
        } else {
            SignalWandOutcome(SimpleSignalsAdminCommand.formatResult(result))
        }
    }

    override fun delete(
        capture: SignalWandCapture,
        binding: SignalWandBinding,
        active: () -> Boolean
    ): SignalWandOutcome {
        val captured = capture as Capture
        fun selectionCurrent(): Boolean = active() && current(captured)
        require(selectionCurrent()) { "Signal selection expired; select again" }
        require(binding.worldId == captured.worldId) { "The bound entrance belongs to another world" }
        val publication = captured.publication
        val fixedSignal = if (binding is FixedBlockSignalWandBinding) {
            FixedBlockSignalEntranceResolver.resolve(
                publication.fixedBlocks.snapshot(captured.topology),
                publication.definitions.fixedBlockSignals, binding
            ).signal ?: throw IllegalArgumentException("This fixed-block entrance has no primary signal to delete")
        } else null
        val protectionSignal = when (binding) {
            is ProtectionCurrentEntrySignalWandBinding -> validateProtectionBinding(publication, binding,
                ProtectionSignalKind.CURRENT_ENTRY, requireUsableNewRoute = false)
            is ProtectionFixedRouteSignalWandBinding -> validateProtectionBinding(publication, binding,
                ProtectionSignalKind.FIXED_ROUTE, requireUsableNewRoute = false)
            else -> null
        }
        val signalId = fixedSignal?.signalId ?: protectionSignal?.signalId
            ?: throw IllegalArgumentException("Select an existing primary signal to delete")
        val attachedIds = publication.definitions.attachedSignals
            .filter { it.primarySignalId == signalId }
            .map { it.signalId }
        require(selectionCurrent()) { "Signal selection expired before submission" }
        val result = manager.applyDefinitionPatch(
            captured.worldId,
            publication.definitions.definitionRevision,
            SimpleSignalsDefinitionPatch(
                removeFixedBlockSignalIds = fixedSignal?.let { listOf(it.signalId) }.orEmpty(),
                removeProtectionSignalIds = protectionSignal?.let { listOf(it.signalId) }.orEmpty(),
                removeAttachedSignalIds = attachedIds
            ),
            captured.runtime,
            captured.topology,
            ::selectionCurrent
        )
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED) {
            SignalWandOutcome("Deleted signal $signalId and ${attachedIds.size} attached device(s); " +
                "definition revision ${result.definitions!!.definitionRevision}")
        } else {
            SignalWandOutcome(SimpleSignalsAdminCommand.formatResult(result))
        }
    }

    private fun protectionChoices(
        captured: Capture,
        controlKind: SignalWandControlKind,
        mode: SignalWandMode,
        point: RailPositionSnapshot
    ): List<SignalWandChoice> {
        val publication = captured.publication
        val entrance = ProtectionSignalEntranceResolver.resolve(
            checkNotNull(publication.regions.compilationSnapshot()), point)
        val entryId = entrance.boundary.boundaryId
        val authoredRoutes = publication.definitions.routeTables.flatMap { it.routes }
            .filter { it.entryBoundaryId == entryId }
            .sortedBy { it.routeId }
        val usableRouteIds = publication.routeSelection.routesById.keys
        val existing = publication.definitions.protectionSignals.filter { signal ->
            when (controlKind) {
                SignalWandControlKind.PROTECTION_CURRENT_ENTRY ->
                    signal.kind == ProtectionSignalKind.CURRENT_ENTRY && signal.bindingId == entryId
                SignalWandControlKind.PROTECTION_FIXED_ROUTE ->
                    signal.kind == ProtectionSignalKind.FIXED_ROUTE &&
                        authoredRoutes.any { it.routeId == signal.bindingId }
                else -> false
            }
        }.sortedBy { it.signalId }
        val choices = mutableListOf<SignalWandChoice>()
        if (mode == SignalWandMode.PLACEMENT) {
            when (controlKind) {
                SignalWandControlKind.PROTECTION_CURRENT_ENTRY -> choices += SignalWandChoice(
                    ProtectionCurrentEntrySignalWandBinding(captured.worldId, entryId, NewProtectionSignal),
                    "New current-entry signal at $entryId")
                SignalWandControlKind.PROTECTION_FIXED_ROUTE -> authoredRoutes
                    .filter { it.routeId in usableRouteIds }
                    .forEach { route -> choices += SignalWandChoice(
                        ProtectionFixedRouteSignalWandBinding(captured.worldId, entryId, route.routeId,
                            NewProtectionSignal),
                        "New fixed-route signal: ${route.routeId} -> ${route.exitBoundaryId}") }
                else -> Unit
            }
        }
        existing.forEach { signal ->
            val identity = ExistingProtectionSignal(signal.signalId, signal.revision)
            val binding = when (controlKind) {
                SignalWandControlKind.PROTECTION_CURRENT_ENTRY ->
                    ProtectionCurrentEntrySignalWandBinding(captured.worldId, entryId, identity)
                SignalWandControlKind.PROTECTION_FIXED_ROUTE ->
                    ProtectionFixedRouteSignalWandBinding(captured.worldId, entryId, signal.bindingId, identity)
                else -> error("not a protection control kind")
            }
            val available = when (controlKind) {
                SignalWandControlKind.PROTECTION_FIXED_ROUTE -> signal.bindingId in usableRouteIds
                SignalWandControlKind.PROTECTION_CURRENT_ENTRY ->
                    publication.routeSelection.routesById.values.any {
                        it.definition.entryBoundaryId == signal.bindingId
                    }
            }
            val logical = if (controlKind == SignalWandControlKind.PROTECTION_FIXED_ROUTE) {
                "route ${signal.bindingId}"
            } else "entry ${signal.bindingId}"
            choices += SignalWandChoice(binding,
                "Existing ${signal.signalId} | $logical | ${if (available) "available" else "unavailable"}")
        }
        require(choices.isNotEmpty()) {
            if (mode == SignalWandMode.DELETION) "This protection entrance has no matching primary signal to delete"
            else "This protection entrance has no currently usable authored route"
        }
        return choices
    }

    private fun validateProtectionBinding(
        publication: SimpleSignalsRuntimePublication,
        binding: SignalWandBinding,
        kind: ProtectionSignalKind,
        requireUsableNewRoute: Boolean
    ): ProtectionSignalDefinition? {
        val entryId = when (binding) {
            is ProtectionCurrentEntrySignalWandBinding -> binding.entryBoundaryId
            is ProtectionFixedRouteSignalWandBinding -> binding.entryBoundaryId
            else -> error("not a protection binding")
        }
        require(entryId in publication.regions.compilationSnapshot()!!.boundariesById) {
            "The bound protection entrance no longer exists"
        }
        val logicalBindingId = when (binding) {
            is ProtectionCurrentEntrySignalWandBinding -> entryId
            is ProtectionFixedRouteSignalWandBinding -> {
                val route = publication.definitions.routeTables.flatMap { it.routes }
                    .singleOrNull { it.routeId == binding.routeId }
                    ?: throw IllegalArgumentException("The bound protection route no longer exists")
                require(route.entryBoundaryId == entryId) { "The bound protection route belongs to another entrance" }
                binding.routeId
            }
        }
        val intent = when (binding) {
            is ProtectionCurrentEntrySignalWandBinding -> binding.intent
            is ProtectionFixedRouteSignalWandBinding -> binding.intent
        }
        if (intent === NewProtectionSignal) {
            if (requireUsableNewRoute) require(logicalBindingId in publication.routeSelection.routesById) {
                "The selected protection route is not currently usable"
            }
            return null
        }
        intent as ExistingProtectionSignal
        val existing = publication.definitions.protectionSignals.singleOrNull { it.signalId == intent.signalId }
            ?: throw IllegalArgumentException("The selected protection signal no longer exists")
        require(existing.revision == intent.revision) { "The selected protection signal revision changed" }
        require(existing.kind == kind && existing.bindingId == logicalBindingId) {
            "The selected protection signal binding changed"
        }
        return existing
    }

    private fun nextRevision(revision: Long): Long = try {
        Math.addExact(revision, 1L)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Signal revision $revision cannot be advanced")
    }

    private fun uniqueSignalId(publication: SimpleSignalsRuntimePublication): String {
        val candidate = newSignalId()
        val definitions = publication.definitions
        val deviceIds = buildSet {
            definitions.fixedBlockSignals.mapTo(this) { it.signalId }
            definitions.protectionSignals.mapTo(this) { it.signalId }
            definitions.routeIndicators.mapTo(this) { it.indicatorId }
            definitions.attachedSignals.mapTo(this) { it.signalId }
        }
        require(candidate !in deviceIds) { "Generated signal ID collides with an existing device: $candidate" }
        return candidate
    }

    private companion object {
        const val DEFAULT_INSPECTION_LEAD_TIME_SECONDS = 0.0
    }
}
