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
    occupants: Collection<String>
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
    val protectionRouteSelection: ProtectionRouteSelectionRuntime,
    private val protectionSignals: work.art1st.javarailways.simplesignals.signal.CompiledProtectionSignals
) {
    private data class PublishedState(
        val occupancy: SectionOccupancySnapshot,
        val signals: SectionSignalSnapshot?,
        val selection: work.art1st.javarailways.simplesignals.signal.ProtectionRouteSelectionSnapshot?,
        val indicators: Map<String, work.art1st.javarailways.simplesignals.signal.RouteIndicatorState>
    )

    @Volatile
    private var latest: PublishedState? = null

    fun indicators() = latest?.indicators.orEmpty()
    fun selectionSnapshot() = latest?.selection

    val source: SignalEpochSource? = if (bindingsByArea.isEmpty() && protectionRouteSelection.source == null) {
        null
    } else {
        SignalEpochSource(
            SECTION_OCCUPANCY_SOURCE_ID,
            bindingsByArea.entries.sortedBy { it.key.toString() }.map { it.value.area },
            signalRules?.watchedContinuationEntries.orEmpty() + protectionRouteSelection.watchedContinuationEntries,
            protectionRouteSelection.watchedAdmissionRailIds
        ) { input -> update(input) }
    }

    fun snapshot(): SectionOccupancySnapshot? = latest?.occupancy

    val sources: List<SignalEpochSource> = listOfNotNull(source)

    fun signalSnapshot(): SectionSignalSnapshot? = latest?.signals

    fun signalFor(section: SectionRef): PublishedSectionSignal? {
        val signalId = signalRules?.signalIdFor(section) ?: return null
        val snapshot = latest?.signals ?: return null
        return PublishedSectionSignal(snapshot.stamp, snapshot.states.getValue(signalId))
    }

    private fun update(input: SignalEpochInput): SignalEpochResult {
        val previous = latest
        val oldSelection = protectionRouteSelection.snapshot()
        protectionRouteSelection.update(input)
        val selection = protectionRouteSelection.snapshot()
        val changedEntries = if (selection !== oldSelection) selection?.changedEntries.orEmpty() else emptySet()
        var indicatorStates = previous?.indicators.orEmpty()
        if (changedEntries.isNotEmpty()) {
            val next = indicatorStates.toMutableMap()
            changedEntries.flatMap { protectionSignals.indicatorsByEntry[it].orEmpty() }.forEach { indicator ->
                val selected = selection!!.selections.getValue(indicator.entryBoundaryId)
                next[indicator.indicatorId] = work.art1st.javarailways.simplesignals.signal.RouteIndicatorState(
                    selected, selected.routeId?.takeIf { selected.available }?.let(indicator.modelVariantsByRouteId::getValue)
                        ?: indicator.noRouteModelVariant)
            }
            indicatorStates = java.util.Map.copyOf(next)
        }
        val observations = previous?.occupancy?.sections?.toMutableMap() ?: linkedMapOf()
        val changed = linkedSetOf<SectionRef>()
        val crossings = linkedMapOf<SectionRef, List<RailwayOccupancyCrossing>>()
        input.areaUpdates.forEach { update ->
            val binding = checkNotNull(bindingsByArea[update.areaId]) {
                "unregistered occupancy area ${update.areaId} reached $SECTION_OCCUPANCY_SOURCE_ID"
            }
            require(update.definitionRevision == binding.definitionRevision) {
                "occupancy area ${update.areaId} revision ${update.definitionRevision} does not match " +
                    "compiled section revision ${binding.definitionRevision}"
            }
            observations[binding.section] = SectionOccupancyObservation(
                binding.section,
                binding.definitionRevision,
                binding.lengthMeters,
                update.occupants
            )
            changed += binding.section
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
        val signals = signalRules?.evaluate(
            previous?.signals,
            occupancy,
            input.observation.activeContinuations,
            input.continuationUpdates.map { it.entry },
            protectionRouteSelection.evaluatedEntryIds
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
        latest = PublishedState(occupancy, signals, selection, indicatorStates)
        return SignalEpochResult(
            emptyList(),
            signals?.let(signalRules::invalidatedDeclarationIds).orEmpty()
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
            from(publication, publication.protectionSignals.ruleRuntime)

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
            }
        )

        private fun from(
            publication: SimpleSignalsRuntimePublication,
            signalRules: SectionSignalRuleRuntime?
        ): SectionOccupancyRuntime {
            val blockSnapshot = publication.fixedBlocks.snapshot(publication.topology)
            val bindings = linkedMapOf<RailObjectRef, Binding>()
            blockSnapshot.blocksById.toSortedMap().forEach { (blockId, block) ->
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
            return SectionOccupancyRuntime(copiedBindings, signalRules, ProtectionRouteSelectionRuntime(publication),
                publication.protectionSignals)
        }
    }
}
