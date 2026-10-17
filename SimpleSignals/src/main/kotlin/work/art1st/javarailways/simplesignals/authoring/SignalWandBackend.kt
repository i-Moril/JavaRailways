package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.presentation.ModelTypeCatalogSnapshot
import work.art1st.javarailways.simplesignals.presentation.RailPlacementModel
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionAuthoringStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signal.AttachedSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition
import work.art1st.javarailways.simplesignals.signalpack.AvailableSignalType
import work.art1st.javarailways.simplesignals.signalpack.SignalTypeCatalogSnapshot
import java.util.UUID

internal interface SignalWandCapture { val worldId: UUID }

internal data class SignalWandOutcome(val text: WandText, val applied: Boolean = false) {
    constructor(message: String) : this(WandText.diagnostic(message))
}

internal data class SignalWandChoice(val binding: SignalWandBinding, val label: WandText)

internal sealed interface SignalRebindChoice {
    val signalId: String
    val controlKind: SignalWandControlKind
    val bindingId: String?
}
internal data class ProtectionSignalRebindChoice(val definition: ProtectionSignalDefinition) : SignalRebindChoice {
    override val signalId get() = definition.signalId
    override val controlKind get() = when (definition.kind) {
        ProtectionSignalKind.FIXED_ROUTE -> SignalWandControlKind.PROTECTION_FIXED_ROUTE
        ProtectionSignalKind.CURRENT_ENTRY -> SignalWandControlKind.PROTECTION_CURRENT_ENTRY
    }
    override val bindingId get() = definition.bindingId
}
internal data class FixedBlockSignalRebindChoice(val definition: FixedBlockSignalDefinition) : SignalRebindChoice {
    override val signalId get() = definition.signalId
    override val controlKind get() = SignalWandControlKind.FIXED_BLOCK
    override val bindingId get() = definition.controlledBlockId
}
internal sealed interface SignalRebindTarget {
    val bindingId: String
    val description: WandText
}
internal data class ProtectionSignalRebindTarget(val entryBoundaryId: String, override val bindingId: String) : SignalRebindTarget {
    override val description get() = WandText.key("signal.rebind.protection_target", authoringName(entryBoundaryId, "name.entrance"), authoringName(bindingId, "name.route"))
}
internal data class FixedBlockSignalRebindTarget(val entry: FixedBlockSignalEntry) : SignalRebindTarget {
    override val bindingId get() = entry.blockId
    override val description get() = WandText.key("signal.rebind.block_target", authoringName(entry.blockId, "name.block"),
        WandText.key("signal.entry.${entry.direction.name}"))
}
internal data class RouteIndicatorWandOptions(val routeIds: List<String>, val variants: List<String>,
    val mappings: Map<String, String>)
internal enum class PlacedSignalKind { FIXED_BLOCK, PROTECTION, ATTACHED, INDICATOR }
internal data class PlacedSignalChoice(val kind: PlacedSignalKind, val id: String, val revision: Long,
    val placement: ModelPlacement)
internal data class SignalDeviceConfiguration(val signalType: String?, val modelId: String?)

/** Worker-facing boundary. Catalog data exposed to commands is immutable intent, not edit authority. */
internal interface SignalWandBackend {
    fun availableSignalTypes(): List<AvailableSignalType>?
    fun availableModelIds(): List<String>?
    fun railPlacementModel(controlKind: SignalWandControlKind, signalType: String, modelId: String?): RailPlacementModel?
    fun capture(worldId: UUID, signalType: String?, modelId: String? = null): SignalWandCapture
    fun current(capture: SignalWandCapture): Boolean
    fun placedDevices(capture: SignalWandCapture): List<PlacedSignalChoice>
    fun protectionEntrance(capture: SignalWandCapture, point: RailPositionSnapshot): Boolean
    fun occupiedPrimary(capture: SignalWandCapture, binding: FixedBlockSignalWandBinding): Boolean
    fun deviceConfiguration(capture: SignalWandCapture, device: PlacedSignalChoice): SignalDeviceConfiguration
    fun diagnostics(capture: SignalWandCapture, device: PlacedSignalChoice): work.art1st.javarailways.simplesignals.runtime.SignalDeviceDiagnostics?
    fun changeType(capture: SignalWandCapture, device: PlacedSignalChoice,
        signalType: String?, modelId: String?, active: () -> Boolean): SignalWandOutcome
    fun deletePlaced(capture: SignalWandCapture, device: PlacedSignalChoice, active: () -> Boolean): SignalWandOutcome
    fun savePlacement(capture: SignalWandCapture, device: PlacedSignalChoice,
        placement: ModelPlacement, active: () -> Boolean): SignalWandOutcome
    fun rebindDevices(capture: SignalWandCapture): List<SignalRebindChoice>
    fun rebindTargets(capture: SignalWandCapture, device: SignalRebindChoice): List<SignalRebindTarget>
    fun rebind(capture: SignalWandCapture, device: SignalRebindChoice,
        target: SignalRebindTarget, active: () -> Boolean): SignalWandOutcome
    fun indicatorDevices(capture: SignalWandCapture): List<RouteIndicatorDefinition>
    fun indicatorEntries(capture: SignalWandCapture): List<String>
    fun indicatorOptions(capture: SignalWandCapture, binding: RouteIndicatorSignalWandBinding,
        entryBoundaryId: String): RouteIndicatorWandOptions
    fun saveIndicator(capture: SignalWandCapture, binding: RouteIndicatorSignalWandBinding,
        entryBoundaryId: String, noRouteVariant: String, mappings: Map<String, String>,
        placement: ModelPlacement?, active: () -> Boolean): SignalWandOutcome
    fun resolveEntrance(
        capture: SignalWandCapture,
        controlKind: SignalWandControlKind,
        mode: SignalWandMode,
        point: RailPositionSnapshot
    ): List<SignalWandChoice>
    fun place(
        capture: SignalWandCapture,
        binding: SignalWandBinding,
        placement: ModelPlacement,
        active: () -> Boolean
    ): SignalWandOutcome

    fun place(
        capture: SignalWandCapture,
        binding: SignalWandBinding,
        point: RailPositionSnapshot,
        active: () -> Boolean
    ): SignalWandOutcome = place(capture, binding, ModelPlacement.RailAttached(point), active)
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
        val signalType: String?,
        val modelId: String?,
        val catalog: SignalTypeCatalogSnapshot,
        val modelCatalog: ModelTypeCatalogSnapshot?,
        val runtime: SimpleSignalsWorldRuntime,
        val publication: SimpleSignalsRuntimePublication,
        val topology: TopologyState
    ) : SignalWandCapture

    override fun availableSignalTypes(): List<AvailableSignalType>? =
        manager.signalTypeCatalogSnapshot()?.signalTypes

    override fun availableModelIds(): List<String>? = manager.modelTypeCatalogSnapshot()?.modelIds

    override fun railPlacementModel(controlKind: SignalWandControlKind, signalType: String,
        modelId: String?): RailPlacementModel? = when (controlKind) {
        SignalWandControlKind.ATTACHED_SIGNAL, SignalWandControlKind.ROUTE_INDICATOR ->
            modelId?.let { manager.modelTypeCatalogSnapshot()?.railPlacementModel(it) }
        else -> manager.signalTypeCatalogSnapshot()?.railPlacementModel(signalType)
    }

    override fun capture(worldId: UUID, signalType: String?, modelId: String?): SignalWandCapture {
        val catalog = manager.signalTypeCatalogSnapshot()
            ?: throw IllegalStateException("Signal type catalog is not ready")
        require(signalType == null || catalog.contains(signalType)) { "Unknown signal type: $signalType" }
        val modelCatalog = manager.modelTypeCatalogSnapshot()
        if (modelId != null) require(modelCatalog?.contains(modelId) == true) {
            "Unknown presentation model: $modelId"
        }
        val runtime = manager.authoringRuntime(worldId)
            ?: throw IllegalStateException("SimpleSignals world is unavailable")
        return Capture(worldId, signalType, modelId, catalog, modelCatalog,
            runtime, runtime.authoringPublication(),
            infrastructure.currentTopology(worldId))
    }

    override fun current(capture: SignalWandCapture): Boolean {
        val captured = capture as Capture
        return manager.currentSignalTypeCatalog(captured.catalog) &&
            manager.currentModelTypeCatalog(captured.modelCatalog) &&
            manager.authoringRuntime(captured.worldId) === captured.runtime &&
            captured.runtime.authoringPublication() === captured.publication &&
            infrastructure.currentTopology(captured.worldId) === captured.topology
    }

    override fun rebindDevices(capture: SignalWandCapture): List<SignalRebindChoice> {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired; select again" }
        return (captured.publication.definitions.protectionSignals.map(::ProtectionSignalRebindChoice) +
            captured.publication.definitions.fixedBlockSignals.map(::FixedBlockSignalRebindChoice)).sortedBy { it.signalId }
    }

    override fun placedDevices(capture: SignalWandCapture): List<PlacedSignalChoice> {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired; select again" }
        val definitions = captured.publication.definitions
        return (definitions.fixedBlockSignals.mapNotNull { device -> device.placement?.let {
            PlacedSignalChoice(PlacedSignalKind.FIXED_BLOCK, device.signalId, device.revision, it)
        } } + definitions.protectionSignals.mapNotNull { device -> device.placement?.let {
            PlacedSignalChoice(PlacedSignalKind.PROTECTION, device.signalId, device.revision, it)
        } } + definitions.attachedSignals.map {
            PlacedSignalChoice(PlacedSignalKind.ATTACHED, it.signalId, it.revision, it.model.placement)
        } + definitions.routeIndicators.mapNotNull { device -> device.model?.let {
            PlacedSignalChoice(PlacedSignalKind.INDICATOR, device.indicatorId, device.revision, it.placement)
        } }).sortedBy { it.id }
    }

    override fun savePlacement(capture: SignalWandCapture, device: PlacedSignalChoice,
        placement: ModelPlacement, active: () -> Boolean): SignalWandOutcome {
        val captured = capture as Capture
        require(active()) { "Signal selection is no longer active" }
        require(device in placedDevices(captured)) { "The selected signal changed; select again" }
        when (val previous = device.placement) {
            is ModelPlacement.RailAttached -> require(placement is ModelPlacement.RailAttached &&
                placement.position == previous.position) { "Numeric editing must retain the rail anchor and facing" }
            is ModelPlacement.Ground -> require(placement is ModelPlacement.Ground) {
                "Numeric editing must retain ground placement"
            }
        }
        if (device.placement == placement) return SignalWandOutcome(WandText.key("placement.unchanged", authoringName(device.id, "name.signal")))
        val revision = nextRevision(device.revision)
        val definitions = captured.publication.definitions
        val patch = when (device.kind) {
            PlacedSignalKind.FIXED_BLOCK -> SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(
                definitions.fixedBlockSignals.single { it.signalId == device.id }.copy(revision = revision, placement = placement)))
            PlacedSignalKind.PROTECTION -> SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(
                definitions.protectionSignals.single { it.signalId == device.id }.copy(revision = revision, placement = placement)))
            PlacedSignalKind.ATTACHED -> {
                val definition = definitions.attachedSignals.single { it.signalId == device.id }
                SimpleSignalsDefinitionPatch(upsertAttachedSignals = listOf(definition.copy(revision = revision,
                    model = definition.model.copy(placement = placement))))
            }
            PlacedSignalKind.INDICATOR -> {
                val definition = definitions.routeIndicators.single { it.indicatorId == device.id }
                SimpleSignalsDefinitionPatch(upsertRouteIndicators = listOf(RouteIndicatorDefinition(
                    definition.indicatorId, revision, definition.entryBoundaryId, definition.noRouteModelVariant,
                    definition.modelVariantsByRouteId, checkNotNull(definition.model).copy(placement = placement))))
            }
        }
        val result = manager.applyDefinitionPatch(captured.worldId, definitions.definitionRevision, patch,
            captured.runtime, captured.topology, { active() && current(captured) })
        return SignalWandOutcome(if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            WandText.key("placement.saved", authoringName(device.id, "name.signal"))
        else WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)))
    }

    override fun changeType(capture: SignalWandCapture, device: PlacedSignalChoice,
        signalType: String?, modelId: String?, active: () -> Boolean): SignalWandOutcome {
        val captured = capture as Capture
        require(active() && current(captured)) { "Signal selection expired" }
        require(device in placedDevices(captured)) { "The selected device changed" }
        val definitions = captured.publication.definitions
        val previous = deviceConfiguration(captured, device)
        if ((previous.signalType != null && previous.signalType == signalType && modelId == null) ||
            (previous.signalType == null && signalType == null && previous.modelId == modelId))
            return SignalWandOutcome(WandText.key("signal.type.unchanged"))
        val revision = nextRevision(device.revision)
        val patch = when (device.kind) {
            PlacedSignalKind.FIXED_BLOCK -> {
                require(signalType != null && captured.catalog.contains(signalType)) { "Unknown signal type" }
                SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(definitions.fixedBlockSignals
                    .single { it.signalId == device.id }.copy(revision = revision, signalType = signalType)))
            }
            PlacedSignalKind.PROTECTION -> {
                require(signalType != null && captured.catalog.contains(signalType)) { "Unknown signal type" }
                SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(definitions.protectionSignals
                    .single { it.signalId == device.id }.copy(revision = revision, signalType = signalType)))
            }
            PlacedSignalKind.ATTACHED -> {
                require(modelId != null && captured.modelCatalog?.contains(modelId) == true) { "Unknown model" }
                val old = definitions.attachedSignals.single { it.signalId == device.id }
                SimpleSignalsDefinitionPatch(upsertAttachedSignals = listOf(old.copy(revision = revision,
                    model = old.model.copy(modelId = modelId))))
            }
            PlacedSignalKind.INDICATOR -> {
                require(modelId != null && captured.modelCatalog?.contains(modelId) == true) { "Unknown model" }
                val old = definitions.routeIndicators.single { it.indicatorId == device.id }
                SimpleSignalsDefinitionPatch(upsertRouteIndicators = listOf(RouteIndicatorDefinition(old.indicatorId,
                    revision, old.entryBoundaryId, old.noRouteModelVariant, old.modelVariantsByRouteId,
                    checkNotNull(old.model).copy(modelId = modelId))))
            }
        }
        val result = manager.applyDefinitionPatch(captured.worldId, definitions.definitionRevision, patch,
            captured.runtime, captured.topology, { active() && current(captured) })
        return SignalWandOutcome(if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            WandText.key("signal.type.changed") else WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)))
    }

    override fun deviceConfiguration(capture: SignalWandCapture, device: PlacedSignalChoice): SignalDeviceConfiguration {
        val captured = capture as Capture
        require(device in placedDevices(captured)) { "The selected device changed" }
        val definitions = captured.publication.definitions
        return when (device.kind) {
            PlacedSignalKind.FIXED_BLOCK -> definitions.fixedBlockSignals.single { it.signalId == device.id }.let {
                SignalDeviceConfiguration(it.signalType, captured.catalog.railPlacementModel(it.signalType)?.modelId) }
            PlacedSignalKind.PROTECTION -> definitions.protectionSignals.single { it.signalId == device.id }.let {
                SignalDeviceConfiguration(it.signalType, captured.catalog.railPlacementModel(it.signalType)?.modelId) }
            PlacedSignalKind.ATTACHED -> SignalDeviceConfiguration(null, definitions.attachedSignals.single { it.signalId == device.id }.model.modelId)
            PlacedSignalKind.INDICATOR -> SignalDeviceConfiguration(null, definitions.routeIndicators.single { it.indicatorId == device.id }.model?.modelId)
        }
    }

    override fun diagnostics(capture: SignalWandCapture, device: PlacedSignalChoice): work.art1st.javarailways.simplesignals.runtime.SignalDeviceDiagnostics? {
        val captured = capture as Capture
        require(device in placedDevices(captured)) { "The selected device changed" }
        return captured.runtime.signalDeviceDiagnostics(device.id)
    }

    override fun protectionEntrance(capture: SignalWandCapture, point: RailPositionSnapshot): Boolean {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired" }
        return captured.publication.regions.compilationSnapshot()?.boundariesById?.values
            ?.any { it.position.near(point) } == true
    }

    override fun occupiedPrimary(capture: SignalWandCapture, binding: FixedBlockSignalWandBinding): Boolean {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired" }
        return captured.publication.definitions.fixedBlockSignals.any {
            it.controlledBlockId == binding.blockId && it.entryDirection == binding.entryDirection
        }
    }

    override fun deletePlaced(capture: SignalWandCapture, device: PlacedSignalChoice, active: () -> Boolean): SignalWandOutcome {
        val captured = capture as Capture
        require(active() && device in placedDevices(captured)) { "The selected device changed" }
        val attached = if (device.kind == PlacedSignalKind.FIXED_BLOCK || device.kind == PlacedSignalKind.PROTECTION)
            captured.publication.definitions.attachedSignals.filter { it.primarySignalId == device.id }.map { it.signalId }
            else emptyList()
        val patch = SimpleSignalsDefinitionPatch(
            removeFixedBlockSignalIds = if (device.kind == PlacedSignalKind.FIXED_BLOCK) listOf(device.id) else emptyList(),
            removeProtectionSignalIds = if (device.kind == PlacedSignalKind.PROTECTION) listOf(device.id) else emptyList(),
            removeRouteIndicatorIds = if (device.kind == PlacedSignalKind.INDICATOR) listOf(device.id) else emptyList(),
            removeAttachedSignalIds = if (device.kind == PlacedSignalKind.ATTACHED) listOf(device.id) else attached)
        val result = manager.applyDefinitionPatch(captured.worldId, captured.publication.definitions.definitionRevision,
            patch, captured.runtime, captured.topology, { active() && current(captured) })
        return SignalWandOutcome(if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            WandText.key("signal.deleted", authoringName(device.id, "name.signal"), attached.size, result.definitions!!.definitionRevision)
        else WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)))
    }

    override fun rebindTargets(capture: SignalWandCapture,
        device: SignalRebindChoice): List<SignalRebindTarget> {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired; select again" }
        if (device is FixedBlockSignalRebindChoice) {
            require(device.definition in captured.publication.definitions.fixedBlockSignals) { "The selected fixed-block signal changed; select again" }
            val blocks = captured.publication.fixedBlocks.snapshot(captured.topology)
            val occupied = captured.publication.definitions.fixedBlockSignals.filter { it.signalId != device.signalId }.mapNotNull { it.entry }.toSet()
            return blocks.blocksById.values.filter { it.definition.blockId !in blocks.unavailableBlockIds }.sortedBy { it.definition.blockId }
                .flatMap { block ->
                    val directions = if (block.definition.bidirectional) FixedBlockEntryDirection.entries else listOf(FixedBlockEntryDirection.FORWARD)
                    directions.map { FixedBlockSignalEntry(block.definition.blockId, it) }
                }.filterNot { it in occupied }.map(::FixedBlockSignalRebindTarget)
        }
        require(device is ProtectionSignalRebindChoice)
        require(device.definition in captured.publication.definitions.protectionSignals) {
            "The selected protection signal changed; select again"
        }
        val routes = captured.publication.routeSelection.routesById.values
        return when (device.definition.kind) {
            ProtectionSignalKind.FIXED_ROUTE -> routes.sortedBy { it.definition.routeId }.map {
                ProtectionSignalRebindTarget(it.definition.entryBoundaryId, it.definition.routeId)
            }
            ProtectionSignalKind.CURRENT_ENTRY -> routes.map { it.definition.entryBoundaryId }.distinct().sorted()
                .map { ProtectionSignalRebindTarget(it, it) }
        }
    }

    override fun rebind(capture: SignalWandCapture, device: SignalRebindChoice,
        target: SignalRebindTarget, active: () -> Boolean): SignalWandOutcome {
        val captured = capture as Capture
        require(active()) { "Signal selection is no longer active" }
        require(target in rebindTargets(captured, device)) { "The selected signal target is not currently usable" }
        val patch = when (device) {
            is ProtectionSignalRebindChoice -> {
                require(target is ProtectionSignalRebindTarget)
                val definition = device.definition
                if (definition.bindingId == target.bindingId) return SignalWandOutcome(WandText.key("binding.unchanged", authoringName(device.signalId, "name.signal")))
                SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(definition.copy(revision = nextRevision(definition.revision), bindingId = target.bindingId)))
            }
            is FixedBlockSignalRebindChoice -> {
                require(target is FixedBlockSignalRebindTarget)
                val definition = device.definition
                if (definition.entry == target.entry) return SignalWandOutcome(WandText.key("binding.unchanged", authoringName(device.signalId, "name.signal")))
                SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(definition.copy(revision = nextRevision(definition.revision),
                    controlledBlockId = target.entry.blockId, entryDirection = target.entry.direction)))
            }
        }
        val result = manager.applyDefinitionPatch(captured.worldId, captured.publication.definitions.definitionRevision,
            patch, captured.runtime, captured.topology,
            { active() && current(captured) })
        return SignalWandOutcome(if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            WandText.key("binding.saved", authoringName(device.signalId, "name.signal"), target.description)
        else WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)))
    }

    override fun indicatorDevices(capture: SignalWandCapture): List<RouteIndicatorDefinition> {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired; select again" }
        return captured.publication.definitions.routeIndicators.sortedBy { it.indicatorId }
    }

    override fun indicatorEntries(capture: SignalWandCapture): List<String> {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired; select again" }
        return captured.publication.routeSelection.routesById.values.map { it.definition.entryBoundaryId }.distinct().sorted()
    }

    override fun indicatorOptions(capture: SignalWandCapture, binding: RouteIndicatorSignalWandBinding,
        entryBoundaryId: String): RouteIndicatorWandOptions {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired; select again" }
        require(binding.worldId == captured.worldId) { "The selected indicator belongs to another world" }
        val existing = validateRouteIndicatorBinding(captured.publication, binding)
        require(entryBoundaryId == binding.entryBoundaryId || entryBoundaryId in indicatorEntries(captured)) {
            "The selected indicator entrance is not currently usable"
        }
        val modelId = if (existing == null) requireNotNull(captured.modelId) {
            "Select a presentation model in the Signal Wand settings"
        } else requireNotNull(existing.model) { "Place this route indicator before editing its mapping" }.modelId
        val variants = requireNotNull(captured.modelCatalog?.variantNames(modelId)) { "Unknown presentation model: $modelId" }
        require(variants.isNotEmpty()) { "This model declares no route indicator variants" }
        val routeIds = captured.publication.definitions.routeTables.flatMap { it.routes }
            .filter { it.entryBoundaryId == entryBoundaryId }.map { it.routeId }.sorted()
        return RouteIndicatorWandOptions(routeIds, variants,
            existing?.modelVariantsByRouteId.orEmpty().filterKeys { it in routeIds })
    }

    override fun saveIndicator(capture: SignalWandCapture, binding: RouteIndicatorSignalWandBinding,
        entryBoundaryId: String, noRouteVariant: String, mappings: Map<String, String>,
        placement: ModelPlacement?, active: () -> Boolean): SignalWandOutcome {
        val captured = capture as Capture
        require(active()) { "Signal selection is no longer active" }
        val options = indicatorOptions(captured, binding, entryBoundaryId)
        require(noRouteVariant in options.variants && mappings.values.all { it in options.variants }) {
            "Unknown route indicator model variant"
        }
        require(mappings.keys.all { it in options.routeIds }) { "Indicator mapping contains another entrance's route" }
        val existing = validateRouteIndicatorBinding(captured.publication, binding)
        require((existing == null) == (placement != null)) { "Only a new indicator accepts placement in the mapping editor" }
        if (existing != null && existing.entryBoundaryId == entryBoundaryId &&
            existing.noRouteModelVariant == noRouteVariant && existing.modelVariantsByRouteId == mappings)
            return SignalWandOutcome(WandText.key("indicator.unchanged", authoringName(existing.indicatorId, "name.signal")))
        val replacement = RouteIndicatorDefinition(existing?.indicatorId ?: uniqueSignalId(captured.publication),
            existing?.let { nextRevision(it.revision) } ?: 0L, entryBoundaryId, noRouteVariant, mappings,
            existing?.model ?: DeviceModelDefinition(checkNotNull(captured.modelId), checkNotNull(placement)))
        val result = manager.applyDefinitionPatch(captured.worldId, captured.publication.definitions.definitionRevision,
            SimpleSignalsDefinitionPatch(upsertRouteIndicators = listOf(replacement)), captured.runtime, captured.topology,
            { active() && current(captured) })
        return SignalWandOutcome(if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            WandText.key("indicator.saved", WandText.key(if (existing == null) "created" else "updated"), authoringName(replacement.indicatorId, "name.signal"), WandText.key(if (existing == null) "placement.created" else "placement.kept"))
        else WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)),
            applied = result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
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
                    WandText.key("binding.block", authoringName(resolved.blockId, "name.block.overlay"), resolved.entryDirection)
                ))
            }
            SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
            SignalWandControlKind.PROTECTION_FIXED_ROUTE -> protectionChoices(captured, controlKind, mode, point)
            SignalWandControlKind.ATTACHED_SIGNAL -> attachedChoices(captured, mode, point)
            SignalWandControlKind.ROUTE_INDICATOR -> routeIndicatorChoices(captured, mode, point)
        }
    }

    override fun place(
        capture: SignalWandCapture,
        binding: SignalWandBinding,
        placement: ModelPlacement,
        active: () -> Boolean
    ): SignalWandOutcome {
        val captured = capture as Capture
        fun selectionCurrent(): Boolean = active() && current(captured)
        require(selectionCurrent()) { "Signal selection expired; select again" }
        require(binding.worldId == captured.worldId) { "The bound entrance belongs to another world" }
        val publication = captured.publication
        val patch: SimpleSignalsDefinitionPatch
        val replacementId: String
        val created: Boolean
        val signalType = if (binding is AttachedSignalWandBinding || binding is RouteIndicatorSignalWandBinding)
            null else requireNotNull(captured.signalType) { "Choose a signal type before placement" }
        when (binding) {
            is FixedBlockSignalWandBinding -> {
                val resolved = FixedBlockSignalEntranceResolver.resolve(
                    publication.fixedBlocks.snapshot(captured.topology),
                    publication.definitions.fixedBlockSignals,
                    binding
                )
                val replacement = resolved.signal?.let { existing ->
                    existing.copy(revision = nextRevision(existing.revision),
                        signalType = checkNotNull(signalType), placement = placement)
                } ?: FixedBlockSignalDefinition(
                    uniqueSignalId(publication), 0L, resolved.blockId,
                    DEFAULT_INSPECTION_LEAD_TIME_SECONDS, checkNotNull(signalType),
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
                    signalType = checkNotNull(signalType), placement = placement)
                    ?: ProtectionSignalDefinition(uniqueSignalId(publication), 0L,
                        ProtectionSignalKind.CURRENT_ENTRY, binding.entryBoundaryId,
                        DEFAULT_INSPECTION_LEAD_TIME_SECONDS, checkNotNull(signalType), placement)
                patch = SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(replacement))
                replacementId = replacement.signalId
                created = existing == null
            }
            is ProtectionFixedRouteSignalWandBinding -> {
                val existing = validateProtectionBinding(publication, binding,
                    ProtectionSignalKind.FIXED_ROUTE, requireUsableNewRoute = true)
                val replacement = existing?.copy(revision = nextRevision(existing.revision),
                    signalType = checkNotNull(signalType), placement = placement)
                    ?: ProtectionSignalDefinition(uniqueSignalId(publication), 0L,
                        ProtectionSignalKind.FIXED_ROUTE, binding.routeId,
                        DEFAULT_INSPECTION_LEAD_TIME_SECONDS, checkNotNull(signalType), placement)
                patch = SimpleSignalsDefinitionPatch(upsertProtectionSignals = listOf(replacement))
                replacementId = replacement.signalId
                created = existing == null
            }
            is AttachedSignalWandBinding -> {
                val modelId = requireNotNull(captured.modelId) {
                    "Select a presentation model in the Signal Wand settings"
                }
                val existing = validateAttachedBinding(publication, binding)
                val replacement = existing?.copy(
                    revision = nextRevision(existing.revision),
                    model = DeviceModelDefinition(modelId, placement)
                ) ?: AttachedSignalDefinition(
                    uniqueSignalId(publication), 0L, binding.primarySignalId,
                    DeviceModelDefinition(modelId, placement)
                )
                patch = SimpleSignalsDefinitionPatch(upsertAttachedSignals = listOf(replacement))
                replacementId = replacement.signalId
                created = existing == null
            }
            is RouteIndicatorSignalWandBinding -> {
                val modelId = requireNotNull(captured.modelId) {
                    "Select a presentation model in the Signal Wand settings"
                }
                val existing = validateRouteIndicatorBinding(publication, binding)
                    ?: throw IllegalArgumentException("Configure the new route indicator before placement")
                val replacement = RouteIndicatorDefinition(
                    existing.indicatorId, nextRevision(existing.revision), existing.entryBoundaryId,
                    existing.noRouteModelVariant, existing.modelVariantsByRouteId,
                    DeviceModelDefinition(modelId, placement)
                )
                patch = SimpleSignalsDefinitionPatch(upsertRouteIndicators = listOf(replacement))
                replacementId = replacement.indicatorId
                created = false
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
            SignalWandOutcome(WandText.key(if (binding is AttachedSignalWandBinding || binding is RouteIndicatorSignalWandBinding) "device.saved" else "signal.saved", WandText.key(if (created) "created" else "updated"), authoringName(replacementId, "name.signal"), if (binding is AttachedSignalWandBinding || binding is RouteIndicatorSignalWandBinding) captured.modelId else captured.signalType, result.definitions!!.definitionRevision), applied = true)
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
        val attachedSignal = (binding as? AttachedSignalWandBinding)?.let {
            validateAttachedBinding(publication, it)
                ?: throw IllegalArgumentException("Select an existing attached signal to delete")
        }
        val routeIndicator = (binding as? RouteIndicatorSignalWandBinding)?.let {
            validateRouteIndicatorBinding(publication, it)
        }
        val signalId = fixedSignal?.signalId ?: protectionSignal?.signalId ?: attachedSignal?.signalId
            ?: routeIndicator?.indicatorId
            ?: throw IllegalArgumentException("Select an existing signal device to delete")
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
                removeRouteIndicatorIds = routeIndicator?.let { listOf(it.indicatorId) }.orEmpty(),
                removeAttachedSignalIds = if (attachedSignal == null) attachedIds else listOf(attachedSignal.signalId),
            ),
            captured.runtime,
            captured.topology,
            ::selectionCurrent
        )
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED) {
            SignalWandOutcome(WandText.key("signal.deleted", authoringName(signalId, "name.signal"), if (attachedSignal == null) attachedIds.size else 0, result.definitions!!.definitionRevision))
        } else {
            SignalWandOutcome(SimpleSignalsAdminCommand.formatResult(result))
        }
    }

    private fun attachedChoices(
        captured: Capture,
        mode: SignalWandMode,
        point: RailPositionSnapshot
    ): List<SignalWandChoice> {
        val definitions = captured.publication.definitions
        val primaries = buildList {
            definitions.fixedBlockSignals.forEach { signal ->
                val placement = signal.placement as? ModelPlacement.RailAttached ?: return@forEach
                if (placement.position.near(point)) add(PrimaryDevice(signal.signalId, signal.revision))
            }
            definitions.protectionSignals.forEach { signal ->
                val placement = signal.placement as? ModelPlacement.RailAttached ?: return@forEach
                if (placement.position.near(point)) add(PrimaryDevice(signal.signalId, signal.revision))
            }
        }.sortedBy(PrimaryDevice::signalId)
        require(primaries.isNotEmpty()) { "No placed primary signal was found at this rail position" }
        val choices = mutableListOf<SignalWandChoice>()
        primaries.forEach { primary ->
            if (mode == SignalWandMode.PLACEMENT) choices += SignalWandChoice(
                AttachedSignalWandBinding(captured.worldId, primary.signalId, primary.revision, NewAttachedSignal),
                WandText.key("choice.attached.new", authoringName(primary.signalId, "name.signal"))
            )
            definitions.attachedSignals.asSequence()
                .filter { it.primarySignalId == primary.signalId }
                .sortedBy { it.signalId }
                .forEach { attached -> choices += SignalWandChoice(
                    AttachedSignalWandBinding(captured.worldId, primary.signalId, primary.revision,
                        ExistingAttachedSignal(attached.signalId, attached.revision)),
                    WandText.key("choice.attached.existing", authoringName(attached.signalId, "name.attached"), authoringName(primary.signalId, "name.signal"))
                ) }
        }
        require(choices.isNotEmpty()) { "The selected primary signal has no attached signal to delete" }
        return choices
    }

    private fun routeIndicatorChoices(
        captured: Capture,
        mode: SignalWandMode,
        point: RailPositionSnapshot
    ): List<SignalWandChoice> {
        val publication = captured.publication
        val entrance = ProtectionSignalEntranceResolver.resolve(
            checkNotNull(publication.regions.compilationSnapshot()), point)
        val indicators = publication.definitions.routeIndicators
            .filter { it.entryBoundaryId == entrance.boundary.boundaryId }
            .sortedBy { it.indicatorId }
        require(mode == SignalWandMode.PLACEMENT || indicators.isNotEmpty()) {
            "This entrance has no route indicator to delete"
        }
        val newChoice = if (mode == SignalWandMode.PLACEMENT) listOf(SignalWandChoice(
            RouteIndicatorSignalWandBinding(captured.worldId, entrance.boundary.boundaryId),
            WandText.key("choice.indicator.new", authoringName(entrance.boundary.boundaryId, "name.entrance")))) else emptyList()
        return newChoice + indicators.map { indicator -> SignalWandChoice(
            RouteIndicatorSignalWandBinding(captured.worldId, indicator.entryBoundaryId,
                indicator.indicatorId, indicator.revision),
            WandText.key("choice.indicator.existing", authoringName(indicator.indicatorId, "name.indicator"))
        ) }
    }

    private fun validateAttachedBinding(
        publication: SimpleSignalsRuntimePublication,
        binding: AttachedSignalWandBinding
    ): AttachedSignalDefinition? {
        val primaryRevision = publication.definitions.fixedBlockSignals
            .singleOrNull { it.signalId == binding.primarySignalId }?.revision
            ?: publication.definitions.protectionSignals
                .singleOrNull { it.signalId == binding.primarySignalId }?.revision
            ?: throw IllegalArgumentException("The selected primary signal no longer exists")
        require(primaryRevision == binding.primarySignalRevision) { "The selected primary signal revision changed" }
        val intent = binding.intent
        if (intent === NewAttachedSignal) return null
        intent as ExistingAttachedSignal
        val existing = publication.definitions.attachedSignals.singleOrNull { it.signalId == intent.signalId }
            ?: throw IllegalArgumentException("The selected attached signal no longer exists")
        require(existing.revision == intent.revision) { "The selected attached signal revision changed" }
        require(existing.primarySignalId == binding.primarySignalId) { "The selected attached signal binding changed" }
        return existing
    }

    private fun validateRouteIndicatorBinding(
        publication: SimpleSignalsRuntimePublication,
        binding: RouteIndicatorSignalWandBinding
    ): RouteIndicatorDefinition? {
        require(binding.entryBoundaryId == null || binding.entryBoundaryId in checkNotNull(publication.regions.compilationSnapshot()).boundariesById) {
            "The bound protection entrance no longer exists"
        }
        if (binding.indicatorId == null) return null
        val existing = publication.definitions.routeIndicators.singleOrNull {
            it.indicatorId == binding.indicatorId
        } ?: throw IllegalArgumentException("The selected route indicator no longer exists")
        require(existing.revision == binding.indicatorRevision) { "The selected route indicator revision changed" }
        require(existing.entryBoundaryId == binding.entryBoundaryId) { "The selected route indicator binding changed" }
        return existing
    }

    private data class PrimaryDevice(val signalId: String, val revision: Long)

    private fun RailPositionSnapshot.near(other: RailPositionSnapshot): Boolean =
        railId == other.railId && facing == other.facing &&
            kotlin.math.abs(arcLengthInAbMeters - other.arcLengthInAbMeters) <= DEVICE_SELECTION_TOLERANCE_METRES

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
                    WandText.key("choice.current.new", authoringName(entryId, "name.entrance")))
                SignalWandControlKind.PROTECTION_FIXED_ROUTE -> authoredRoutes
                    .filter { it.routeId in usableRouteIds }
                    .forEach { route -> choices += SignalWandChoice(
                        ProtectionFixedRouteSignalWandBinding(captured.worldId, entryId, route.routeId,
                            NewProtectionSignal),
                        WandText.key("choice.route.new", authoringName(route.routeId, "name.route"), authoringName(route.exitBoundaryId, "name.exit"))) }
                else -> Unit
            }
        }
        existing.forEach { signal ->
            val identity = ExistingProtectionSignal(signal.signalId, signal.revision)
            val binding = when (controlKind) {
                SignalWandControlKind.PROTECTION_CURRENT_ENTRY ->
                    ProtectionCurrentEntrySignalWandBinding(captured.worldId, entryId, identity)
                SignalWandControlKind.PROTECTION_FIXED_ROUTE ->
                    ProtectionFixedRouteSignalWandBinding(captured.worldId, entryId, checkNotNull(signal.bindingId), identity)
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
                WandText.key("choice.route", signal.bindingId?.let { authoringName(it, "name.route") } ?: WandText.key("none"))
            } else WandText.key("choice.entry", signal.bindingId?.let { authoringName(it, "name.entrance") } ?: WandText.key("none"))
            choices += SignalWandChoice(binding,
                WandText.key("choice.existing", authoringName(signal.signalId, "name.signal"), logical, WandText.key(if (available) "overlay.available" else "overlay.unavailable")))
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
        const val DEVICE_SELECTION_TOLERANCE_METRES = 0.75
    }
}
