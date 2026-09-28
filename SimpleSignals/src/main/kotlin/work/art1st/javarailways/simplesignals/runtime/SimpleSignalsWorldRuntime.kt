package work.art1st.javarailways.simplesignals.runtime

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditDecision
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditStatus
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictionSource
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalog
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalogStatus
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCatalog
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCatalogStatus
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableCatalog
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableCatalogStatus
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionLoadResult
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionSource
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionStore
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
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
    val removeFixedBlockIds: List<String>
) {
    constructor(
        upsertRegions: Collection<ProtectionRegionDefinition> = emptyList(),
        removeRegionIds: Collection<String> = emptyList(),
        upsertRouteTables: Collection<ProtectionRouteTableDefinition> = emptyList(),
        removeRouteTableIds: Collection<String> = emptyList(),
        upsertFixedBlocks: Collection<FixedBlockDefinition> = emptyList(),
        removeFixedBlockIds: Collection<String> = emptyList()
    ) : this(
        java.util.List.copyOf(upsertRegions),
        java.util.List.copyOf(removeRegionIds),
        java.util.List.copyOf(upsertRouteTables),
        java.util.List.copyOf(removeRouteTableIds),
        java.util.List.copyOf(upsertFixedBlocks),
        java.util.List.copyOf(removeFixedBlockIds)
    )
}

internal enum class SimpleSignalsDefinitionAuthoringStatus {
    APPLIED,
    REVISION_REJECTED,
    PATCH_REJECTED,
    VALIDATION_REJECTED,
    EDIT_BLOCKED,
    STALE_TOPOLOGY,
    PERSISTENCE_FAILED,
    WORLD_UNAVAILABLE
}

internal data class SimpleSignalsDefinitionAuthoringResult(
    val status: SimpleSignalsDefinitionAuthoringStatus,
    val definitions: SimpleSignalsWorldDefinitions? = null,
    val editDecision: TopologyEditDecision? = null,
    val diagnostics: List<String> = emptyList()
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
    val baseAffectedRailIds: Set<UUID>,
    val affectedRailIds: Set<UUID>
)

internal class SimpleSignalsRuntimePublication(
    val topology: TopologyState,
    val definitions: SimpleSignalsWorldDefinitions,
    val affectedRailIds: Set<UUID>,
    val regions: ProtectionRegionCatalog,
    val routeTables: ProtectionRouteTableCatalog,
    val fixedBlocks: FixedBlockCatalog
)

/**
 * One plugin-owned runtime for one exact host world load.
 *
 * Definitions are compiled into fresh candidate catalogs and become visible through one volatile
 * publication only after both catalogs accept them. Route search reads that publication and never
 * rebuilds region or route-table state inside its callback.
 */
internal class SimpleSignalsWorldRuntime(
    val worldId: UUID
) {
    private data class RuntimeState(
        val publication: SimpleSignalsRuntimePublication?,
        val sectionOccupancy: SectionOccupancyRuntime?
    )

    private val lock = Any()

    @Volatile
    private var state = RuntimeState(null, null)

    val routePlanningBinding = RoutePlanningRestrictionBinding(
        PROTECTION_ROUTE_SOURCE_ID,
        RoutePlanningRestrictionSource { topology ->
            val captured = state.publication
                ?: error("SimpleSignals definitions have not been published for world $worldId")
            captured.routeTables.restrictionSource.restrictions(topology)
        }
    )

    fun currentDefinitions(): SimpleSignalsWorldDefinitions = synchronized(lock) {
        checkNotNull(state.publication) { "SimpleSignals definitions have not been published for world $worldId" }
            .definitions
    }

    fun currentTopology(): TopologyState = synchronized(lock) {
        checkNotNull(state.publication) { "SimpleSignals definitions have not been published for world $worldId" }
            .topology
    }

    fun fixedBlockSnapshot(topology: TopologyState): FixedBlockReadSnapshot =
        checkNotNull(state.publication) {
            "SimpleSignals definitions have not been published for world $worldId"
        }.fixedBlocks.snapshot(topology)

    /** Installs immutable fixed-block monitoring geometry exactly once for this world load. */
    fun initializeSectionOccupancy(): SectionOccupancyRuntime = synchronized(lock) {
        val current = state
        check(current.sectionOccupancy == null) { "section occupancy is already initialized for world $worldId" }
        SectionOccupancyRuntime.from(
            checkNotNull(current.publication) {
                "SimpleSignals definitions have not been published for world $worldId"
            }
        ).also { state = RuntimeState(current.publication, it) }
    }

    fun sectionOccupancySnapshot(): SectionOccupancySnapshot? = state.sectionOccupancy?.snapshot()

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
        val copiedDefinitions = SimpleSignalsWorldDefinitions(
            worldId,
            definitions.definitionRevision,
            copiedRegions,
            copiedTables,
            copiedBlocks
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
        if (current != null && current.topology === topology &&
            copiedDefinitions == current.definitions
        ) {
            return@synchronized SimpleSignalsDefinitionPreparationResult.Unchanged
        }

        val candidateRegions = ProtectionRegionCatalog()
        val regionResult = candidateRegions.publish(
            topology,
            definitions.definitionRevision,
            copiedRegions
        )
        if (regionResult.status == ProtectionRegionCatalogStatus.REJECTED) {
            return@synchronized preparationRejected(regionResult.diagnostics.map { it.detail })
        }
        val candidateTables = ProtectionRouteTableCatalog(candidateRegions)
        val tableResult = candidateTables.publish(definitions.definitionRevision, copiedTables)
        if (tableResult.status == ProtectionRouteTableCatalogStatus.REJECTED) {
            return@synchronized preparationRejected(tableResult.diagnostics.map { it.detail })
        }

        val candidateBlocks = FixedBlockCatalog()
        val blockResult = candidateBlocks.publish(
            topology,
            definitions.definitionRevision,
            copiedBlocks
        )
        if (blockResult.status == FixedBlockCatalogStatus.REJECTED) {
            return@synchronized preparationRejected(blockResult.diagnostics.map { it.detail })
        }
        validateSectionSeparation(candidateRegions, candidateBlocks, topology)?.let { diagnostic ->
            return@synchronized preparationRejected(diagnostic)
        }

        val affectedRailIds = affectedRailIds(
            candidateRegions,
            copiedRegions,
            copiedTables,
            copiedBlocks
        )
        val candidate = SimpleSignalsRuntimePublication(
            topology,
            copiedDefinitions,
            affectedRailIds,
            candidateRegions,
            candidateTables,
            candidateBlocks
        )
        SimpleSignalsDefinitionPreparationResult.Prepared(
            SimpleSignalsPreparedDefinitions(
                this,
                current,
                candidate,
                topology,
                copiedDefinitions,
                current?.affectedRailIds.orEmpty(),
                affectedRailIds
            )
        )
    }

    fun commitPrepared(
        prepared: SimpleSignalsPreparedDefinitions,
        replacementSectionOccupancy: SectionOccupancyRuntime? = null,
        beforeCommit: () -> Unit = {}
    ): SimpleSignalsDefinitionCommitStatus = synchronized(lock) {
        require(prepared.runtime === this) {
            "prepared SimpleSignals definitions belong to another world runtime"
        }
        val current = state
        if (current.publication !== prepared.basePublication) {
            return@synchronized SimpleSignalsDefinitionCommitStatus.STALE_BASE
        }
        val candidateState = RuntimeState(
            prepared.candidatePublication,
            replacementSectionOccupancy ?: current.sectionOccupancy
        )
        beforeCommit()
        state = candidateState
        SimpleSignalsDefinitionCommitStatus.COMMITTED
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

    private fun affectedRailIds(
        regions: ProtectionRegionCatalog,
        regionDefinitions: Collection<ProtectionRegionDefinition>,
        routeTables: Collection<ProtectionRouteTableDefinition>,
        fixedBlocks: Collection<FixedBlockDefinition>
    ): Set<UUID> {
        val result = linkedSetOf<UUID>()
        regionDefinitions.flatMapTo(result) { region ->
            region.boundaries.map { it.position.railId }
        }
        regions.compilationSnapshot()?.regionsById?.values?.forEach { region ->
            region.interior.mapTo(result) { it.railId }
        }
        routeTables.forEach { table ->
            table.routes.forEach { route ->
                route.traversals.mapTo(result) { it.railId }
            }
        }
        fixedBlocks.forEach { block ->
            block.traversals.mapTo(result) { it.railId }
        }
        return java.util.Set.copyOf(result)
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

    /** Fixed blocks and protection interiors are distinct section types and may only touch at cuts. */
    private fun validateSectionSeparation(
        regions: ProtectionRegionCatalog,
        fixedBlocks: FixedBlockCatalog,
        topology: TopologyState
    ): String? {
        val regionSnapshot = regions.compilationSnapshot() ?: return null
        val regionSlicesByRail = regionSnapshot.regionsById.values
            .flatMap { region -> region.interior.map { slice -> region.definition.regionId to slice } }
            .groupBy { (_, slice) -> slice.railId }
            .mapValues { (_, slices) -> slices.sortedBy { (_, slice) -> slice.startAbMeters } }
        fixedBlocks.snapshot(topology).blocksById.values.forEach { block ->
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
    private val signalEpochSources: SignalEpochSourceService
) {
    private val lock = Any()
    private val runtimes = linkedMapOf<UUID, SimpleSignalsWorldRuntime>()

    fun install(worldId: UUID): SimpleSignalsWorldInstallResult = synchronized(lock) {
        if (worldId in runtimes) {
            return@synchronized SimpleSignalsWorldInstallResult(
                SimpleSignalsWorldInstallStatus.ALREADY_INSTALLED
            )
        }
        val load = try {
            definitions.load(worldId)
        } catch (failure: Exception) {
            return@synchronized unavailable(
                worldId,
                "SimpleSignals definitions could not be read: " +
                    (failure.message ?: failure.javaClass.simpleName)
            )
        }
        val topology = try {
            infrastructure.currentTopology(worldId)
        } catch (failure: Exception) {
            return@synchronized SimpleSignalsWorldInstallResult(
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
                    "Ignored invalid definitions candidate ${failure.file}: " +
                        describe(failure.error)
                }
            )
            is SimpleSignalsWorldDefinitionLoadResult.Corrupted -> {
                return@synchronized unavailable(
                    worldId,
                    "all SimpleSignals definition files are invalid",
                    load.failures.map { failure ->
                        "Failed to read ${failure.file}: ${describe(failure.error)}"
                    }
                )
            }
        }

        val runtime = SimpleSignalsWorldRuntime(worldId)
        val initial = try {
            runtime.initializeDefinitions(topology, loaded.definitions)
        } catch (failure: Exception) {
            return@synchronized unavailable(
                worldId,
                "SimpleSignals definitions could not be compiled: ${describe(failure)}"
            )
        }
        if (initial.status != SimpleSignalsDefinitionPublicationStatus.PUBLISHED) {
            return@synchronized unavailable(
                worldId,
                "SimpleSignals definitions were rejected: ${initial.diagnostics.joinToString()}"
            )
        }
        val sectionOccupancy = runtime.initializeSectionOccupancy()
        try {
            signalEpochSources.replaceSources(
                plugin,
                worldId,
                listOfNotNull(sectionOccupancy.source)
            )
        } catch (failure: Exception) {
            return@synchronized unavailable(
                worldId,
                "SimpleSignals section occupancy could not be registered: ${describe(failure)}"
            )
        }
        try {
            routePlanningSources.replaceRoutePlanningSources(
                plugin,
                worldId,
                listOf(runtime.routePlanningBinding)
            )
        } catch (failure: Exception) {
            return@synchronized unavailable(
                worldId,
                "SimpleSignals route source could not be registered: ${describe(failure)}"
            )
        }
        runtimes[worldId] = runtime
        SimpleSignalsWorldInstallResult(
            SimpleSignalsWorldInstallStatus.INSTALLED,
            definitionSource = loaded.source,
            diagnostics = loaded.diagnostics
        )
    }

    fun remove(worldId: UUID) {
        synchronized(lock) { runtimes.remove(worldId) }
    }

    fun clearLocal() {
        synchronized(lock) { runtimes.clear() }
    }

    /**
     * Applies one low-frequency authoring transaction. The same host edit permit covers the
     * durable write and the single live-publication swap.
     */
    internal fun applyDefinitionPatch(
        worldId: UUID,
        expectedDefinitionRevision: Long,
        patch: SimpleSignalsDefinitionPatch
    ): SimpleSignalsDefinitionAuthoringResult = synchronized(lock) {
        val runtime = runtimes[worldId] ?: return@synchronized authoringResult(
            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
            "SimpleSignals world runtime is not installed: $worldId"
        )
        val current = runtime.currentDefinitions()
        if (expectedDefinitionRevision != current.definitionRevision) {
            return@synchronized authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
                "expected definition revision $expectedDefinitionRevision, current revision is " +
                    current.definitionRevision
            )
        }
        val nextRevision = try {
            Math.addExact(current.definitionRevision, 1L)
        } catch (_: ArithmeticException) {
            return@synchronized authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
                "definition revision ${current.definitionRevision} cannot be advanced"
            )
        }
        val candidate = when (val application = applyPatch(current, nextRevision, patch)) {
            is PatchApplication.Applied -> application.definitions
            is PatchApplication.Rejected -> return@synchronized authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED,
                application.diagnostics
            )
        }
        val topology = try {
            infrastructure.currentTopology(worldId)
        } catch (failure: Exception) {
            return@synchronized authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
                "current topology is unavailable: ${describe(failure)}"
            )
        }
        val replaceSectionOccupancy =
            candidate.fixedBlocks != current.fixedBlocks || topology !== runtime.currentTopology()
        val prepared = when (val preparation = runtime.prepareDefinitions(topology, candidate)) {
            SimpleSignalsDefinitionPreparationResult.Unchanged -> return@synchronized authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.PATCH_REJECTED,
                "definition patch does not change the current definitions"
            )
            is SimpleSignalsDefinitionPreparationResult.Rejected -> return@synchronized authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
                preparation.diagnostics
            )
            is SimpleSignalsDefinitionPreparationResult.Prepared -> preparation.value
        }
        val impactRailIds = linkedSetOf<UUID>().apply {
            addAll(prepared.baseAffectedRailIds)
            addAll(prepared.affectedRailIds)
        }
        if (impactRailIds.isEmpty()) {
            return@synchronized authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED,
                "definition edit does not resolve to any affected rail"
            )
        }
        val permitResult = try {
            infrastructure.acquireEditPermit(
                plugin,
                worldId,
                TopologyEditRequest(
                    topology.revision,
                    InfrastructureOwner("simplesignals:definitions/$worldId", nextRevision),
                    TopologyEditImpact(impactRailIds, emptySet(), emptySet())
                )
            )
        } catch (failure: Exception) {
            return@synchronized authoringResult(
                SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE,
                "edit permit is unavailable: ${describe(failure)}"
            )
        }
        val permit = permitResult.permit().orElse(null)
        if (permit == null) {
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
            var callbackStarted = false
            var staleBase = false
            var staleTopologyDuringCommit = false
            var topologyUnavailableDuringCommit = false
            val commit = if (replaceSectionOccupancy) {
                val candidateOccupancy = SectionOccupancyRuntime.from(
                    prepared.candidatePublication
                )
                try {
                    signalEpochSources.replaceSourcesAtomically(
                        plugin,
                        worldId,
                        listOfNotNull(candidateOccupancy.source)
                    ) {
                        callbackStarted = true
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
                        val committed = runtime.commitPrepared(
                            prepared,
                            candidateOccupancy
                        ) {
                            definitions.save(worldId, prepared.definitions)
                        }
                        if (committed == SimpleSignalsDefinitionCommitStatus.STALE_BASE) {
                            staleBase = true
                            error("definitions changed before the prepared edit could be committed")
                        }
                    }
                    SimpleSignalsDefinitionCommitStatus.COMMITTED
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
                        topologyUnavailableDuringCommit ->
                            SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE
                        staleBase -> SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED
                        callbackStarted -> SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED
                        failure is IllegalArgumentException ->
                            SimpleSignalsDefinitionAuthoringStatus.VALIDATION_REJECTED
                        else -> SimpleSignalsDefinitionAuthoringStatus.WORLD_UNAVAILABLE
                    }
                    val prefix = when (status) {
                        SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED ->
                            "definitions could not be saved"
                        SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED ->
                            "definitions changed before the prepared edit could be committed"
                        else -> "section occupancy source could not be replaced"
                    }
                    return@synchronized authoringResult(
                        status,
                        "$prefix: ${describe(failure)}"
                    )
                }
            } else {
                try {
                    runtime.commitPrepared(prepared) {
                        definitions.save(worldId, prepared.definitions)
                    }
                } catch (failure: Exception) {
                    return@synchronized authoringResult(
                        SimpleSignalsDefinitionAuthoringStatus.PERSISTENCE_FAILED,
                        "definitions could not be saved: ${describe(failure)}"
                    )
                }
            }
            if (commit == SimpleSignalsDefinitionCommitStatus.STALE_BASE) {
                return@synchronized authoringResult(
                    SimpleSignalsDefinitionAuthoringStatus.REVISION_REJECTED,
                    "definitions changed before the prepared edit could be committed"
                )
            }
            SimpleSignalsDefinitionAuthoringResult(
                SimpleSignalsDefinitionAuthoringStatus.APPLIED,
                definitions = prepared.definitions,
                editDecision = permitResult.decision()
            )
        } finally {
            permit.close()
        }
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
        if (patch.upsertRegions.isEmpty() && patch.removeRegionIds.isEmpty() &&
            patch.upsertRouteTables.isEmpty() && patch.removeRouteTableIds.isEmpty() &&
            patch.upsertFixedBlocks.isEmpty() && patch.removeFixedBlockIds.isEmpty()
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
        val candidate = SimpleSignalsWorldDefinitions(
            current.worldId,
            nextRevision,
            regions.values.sortedBy { it.regionId },
            tables.values.sortedBy { it.tableId },
            fixedBlocks.values.sortedBy { it.blockId }
        )
        if (candidate.regions == current.regions &&
            candidate.routeTables == current.routeTables &&
            candidate.fixedBlocks == current.fixedBlocks
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

    private fun unavailable(
        worldId: UUID,
        detail: String,
        diagnostics: List<String> = emptyList()
    ): SimpleSignalsWorldInstallResult {
        val allDiagnostics = diagnostics.toMutableList()
        try {
            signalEpochSources.replaceSources(plugin, worldId, emptyList())
        } catch (failure: Exception) {
            allDiagnostics += "Could not clear section occupancy source: ${describe(failure)}"
        }
        try {
            routePlanningSources.replaceRoutePlanningSources(
                plugin,
                worldId,
                listOf(
                    RoutePlanningRestrictionBinding(
                        PROTECTION_ROUTE_SOURCE_ID,
                        RoutePlanningRestrictionSource {
                            throw IllegalStateException(detail)
                        }
                    )
                )
            )
        } catch (failure: Exception) {
            allDiagnostics += "Could not install fail-closed route source: ${describe(failure)}"
        }
        return SimpleSignalsWorldInstallResult(
            SimpleSignalsWorldInstallStatus.UNAVAILABLE,
            detail,
            diagnostics = java.util.List.copyOf(allDiagnostics)
        )
    }

    private fun describe(failure: Exception): String =
        "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
}
