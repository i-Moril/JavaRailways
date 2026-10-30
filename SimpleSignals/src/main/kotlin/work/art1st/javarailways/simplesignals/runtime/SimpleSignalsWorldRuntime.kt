package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueInput
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueKind

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditDecision
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditImpact
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditRequest
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditStatus
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictionSource
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalog
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalogStatus
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockInspectionSource
import work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCatalog
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCatalogStatus
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCompilationSnapshot
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionInspectionSource
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableCatalog
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableCatalogStatus
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.signal.CompiledFixedBlockSignals
import work.art1st.javarailways.simplesignals.signal.AttachedSignalDefinition
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionRouteSelectionIndex
import work.art1st.javarailways.simplesignals.signal.CompiledProtectionSignals
import work.art1st.javarailways.simplesignals.signal.CompiledProtectionRoute
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionSource
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionStore
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.presentation.LoadedPackageModel
import work.art1st.javarailways.simplesignals.presentation.ModelTypeCatalogSnapshot
import work.art1st.javarailways.simplesignals.presentation.PreparedSignalModels
import work.art1st.javarailways.simplesignals.presentation.SignalModelBinding
import work.art1st.javarailways.simplesignals.presentation.SignalModelHandles
import work.art1st.javarailways.simplesignals.presentation.SimpleSignalsModelPackages
import work.art1st.javarailways.simplesignals.signalpack.SignalObservationGraphBuilder
import work.art1st.javarailways.simplesignals.signalpack.SignalObservationGraphPublication
import work.art1st.javarailways.simplesignals.signalpack.SignalLogicRuntime
import work.art1st.javarailways.simplesignals.signalpack.SignalPackCatalog
import work.art1st.javarailways.simplesignals.signalpack.SignalTypeBinding
import work.art1st.javarailways.simplesignals.signalpack.SignalPackEpochRuntime
import work.art1st.javarailways.simplesignals.signalpack.SignalPackLoader
import work.art1st.javarailways.simplesignals.signalpack.SignalTypeCatalogSnapshot
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

internal const val PROTECTION_ROUTE_SOURCE_ID = "simplesignals:fixed-protection-routes"

internal enum class SimpleSignalsDefinitionPublicationStatus {
    PUBLISHED,
    UNCHANGED,
    REJECTED
}

internal data class SimpleSignalsDefinitionPublicationResult(
    val status: SimpleSignalsDefinitionPublicationStatus,
    val diagnostics: List<String> = emptyList()
)

@ConsistentCopyVisibility
internal data class SimpleSignalsDefinitionPatch private constructor(
    val upsertRegions: List<ProtectionRegionDefinition>,
    val removeRegionIds: List<String>,
    val upsertRouteTables: List<ProtectionRouteTableDefinition>,
    val removeRouteTableIds: List<String>,
    val upsertFixedBlocks: List<FixedBlockDefinition>,
    val removeFixedBlockIds: List<String>,
    val upsertFixedBlockSignals: List<FixedBlockSignalDefinition>,
    val removeFixedBlockSignalIds: List<String>,
    val upsertProtectionSignals: List<ProtectionSignalDefinition>,
    val removeProtectionSignalIds: List<String>,
    val upsertRouteIndicators: List<RouteIndicatorDefinition>,
    val removeRouteIndicatorIds: List<String>,
    val upsertAttachedSignals: List<AttachedSignalDefinition>,
    val removeAttachedSignalIds: List<String>,
    val pendingProtectionBoundaries: List<work.art1st.javarailways.simplesignals.region.ProtectionBoundaryCut>?,
    val boundaryNames: List<work.art1st.javarailways.simplesignals.persistence.BoundaryName>?
) {
    constructor(
        upsertRegions: Collection<ProtectionRegionDefinition> = emptyList(),
        removeRegionIds: Collection<String> = emptyList(),
        upsertRouteTables: Collection<ProtectionRouteTableDefinition> = emptyList(),
        removeRouteTableIds: Collection<String> = emptyList(),
        upsertFixedBlocks: Collection<FixedBlockDefinition> = emptyList(),
        removeFixedBlockIds: Collection<String> = emptyList(),
        upsertFixedBlockSignals: Collection<FixedBlockSignalDefinition> = emptyList(),
        removeFixedBlockSignalIds: Collection<String> = emptyList(),
        upsertProtectionSignals: Collection<ProtectionSignalDefinition> = emptyList(),
        removeProtectionSignalIds: Collection<String> = emptyList(),
        upsertRouteIndicators: Collection<RouteIndicatorDefinition> = emptyList(),
        removeRouteIndicatorIds: Collection<String> = emptyList(),
        upsertAttachedSignals: Collection<AttachedSignalDefinition> = emptyList(),
        removeAttachedSignalIds: Collection<String> = emptyList(),
        pendingProtectionBoundaries: Collection<work.art1st.javarailways.simplesignals.region.ProtectionBoundaryCut>? = null,
        boundaryNames: Collection<work.art1st.javarailways.simplesignals.persistence.BoundaryName>? = null
    ) : this(
        java.util.List.copyOf(upsertRegions),
        java.util.List.copyOf(removeRegionIds),
        java.util.List.copyOf(upsertRouteTables),
        java.util.List.copyOf(removeRouteTableIds),
        java.util.List.copyOf(upsertFixedBlocks),
        java.util.List.copyOf(removeFixedBlockIds),
        java.util.List.copyOf(upsertFixedBlockSignals),
        java.util.List.copyOf(removeFixedBlockSignalIds),
        java.util.List.copyOf(upsertProtectionSignals), java.util.List.copyOf(removeProtectionSignalIds),
        java.util.List.copyOf(upsertRouteIndicators), java.util.List.copyOf(removeRouteIndicatorIds),
        java.util.List.copyOf(upsertAttachedSignals), java.util.List.copyOf(removeAttachedSignalIds),
        pendingProtectionBoundaries?.let { java.util.List.copyOf(it) },
        boundaryNames?.let { java.util.List.copyOf(it) }
    )
}

internal enum class SimpleSignalsDefinitionAuthoringStatus {
    APPLIED,
    VALIDATED,
    REVISION_REJECTED,
    PATCH_REJECTED,
    VALIDATION_REJECTED,
    EDIT_BLOCKED,
    RETRY_REQUIRED,
    STALE_TOPOLOGY,
    PERSISTENCE_FAILED,
    WORLD_UNAVAILABLE
}

internal data class SimpleSignalsDefinitionAuthoringResult(
    val status: SimpleSignalsDefinitionAuthoringStatus,
    val definitions: SimpleSignalsWorldDefinitions? = null,
    val editDecision: TopologyEditDecision? = null,
    val diagnostics: List<String> = emptyList(),
    val affectedRailIds: Set<UUID> = emptySet()
)

internal sealed interface SimpleSignalsDefinitionPreparationResult {
    data class Prepared(val value: SimpleSignalsPreparedDefinitions) :
        SimpleSignalsDefinitionPreparationResult

    data object Unchanged : SimpleSignalsDefinitionPreparationResult

    data class Rejected(val diagnostics: List<String>) :
        SimpleSignalsDefinitionPreparationResult
}

internal enum class SimpleSignalsDefinitionCommitStatus {
    COMMITTED,
    STALE_BASE
}

/** A fully compiled candidate tied to one live publication and exact immutable topology root. */
internal class SimpleSignalsPreparedDefinitions internal constructor(
    internal val runtime: SimpleSignalsWorldRuntime,
    internal val basePublication: SimpleSignalsRuntimePublication?,
    internal val candidatePublication: SimpleSignalsRuntimePublication,
    val topology: TopologyState,
    val definitions: SimpleSignalsWorldDefinitions,
    val editImpact: TopologyEditImpact?
) {
    val affectedRailIds: Set<UUID> get() = editImpact?.railIds.orEmpty()
}

internal class SimpleSignalsRuntimePublication(
    val topology: TopologyState,
    val definitions: SimpleSignalsWorldDefinitions,
    val regions: ProtectionRegionCatalog,
    val routeTables: ProtectionRouteTableCatalog,
    val routeSelection: ProtectionRouteSelectionIndex,
    val fixedBlocks: FixedBlockCatalog,
    val fixedBlockSignals: CompiledFixedBlockSignals,
    val protectionSignals: CompiledProtectionSignals,
    val signalObservationGraph: SignalObservationGraphPublication,
    previous: SimpleSignalsRuntimePublication? = null
) {
    internal var topologyIndex: SectionTopologyIndex? = null
    internal var topologyGuard: SectionTopologyGuard? = null
    val unavailableTerritory = work.art1st.javarailways.simplesignals.region.UnavailableProtectionTerritory(
        checkNotNull(regions.compilationSnapshot()), routeTables.unavailableRegionIds())
    val routeBindingCompiler = work.art1st.javarailways.simplesignals.signal.ProtectionRouteBindingCompiler(this)
    // Canonical immutable route references are reused only across exact unchanged geometry.
    // Compilation never reads an epoch's mutable request or clearance state.
    private val regionsById = definitions.regions.associateBy { it.regionId }
    val clearanceRoutesById: Map<String, CompiledProtectionRoute> =
        routeSelection.routesById.mapValues { (id, route) ->
            if (previous != null && (route === previous.routeSelection.routesById[id] ||
                    topology === previous.topology && route == previous.routeSelection.routesById[id]) &&
                regionsById[route.regionId] == previous.regionsById[route.regionId]
            ) previous.clearanceRoutesById.getValue(id) else route
        }
}

/** Only the selected region's authoring/availability input for a read-only player preview. */
internal data class PreviewRegionState(val definition: ProtectionRegionDefinition?, val unavailable: Boolean)

/**
 * One plugin-owned runtime for one exact host world load.
 *
 * Definitions are compiled into fresh candidate catalogs and a concrete fixed-block rule runtime,
 * then become visible through one volatile publication only after every candidate accepts them.
 * Hot-path readers never rebuild definition state inside their callbacks.
 */
internal class SimpleSignalsWorldRuntime(
    val worldId: UUID,
    private val protectionRegionMaxDistanceMeters: Double = 1000.0
) {
    private data class RuntimeState(
        val publication: SimpleSignalsRuntimePublication?,
        val sectionOccupancy: SectionOccupancyRuntime?,
        val models: SignalModelBinding? = null
    )

    private val lock = Any()
    /** Low-frequency work only; the physics callback never acquires this monitor. */
    internal val managementLock = Any()
    internal var installed = false
    // A failed host detach retains this reservation and its engine until remove/clear retries it.
    @Volatile internal var sourceRetirementPending = false
    private var signalLogic: SignalLogicRuntime? = null

    internal fun signalLogic(catalog: work.art1st.javarailways.simplesignals.signalpack.SignalRuleCatalog): SignalLogicRuntime =
        synchronized(managementLock) {
            check(!closed) { "SimpleSignals world runtime is closed: $worldId" }
            signalLogic ?: SignalLogicRuntime(catalog).also { signalLogic = it }
        }

    internal fun markClosed() { closed = true }

    internal fun closeLogic() {
        val retired = synchronized(managementLock) { signalLogic.also { signalLogic = null } }
        retired?.close()
    }

    internal val topologyGuard = SectionTopologyGuard()
    internal var topologySubscription: AutoCloseable? = null
    internal val topologyWorkerScheduled = java.util.concurrent.atomic.AtomicBoolean()
    private val sectionEpochContinuity = SectionOccupancyRuntime.EpochContinuity()
    private val modelLifecycleLock = Any()
    internal val modelHandles = SignalModelHandles()

    @Volatile var closed = false
        private set

    @Volatile
    private var state = RuntimeState(null, null)
    // One disposable exact-root derivation shared by all preparation catalogs, including pending clicks.
    private var topologyIndex: work.art1st.javarailways.simplesignals.region.ProtectionRegionTopology? = null
    private class PreviewRegionObserver(val regionId: String, val listener: (PreviewRegionState) -> Unit,
        initial: PreviewRegionState) {
        val previous = java.util.concurrent.atomic.AtomicReference(initial)
    }
    private val previewRegionObservers = java.util.concurrent.CopyOnWriteArrayList<PreviewRegionObserver>()

    internal fun previewRegionState(regionId: String): PreviewRegionState {
        if (closed) return PreviewRegionState(null, true)
        val definition = state.publication?.regions?.compilationSnapshot()?.regionsById?.get(regionId)?.definition
        return PreviewRegionState(definition, definition == null ||
            topologyGuard.sectionUnavailable(TopologySectionKind.REGION, regionId) || definition.boundaryDraft != null)
    }

    /** Listeners only mark pending worker work; no platform calls or graph work during publication. */
    internal fun observePreviewRegion(regionId: String, listener: (PreviewRegionState) -> Unit): AutoCloseable {
        val observer = PreviewRegionObserver(regionId, listener, previewRegionState(regionId))
        previewRegionObservers.add(observer)
        notifyPreviewRegions()
        return AutoCloseable { previewRegionObservers.remove(observer) }
    }

    internal fun notifyPreviewRegions() {
        previewRegionObservers.forEach { observer ->
            val next = previewRegionState(observer.regionId)
            val previous = observer.previous.get()
            if (next != previous && observer.previous.compareAndSet(previous, next)) {
                // A broken UI observer cannot reject a safety/definition publication.
                try { observer.listener(next) } catch (_: Exception) { previewRegionObservers.remove(observer) }
            }
        }
    }

    fun retire() {
        closed = true
        notifyPreviewRegions()
        previewRegionObservers.clear()
        val subscription = topologySubscription
        topologySubscription = null
        try { subscription?.close() } finally { modelHandles.retire() }
    }

    fun currentModels(): SignalModelBinding? = state.models

    fun attachModels(publication: SimpleSignalsRuntimePublication, models: SignalModelBinding): Boolean = synchronized(lock) {
        val captured = state
        if (closed || captured.publication !== publication || captured.models != null) return@synchronized false
        check(models.handles === modelHandles)
        checkNotNull(captured.sectionOccupancy).attachModelBinding(models)
        models.publish { state = RuntimeState(publication, captured.sectionOccupancy, models) }
        true
    }

    /** Replaces presentation for one unchanged definition publication without touching safety output. */
    fun replaceModels(publication: SimpleSignalsRuntimePublication, expected: SignalModelBinding?,
        replacement: SignalModelBinding): Boolean = synchronized(lock) {
        val captured = state
        if (closed || captured.publication !== publication || captured.models !== expected) return@synchronized false
        check(replacement.handles === modelHandles)
        checkNotNull(captured.sectionOccupancy).attachModelBinding(replacement)
        replacement.publish { state = RuntimeState(publication, captured.sectionOccupancy, replacement) }
        true
    }

    /** This monitor is used only by lifecycle workers, never by a safety/publication callback. */
    fun startModels() = synchronized(modelLifecycleLock) {
        while (!closed) {
            val models = state.models ?: return@synchronized
            models.start({ !closed && state.models === models },
                { state.sectionOccupancy?.presentationSnapshot() })
            if (state.models === models) return@synchronized
        }
    }

    fun closeModels() = synchronized(modelLifecycleLock) {
        val binding = state.models
        modelHandles.close { binding?.cleanupFailure(it) }
    }

    val routePlanningBinding = RoutePlanningRestrictionBinding(
        PROTECTION_ROUTE_SOURCE_ID,
        RoutePlanningRestrictionSource { topology ->
            val captured = state.publication
                ?: error("SimpleSignals definitions have not been published for world $worldId")
            check(topology === topologyGuard.currentTopology()) { "route query did not capture the current topology" }
            topologyGuard.restrictions(captured.routeTables.compiledRestrictions())
        }
    )

    val fixedBlockInspectionSource = FixedBlockInspectionSource(
        ::fixedBlockSnapshot,
        ::fixedBlockSignal,
        ::hasConfiguredFixedBlockSignal,
        ::fixedBlockInspectionLeadTime,
        ::protectionDeviceDeclarations,
        ::protectionDeviceSignal,
        { id -> state.publication?.protectionSignals?.signalIdsByDeclaration?.containsKey(id) == true }
    ).source

    val protectionRegionInspectionSource = ProtectionRegionInspectionSource(
        ::authoringPublication
    ).source

    fun currentDefinitions(): SimpleSignalsWorldDefinitions =
        checkNotNull(state.publication) { "SimpleSignals definitions have not been published for world $worldId" }
            .definitions

    fun currentTopology(): TopologyState =
        checkNotNull(state.publication) { "SimpleSignals definitions have not been published for world $worldId" }
            .topology

    fun authoringPublication(): SimpleSignalsRuntimePublication = checkNotNull(state.publication)

    fun fixedBlockSnapshot(topology: TopologyState): FixedBlockReadSnapshot =
        checkNotNull(state.publication) {
            "SimpleSignals definitions have not been published for world $worldId"
        }.fixedBlocks.snapshot(topology)

    /** Captures the fixed-block index and its exact topology root from one publication. */
    fun fixedBlockSnapshot(): FixedBlockReadSnapshot {
        val captured = checkNotNull(state.publication) {
            "SimpleSignals definitions have not been published for world $worldId"
        }
        return captured.fixedBlocks.snapshot(captured.topology)
    }

    /** Captures the immutable region index used to compile one exact frozen-route binding. */
    fun protectionRegionSnapshot(): ProtectionRegionCompilationSnapshot {
        val captured = checkNotNull(state.publication) {
            "SimpleSignals definitions have not been published for world $worldId"
        }
        return checkNotNull(captured.regions.compilationSnapshot()) {
            "SimpleSignals protection regions have not been published for world $worldId"
        }
    }

    /** Installs immutable fixed-block monitoring geometry exactly once for this world load. */
    fun initializeSectionOccupancy(signalTypeRuntime: SignalPackEpochRuntime? = null): SectionOccupancyRuntime = synchronized(lock) {
        val current = state
        check(current.sectionOccupancy == null) { "section occupancy is already initialized for world $worldId" }
        val publication = checkNotNull(current.publication) {
            "SimpleSignals definitions have not been published for world $worldId"
        }
        val occupancy = signalTypeRuntime?.let { SectionOccupancyRuntime.from(publication, it) }
            ?: SectionOccupancyRuntime.from(publication)
        occupancy.also {
            it.attachEpochContinuity(sectionEpochContinuity)
            state = RuntimeState(current.publication, it)
        }
    }

    fun sectionOccupancySnapshot(): SectionOccupancySnapshot? = state.sectionOccupancy?.snapshot()
    internal fun currentSectionOccupancy(): SectionOccupancyRuntime? = state.sectionOccupancy

    fun sectionSignalSnapshot(): SectionSignalSnapshot? = state.sectionOccupancy?.signalSnapshot()

    /** One exact definition/runtime pair; attached models share their primary's logical result. */
    fun signalDeviceDiagnostics(deviceId: String): SignalDeviceDiagnostics? {
        val captured = state
        val definitions = captured.publication?.definitions ?: return null
        val primaryId = definitions.attachedSignals.firstOrNull { it.signalId == deviceId }?.primarySignalId ?: deviceId
        return captured.sectionOccupancy?.deviceDiagnostics(primaryId)
    }

    fun protectionRouteSelectionSnapshot() = state.sectionOccupancy?.selectionSnapshot()

    fun routeIndicatorStates() = state.sectionOccupancy?.indicators().orEmpty()

    private fun protectionDeviceSignal(id: String): PublishedSectionSignal? {
        val captured = state
        val signalId = captured.publication?.protectionSignals?.signalIdsByDeclaration?.get(id) ?: return null
        return captured.sectionOccupancy?.signalById(signalId)
    }

    private fun protectionDeviceDeclarations(blockSnapshot: FixedBlockReadSnapshot,
        route: work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot):
        List<work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration> {
        val captured = state
        val publication = checkNotNull(captured.publication)
        check(publication.fixedBlocks.snapshot(publication.topology) === blockSnapshot) {
            "signal publication changed while compiling device declarations"
        }
        val devices = publication.protectionSignals
        if (devices.definitionsById.isEmpty()) return emptyList()
        val selector = checkNotNull(captured.sectionOccupancy).protectionRouteSelection
        val windows = selector.deviceRouteWindows(route)
        return windows.flatMap { window -> devices.signalIdsByRoute[window.routeId].orEmpty().map { id ->
            val definition = devices.definitionsById.getValue(id)
            val entry = devices.routes.routesById.getValue(window.routeId).definition.traversals.first()
            work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration(
                work.art1st.javarailways.simplesignals.signal.protectionSignalDeclarationId(id), entry.railId, entry.facing,
                window.entryMeters, definition.inspectionLeadTimeSeconds, window.floorMeters)
        } }
    }

    private fun fixedBlockSignal(entry: work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry): PublishedSectionSignal? =
        checkNotNull(state.sectionOccupancy) {
            "section occupancy is not initialized for world $worldId"
        }.fixedBlockSignal(entry)

    private fun hasConfiguredFixedBlockSignal(entry: work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry): Boolean =
        checkNotNull(state.publication) {
            "SimpleSignals definitions have not been published for world $worldId"
        }.fixedBlockSignals.hasSignalForEntry(entry)

    private fun fixedBlockInspectionLeadTime(
        blockSnapshot: FixedBlockReadSnapshot,
        entry: work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry
    ): Double {
        val captured = checkNotNull(state.publication) {
            "SimpleSignals definitions have not been published for world $worldId"
        }
        check(captured.fixedBlocks.snapshot(captured.topology) === blockSnapshot) {
            "fixed-block publication changed while compiling an inspection binding"
        }
        return captured.fixedBlockSignals.inspectionLeadTimeSeconds(entry)
    }

    fun prepareDefinitions(
        topology: TopologyState,
        definitions: SimpleSignalsWorldDefinitions
    ): SimpleSignalsDefinitionPreparationResult = synchronized(lock) {
        require(definitions.worldId == worldId) {
            "SimpleSignals definitions belong to ${definitions.worldId}, expected $worldId"
        }
        val copiedRegions = java.util.List.copyOf(definitions.regions.sortedBy { it.regionId })
        val copiedTables = java.util.List.copyOf(definitions.routeTables.sortedBy { it.tableId })
        val copiedBlocks = java.util.List.copyOf(definitions.fixedBlocks.sortedBy { it.blockId })
        val copiedSignals = java.util.List.copyOf(
            definitions.fixedBlockSignals.sortedBy { it.signalId }
        )
        val copiedDefinitions = SimpleSignalsWorldDefinitions(
            worldId,
            definitions.definitionRevision,
            copiedRegions,
            copiedTables,
            copiedBlocks,
            copiedSignals,
            definitions.protectionSignals.sortedBy { it.signalId },
            definitions.routeIndicators.sortedBy { it.indicatorId },
            definitions.attachedSignals.sortedBy { it.signalId },
            definitions.pendingProtectionBoundaries.sortedBy { it.boundaryId },
            definitions.boundaryNames
        )
        val current = state.publication
        if (current != null && definitions.definitionRevision < current.definitions.definitionRevision) {
            return@synchronized preparationRejected(
                "definition revision ${definitions.definitionRevision} precedes published revision " +
                    current.definitions.definitionRevision
            )
        }
        if (current != null && definitions.definitionRevision == current.definitions.definitionRevision &&
            copiedDefinitions != current.definitions
        ) {
            return@synchronized preparationRejected(
                "definition content changed without advancing revision ${definitions.definitionRevision}"
            )
        }
        validateRetainedRegionRevisions(current?.definitions?.regions.orEmpty(), copiedRegions)?.let {
            return@synchronized preparationRejected(it)
        }
        validateRetainedTableRevisions(current?.definitions?.routeTables.orEmpty(), copiedTables)?.let {
            return@synchronized preparationRejected(it)
        }
        validateRetainedBlockRevisions(current?.definitions?.fixedBlocks.orEmpty(), copiedBlocks)?.let {
            return@synchronized preparationRejected(it)
        }
        validateRetainedSignalRevisions(
            current?.definitions?.fixedBlockSignals.orEmpty(),
            copiedSignals
        )?.let {
            return@synchronized preparationRejected(it)
        }
        retainedDeviceRevisionFailure(current?.definitions?.protectionSignals.orEmpty(), copiedDefinitions.protectionSignals,
            { it.signalId }, { it.revision })?.let { return@synchronized preparationRejected(it) }
        retainedDeviceRevisionFailure(current?.definitions?.routeIndicators.orEmpty(), copiedDefinitions.routeIndicators,
            { it.indicatorId }, { it.revision })?.let { return@synchronized preparationRejected(it) }
        retainedDeviceRevisionFailure(current?.definitions?.attachedSignals.orEmpty(), copiedDefinitions.attachedSignals,
            { it.signalId }, { it.revision })?.let { return@synchronized preparationRejected(it) }
        if (current != null && current.topology === topology &&
            copiedDefinitions == current.definitions
        ) {
            return@synchronized SimpleSignalsDefinitionPreparationResult.Unchanged
        }

        var candidateRegions = ProtectionRegionCatalog()
        val retainedRegions = current?.regions?.compilationSnapshot()?.let { old ->
            old.regionsById.filter { (id, region) -> id in old.unavailableRegionIds && region.definition in copiedRegions }
        }.orEmpty()
        val oldBlocks = current?.fixedBlocks?.snapshot(current.topology)
        val retainedBlocks = oldBlocks?.blocksById?.filter { (id, block) ->
            id in oldBlocks.unavailableBlockIds && block.definition in copiedBlocks
        }.orEmpty()
        val index = topologyIndex?.takeIf { it.topologyState === topology }
            ?: if (copiedRegions.isEmpty() && copiedBlocks.isEmpty()) {
                work.art1st.javarailways.simplesignals.region.ProtectionRegionTopology.empty(topology)
            } else {
                work.art1st.javarailways.simplesignals.region.ProtectionRegionTopology.direct(topology)
                    .also { topologyIndex = it }
            }
        val placedBoundaries = copiedRegions.filter { it.regionId !in retainedRegions }.flatMap { it.effectiveCuts } + copiedDefinitions.pendingProtectionBoundaries
        if (placedBoundaries.map { it.boundaryId }.distinct().size != placedBoundaries.size) {
            return@synchronized preparationRejected("placed protection boundary IDs must be unique")
        }
        for (region in copiedRegions.filter { it.regionId !in retainedRegions && it.boundaryDraft == null }) {
            val search = try {
                work.art1st.javarailways.simplesignals.authoring.ProtectionBoundarySearch.search(topology,
                    region.boundaries.first(), region.boundaries, protectionRegionMaxDistanceMeters)
            } catch (failure: IllegalArgumentException) {
                return@synchronized preparationRejected(failure.message ?: "protection boundaries are invalid")
            }
            if (!search.complete || search.boundaries.map { it.boundaryId }.toSet() != region.boundaries.map { it.boundaryId }.toSet())
                return@synchronized preparationRejected("region ${region.regionId}: ${search.detail}")
        }
        val pendingBoundaryIds = copiedDefinitions.pendingProtectionBoundaries.map { it.boundaryId }.toSet()
        for (boundary in placedBoundaries) {
            val rail = topology.railProjection[boundary.position.railId]?.singleOrNull()
                ?: return@synchronized preparationRejected("boundary ${boundary.boundaryId} references missing rail")
            val length = maxOf(rail.railStartAbMeters, rail.railEndAbMeters)
            val arc = boundary.position.arcLengthInAbMeters
            if (boundary.boundaryId in pendingBoundaryIds) {
                if (arc > length) return@synchronized preparationRejected("pending boundary ${boundary.boundaryId} lies beyond its rail")
            } else if (arc <= Curve.TOLERANCE || arc >= length - Curve.TOLERANCE) {
                return@synchronized preparationRejected("boundary ${boundary.boundaryId} must lie strictly inside its rail")
            }
        }
        val regionResult = candidateRegions.publish(
            topology,
            definitions.definitionRevision,
            copiedRegions.filter { it.regionId !in retainedRegions },
            index
        )
        if (regionResult.status == ProtectionRegionCatalogStatus.REJECTED) {
            return@synchronized preparationRejected(regionResult.diagnostics.map { it.detail })
        }
        if (retainedRegions.isNotEmpty()) candidateRegions = ProtectionRegionCatalog.fromCompiled(topology,
            definitions.definitionRevision, checkNotNull(candidateRegions.compilationSnapshot()).regionsById.values + retainedRegions.values,
            retainedRegions.keys)
        val candidateTables = ProtectionRouteTableCatalog(candidateRegions)
        val changedRegions = checkNotNull(candidateRegions.compilationSnapshot()).regionsById.filter { (id, region) ->
            current?.regions?.compilationSnapshot()?.regionsById?.get(id) != region
        }.keys
        val tableResult = candidateTables.publish(definitions.definitionRevision, copiedTables,
            current?.routeTables, changedRegions)
        if (tableResult.status == ProtectionRouteTableCatalogStatus.REJECTED) {
            return@synchronized preparationRejected(tableResult.diagnostics.map { it.detail })
        }
        val candidateRouteSelection = try {
            ProtectionRouteSelectionIndex.compile(topology, candidateTables.usableDefinitions())
        } catch (failure: IllegalArgumentException) {
            return@synchronized preparationRejected(
                failure.message ?: "protection-route selection could not be compiled"
            )
        }

        var candidateBlocks = FixedBlockCatalog()
        val blockResult = candidateBlocks.publish(
            topology,
            definitions.definitionRevision,
            copiedBlocks.filter { it.blockId !in retainedBlocks },
            index
        )
        if (blockResult.status == FixedBlockCatalogStatus.REJECTED) {
            return@synchronized preparationRejected(blockResult.diagnostics.map { it.detail })
        }
        if (retainedBlocks.isNotEmpty()) candidateBlocks = FixedBlockCatalog.fromCompiled(topology,
            definitions.definitionRevision, copiedBlocks, candidateBlocks.snapshot(topology).blocksById + retainedBlocks,
            retainedBlocks.keys)
        val (candidateSignals, candidateProtectionSignals) = try {
            val primaryIds = copiedSignals.mapTo(hashSetOf()) { it.signalId }
            copiedDefinitions.protectionSignals.mapTo(primaryIds) { it.signalId }
            val deviceIds = primaryIds + copiedDefinitions.routeIndicators.map { it.indicatorId }
            val attachedIds = hashSetOf<String>()
            copiedDefinitions.attachedSignals.forEach { attached ->
                require(attachedIds.add(attached.signalId) && attached.signalId !in deviceIds) {
                    "attached signal ${attached.signalId} has a duplicate device ID"
                }
                require(attached.primarySignalId in primaryIds) {
                    "attached signal ${attached.signalId} binds unknown primary signal ${attached.primarySignalId}"
                }
                attached.model.validatePlacement(topology, attached.signalId)
            }
            CompiledProtectionSignals.compile(topology, definitions.definitionRevision,
                candidateRouteSelection, candidateBlocks.snapshot(topology), copiedSignals,
                copiedDefinitions.protectionSignals, copiedDefinitions.routeIndicators, copiedTables,
                authoredEntryBoundaryIds = copiedRegions.flatMap { it.boundaries }.mapTo(hashSetOf()) { it.boundaryId })
        } catch (failure: IllegalArgumentException) {
            return@synchronized preparationRejected(
                failure.message ?: "fixed-block signals could not be compiled"
            )
        }
        validateSectionSeparation(candidateRegions, candidateBlocks, topology)?.let { diagnostic ->
            return@synchronized preparationRejected(diagnostic)
        }

        val candidateObservationGraph = try {
            SignalObservationGraphBuilder.compile(
                topology,
                copiedDefinitions,
                candidateBlocks.snapshot(topology),
                checkNotNull(candidateRegions.compilationSnapshot()),
                candidateRouteSelection,
                candidateTables.unavailableRegionIds()
            )
        } catch (failure: IllegalArgumentException) {
            return@synchronized preparationRejected(
                failure.message ?: "signal observation graph could not be compiled"
            )
        }
        val candidate = SimpleSignalsRuntimePublication(
            topology,
            copiedDefinitions,
            candidateRegions,
            candidateTables,
            candidateRouteSelection,
            candidateBlocks,
            candidateSignals,
            candidateProtectionSignals,
            candidateObservationGraph,
            current
        )
        candidate.topologyIndex = SectionTopologyIndex(candidate, current?.topologyIndex)
        SimpleSignalsDefinitionPreparationResult.Prepared(
            SimpleSignalsPreparedDefinitions(
                this,
                current,
                candidate,
                topology,
                copiedDefinitions,
                SimpleSignalsDefinitionEditImpact.impact(current, candidate)
            )
        )
    }

    fun commitPrepared(
        prepared: SimpleSignalsPreparedDefinitions,
        replacementSectionOccupancy: SectionOccupancyRuntime? = null,
        replacementModels: SignalModelBinding? = null,
        beforeCommit: () -> Unit = {}
    ): SimpleSignalsDefinitionCommitStatus = synchronized(lock) {
        require(prepared.runtime === this) {
            "prepared SimpleSignals definitions belong to another world runtime"
        }
        val current = state
        if (closed || current.publication !== prepared.basePublication) {
            return@synchronized SimpleSignalsDefinitionCommitStatus.STALE_BASE
        }
        val candidateState = RuntimeState(
            prepared.candidatePublication,
            replacementSectionOccupancy ?: current.sectionOccupancy,
            if (replacementSectionOccupancy == null) current.models else replacementModels
        )
        check(replacementModels == null || replacementModels.handles === modelHandles)
        beforeCommit()
        bindTopologyGuard(prepared.candidatePublication)
        topologyGuard.install(prepared.topology, checkNotNull(prepared.candidatePublication.topologyIndex))
        replacementSectionOccupancy?.attachEpochContinuity(sectionEpochContinuity)
        if (replacementSectionOccupancy != null) {
            modelHandles.publish(replacementModels) { state = candidateState }
        } else state = candidateState
        notifyPreviewRegions()
        SimpleSignalsDefinitionCommitStatus.COMMITTED
    }

    private fun bindTopologyGuard(publication: SimpleSignalsRuntimePublication) {
        publication.fixedBlocks.snapshot(publication.topology).also {
            it.liveTopology = topologyGuard::currentTopology
            it.requireUsable = topologyGuard::requireUsable
        }
        checkNotNull(publication.regions.compilationSnapshot()).also {
            it.liveTopology = topologyGuard::currentTopology
            it.requireUsable = topologyGuard::requireUsable
        }
        publication.topologyGuard = topologyGuard
    }

    internal fun commitTopologyRevalidation(base: SimpleSignalsRuntimePublication,
        expected: SectionTopologyGuard.View, candidate: SimpleSignalsRuntimePublication,
        occupancy: SectionOccupancyRuntime): Boolean = synchronized(lock) {
        if (closed || state.publication !== base) return@synchronized false
        val committed = topologyGuard.commit(expected, checkNotNull(candidate.topologyIndex)) {
            bindTopologyGuard(candidate)
            occupancy.attachEpochContinuity(sectionEpochContinuity)
            // A binding prepared from the old exact root must not render new-root epochs while
            // the model worker recomputes rail-attached poses. replaceModels attaches the new one.
            state = RuntimeState(candidate, occupancy, state.models)
        }
        if (committed) notifyPreviewRegions()
        committed
    }

    /** Replaces only the epoch owner for one unchanged publication. */
    internal fun replaceSectionOccupancy(
        publication: SimpleSignalsRuntimePublication,
        expected: SectionOccupancyRuntime?,
        replacement: SectionOccupancyRuntime
    ): Boolean = synchronized(lock) {
        val current = state
        if (closed || current.publication !== publication || current.sectionOccupancy !== expected) {
            return@synchronized false
        }
        replacement.attachEpochContinuity(sectionEpochContinuity)
        current.models?.let(replacement::attachModelBinding)
        state = RuntimeState(publication, replacement, current.models)
        true
    }

    fun initializeDefinitions(
        topology: TopologyState,
        definitions: SimpleSignalsWorldDefinitions
    ): SimpleSignalsDefinitionPublicationResult {
        return when (val prepared = prepareDefinitions(topology, definitions)) {
            SimpleSignalsDefinitionPreparationResult.Unchanged ->
                SimpleSignalsDefinitionPublicationResult(SimpleSignalsDefinitionPublicationStatus.UNCHANGED)
            is SimpleSignalsDefinitionPreparationResult.Rejected -> rejected(prepared.diagnostics)
            is SimpleSignalsDefinitionPreparationResult.Prepared -> when (
                commitPrepared(prepared.value)
            ) {
                SimpleSignalsDefinitionCommitStatus.COMMITTED ->
                    SimpleSignalsDefinitionPublicationResult(SimpleSignalsDefinitionPublicationStatus.PUBLISHED)
                SimpleSignalsDefinitionCommitStatus.STALE_BASE -> rejected(
                    "SimpleSignals definitions changed before prepared publication commit"
                )
            }
        }
    }

    private fun <T> retainedDeviceRevisionFailure(previous: Collection<T>, candidate: Collection<T>,
        id: (T) -> String, revision: (T) -> Long): String? {
        val old = previous.associateBy(id)
        candidate.forEach { next -> old[id(next)]?.let { retained ->
            if (revision(next) < revision(retained) || revision(next) == revision(retained) && next != retained)
                return "device ${id(next)} changed without advancing its revision"
        } }
        return null
    }

    private fun validateRetainedRegionRevisions(
        previous: Collection<ProtectionRegionDefinition>,
        candidate: Collection<ProtectionRegionDefinition>
    ): String? {
        val previousById = previous.associateBy { it.regionId }
        for (definition in candidate) {
            val retained = previousById[definition.regionId] ?: continue
            if (definition.revision < retained.revision) {
                return "protection region ${definition.regionId} revision ${definition.revision} precedes " +
                    "published revision ${retained.revision}"
            }
            if (definition.revision == retained.revision && definition != retained) {
                return "protection region ${definition.regionId} changed without advancing revision " +
                    definition.revision
            }
        }
        return null
    }

    private fun validateRetainedTableRevisions(
        previous: Collection<ProtectionRouteTableDefinition>,
        candidate: Collection<ProtectionRouteTableDefinition>
    ): String? {
        val previousById = previous.associateBy { it.tableId }
        for (definition in candidate) {
            val retained = previousById[definition.tableId] ?: continue
            if (definition.revision < retained.revision) {
                return "route table ${definition.tableId} revision ${definition.revision} precedes " +
                    "published revision ${retained.revision}"
            }
            if (definition.revision == retained.revision && definition != retained) {
                return "route table ${definition.tableId} changed without advancing revision " +
                    definition.revision
            }
        }
        return null
    }

    private fun validateRetainedBlockRevisions(
        previous: Collection<FixedBlockDefinition>,
        candidate: Collection<FixedBlockDefinition>
    ): String? {
        val previousById = previous.associateBy { it.blockId }
        for (definition in candidate) {
            val retained = previousById[definition.blockId] ?: continue
            if (definition.revision < retained.revision) {
                return "fixed block ${definition.blockId} revision ${definition.revision} precedes " +
                    "published revision ${retained.revision}"
            }
            if (definition.revision == retained.revision && definition != retained) {
                return "fixed block ${definition.blockId} changed without advancing revision " +
                    definition.revision
            }
        }
        return null
    }

    private fun validateRetainedSignalRevisions(
        previous: Collection<FixedBlockSignalDefinition>,
        candidate: Collection<FixedBlockSignalDefinition>
    ): String? {
        val previousById = previous.associateBy { it.signalId }
        for (definition in candidate) {
            val retained = previousById[definition.signalId] ?: continue
            if (definition.revision < retained.revision) {
                return "fixed-block signal ${definition.signalId} revision ${definition.revision} precedes " +
                    "published revision ${retained.revision}"
            }
            if (definition.revision == retained.revision && definition != retained) {
                return "fixed-block signal ${definition.signalId} changed without advancing revision " +
                    definition.revision
            }
        }
        return null
    }

    /** Fixed blocks and protection interiors are distinct section types and may only touch at cuts. */
    private fun validateSectionSeparation(
        regions: ProtectionRegionCatalog,
        fixedBlocks: FixedBlockCatalog,
        topology: TopologyState
    ): String? {
        val regionSnapshot = regions.compilationSnapshot() ?: return null
        val regionSlicesByRail = regionSnapshot.regionsById.values.filter { it.definition.regionId !in regionSnapshot.unavailableRegionIds }
            .flatMap { region -> region.interior.map { slice -> region.definition.regionId to slice } }
            .groupBy { (_, slice) -> slice.railId }
            .mapValues { (_, slices) -> slices.sortedBy { (_, slice) -> slice.startAbMeters } }
        fixedBlocks.snapshot(topology).blocksById.values.forEach { block ->
            if (block.definition.blockId in fixedBlocks.snapshot(topology).unavailableBlockIds) return@forEach
            block.occupancyArea.intervals.forEach { interval ->
                val slices = regionSlicesByRail[interval.railId].orEmpty()
                var low = 0
                var high = slices.size
                while (low < high) {
                    val middle = (low + high) ushr 1
                    if (slices[middle].second.endAbMeters <=
                        interval.minArcLengthInAbMeters + Curve.TOLERANCE
                    ) {
                        low = middle + 1
                    } else {
                        high = middle
                    }
                }
                for (index in low until slices.size) {
                    val (regionId, slice) = slices[index]
                    if (slice.startAbMeters >=
                        interval.maxArcLengthInAbMeters - Curve.TOLERANCE
                    ) {
                        break
                    }
                    val overlapStart = max(interval.minArcLengthInAbMeters, slice.startAbMeters)
                    val overlapEnd = min(interval.maxArcLengthInAbMeters, slice.endAbMeters)
                    if (overlapEnd - overlapStart > Curve.TOLERANCE) {
                        return "fixed block ${block.definition.blockId} overlaps protection region " +
                            "$regionId on rail ${interval.railId}"
                    }
                }
            }
        }
        return null
    }

    private fun rejected(detail: String) = rejected(listOf(detail))

    private fun rejected(diagnostics: List<String>) = SimpleSignalsDefinitionPublicationResult(
        SimpleSignalsDefinitionPublicationStatus.REJECTED,
        java.util.List.copyOf(diagnostics)
    )

    private fun preparationRejected(detail: String) = preparationRejected(listOf(detail))

    private fun preparationRejected(diagnostics: List<String>) =
        SimpleSignalsDefinitionPreparationResult.Rejected(
            java.util.List.copyOf(diagnostics)
        )
}

internal enum class SimpleSignalsWorldInstallStatus {
    INSTALLED,
    ALREADY_INSTALLED,
    UNAVAILABLE
}

internal enum class SimpleSignalsWorldInstallDefinitionSource {
    MISSING,
    PRIMARY,
    BACKUP
}

internal data class SimpleSignalsWorldInstallResult(
    val status: SimpleSignalsWorldInstallStatus,
    val detail: String = "",
    val definitionSource: SimpleSignalsWorldInstallDefinitionSource? = null,
    val diagnostics: List<String> = emptyList()
)

/** Owns the local runtime map and the single host route-planning binding for each world load. */
internal class SimpleSignalsWorldRuntimeManager(
    private val plugin: Plugin,
    private val infrastructure: InfrastructureResourceService,
    private val routePlanningSources: RoutePlanningRestrictionSourceService,
    private val definitions: SimpleSignalsWorldDefinitionStore,
    private val signalEpochSources: SignalEpochSourceService,
    private val inspectionSources: InspectionSourceService,
    private val railTargets: RailTargetService,
    private val modelPresentation: ModelPresentationService,
    private val modelWorker: (() -> Unit) -> Unit = { action -> CompletableFuture.runAsync(action) },
    private val modelFailure: (String, Throwable) -> Unit = { _, _ -> },
    private val protectionRegionMaxDistanceMeters: Double = 1000.0,
    private val topologyWorker: (() -> Unit) -> Unit = { action -> CompletableFuture.runAsync(action) },
    private val worldIssues: WorldIssueService = WorldIssueService()
) {
    private data class PreparedSignalPacks(val catalog: SignalPackCatalog)

    private enum class SignalPackFallback {
        NONE,
        MISSING_CATALOG,
        ANY_FAILURE
    }

    private val lock = Any()
    private val issueLock = Any()
    private val runtimes = linkedMapOf<UUID, SimpleSignalsWorldRuntime>()

    @Volatile
    private var runtimeReadView: Map<UUID, SimpleSignalsWorldRuntime> = emptyMap()

    @Volatile private var closed = false
    @Volatile private var modelPackages: Map<String, LoadedPackageModel>? = null
    @Volatile private var modelPackageFailure: Throwable? = null
    @Volatile private var signalPacks: PreparedSignalPacks? = null
    @Volatile private var signalPackFailure: Throwable? = null

    /** Called once on the enable-time worker. Package files are never read from an epoch callback. */
    fun loadSignalPacks(directory: Path) {
        ThreadGuard.ensureOffMainThread("SimpleSignals signal catalog preparation")
        if (closed) return
        val prepared = try {
            java.nio.file.Files.createDirectories(directory)
            val catalog = SignalPackLoader.load(directory)
            PreparedSignalPacks(catalog)
        } catch (failure: Exception) {
            signalPackFailure = failure
            reportModelFailure("SimpleSignals signal rules unavailable: packages could not be loaded from $directory", failure)
            return
        }
        val worlds = synchronized(lock) {
            if (closed) return
            check(signalPacks == null) { "SimpleSignals signal catalog is already prepared" }
            signalPacks = prepared
            runtimes.values.toList()
        }
        worlds.forEach { runtime -> modelWorker { if (attachSignalPacks(runtime)) attachModels(runtime) } }
    }

    private fun signalTypeBindings(publication: SimpleSignalsRuntimePublication): List<SignalTypeBinding> =
        (publication.definitions.fixedBlockSignals.map { definition ->
            SignalTypeBinding(definition.signalId, definition.signalType)
        } + publication.definitions.protectionSignals.map { definition ->
            SignalTypeBinding(definition.signalId, definition.signalType)
        }).sortedBy(SignalTypeBinding::deviceId)

    private fun prepareSectionOccupancy(
        runtime: SimpleSignalsWorldRuntime,
        publication: SimpleSignalsRuntimePublication,
        fallback: SignalPackFallback,
        packages: PreparedSignalPacks? = signalPacks
    ): SectionOccupancyRuntime {
        val bindings = signalTypeBindings(publication)
        if (bindings.isEmpty()) return SectionOccupancyRuntime.from(publication)
        if (packages != null) {
            return try {
                SectionOccupancyRuntime.from(publication,
                    SignalPackEpochRuntime.compile(publication, packages.catalog.rules, runtime.signalLogic(packages.catalog.rules), bindings,
                        reportFailure = ::reportModelFailure) { topology, rails ->
                        infrastructure.railSpeedLimits(publication.definitions.worldId, topology, rails)
                    }.also { bindIssues(it, publication) })
            } catch (failure: Exception) {
                if (fallback != SignalPackFallback.ANY_FAILURE) throw failure
                reportModelFailure("signal-rule-preparation-failed world=${publication.definitions.worldId} " +
                    "definition=${publication.definitions.definitionRevision} topology=${publication.topology.revision}", failure)
                SectionOccupancyRuntime.from(publication, SignalPackEpochRuntime.unavailable(
                    publication, bindings, "signal type binding is unavailable: ${describe(failure)}").also { bindIssues(it, publication) })
            }
        }
        if (fallback == SignalPackFallback.NONE) {
            throw IllegalArgumentException("signal catalog is unavailable: " +
                (signalPackFailure?.let { it.message ?: it.javaClass.simpleName }
                    ?: "startup preparation has not completed"))
        }
        return SectionOccupancyRuntime.from(publication, SignalPackEpochRuntime.unavailable(
            publication, bindings, "signal catalog is unavailable: " +
                (signalPackFailure?.let { it.message ?: it.javaClass.simpleName }
                    ?: "startup preparation has not completed")).also { bindIssues(it, publication) })
    }

    private fun bindIssues(epoch: SignalPackEpochRuntime, publication: SimpleSignalsRuntimePublication) {
        epoch.issueChanged = { id, message -> synchronized(issueLock) {
            val runtime = runtimeReadView[publication.definitions.worldId]
            if (!closed && runtime != null && !runtime.closed &&
                runtime.authoringPublication() === publication && runtime.currentSectionOccupancy()?.ownsRules(epoch) == true) {
                if (message == null) worldIssues.resolve(runtime.worldId, "simplesignals:rules", WorldIssueKind.SIGNAL, id, "rule-unavailable")
                else worldIssues.report(runtime.worldId, "simplesignals:rules",
                    WorldIssueInput(WorldIssueKind.SIGNAL, id, "rule-unavailable", message))
            }
        } }
    }

    private fun publishIssues(runtime: SimpleSignalsWorldRuntime) {
        synchronized(issueLock) {
            if (closed || runtime.closed || runtimeReadView[runtime.worldId] !== runtime) return
            val view = runtime.topologyGuard.capture()
            val definitions = runtime.currentDefinitions()
            val unboundBlocks = definitions.fixedBlockSignals.filter { it.controlledBlockId == null }.mapTo(hashSetOf()) { it.signalId }
            val unboundProtection = definitions.protectionSignals.filter { it.bindingId == null }.mapTo(hashSetOf()) { it.signalId }
            worldIssues.replace(runtime.worldId, "simplesignals:topology", view.index.unavailable.map { section ->
                val kind = when (section.kind) {
                    TopologySectionKind.BLOCK -> WorldIssueKind.BLOCK
                    TopologySectionKind.REGION -> WorldIssueKind.PROTECTION_REGION
                    TopologySectionKind.SIGNAL -> WorldIssueKind.SIGNAL
                }
                val message = when (section.kind) {
                    TopologySectionKind.BLOCK -> "区间路径不可用，请检查引用轨道、连接及范围冲突。"
                    TopologySectionKind.REGION -> "保护区域或进路不可用，请检查边界及关联轨道。"
                    TopologySectionKind.SIGNAL -> when {
                        section.id in unboundBlocks ->
                            "信号机尚未绑定区间。"
                        section.id in unboundProtection ->
                            "信号机尚未绑定进路或入口。"
                        else -> "信号机绑定的区间、进路或入口不可用。"
                    }
                }
                WorldIssueInput(kind, section.id, "definition-unavailable", message,
                    "definitionRevision=${definitions.definitionRevision}; topologyRevision=${view.topology.revision}")
            })
            val ids = runtime.currentDefinitions().let { definitions ->
                (definitions.fixedBlockSignals.map { it.signalId } + definitions.protectionSignals.map { it.signalId }).toSet()
            }
            // Removed devices cannot be evaluated again to clear their old diagnostics.
            worldIssues.snapshot(runtime.worldId).filter { it.source in setOf("simplesignals:rules", "simplesignals:preparation") && it.objectId !in ids }
                .forEach { worldIssues.resolve(runtime.worldId, it.source, it.kind, it.objectId, it.code) }
            val displayIds = definitions.fixedBlockSignals.filter { it.placement != null }.map { it.signalId } +
                definitions.protectionSignals.filter { it.placement != null }.map { it.signalId } +
                definitions.routeIndicators.filter { it.model != null }.map { it.indicatorId } +
                definitions.attachedSignals.map { it.signalId }
            worldIssues.snapshot(runtime.worldId).filter {
                it.source in setOf("simplesignals:models", "simplesignals:model-preparation") && it.objectId !in displayIds
            }.forEach { worldIssues.resolve(runtime.worldId, it.source, it.kind, it.objectId, it.code) }
            if (view.pending.isEmpty()) worldIssues.replace(runtime.worldId, "simplesignals:revalidation", emptyList())
        }
    }

    /**
     * A pending catalog must not block edits which introduce no package binding that still needs
     * validation. Existing bindings remain restrictive; once the catalog exists, every candidate
     * is compiled strictly against its JavaScript signal type.
     */
    private fun prepareCandidateSectionOccupancy(
        runtime: SimpleSignalsWorldRuntime,
        base: SimpleSignalsRuntimePublication,
        candidate: SimpleSignalsRuntimePublication,
        packages: PreparedSignalPacks? = signalPacks
    ): SectionOccupancyRuntime {
        val baseBindings = signalTypeBindings(base).toSet()
        val candidateBindings = signalTypeBindings(candidate)
        val fallback = if (packages == null && candidateBindings.all(baseBindings::contains)) {
            SignalPackFallback.MISSING_CATALOG
        } else {
            SignalPackFallback.NONE
        }
        return prepareSectionOccupancy(runtime, candidate, fallback, packages)
    }

    /** Catch up one exact publication; a concurrent definition/topology replacement simply retries later. */
    private fun attachSignalPacks(runtime: SimpleSignalsWorldRuntime): Boolean = synchronized(runtime.managementLock) {
        while (!closed && !runtime.closed && runtimeReadView[runtime.worldId] === runtime) {
            val publication = runtime.authoringPublication()
            if (signalTypeBindings(publication).isEmpty()) return true
            val previous = runtime.currentSectionOccupancy()
            val occupancy = try {
                prepareSectionOccupancy(runtime, publication, SignalPackFallback.NONE).also { it.retainPublishedFrom(previous) }
            } catch (failure: Exception) {
                if (runtime.authoringPublication() !== publication) continue
                synchronized(issueLock) {
                    if (!runtime.closed && runtimeReadView[runtime.worldId] === runtime) {
                        worldIssues.replace(runtime.worldId, "simplesignals:preparation", signalTypeBindings(publication).map {
                            WorldIssueInput(WorldIssueKind.SIGNAL, it.deviceId, "rule-preparation-failed",
                                "信号规则准备失败。", describe(failure))
                        })
                    }
                }
                reportModelFailure("SimpleSignals signal rules unavailable for world ${runtime.worldId}; " +
                    "bound devices remain restrictive", failure)
                return false
            }
            var committed = false
            try {
                signalEpochSources.replaceSourcesAtomically(plugin, runtime.worldId, occupancy.sources) {
                    check(!closed && runtimeReadView[runtime.worldId] === runtime) {
                        "signal catalog installation became stale"
                    }
                    check(infrastructure.currentTopology(runtime.worldId) === publication.topology) {
                        "topology changed before signal catalog installation"
                    }
                    committed = runtime.replaceSectionOccupancy(publication, previous, occupancy)
                    check(committed) { "signal catalog installation became stale" }
                }
            } catch (failure: Exception) {
                if (runtime.closed || runtime.authoringPublication() !== publication) continue
                reportModelFailure("SimpleSignals signal rules could not be installed for world ${runtime.worldId}; " +
                    "bound devices remain restrictive", failure)
                return false
            }
            if (committed) {
                synchronized(issueLock) {
                    if (!runtime.closed && runtimeReadView[runtime.worldId] === runtime)
                        worldIssues.replace(runtime.worldId, "simplesignals:preparation", emptyList())
                }
                return true
            }
        }
        return false
    }

    /** Called once on the enable-time Paper worker, independently of safety-world installation. */
    fun loadModelPackages(directory: Path) {
        ThreadGuard.ensureOffMainThread("SimpleSignals model package preparation")
        if (closed) return
        val prepared = try {
            SimpleSignalsModelPackages.load(directory)
        } catch (failure: Exception) {
            modelPackageFailure = failure
            runtimeReadView.values.forEach { reportModelPreparationIssue(it, it.authoringPublication(), failure) }
            reportModelFailure("SimpleSignals rendering unavailable: model packages could not be loaded from $directory", failure)
            return
        }
        val worlds = synchronized(lock) {
            if (closed) return
            check(modelPackages == null) { "SimpleSignals model packages are already prepared" }
            modelPackages = prepared
            runtimes.values.toList()
        }
        worlds.forEach(::attachModels)
    }

    private fun prepareModels(publication: SimpleSignalsRuntimePublication,
        previous: PreparedSignalModels? = null,
        packages: PreparedSignalPacks? = signalPacks): PreparedSignalModels {
        val definitions = publication.definitions
        val configured = definitions.routeIndicators.any { it.model != null } ||
            definitions.attachedSignals.isNotEmpty()
        val explicitModels = modelPackages ?: if (!configured) emptyMap() else {
            throw IllegalArgumentException("SimpleSignals rendering unavailable: " +
                (modelPackageFailure?.message ?: "model packages are still being prepared"))
        }
        return PreparedSignalModels.prepare(definitions, explicitModels, railTargets, publication.topology, previous,
            packages?.catalog)
    }

    private fun modelBinding(runtime: SimpleSignalsWorldRuntime, publication: SimpleSignalsRuntimePublication,
        models: PreparedSignalModels): SignalModelBinding {
        lateinit var binding: SignalModelBinding
        binding = SignalModelBinding(plugin, publication.definitions.worldId, publication.topology.revision,
            models, modelPresentation, ::reportModelFailure, runtime.modelHandles) { id, failure ->
                synchronized(issueLock) {
                    if (!closed && !runtime.closed && runtimeReadView[runtime.worldId] === runtime &&
                        runtime.authoringPublication() === publication && runtime.currentModels() === binding) {
                        if (failure == null) worldIssues.resolve(runtime.worldId, "simplesignals:models", WorldIssueKind.SIGNAL, id, "model-unavailable")
                        else worldIssues.report(runtime.worldId, "simplesignals:models", WorldIssueInput(
                            WorldIssueKind.SIGNAL, id, "model-unavailable", "信号设备显示失败。",
                            "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"))
                    }
                }
            }
        return binding
    }

    private fun reportModelPreparationIssue(runtime: SimpleSignalsWorldRuntime,
        publication: SimpleSignalsRuntimePublication, failure: Exception) = synchronized(issueLock) {
        if (!closed && !runtime.closed && runtimeReadView[runtime.worldId] === runtime && runtime.authoringPublication() === publication) {
            val definitions = publication.definitions
            val ids = definitions.fixedBlockSignals.filter { it.placement != null }.map { it.signalId } +
                definitions.protectionSignals.filter { it.placement != null }.map { it.signalId } +
                definitions.routeIndicators.filter { it.model != null }.map { it.indicatorId } +
                definitions.attachedSignals.map { it.signalId }
            worldIssues.replace(runtime.worldId, "simplesignals:model-preparation", ids.map { id ->
                WorldIssueInput(WorldIssueKind.SIGNAL, id, "model-preparation-failed", "信号设备显示准备失败。", describe(failure))
            })
        }
    }

    private fun reportModelFailure(context: String, failure: Throwable) {
        try { modelFailure(context, failure) } catch (_: Exception) { }
    }

    /** Catch presentation up to the exact current publication, topology and loaded package identities. */
    private fun attachModels(runtime: SimpleSignalsWorldRuntime) {
        while (!closed && !runtime.closed && runtimeReadView[runtime.worldId] === runtime) {
            val publication = runtime.authoringPublication()
            val previous = runtime.currentModels()
            val packages = signalPacks
            val explicitModels = modelPackages
            if (previous != null && previous.prepared.signalPackCatalog === packages?.catalog &&
                previous.prepared.explicitModels === explicitModels &&
                previous.prepared.topology === publication.topology) return
            val binding = try {
                modelBinding(runtime, publication, prepareModels(publication, previous?.prepared, packages))
            } catch (failure: Exception) {
                // A concurrent edit may have removed the invalid configuration; catch up to that exact publication.
                if (runtime.authoringPublication() !== publication) continue
                reportModelPreparationIssue(runtime, publication, failure)
                reportModelFailure("SimpleSignals rendering unavailable for world ${runtime.worldId}", failure)
                return
            }
            val attached = try {
                synchronized(runtime.managementLock) {
                    if (closed || runtime.closed || runtimeReadView[runtime.worldId] !== runtime) false else {
                        check(infrastructure.currentTopology(runtime.worldId) === publication.topology) {
                            "topology root changed before model attachment"
                        }
                        if (previous == null) runtime.attachModels(publication, binding)
                        else runtime.replaceModels(publication, previous, binding)
                    }
                }
            } catch (failure: Exception) {
                binding.retire()
                reportModelFailure("SimpleSignals rendering unavailable for world ${runtime.worldId}", failure)
                return
            }
            if (attached) {
                synchronized(issueLock) {
                    if (!runtime.closed && runtimeReadView[runtime.worldId] === runtime)
                        worldIssues.replace(runtime.worldId, "simplesignals:model-preparation", emptyList())
                }
                runtime.startModels()
                return
            }
            binding.retire()
            if (runtime.authoringPublication() === publication && runtime.currentModels() === previous) return
        }
    }

    private fun scheduleModels(runtime: SimpleSignalsWorldRuntime) {
        if ((modelPackages != null || signalPacks != null) && !closed) {
            try { modelWorker { attachModels(runtime) } } catch (failure: Exception) {
                reportModelFailure("SimpleSignals rendering unavailable for world ${runtime.worldId}", failure)
            }
        }
    }

    /** Hot-path lookup used by the resource provider/contributor without waiting for authoring I/O. */
    fun fixedBlockSnapshot(worldId: UUID): FixedBlockReadSnapshot? =
        runtimeReadView[worldId]?.fixedBlockSnapshot()

    /** Captures the existing world-load identity without waiting for worker I/O. */
    fun authoringRuntime(worldId: UUID): SimpleSignalsWorldRuntime? = runtimeReadView[worldId]

    /** Immutable player-facing type list. Its captured catalog identity is authoring authority, not its contents. */
    fun signalTypeCatalogSnapshot(): SignalTypeCatalogSnapshot? = signalPacks?.let { prepared ->
        SignalTypeCatalogSnapshot.from(prepared.catalog)
    }

    fun currentSignalTypeCatalog(snapshot: SignalTypeCatalogSnapshot): Boolean =
        !closed && signalPacks?.catalog?.let(snapshot::represents) == true

    /** Immutable model-only IDs for presentation-device authoring; no package asset escapes this view. */
    fun modelTypeCatalogSnapshot(): ModelTypeCatalogSnapshot? = modelPackages?.let(ModelTypeCatalogSnapshot::from)

    fun currentModelTypeCatalog(snapshot: ModelTypeCatalogSnapshot?): Boolean =
        !closed && if (snapshot == null) modelPackages == null else modelPackages?.let(snapshot::represents) == true

    /** Called under the source World's capture boundary; never takes a management or guest monitor. */
    fun prepareSimulation(worldId: UUID): work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature {
        val runtime = checkNotNull(runtimeReadView[worldId]) { "SimpleSignals world runtime is unavailable: $worldId" }
        val publication = runtime.authoringPublication()
        val packages = checkNotNull(signalPacks) { "SimpleSignals signal catalog is unavailable for world $worldId" }
        val guard = runtime.topologyGuard.capture()
        check(!closed && !runtime.closed && runtimeReadView[worldId] === runtime &&
            guard.topology === publication.topology && guard.pending.isEmpty()) {
            "SimpleSignals topology preparation is pending for world $worldId"
        }
        return SimpleSignalsSimulationFeature(plugin.name.lowercase(), publication.definitions,
            packages.catalog.rules, protectionRegionMaxDistanceMeters)
    }

    fun validateRouteAdmission(worldId: UUID, intent: work.art1st.javarailways.basic.api.route.RouteResourceIntent) {
        val publication = checkNotNull(runtimeReadView[worldId]) { "SimpleSignals world runtime is unavailable" }.authoringPublication()
        publication.unavailableTerritory.requireUsable(intent.route, intent.desiredAuthority.distanceFromRouteStartMeters)
    }

    fun install(worldId: UUID, deferSignalPreparation: Boolean = false): SimpleSignalsWorldInstallResult {
        // Reserve the actual load identity without publishing an incompletely installed runtime.
        val runtime: SimpleSignalsWorldRuntime
        while (true) {
            val reserved = synchronized(lock) {
                if (closed) return SimpleSignalsWorldInstallResult(SimpleSignalsWorldInstallStatus.UNAVAILABLE,
                    "SimpleSignals is disabled")
                val previous = runtimes[worldId]
                if (previous == null) {
                    SimpleSignalsWorldRuntime(worldId, protectionRegionMaxDistanceMeters).also { runtimes[worldId] = it }
                } else previous
            }
            if (!reserved.closed) {
                // A concurrent installer owns this same reservation until its local gate completes.
                runtime = reserved
                break
            }
            synchronized(reserved.managementLock) {
                if (reserved.sourceRetirementPending) return SimpleSignalsWorldInstallResult(
                    SimpleSignalsWorldInstallStatus.UNAVAILABLE,
                    "SimpleSignals failed installation is awaiting source cleanup")
                synchronized(lock) {
                    if (runtimes[worldId] === reserved) {
                        clearIssues(worldId)
                        runtimes.remove(worldId)
                    }
                }
            }
        }
        val result = synchronized(runtime.managementLock) {
        var installFailClosed = true
        val outcome = run installation@ {
        if (closed || runtime.closed) return@installation SimpleSignalsWorldInstallResult(
            SimpleSignalsWorldInstallStatus.UNAVAILABLE, "SimpleSignals world installation was retired")
        if (runtimeReadView[worldId] === runtime) return@installation SimpleSignalsWorldInstallResult(
            SimpleSignalsWorldInstallStatus.ALREADY_INSTALLED)
        val load = try {
            definitions.load(worldId)
        } catch (failure: Exception) {
            return@installation unavailable(
                worldId,
                "SimpleSignals definitions could not be read: " +
                    (failure.message ?: failure.javaClass.simpleName)
            )
        }
        val topology = try {
            infrastructure.currentTopology(worldId)
        } catch (failure: Exception) {
            installFailClosed = false
            return@installation SimpleSignalsWorldInstallResult(
                SimpleSignalsWorldInstallStatus.UNAVAILABLE,
                failure.message ?: failure.javaClass.simpleName
            )
        }
        val loaded = when (load) {
            SimpleSignalsWorldDefinitionLoadResult.Missing -> LoadedDefinitions(
                SimpleSignalsWorldDefinitions.empty(worldId),
                SimpleSignalsWorldInstallDefinitionSource.MISSING
            )
            is SimpleSignalsWorldDefinitionLoadResult.Loaded -> LoadedDefinitions(
                load.definitions,
                when (load.source) {
                    SimpleSignalsWorldDefinitionSource.PRIMARY ->
                        SimpleSignalsWorldInstallDefinitionSource.PRIMARY
                    SimpleSignalsWorldDefinitionSource.BACKUP ->
                        SimpleSignalsWorldInstallDefinitionSource.BACKUP
                },
                load.ignoredFailures.map { failure ->
                    reportModelFailure("SimpleSignals invalid definitions candidate world=$worldId file=${failure.file}", failure.error)
                    "Ignored invalid definitions candidate ${failure.file}: " +
                        describe(failure.error)
                }
            )
            is SimpleSignalsWorldDefinitionLoadResult.Corrupted -> {
                return@installation unavailable(
                    worldId,
                    "all SimpleSignals definition files are invalid",
                    load.failures.map { failure ->
                        reportModelFailure("SimpleSignals invalid definitions world=$worldId file=${failure.file}", failure.error)
                        "Failed to read ${failure.file}: ${describe(failure.error)}"
                    }
                )
            }
        }

        val initial = try {
            runtime.initializeDefinitions(topology, loaded.definitions)
        } catch (failure: Exception) {
            if (!closed && !runtime.closed) reportModelFailure("SimpleSignals definitions could not be compiled world=$worldId", failure)
            return@installation unavailable(
                worldId,
                "SimpleSignals definitions could not be compiled: ${describe(failure)}"
            )
        }
        if (initial.status != SimpleSignalsDefinitionPublicationStatus.PUBLISHED) {
            return@installation unavailable(
                worldId,
                "SimpleSignals definitions were rejected: ${initial.diagnostics.joinToString()}"
            )
        }
        val sectionOccupancy = try {
            prepareSectionOccupancy(runtime, runtime.authoringPublication(), SignalPackFallback.ANY_FAILURE,
                if (deferSignalPreparation) null else signalPacks)
        } catch (failure: Exception) {
            if (!closed && !runtime.closed) reportModelFailure("SimpleSignals signal runtime could not be prepared world=$worldId", failure)
            return@installation unavailable(
                worldId,
                "SimpleSignals signal runtime could not be prepared: ${describe(failure)}"
            )
        }
        check(runtime.replaceSectionOccupancy(runtime.authoringPublication(), null, sectionOccupancy))
        try {
            runtime.topologySubscription = infrastructure.observeTopologyChanges(plugin, worldId) { change ->
                if (!runtime.closed && runtime.topologyGuard.invalidate(change)) {
                    runtime.notifyPreviewRegions()
                    scheduleTopologyRevalidation(runtime)
                }
            }
            check(infrastructure.currentTopology(worldId) === topology) { "topology changed during world installation" }
        } catch (failure: Exception) {
            return@installation unavailable(worldId, "SimpleSignals topology observation could not be installed: ${describe(failure)}")
        }
        try {
            signalEpochSources.replaceSourcesAtomically(plugin, worldId, sectionOccupancy.sources) {
                check(!closed && !runtime.closed && synchronized(lock) { runtimes[worldId] === runtime }) {
                    "world installation became stale"
                }
                check(infrastructure.currentTopology(worldId) === topology) { "topology changed during world installation" }
            }
        } catch (failure: Exception) {
            if (!closed && !runtime.closed) reportModelFailure("SimpleSignals section occupancy could not be registered world=$worldId", failure)
            return@installation unavailable(
                worldId,
                "SimpleSignals section occupancy could not be registered: ${describe(failure)}"
            )
        }
        try {
            check(!runtime.closed) { "world installation became stale" }
            routePlanningSources.replaceRoutePlanningSources(
                plugin,
                worldId,
                listOf(runtime.routePlanningBinding)
            )
        } catch (failure: Exception) {
            if (!closed && !runtime.closed) reportModelFailure("SimpleSignals route source could not be registered world=$worldId", failure)
            return@installation unavailable(
                worldId,
                "SimpleSignals route source could not be registered: ${describe(failure)}"
            )
        }
        try {
            check(!runtime.closed) { "world installation became stale" }
            inspectionSources.replaceInspectionSources(
                plugin,
                worldId,
                listOf(
                    runtime.fixedBlockInspectionSource,
                    runtime.protectionRegionInspectionSource
                )
            )
        } catch (failure: Exception) {
            if (!closed && !runtime.closed) reportModelFailure("SimpleSignals inspection sources could not be registered world=$worldId", failure)
            return@installation unavailable(
                worldId,
                "SimpleSignals inspection sources could not be registered: ${describe(failure)}"
            )
        }
        synchronized(lock) {
            if (!closed && !runtime.closed && runtimes[worldId] === runtime) {
                runtime.installed = true
                publishRuntimeReadView()
            }
        }
        if (closed || runtime.closed) return@installation SimpleSignalsWorldInstallResult(
            SimpleSignalsWorldInstallStatus.UNAVAILABLE, "SimpleSignals world installation was retired")
        SimpleSignalsWorldInstallResult(
            SimpleSignalsWorldInstallStatus.INSTALLED,
            definitionSource = loaded.source,
            diagnostics = loaded.diagnostics
        )
        }
        if (outcome.status != SimpleSignalsWorldInstallStatus.INSTALLED &&
            outcome.status != SimpleSignalsWorldInstallStatus.ALREADY_INSTALLED) {
            rollbackFailedInstallation(runtime, outcome, installFailClosed)
        } else outcome
        }
        if (result.status == SimpleSignalsWorldInstallStatus.INSTALLED) {
            synchronized(lock) {
                if (runtimeReadView[worldId] === runtime) synchronized(issueLock) {
                    worldIssues.replace(worldId, "simplesignals:installation", emptyList())
                }
            }
            publishIssues(runtime)
            if (runtime.topologyGuard.capture().pending.isNotEmpty()) scheduleTopologyRevalidation(runtime)
            if (deferSignalPreparation && signalPacks != null) modelWorker {
                if (attachSignalPacks(runtime)) attachModels(runtime)
            } else scheduleModels(runtime)
        } else if (result.status != SimpleSignalsWorldInstallStatus.ALREADY_INSTALLED) {
            var retirementFailure: Throwable? = null
            synchronized(runtime.managementLock) {
                if (runtime.sourceRetirementPending) return result
                try { runtime.retire() } catch (failure: Throwable) { retirementFailure = failure }
                finally {
                    synchronized(lock) {
                        if (runtimes[worldId] === runtime) runtimes.remove(worldId)
                        publishRuntimeReadView()
                    }
                }
            }
            CompletableFuture.runAsync { closeRuntimeResources(runtime, retirementFailure, retire = false) }
                .whenComplete { _, failure -> if (failure != null)
                    reportModelFailure("SimpleSignals failed-install cleanup for world $worldId", failure) }
        }
        return result
    }

    private fun scheduleTopologyRevalidation(runtime: SimpleSignalsWorldRuntime) {
        if (!runtime.topologyWorkerScheduled.compareAndSet(false, true)) return
        try {
            topologyWorker {
                var failed: SectionTopologyGuard.View? = null
                try {
                    while (!closed && !runtime.closed && runtimeReadView[runtime.worldId] === runtime) {
                        val captured = runtime.topologyGuard.capture()
                        if (captured.pending.isEmpty()) break
                        // Acquiring the immutable root also waits for the notifying commit to publish it.
                        infrastructure.currentTopology(runtime.worldId)
                        val base = runtime.authoringPublication()
                        val candidate = SectionTopologyRevalidation.compile(base, captured, protectionRegionMaxDistanceMeters)
                        val occupancy = prepareSectionOccupancy(runtime, candidate, SignalPackFallback.ANY_FAILURE)
                        occupancy.retainPublishedFrom(runtime.currentSectionOccupancy())
                        var committed = false
                        try {
                            signalEpochSources.replaceSourcesAtomically(plugin, runtime.worldId, occupancy.sources) {
                                check(!closed && runtimeReadView[runtime.worldId] === runtime) { "topology refresh became stale" }
                                committed = runtime.commitTopologyRevalidation(base, captured, candidate, occupancy)
                                check(committed) { "topology refresh became stale" }
                            }
                        } catch (failure: Exception) {
                            if (runtime.closed || runtime.topologyGuard.capture() !== captured || runtime.authoringPublication() !== base) continue
                            throw failure
                        }
                        if (committed) {
                            publishIssues(runtime)
                            scheduleModels(runtime)
                        }
                        if (committed && runtime.topologyGuard.capture().pending.isEmpty()) break
                    }
                } catch (failure: Exception) {
                    failed = runtime.topologyGuard.capture()
                    reportRevalidationIssue(runtime, failure)
                    reportModelFailure("SimpleSignals topology revalidation failed for world ${runtime.worldId}; affected sections remain unavailable", failure)
                } finally {
                    runtime.topologyWorkerScheduled.set(false)
                    val latest = runtime.topologyGuard.capture()
                    if (!closed && !runtime.closed && runtimeReadView[runtime.worldId] === runtime &&
                        latest.pending.isNotEmpty() && latest !== failed)
                        scheduleTopologyRevalidation(runtime)
                }
            }
        } catch (failure: Exception) {
            runtime.topologyWorkerScheduled.set(false)
            reportRevalidationIssue(runtime, failure)
            reportModelFailure("SimpleSignals topology worker could not be scheduled", failure)
        }
    }

    private fun reportRevalidationIssue(runtime: SimpleSignalsWorldRuntime, failure: Exception) = synchronized(issueLock) {
        if (!closed && !runtime.closed && runtimeReadView[runtime.worldId] === runtime) {
            worldIssues.replace(runtime.worldId, "simplesignals:revalidation", runtime.topologyGuard.capture().pending.keys.map { section ->
                WorldIssueInput(when (section.kind) {
                    TopologySectionKind.BLOCK -> WorldIssueKind.BLOCK
                    TopologySectionKind.REGION -> WorldIssueKind.PROTECTION_REGION
                    TopologySectionKind.SIGNAL -> WorldIssueKind.SIGNAL
                }, section.id, "revalidation-failed", "轨道重验失败，等待修复。", describe(failure))
            })
        }
    }

    fun remove(worldId: UUID) {
        val removed = synchronized(lock) {
            val current = runtimes[worldId]
            if (current == null) {
                // Failed installation can leave a current content fault after its runtime retires.
                // Clear it on world removal while excluding a replacement install reservation.
                clearIssues(worldId)
                return
            }
            current.markClosed()
            publishRuntimeReadView()
            current
        }
        var retirementFailure: Throwable? = null
        // Finish an old install and retire diagnostics before making its UUID available to a new load.
        synchronized(removed.managementLock) {
            if (removed.sourceRetirementPending) retireFailedInstallationSources(removed)
            try { removed.retire() } catch (failure: Throwable) { retirementFailure = failure }
            finally {
                synchronized(lock) {
                    if (runtimes[worldId] === removed) {
                        clearIssues(worldId)
                        runtimes.remove(worldId)
                    }
                    publishRuntimeReadView()
                }
            }
        }
        CompletableFuture.runAsync { closeRuntimeResources(removed, retirementFailure, retire = false) }
            .whenComplete { _, failure -> if (failure != null)
                reportModelFailure("SimpleSignals world cleanup failed for $worldId", failure) }
        retirementFailure?.let { throw it }
    }

    /** Cleanup runs on workers; guest close is never performed inside either management monitor. */
    private fun closeRuntimeResources(runtime: SimpleSignalsWorldRuntime, initialFailure: Throwable? = null,
        retire: Boolean = true) {
        var failure = initialFailure
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (error !== failure) failure!!.addSuppressed(error)
            }
        }
        // An uncertain detach must leave the engine alive, even if every other close could proceed.
        synchronized(runtime.managementLock) {
            if (runtime.sourceRetirementPending) retireFailedInstallationSources(runtime)
            synchronized(lock) {
                if (runtimes[runtime.worldId] === runtime) runtimes.remove(runtime.worldId)
                publishRuntimeReadView()
            }
        }
        if (retire) attempt { synchronized(runtime.managementLock) { runtime.retire() } }
        attempt { runtime.closeModels() }
        attempt { runtime.closeLogic() }
        failure?.let { throw it }
    }

    fun clearLocal(): CompletionStage<Void> {
        val removed = synchronized(lock) {
            closed = true
            val removed = runtimes.values.toList()
            removed.forEach { it.markClosed() }
            // A still-installing candidate can become pending while its management gate is held.
            // Keep every reservation until its worker confirms source detachment under that gate.
            publishRuntimeReadView()
            removed
        }
        synchronized(issueLock) {
            listOf("simplesignals:topology", "simplesignals:rules", "simplesignals:revalidation", "simplesignals:installation", "simplesignals:preparation", "simplesignals:models", "simplesignals:model-preparation")
                .forEach(worldIssues::clearSource)
        }
        val cleanups = removed.map { runtime -> CompletableFuture.runAsync { closeRuntimeResources(runtime) } }
        return CompletableFuture.allOf(*cleanups.toTypedArray()).handle { _, _ ->
            var first: Throwable? = null
            cleanups.forEach { cleanup ->
                try { cleanup.join() } catch (error: java.util.concurrent.CompletionException) {
                    val cause = error.cause ?: error
                    if (first == null) first = cause else if (cause !== first) first!!.addSuppressed(cause)
                }
            }
            first
        }.thenCompose { failure ->
            val ownerCleanup = try { modelPresentation.closeOwner(plugin) }
                catch (error: Throwable) { CompletableFuture.failedFuture<Void>(error) }
            ownerCleanup.handle<Void> { _, error ->
                if (failure != null) {
                    if (error != null && error !== failure) failure.addSuppressed(error)
                    throw java.util.concurrent.CompletionException(failure)
                }
                if (error != null) throw java.util.concurrent.CompletionException(error)
                null
            }
        }
    }

    private fun clearIssues(worldId: UUID) = synchronized(issueLock) {
        listOf("simplesignals:topology", "simplesignals:rules", "simplesignals:revalidation", "simplesignals:installation", "simplesignals:preparation", "simplesignals:models", "simplesignals:model-preparation")
            .forEach { worldIssues.replace(worldId, it, emptyList()) }
    }

    private fun publishRuntimeReadView() {
        runtimeReadView = java.util.Map.copyOf(runtimes.filterValues { it.installed && !it.closed })
    }

    private sealed interface PatchPreparation {
        data class Ready(val value: SimpleSignalsPreparedDefinitions) : PatchPreparation
        data class Rejected(val result: SimpleSignalsDefinitionAuthoringResult) : PatchPreparation
    }

    private fun prepareDefinitionPatch(
        runtime: SimpleSignalsWorldRuntime,
        expectedDefinitionRevision: Long,
        patch: SimpleSignalsDefinitionPatch,
        expectedTopology: TopologyState? = null
    ): PatchPreparation {
        val worldId = runtime.worldId
        val current = runtime.currentDefinitions()
        if (expectedDefinitionRevision != current.definitionRevision) {
            return PatchPreparation.Rejected(authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
                "expected definition revision $expectedDefinitionRevision, current revision is " +
                    current.definitionRevision
            ))
        }
        val nextRevision = try {
            Math.addExact(current.definitionRevision, 1L)
        } catch (_: ArithmeticException) {
            return PatchPreparation.Rejected(authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
                "definition revision ${current.definitionRevision} cannot be advanced"
            ))
        }
        val appliedCandidate = when (val application = applyPatch(current, nextRevision, patch)) {
            is PatchApplication.Applied -> application.definitions
            is PatchApplication.Rejected -> return PatchPreparation.Rejected(authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED,
                application.diagnostics
            ))
        }
        val topology = try {
            infrastructure.currentTopology(worldId)
        } catch (failure: Exception) {
            return PatchPreparation.Rejected(authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
                "current topology is unavailable: ${describe(failure)}"
            ))
        }
        val candidate = work.art1st.javarailways.simplesignals.authoring.BoundaryNames.normalize(appliedCandidate, topology)
        val prepared = when (val preparation = runtime.prepareDefinitions(topology, candidate)) {
            SimpleSignalsDefinitionPreparationResult.Unchanged -> return PatchPreparation.Rejected(authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED,
                "definition patch does not change the current definitions"
            ))
            is SimpleSignalsDefinitionPreparationResult.Rejected -> return PatchPreparation.Rejected(authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
                preparation.diagnostics
            ))
            is SimpleSignalsDefinitionPreparationResult.Prepared -> preparation.value
        }
        if (expectedTopology != null && !topology.preservesRails(expectedTopology,
                SimpleSignalsDefinitionEditImpact.impact(prepared.basePublication, prepared.candidatePublication,
                    includeTopologyChanges = false)?.railIds.orEmpty())) {
            return PatchPreparation.Rejected(authoringResult(SimpleSignalsDefinitionAuthoringStatus.STALE_TOPOLOGY,
                "selected rail dependencies changed after the authoring selection"))
        }
        return PatchPreparation.Ready(prepared)
    }

    /** Pure candidate validation: no permit, save or source replacement. */
    internal fun validateDefinitionPatch(
        worldId: UUID,
        expectedDefinitionRevision: Long,
        patch: SimpleSignalsDefinitionPatch,
        expectedRuntime: SimpleSignalsWorldRuntime? = null,
        expectedTopology: TopologyState? = null
    ): SimpleSignalsDefinitionAuthoringResult {
        ThreadGuard.ensureOffMainThread("SimpleSignals definition validation")
        val runtime = runtimeReadView[worldId]
        if (runtime == null || (expectedRuntime != null && (runtime !== expectedRuntime || !plugin.isEnabled))) {
            return authoringResult(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
                "captured world runtime is no longer available or plugin is disabled")
        }
        return when (val preparation = prepareDefinitionPatch(runtime, expectedDefinitionRevision, patch, expectedTopology)) {
            is PatchPreparation.Rejected -> preparation.result
            is PatchPreparation.Ready -> try {
                val packages = signalPacks
                prepareCandidateSectionOccupancy(runtime, requireNotNull(preparation.value.basePublication) {
                    "definition edit has no live base publication"
                }, preparation.value.candidatePublication, packages)
                prepareModels(preparation.value.candidatePublication, runtime.currentModels()?.prepared, packages)
                SimpleSignalsDefinitionAuthoringResult(
                    SimpleSignalsDefinitionAuthoringStatus.VALIDATED,
                    definitions = preparation.value.definitions,
                    affectedRailIds = preparation.value.affectedRailIds
                )
            } catch (failure: Exception) {
                authoringResult(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
                    "signal or model configuration was rejected: ${describe(failure)}")
            }
        }
    }

    /**
     * Applies one low-frequency authoring transaction. The same host edit permit covers the
     * durable write and the single live-publication swap.
     */
    internal fun applyDefinitionPatch(
        worldId: UUID,
        expectedDefinitionRevision: Long,
        patch: SimpleSignalsDefinitionPatch,
        expectedRuntime: SimpleSignalsWorldRuntime? = null,
        expectedTopology: TopologyState? = null,
        authoringGuard: () -> Boolean = { true }
    ): SimpleSignalsDefinitionAuthoringResult = applyDefinitionPatchAttempt(worldId,
        expectedDefinitionRevision, patch, expectedRuntime, expectedTopology, authoringGuard, true)

    private fun applyDefinitionPatchAttempt(
        worldId: UUID, expectedDefinitionRevision: Long, patch: SimpleSignalsDefinitionPatch,
        expectedRuntime: SimpleSignalsWorldRuntime?, expectedTopology: TopologyState?,
        authoringGuard: () -> Boolean, topologyRetryAvailable: Boolean
    ): SimpleSignalsDefinitionAuthoringResult {
        ThreadGuard.ensureOffMainThread("SimpleSignals definition authoring preparation")
        if (!authoringGuard()) return authoringResult(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            "authoring selection is no longer active")
        val runtime = runtimeReadView[worldId] ?: return authoringResult(
            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            "SimpleSignals world runtime is not installed: $worldId"
        )
        if (expectedRuntime != null && (runtime !== expectedRuntime || !plugin.isEnabled)) {
            return authoringResult(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
                "captured world runtime is no longer available or plugin is disabled")
        }
        val prepared = when (val preparation = prepareDefinitionPatch(runtime, expectedDefinitionRevision, patch, expectedTopology)) {
            is PatchPreparation.Ready -> preparation.value
            is PatchPreparation.Rejected -> return preparation.result
        }
        val packages = signalPacks
        val candidateModels = try {
            modelBinding(runtime, prepared.candidatePublication,
                prepareModels(prepared.candidatePublication, runtime.currentModels()?.prepared, packages))
        } catch (failure: Exception) {
            return authoringResult(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
                "model configuration was rejected: ${describe(failure)}")
        }
        if (closed || runtime.closed || runtimeReadView[worldId] !== runtime) {
            candidateModels.retire()
            return authoringResult(SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
                "captured world runtime is no longer available")
        }
        val candidateOccupancy = try {
            prepareCandidateSectionOccupancy(runtime, requireNotNull(prepared.basePublication) {
                "definition edit has no live base publication"
            }, prepared.candidatePublication, packages)
        } catch (failure: Exception) {
            return authoringResult(SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
                "signal type configuration was rejected: ${describe(failure)}")
        }
        candidateOccupancy.attachModelBinding(candidateModels)
        val result = synchronized(runtime.managementLock) {
        if (closed || runtime.closed || runtimeReadView[worldId] !== runtime) return@synchronized authoringResult(
            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            "captured world runtime is no longer available or plugin is disabled")
        if (runtime.authoringPublication() !== prepared.basePublication) return@synchronized authoringResult(
            SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
            "definitions changed before the prepared edit could be committed")
        if (signalPacks !== packages) return@synchronized authoringResult(
            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            "signal catalog changed while the definition edit was being prepared; retry the edit")
        val topology = prepared.topology
        val nextRevision = prepared.definitions.definitionRevision
        // Unbound ground devices can have no infrastructure impact. They still use the
        // same guarded atomic transaction; host permits only represent real infrastructure.
        val permitResult = try {
            prepared.editImpact?.let { impact ->
                infrastructure.acquireEditPermit(
                    plugin,
                    worldId,
                    TopologyEditRequest(
                        topology.revision,
                        InfrastructureOwner("simplesignals:definitions/$worldId", nextRevision),
                        impact
                    )
                )
            }
        } catch (failure: Exception) {
            return@synchronized authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
                "edit permit is unavailable: ${describe(failure)}"
            )
        }
        val permit = permitResult?.permit()?.orElse(null)
        if (permitResult != null && permit == null) {
            val status = when (permitResult.decision().status) {
                TopologyEditStatus.STALE_TOPOLOGY ->
                    SimpleSignalsDefinitionAuthoringStatus.STALE_TOPOLOGY
                TopologyEditStatus.WORLD_UNAVAILABLE, TopologyEditStatus.SERVICE_CLOSED ->
                    SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE
                else -> SimpleSignalsDefinitionAuthoringStatus.EDIT_BLOCKED
            }
            return@synchronized SimpleSignalsDefinitionAuthoringResult(
                status,
                editDecision = permitResult.decision(),
                diagnostics = listOf(permitResult.decision().detail).filter(String::isNotBlank)
            )
        }

        try {
            val currentTopology = try {
                infrastructure.currentTopology(worldId)
            } catch (failure: Exception) {
                return@synchronized authoringResult(
                    SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
                    "current topology became unavailable after permit acquisition: ${describe(failure)}"
                )
            }
            if (currentTopology !== prepared.topology) {
                return@synchronized SimpleSignalsDefinitionAuthoringResult(
                    SimpleSignalsDefinitionAuthoringStatus.STALE_TOPOLOGY,
                    editDecision = TopologyEditDecision(
                        TopologyEditStatus.STALE_TOPOLOGY,
                        detail = "topology root changed after edit permit acquisition"
                    ),
                    diagnostics = listOf("topology root changed after edit permit acquisition")
                )
            }
            val stagedSave = try {
                definitions.prepareSave(worldId, prepared.definitions)
            } catch (failure: Exception) {
                return@synchronized authoringResult(
                    SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED,
                    "definitions could not be staged: ${describe(failure)}"
                )
            }
            try {
                var callbackStarted = false
                var staleBase = false
                var staleTopologyDuringCommit = false
                var topologyUnavailableDuringCommit = false
                var staleSignalPacksDuringCommit = false
                var authoringCancelledDuringCommit = false
                try {
                    signalEpochSources.replaceSourcesAtomically(
                        plugin,
                        worldId,
                        candidateOccupancy.sources
                    ) {
                        callbackStarted = true
                        if (closed || runtime.closed || runtimeReadView[worldId] !== runtime || !authoringGuard() || expectedRuntime != null && !plugin.isEnabled) {
                            topologyUnavailableDuringCommit = true
                            error("captured world runtime is no longer available or plugin is disabled")
                        }
                        val commitTopology = try {
                            infrastructure.currentTopology(worldId)
                        } catch (failure: Exception) {
                            topologyUnavailableDuringCommit = true
                            throw failure
                        }
                        if (commitTopology !== prepared.topology) {
                            staleTopologyDuringCommit = true
                            error("topology root changed before the atomic definition commit")
                        }
                        if (signalPacks !== packages) {
                            staleSignalPacksDuringCommit = true
                            error("signal catalog changed before the atomic definition commit")
                        }
                        val committed = runtime.commitPrepared(
                            prepared,
                            candidateOccupancy,
                            candidateModels
                        ) {
                            if (!authoringGuard()) {
                                authoringCancelledDuringCommit = true
                                error("authoring selection is no longer active")
                            }
                            stagedSave.commitPrimary()
                        }
                        if (committed == SimpleSignalsDefinitionCommitStatus.STALE_BASE) {
                            staleBase = true
                            error("definitions changed before the prepared edit could be committed")
                        }
                    }
                } catch (failure: Exception) {
                    if (staleTopologyDuringCommit) {
                        return@synchronized SimpleSignalsDefinitionAuthoringResult(
                            SimpleSignalsDefinitionAuthoringStatus.STALE_TOPOLOGY,
                            editDecision = TopologyEditDecision(
                                TopologyEditStatus.STALE_TOPOLOGY,
                                detail = "topology root changed before the atomic definition commit"
                            ),
                            diagnostics = listOf(
                                "topology root changed before the atomic definition commit"
                            )
                        )
                    }
                    val status = when {
                        staleSignalPacksDuringCommit ->
                            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE
                        authoringCancelledDuringCommit ->
                            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE
                        topologyUnavailableDuringCommit ->
                            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE
                        staleBase -> SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED
                        callbackStarted -> SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED
                        failure is IllegalArgumentException ->
                            SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED
                        else -> SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE
                    }
                    val prefix = when {
                        staleSignalPacksDuringCommit ->
                            "signal catalog changed before the prepared edit could be committed"
                        authoringCancelledDuringCommit ->
                            "authoring selection became unavailable before the prepared edit could be committed"
                        status == SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED ->
                            "definitions could not be saved"
                        status == SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED ->
                            "definitions changed before the prepared edit could be committed"
                        else -> "section occupancy source could not be replaced"
                    }
                    return@synchronized authoringResult(
                        status,
                        "$prefix: ${describe(failure)}"
                    )
                }
                SimpleSignalsDefinitionAuthoringResult(
                    SimpleSignalsDefinitionAuthoringStatus.APPLIED,
                    definitions = prepared.definitions,
                    editDecision = permitResult?.decision()
                )
            } finally {
                stagedSave.close()
            }
        } finally {
            permit?.close()
        }
        }
        if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED) {
            synchronized(issueLock) {
                if (!runtime.closed && runtimeReadView[runtime.worldId] === runtime && signalPacks != null)
                    worldIssues.replace(runtime.worldId, "simplesignals:preparation", emptyList())
            }
            publishIssues(runtime)
            runtime.startModels()
        } else candidateModels.retire()
        if (result.status == SimpleSignalsDefinitionAuthoringStatus.STALE_TOPOLOGY ||
            result.status == SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED) {
            val currentPublication = runtime.authoringPublication()
            val latestTopology = infrastructure.currentTopology(worldId)
            val selectionRails = SimpleSignalsDefinitionEditImpact.impact(prepared.basePublication,
                prepared.candidatePublication, includeTopologyChanges = false)?.railIds.orEmpty()
            if ((latestTopology !== prepared.topology || currentPublication !== prepared.basePublication) &&
                currentPublication.definitions.definitionRevision == expectedDefinitionRevision &&
                latestTopology.preservesRails(expectedTopology ?: prepared.topology, selectionRails)) {
                // Retire the old model preparation before recompiling all graph-bound inputs on the worker.
                // Keep the original selection baseline and definition CAS through the retry.
                return if (topologyRetryAvailable) applyDefinitionPatchAttempt(worldId, expectedDefinitionRevision, patch,
                    expectedRuntime, expectedTopology ?: prepared.topology, authoringGuard, false)
                else authoringResult(SimpleSignalsDefinitionAuthoringStatus.RETRY_REQUIRED,
                    "unrelated topology edits continued during preparation; retry the unchanged selection")
            }
        }
        return result
    }

    private fun applyPatch(
        current: SimpleSignalsWorldDefinitions,
        nextRevision: Long,
        patch: SimpleSignalsDefinitionPatch
    ): PatchApplication {
        val diagnostics = mutableListOf<String>()
        validatePatchIdentities(
            "protection region",
            patch.upsertRegions.map { it.regionId },
            patch.removeRegionIds,
            current.regions.map { it.regionId }.toSet(),
            diagnostics
        )
        validatePatchIdentities(
            "protection route table",
            patch.upsertRouteTables.map { it.tableId },
            patch.removeRouteTableIds,
            current.routeTables.map { it.tableId }.toSet(),
            diagnostics
        )
        validatePatchIdentities(
            "fixed block",
            patch.upsertFixedBlocks.map { it.blockId },
            patch.removeFixedBlockIds,
            current.fixedBlocks.map { it.blockId }.toSet(),
            diagnostics
        )
        validatePatchIdentities(
            "fixed-block signal",
            patch.upsertFixedBlockSignals.map { it.signalId },
            patch.removeFixedBlockSignalIds,
            current.fixedBlockSignals.map { it.signalId }.toSet(),
            diagnostics
        )
        validatePatchIdentities("protection signal", patch.upsertProtectionSignals.map { it.signalId },
            patch.removeProtectionSignalIds, current.protectionSignals.map { it.signalId }.toSet(), diagnostics)
        validatePatchIdentities("route indicator", patch.upsertRouteIndicators.map { it.indicatorId },
            patch.removeRouteIndicatorIds, current.routeIndicators.map { it.indicatorId }.toSet(), diagnostics)
        validatePatchIdentities("attached signal", patch.upsertAttachedSignals.map { it.signalId },
            patch.removeAttachedSignalIds, current.attachedSignals.map { it.signalId }.toSet(), diagnostics)
        if (patch.upsertRegions.isEmpty() && patch.removeRegionIds.isEmpty() &&
            patch.upsertRouteTables.isEmpty() && patch.removeRouteTableIds.isEmpty() &&
            patch.upsertFixedBlocks.isEmpty() && patch.removeFixedBlockIds.isEmpty() &&
            patch.upsertFixedBlockSignals.isEmpty() && patch.removeFixedBlockSignalIds.isEmpty() &&
            patch.upsertProtectionSignals.isEmpty() && patch.removeProtectionSignalIds.isEmpty() &&
            patch.upsertRouteIndicators.isEmpty() && patch.removeRouteIndicatorIds.isEmpty() &&
            patch.upsertAttachedSignals.isEmpty() && patch.removeAttachedSignalIds.isEmpty() &&
            patch.pendingProtectionBoundaries == null && patch.boundaryNames == null
        ) {
            diagnostics += "definition patch is empty"
        }
        if (diagnostics.isNotEmpty()) {
            return PatchApplication.Rejected(java.util.List.copyOf(diagnostics))
        }

        val regions = current.regions.associateByTo(linkedMapOf()) { it.regionId }
        patch.removeRegionIds.forEach(regions::remove)
        patch.upsertRegions.forEach { regions[it.regionId] = it }
        val tables = current.routeTables.associateByTo(linkedMapOf()) { it.tableId }
        patch.removeRouteTableIds.forEach(tables::remove)
        patch.upsertRouteTables.forEach { tables[it.tableId] = it }
        val fixedBlocks = current.fixedBlocks.associateByTo(linkedMapOf()) { it.blockId }
        patch.removeFixedBlockIds.forEach(fixedBlocks::remove)
        patch.upsertFixedBlocks.forEach { fixedBlocks[it.blockId] = it }
        val fixedBlockSignals = current.fixedBlockSignals.associateByTo(linkedMapOf()) {
            it.signalId
        }
        patch.removeFixedBlockSignalIds.forEach(fixedBlockSignals::remove)
        patch.upsertFixedBlockSignals.forEach { fixedBlockSignals[it.signalId] = it }
        val protectionSignals = current.protectionSignals.associateByTo(linkedMapOf()) { it.signalId }
        patch.removeProtectionSignalIds.forEach(protectionSignals::remove)
        patch.upsertProtectionSignals.forEach { protectionSignals[it.signalId] = it }
        val indicators = current.routeIndicators.associateByTo(linkedMapOf()) { it.indicatorId }
        patch.removeRouteIndicatorIds.forEach(indicators::remove)
        patch.upsertRouteIndicators.forEach { indicators[it.indicatorId] = it }
        val removedRouteIds = current.routeTables.flatMap { it.routes }.mapTo(hashSetOf()) { it.routeId }
        tables.values.forEach { table -> table.routes.forEach { removedRouteIds.remove(it.routeId) } }
        if (removedRouteIds.isNotEmpty()) {
            val explicitSignals = patch.upsertProtectionSignals.mapTo(hashSetOf()) { it.signalId }
            for ((id, signal) in protectionSignals) {
                if (id in explicitSignals || signal.kind != ProtectionSignalKind.FIXED_ROUTE ||
                    signal.bindingId !in removedRouteIds) continue
                val revision = try { Math.addExact(signal.revision, 1L) } catch (_: ArithmeticException) {
                    return PatchApplication.Rejected(listOf("signal $id revision cannot be advanced"))
                }
                protectionSignals[id] = signal.copy(revision = revision, bindingId = null)
            }
            val explicitIndicators = patch.upsertRouteIndicators.mapTo(hashSetOf()) { it.indicatorId }
            for ((id, indicator) in indicators) {
                if (id in explicitIndicators || indicator.modelVariantsByRouteId.keys.none { it in removedRouteIds })
                    continue
                val revision = try { Math.addExact(indicator.revision, 1L) } catch (_: ArithmeticException) {
                    return PatchApplication.Rejected(listOf("indicator $id revision cannot be advanced"))
                }
                indicators[id] = RouteIndicatorDefinition(id, revision, indicator.entryBoundaryId, indicator.noRouteModelVariant,
                    indicator.modelVariantsByRouteId.filterKeys { it !in removedRouteIds }, indicator.model)
            }
        }
        val attachedSignals = current.attachedSignals.associateByTo(linkedMapOf()) { it.signalId }
        patch.removeAttachedSignalIds.forEach(attachedSignals::remove)
        patch.upsertAttachedSignals.forEach { attachedSignals[it.signalId] = it }
        val candidate = SimpleSignalsWorldDefinitions(
            current.worldId,
            nextRevision,
            regions.values.sortedBy { it.regionId },
            tables.values.sortedBy { it.tableId },
            fixedBlocks.values.sortedBy { it.blockId },
            fixedBlockSignals.values.sortedBy { it.signalId },
            protectionSignals.values.sortedBy { it.signalId }, indicators.values.sortedBy { it.indicatorId },
            attachedSignals.values.sortedBy { it.signalId },
            (patch.pendingProtectionBoundaries ?: current.pendingProtectionBoundaries).sortedBy { it.boundaryId },
            patch.boundaryNames ?: current.boundaryNames
        )
        if (candidate.regions == current.regions &&
            candidate.routeTables == current.routeTables &&
            candidate.fixedBlocks == current.fixedBlocks &&
            candidate.fixedBlockSignals == current.fixedBlockSignals &&
            candidate.protectionSignals == current.protectionSignals &&
            candidate.routeIndicators == current.routeIndicators && candidate.attachedSignals == current.attachedSignals &&
            candidate.pendingProtectionBoundaries == current.pendingProtectionBoundaries && candidate.boundaryNames == current.boundaryNames
        ) {
            return PatchApplication.Rejected(
                listOf("definition patch does not change the current definitions")
            )
        }
        return PatchApplication.Applied(candidate)
    }

    private fun validatePatchIdentities(
        label: String,
        upsertIds: List<String>,
        removeIds: List<String>,
        currentIds: Set<String>,
        diagnostics: MutableList<String>
    ) {
        upsertIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            diagnostics += "$label $id is upserted more than once"
        }
        removeIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            diagnostics += "$label $id is removed more than once"
        }
        upsertIds.toSet().intersect(removeIds.toSet()).forEach { id ->
            diagnostics += "$label $id cannot be upserted and removed in the same patch"
        }
        removeIds.filterNot(currentIds::contains).forEach { id ->
            diagnostics += "$label $id cannot be removed because it does not exist"
        }
    }

    private sealed interface PatchApplication {
        data class Applied(val definitions: SimpleSignalsWorldDefinitions) : PatchApplication
        data class Rejected(val diagnostics: List<String>) : PatchApplication
    }

    private fun authoringResult(
        status: SimpleSignalsDefinitionAuthoringStatus,
        diagnostic: String
    ) = authoringResult(status, listOf(diagnostic))

    private fun authoringResult(
        status: SimpleSignalsDefinitionAuthoringStatus,
        diagnostics: List<String>
    ) = SimpleSignalsDefinitionAuthoringResult(
        status,
        diagnostics = java.util.List.copyOf(diagnostics)
    )

    private data class LoadedDefinitions(
        val definitions: SimpleSignalsWorldDefinitions,
        val source: SimpleSignalsWorldInstallDefinitionSource,
        val diagnostics: List<String> = emptyList()
    )

    /** Retire host callbacks before a failed candidate's engine can be closed or its UUID reused. */
    private fun rollbackFailedInstallation(
        runtime: SimpleSignalsWorldRuntime,
        outcome: SimpleSignalsWorldInstallResult,
        installFailClosed: Boolean
    ): SimpleSignalsWorldInstallResult {
        val installFallback = installFailClosed && !closed && !runtime.closed && synchronized(lock) {
            runtimes[runtime.worldId] === runtime
        }
        runtime.markClosed()
        val diagnostics = outcome.diagnostics.toMutableList()
        runtime.sourceRetirementPending = true
        try {
            retireFailedInstallationSources(runtime)
        } catch (failure: Exception) {
            diagnostics += "Could not retire failed installation sources: ${describe(failure)}"
            reportModelFailure("SimpleSignals failed-install source cleanup for world ${runtime.worldId}", failure)
        }
        // This callback owns no runtime or JS resources. Preserve the existing refusal of new
        // routes after invalid definitions, without retaining callbacks into the failed candidate.
        if (installFallback && !closed && synchronized(lock) { runtimes[runtime.worldId] === runtime }) try {
            routePlanningSources.replaceRoutePlanningSources(plugin, runtime.worldId, listOf(
                RoutePlanningRestrictionBinding(PROTECTION_ROUTE_SOURCE_ID, RoutePlanningRestrictionSource {
                    throw IllegalStateException(outcome.detail)
                })
            ))
        } catch (failure: Exception) {
            diagnostics += "Could not install fail-closed route source: ${describe(failure)}"
            reportModelFailure("SimpleSignals failed-install fallback for world ${runtime.worldId}", failure)
        }
        return outcome.copy(diagnostics = java.util.List.copyOf(diagnostics))
    }

    private fun retireFailedInstallationSources(runtime: SimpleSignalsWorldRuntime) {
        signalEpochSources.retireSources(
            plugin, runtime.worldId,
            runtime.currentSectionOccupancy()?.sources.orEmpty(),
            listOf(runtime.fixedBlockInspectionSource, runtime.protectionRegionInspectionSource),
            listOf(runtime.routePlanningBinding)
        )
        runtime.sourceRetirementPending = false
    }

    private fun unavailable(
        worldId: UUID,
        detail: String,
        diagnostics: List<String> = emptyList()
    ): SimpleSignalsWorldInstallResult {
        worldIssues.report(worldId, "simplesignals:installation", WorldIssueInput(
            WorldIssueKind.WORLD, worldId.toString(), "world-install-failed", detail, diagnostics.joinToString("\n")))
        return SimpleSignalsWorldInstallResult(SimpleSignalsWorldInstallStatus.UNAVAILABLE,
            detail, diagnostics = java.util.List.copyOf(diagnostics))
    }

    private fun describe(failure: Exception): String =
        "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
}
