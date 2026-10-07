package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossing
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.simpleato.api.signal.SignalEpochInput
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_SIGNAL_SOURCE_ID
import work.art1st.javarailways.simplesignals.signal.ProtectionRouteSelectionRuntime
import work.art1st.javarailways.simplesignals.signal.protectionRouteAreaId
import work.art1st.javarailways.simplesignals.presentation.SignalModelBinding
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signal.fixedBlockSignalDeclarationId
import work.art1st.javarailways.simplesignals.signalpack.SignalPackEpochRuntime

internal const val SECTION_OCCUPANCY_SOURCE_ID = FIXED_BLOCK_SIGNAL_SOURCE_ID

internal enum class SectionKind {
    FIXED_BLOCK,
    PROTECTION_ROUTE
}

internal data class SectionRef(
    val kind: SectionKind,
    val id: String
) : Comparable<SectionRef> {
    override fun compareTo(other: SectionRef): Int = SECTION_REF_ORDER.compare(this, other)
}

private val SECTION_REF_ORDER = compareBy<SectionRef>({ it.kind.name }, { it.id })

/** Latest conservative occupancy known for one plugin-owned section. */
internal class SectionOccupancyObservation(
    val section: SectionRef,
    val definitionRevision: Long,
    val lengthMeters: Double,
    occupants: Collection<String>,
    val available: Boolean = true
) {
    val occupants: Set<String> = java.util.Set.copyOf(occupants)
}

/**
 * Immutable result of the last baseline or changed-area callback.
 *
 * A null runtime snapshot means that the host has not delivered the first physical baseline yet;
 * it must not be interpreted as an empty section. [changedSections] belongs to [stamp] only, while
 * [sections] contains the latest conservative occupant set for every compiled section.
 */
internal class SectionOccupancySnapshot(
    val stamp: RailwayStateStamp,
    sections: Map<SectionRef, SectionOccupancyObservation>,
    changedSections: Collection<SectionRef>,
    crossingsBySection: Map<SectionRef, List<RailwayOccupancyCrossing>>
) {
    val sections: Map<SectionRef, SectionOccupancyObservation> = immutableSectionMap(sections)
    val changedSections: Set<SectionRef> = java.util.Collections.unmodifiableSet(
        LinkedHashSet(changedSections.sortedWith(SECTION_REF_ORDER))
    )
    val crossingsBySection: Map<SectionRef, List<RailwayOccupancyCrossing>> = immutableSectionMap(
        crossingsBySection.mapValues { java.util.List.copyOf(it.value) }
    )

    private fun <T> immutableSectionMap(values: Map<SectionRef, T>): Map<SectionRef, T> {
        val ordered = linkedMapOf<SectionRef, T>()
        values.entries.sortedWith { left, right -> SECTION_REF_ORDER.compare(left.key, right.key) }
            .forEach { (section, value) -> ordered[section] = value }
        return java.util.Collections.unmodifiableMap(ordered)
    }
}

/**
 * Converts the shared fixed-block/protection-device publication into one host epoch source.
 *
 * Basic performs the rail-local occupancy/crossing work once per physical epoch. This class maps
 * changed areas, selections and continuations to configured rules, then atomically publishes
 * occupancy, signal and indicator state. It owns no tick loop or world lookup.
 */
internal class SectionOccupancyRuntime private constructor(
    private val bindingsByArea: Map<RailObjectRef, Binding>,
    private val signalRules: SectionSignalRuleRuntime?,
    private val signalPackRules: SignalPackEpochRuntime?,
    val protectionRouteSelection: ProtectionRouteSelectionRuntime,
    private val protectionSignals: work.art1st.javarailways.simplesignals.signal.CompiledProtectionSignals,
    private val blocks: work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot,
    private val directionPolicy: work.art1st.javarailways.simplesignals.block.FixedBlockDirectionPolicy,
    private val publication: SimpleSignalsRuntimePublication
) {
    /** Single completed epoch owner, safely read during handoff; never forms a predecessor chain. */
    internal class EpochContinuity {
        @Volatile var lastProcessed: SectionOccupancyRuntime? = null
    }

    private var epochContinuity: EpochContinuity? = null
    private var continuityTransferred = false
    private var retainedPublication = false
    private var awaitingBaseline: Set<TopologySection> = emptySet()
    private val bindingsBySignal = (publication.fixedBlockSignals.bindings + publication.protectionSignals.bindings)
        .associateBy { it.signalId }
    private val fixedBlockIdByDeviceId = publication.definitions.fixedBlockSignals
        .associate { it.signalId to it.controlledBlockId }

    fun attachEpochContinuity(continuity: EpochContinuity) {
        check(epochContinuity == null || epochContinuity === continuity)
        epochContinuity = continuity
    }

    internal data class PublishedState(
        val occupancy: SectionOccupancySnapshot,
        val signals: SectionSignalSnapshot?,
        val selection: work.art1st.javarailways.simplesignals.signal.ProtectionRouteSelectionSnapshot?,
        val indicators: Map<String, work.art1st.javarailways.simplesignals.signal.RouteIndicatorState>,
        val blockControls: Map<String, SectionSignalState> = emptyMap()
    )

    @Volatile
    private var latest: PublishedState? = null

    /** Immutable output handoff only. Mutable route-clearance state still transfers on the epoch owner. */
    fun retainPublishedFrom(previous: SectionOccupancyRuntime?) {
        val old = previous?.latest ?: return
        retainedPublication = true
        val guard = publication.topologyGuard
        awaitingBaseline = awaitingBaseline + previous.awaitingBaseline + guard?.capture()?.pending?.keys.orEmpty()
        val sections = bindingsByArea.values.associate { binding ->
            val retained = old.occupancy.sections[binding.section]
            val unchanged = previous.bindingsByArea[binding.area.areaId]?.area === binding.area ||
                (binding.section.kind == SectionKind.PROTECTION_ROUTE &&
                    previous.protectionSignals.routeGeometry.blocksById[binding.section.id] ===
                    protectionSignals.routeGeometry.blocksById[binding.section.id])
            binding.section to (retained?.takeIf { unchanged } ?: SectionOccupancyObservation(
                binding.section, binding.definitionRevision, binding.lengthMeters, retained?.occupants.orEmpty(), false))
        }.toMutableMap()
        // Missing-rail areas cannot be registered with Basic, but their last known occupants remain evidence.
        val authoredRoutes = publication.definitions.routeTables.flatMapTo(hashSetOf()) { table -> table.routes.map { it.routeId } }
        old.occupancy.sections.forEach { (section, observation) ->
            val retainedUnavailable = when (section.kind) {
                SectionKind.FIXED_BLOCK -> section.id in blocks.unavailableBlockIds
                SectionKind.PROTECTION_ROUTE -> section.id in authoredRoutes && section !in sections
            }
            if (retainedUnavailable)
                sections[section] = SectionOccupancyObservation(section, observation.definitionRevision,
                    observation.lengthMeters, observation.occupants, false)
        }
        val signals = old.signals?.let { snapshot -> SectionSignalSnapshot(snapshot.stamp,
            snapshot.states.mapValues { (id, state) -> if (guard?.signalUnavailable(id) == true ||
                TopologySection(TopologySectionKind.SIGNAL, id) in awaitingBaseline)
                SectionSignalState.Unavailable("signal awaits topology revalidation baseline") else state },
            emptySet(), snapshot.resolvedSectionsBySignal) }
        latest = PublishedState(SectionOccupancySnapshot(old.occupancy.stamp, sections, emptySet(), emptyMap()),
            signals, old.selection, old.indicators, old.blockControls)
    }

    @Volatile private var modelBinding: SignalModelBinding? = null

    /** Worker attachment precedes catch-up; callbacks always consume this complete publication. */
    fun attachModelBinding(binding: SignalModelBinding) { modelBinding = binding }

    fun presentationSnapshot(): PublishedState? = latest

    fun indicators() = latest?.indicators.orEmpty()
    fun selectionSnapshot() = latest?.selection

    val source: SignalEpochSource? = if (bindingsByArea.isEmpty() && protectionRouteSelection.source == null &&
        signalRules == null && signalPackRules == null) {
        null
    } else {
        SignalEpochSource(
            SECTION_OCCUPANCY_SOURCE_ID,
            bindingsByArea.entries.sortedBy { it.key.toString() }.map { it.value.area },
            signalRules?.watchedContinuationEntries.orEmpty() +
                signalPackRules?.watchedContinuationEntries.orEmpty() +
                protectionRouteSelection.watchedContinuationEntries,
            protectionRouteSelection.watchedAdmissionRailIds,
            blocks.blockIdsByRail.keys + (publication.topologyIndex?.railsByObject?.filterKeys {
                it.kind == TopologySectionKind.SIGNAL
            }?.values?.flatten().orEmpty()),
            watchedInspectionSourceIds = setOf(work.art1st.javarailways.simplesignals.region.PROTECTION_REGION_INSPECTION_SOURCE_ID)
        ) { input -> update(input) }
    }

    fun snapshot(): SectionOccupancySnapshot? {
        val own = latest?.occupancy ?: return null
        val completed = lastCompletedRuntime() ?: return own
        val observed = completed.latest?.occupancy ?: return own
        return SectionOccupancySnapshot(observed.stamp, own.sections.mapValues { (section, retained) ->
            val current = observed.sections[section] ?: return@mapValues retained
            val unchanged = when (section.kind) {
                SectionKind.FIXED_BLOCK -> blocks.blocksById[section.id] === completed.blocks.blocksById[section.id]
                SectionKind.PROTECTION_ROUTE -> protectionSignals.routeGeometry.blocksById[section.id] != null &&
                    protectionSignals.routeGeometry.blocksById[section.id] === completed.protectionSignals.routeGeometry.blocksById[section.id]
            }
            if (unchanged && retained.available) current else SectionOccupancyObservation(section,
                retained.definitionRevision, retained.lengthMeters, current.occupants, false)
        }, emptySet(), emptyMap())
    }

    val sources: List<SignalEpochSource> = listOfNotNull(source)

    fun signalSnapshot(): SectionSignalSnapshot? = latest?.signals?.let { snapshot ->
        val completed = lastCompletedRuntime()?.latest
        if (completed != null) return@let SectionSignalSnapshot(completed.occupancy.stamp,
            snapshot.states.mapValues { (id, state) -> signalById(id)?.state ?: state },
            snapshot.changedSignalIds, snapshot.resolvedSectionsBySignal)
        val guard = publication.topologyGuard ?: return@let snapshot
        val unavailable = snapshot.states.keys.filter(guard::signalUnavailable)
        if (unavailable.isEmpty()) snapshot else SectionSignalSnapshot(snapshot.stamp,
            snapshot.states + unavailable.associateWith { SectionSignalState.Unavailable("signal topology is unavailable") },
            unavailable, snapshot.resolvedSectionsBySignal)
    }

    private fun lastCompletedRuntime(): SectionOccupancyRuntime? =
        epochContinuity?.lastProcessed?.takeIf { retainedPublication && it !== this }

    /** Controller lookup is O(1); untouched bindings read the last completed epoch, not a worker copy. */
    fun signalById(signalId: String): PublishedSectionSignal? {
        val own = latest ?: return null
        val completed = lastCompletedRuntime()
        val captured = if (completed != null && bindingsBySignal[signalId] != null &&
            bindingsBySignal[signalId] === completed.bindingsBySignal[signalId]) completed.latest ?: own else own
        if (publication.topologyGuard?.signalUnavailable(signalId) == true ||
            TopologySection(TopologySectionKind.SIGNAL, signalId) in awaitingBaseline)
            return PublishedSectionSignal(captured.occupancy.stamp, SectionSignalState.Unavailable("signal topology awaits a valid baseline"))
        val signals = captured.signals ?: return null
        return signals.states[signalId]?.let { PublishedSectionSignal(signals.stamp, it) }
    }

    fun fixedBlockSignal(entry: FixedBlockSignalEntry): PublishedSectionSignal? {
        val blockId = entry.blockId
        val own = latest ?: return null
        val completed = lastCompletedRuntime()
        val captured = if (completed != null &&
            blocks.blocksById[blockId] === completed.blocks.blocksById[blockId]) completed.latest ?: own else own
        if (publication.topologyGuard?.sectionUnavailable(TopologySectionKind.BLOCK, blockId) == true ||
                TopologySection(TopologySectionKind.BLOCK, blockId) in awaitingBaseline)
            return PublishedSectionSignal(captured.occupancy.stamp, SectionSignalState.Unavailable("fixed-block topology is unavailable"))
        captured.blockControls[blockId]?.let {
            return PublishedSectionSignal(captured.occupancy.stamp, it)
        }
        val signalId = signalRules?.signalIdForDeclaration(fixedBlockSignalDeclarationId(blockId, entry.direction)) ?: return null
        return signalById(signalId)
    }

    private fun update(input: SignalEpochInput): SignalEpochResult {
        if (!continuityTransferred) {
            epochContinuity?.lastProcessed?.takeIf { it !== this }?.let { completed ->
                if (retainedPublication) retainPublishedFrom(completed)
                protectionRouteSelection.retainClearanceFrom(completed.protectionRouteSelection)
            }
            continuityTransferred = true
        }
        val previous = latest
        val oldSelection = protectionRouteSelection.snapshot()
        protectionRouteSelection.update(input)
        val selection = protectionRouteSelection.snapshot()
        val changedEntries = if (selection !== oldSelection) selection?.changedEntries.orEmpty() else emptySet()
        var indicatorStates = previous?.indicators.orEmpty()
        val changedIndicatorIds = mutableListOf<String>()
        if (changedEntries.isNotEmpty()) {
            val next = indicatorStates.toMutableMap()
            changedEntries.flatMap { protectionSignals.indicatorsByEntry[it].orEmpty() }.forEach { indicator ->
                changedIndicatorIds += indicator.indicatorId
                val selected = selection!!.selections.getValue(indicator.entryBoundaryId)
                next[indicator.indicatorId] = work.art1st.javarailways.simplesignals.signal.RouteIndicatorState(
                    selected, selected.routeId?.takeIf { selected.available }?.let(indicator.modelVariantsByRouteId::getValue)
                        ?: indicator.noRouteModelVariant)
            }
            indicatorStates = java.util.Map.copyOf(next)
        }
        val observations = previous?.occupancy?.sections?.toMutableMap() ?: linkedMapOf()
        val changed = linkedSetOf<SectionRef>()
        val topologyAffected = if (input.topologyBaseline)
            publication.topologyGuard?.capture()?.pending?.keys.orEmpty() + awaitingBaseline else emptySet()
        topologyAffected.filter { it.kind == TopologySectionKind.SIGNAL }.forEach { key ->
            bindingsBySignal[key.id]?.observedSections?.let(changed::addAll)
        }
        val policyBlocks = (if (previous == null) blocks.blocksById.values.toList() else {
            blocks.candidates(input.changedOperatingDirectionRailIds) + topologyAffected
                .filter { it.kind == TopologySectionKind.BLOCK }.mapNotNull { blocks.blocksById[it.id] }
        }).distinctBy { it.definition.blockId }.filter { it.definition.blockId !in blocks.unavailableBlockIds }
        val changedControls = linkedSetOf<String>()
        val controls = if (policyBlocks.isEmpty()) previous?.blockControls.orEmpty() else {
            val policyStates = try {
                val disabled = directionPolicy.disabledBlocks(blocks, policyBlocks)
                policyBlocks.associate { block -> block.definition.blockId to
                    if (block.definition.blockId in disabled) SectionSignalState.Unavailable("fixed-block operating directions disagree")
                    else null }
            } catch (failure: Exception) {
                policyBlocks.associate { it.definition.blockId to SectionSignalState.Unavailable(
                    failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.name) }
            }
            val nextControls = previous?.blockControls?.toMutableMap() ?: linkedMapOf()
            policyStates.forEach { (id, next) ->
                val old = nextControls[id]
                if (next == null) nextControls.remove(id) else nextControls[id] = next
                if (old != next) {
                    changed += SectionRef(SectionKind.FIXED_BLOCK, id)
                    changedControls += id
                }
            }
            java.util.Map.copyOf(nextControls)
        }
        val crossings = linkedMapOf<SectionRef, List<RailwayOccupancyCrossing>>()
        input.areaUpdates.forEach { update ->
            val binding = checkNotNull(bindingsByArea[update.areaId]) {
                "unregistered occupancy area ${update.areaId} reached $SECTION_OCCUPANCY_SOURCE_ID"
            }
            require(update.definitionRevision == binding.definitionRevision) {
                "occupancy area ${update.areaId} revision ${update.definitionRevision} does not match " +
                    "compiled section revision ${binding.definitionRevision}"
            }
            val oldObservation = observations[binding.section]
            val nextObservation = SectionOccupancyObservation(
                binding.section,
                binding.definitionRevision,
                binding.lengthMeters,
                update.occupants,
                update.available
            )
            if (oldObservation == null || oldObservation.definitionRevision != nextObservation.definitionRevision ||
                oldObservation.lengthMeters != nextObservation.lengthMeters || oldObservation.available != nextObservation.available ||
                oldObservation.occupants != nextObservation.occupants || update.crossings.isNotEmpty()) {
                observations[binding.section] = nextObservation
                changed += binding.section
            }
            if (update.crossings.isNotEmpty()) {
                crossings[binding.section] = update.crossings
            }
        }
        if (previous == null) {
            require(observations.size == bindingsByArea.size) {
                "first section occupancy callback must baseline every compiled section"
            }
        }
        val occupancy = SectionOccupancySnapshot(input.observation.stamp, observations, changed, crossings)
        val configuredSignals = signalRules?.evaluate(
            previous?.signals,
            occupancy,
            input.observation.activeContinuations,
            input.continuationUpdates.map { it.entry },
            protectionRouteSelection.evaluatedEntryIds,
            { section -> controls[section.id].takeIf { section.kind == SectionKind.FIXED_BLOCK } },
            { id -> publication.topologyGuard?.signalUnavailable(id) == true }
        ) { binding ->
            val definition = protectionSignals.definitionsById[binding.signalId]
            if (definition == null) binding.controlledSection else when (definition.kind) {
                work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind.FIXED_ROUTE ->
                    binding.controlledSection.takeIf { protectionRouteSelection.fixedRouteReady(definition.bindingId, input) }
                work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind.CURRENT_ENTRY ->
                    selection?.selections?.get(definition.bindingId)?.takeIf { it.available && it.routeReady }
                        ?.routeId?.let(protectionSignals::routeSection)
            }
        }
        val signals = signalPackRules?.evaluate(
            previous?.signals,
            configuredSignals,
            occupancy,
            input,
            selection,
            protectionRouteSelection.evaluatedEntryIds,
            { deviceId ->
                when {
                    publication.topologyGuard?.signalUnavailable(deviceId) == true ->
                        "signal topology is unavailable"
                    else -> fixedBlockIdByDeviceId[deviceId]
                        ?.let { controls[it] as? SectionSignalState.Unavailable }
                        ?.reason
                }
            }
        ) { routeId -> protectionRouteSelection.fixedRouteReady(routeId, input) } ?: configuredSignals
        val published = PublishedState(occupancy, signals, selection, indicatorStates, controls)
        latest = published
        epochContinuity?.lastProcessed = this
        awaitingBaseline = emptySet()
        modelBinding?.published(published, signals?.changedSignalIds.orEmpty(), changedIndicatorIds)
        return SignalEpochResult(
            emptyList(),
            signals?.let { signalRules?.invalidatedDeclarationIds(it) }.orEmpty() +
                changedControls.flatMap { id -> FixedBlockEntryDirection.entries.map { direction ->
                    fixedBlockSignalDeclarationId(id, direction)
                } }
        )
    }

    private data class Binding(
        val section: SectionRef,
        val definitionRevision: Long,
        val lengthMeters: Double,
        val area: work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
    )

    companion object {
        fun from(publication: SimpleSignalsRuntimePublication): SectionOccupancyRuntime =
            from(publication, publication.protectionSignals.ruleRuntime, null)

        fun from(
            publication: SimpleSignalsRuntimePublication,
            signalPackRules: SignalPackEpochRuntime
        ): SectionOccupancyRuntime {
            require(signalPackRules.publication === publication) {
                "signal-pack rules target another SimpleSignals publication"
            }
            return from(publication, publication.protectionSignals.ruleRuntime, signalPackRules)
        }

        fun from(
            publication: SimpleSignalsRuntimePublication,
            signalRuleBindings: Collection<SectionSignalRuleBinding>
        ): SectionOccupancyRuntime = from(
            publication,
            signalRuleBindings.takeIf { it.isNotEmpty() }?.let { rules ->
                SectionSignalRuleRuntime.compile(
                    publication.fixedBlocks.snapshot(publication.topology).blocksById.keys.map {
                        SectionRef(SectionKind.FIXED_BLOCK, it)
                    } + publication.protectionSignals.routeGeometry.blocksById.keys.map {
                        SectionRef(SectionKind.PROTECTION_ROUTE, it)
                    },
                    java.util.List.copyOf(rules)
                )
            },
            null
        )

        private fun from(
            publication: SimpleSignalsRuntimePublication,
            signalRules: SectionSignalRuleRuntime?,
            signalPackRules: SignalPackEpochRuntime?
        ): SectionOccupancyRuntime {
            val blockSnapshot = publication.fixedBlocks.snapshot(publication.topology)
            val bindings = linkedMapOf<RailObjectRef, Binding>()
            blockSnapshot.blocksById.toSortedMap().forEach { (blockId, block) ->
                if (blockId in blockSnapshot.unavailableBlockIds) return@forEach
                val binding = Binding(
                    SectionRef(SectionKind.FIXED_BLOCK, blockId),
                    block.definition.revision,
                    block.lengthMeters,
                    block.occupancyArea
                )
                check(bindings.putIfAbsent(binding.area.areaId, binding) == null) {
                    "duplicate SimpleSignals occupancy area ${binding.area.areaId}"
                }
            }
            publication.protectionSignals.routeGeometry.blocksById.toSortedMap().forEach { (routeId, route) ->
                val original = route.occupancyArea
                val area = work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition.create(
                    protectionRouteAreaId(routeId),
                    original.definitionRevision, original.topologyRevision, original.intervals, original.boundaries)
                val binding = Binding(SectionRef(SectionKind.PROTECTION_ROUTE, routeId),
                    original.definitionRevision, route.lengthMeters, area)
                check(bindings.putIfAbsent(area.areaId, binding) == null)
            }
            val copiedBindings = java.util.Map.copyOf(bindings)
            return SectionOccupancyRuntime(copiedBindings, signalRules, signalPackRules,
                ProtectionRouteSelectionRuntime(publication),
                publication.protectionSignals, blockSnapshot, publication.directionPolicy, publication)
        }
    }
}
