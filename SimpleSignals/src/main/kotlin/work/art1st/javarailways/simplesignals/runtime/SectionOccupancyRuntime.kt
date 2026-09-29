package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossing
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.simpleato.api.signal.SignalEpochInput
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_SIGNAL_SOURCE_ID

internal const val SECTION_OCCUPANCY_SOURCE_ID = FIXED_BLOCK_SIGNAL_SOURCE_ID

internal enum class SectionKind {
    FIXED_BLOCK
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
 * Converts one compiled fixed-block publication into one host occupancy source.
 *
 * Basic performs the rail-local occupancy/crossing work once per physical epoch. This class only
 * maps changed area IDs back to SimpleSignals section IDs and atomically publishes immutable state;
 * it owns no tick loop, world lookup, aspect or restriction policy.
 */
internal class SectionOccupancyRuntime private constructor(
    private val bindingsByArea: Map<RailObjectRef, Binding>,
    private val signalRules: SectionSignalRuleRuntime?
) {
    private data class PublishedState(
        val occupancy: SectionOccupancySnapshot,
        val signals: SectionSignalSnapshot?
    )

    @Volatile
    private var latest: PublishedState? = null

    val source: SignalEpochSource? = if (bindingsByArea.isEmpty()) {
        null
    } else {
        SignalEpochSource(
            SECTION_OCCUPANCY_SOURCE_ID,
            bindingsByArea.entries.sortedBy { it.key.toString() }.map { it.value.area }
        ) { input -> update(input) }
    }

    fun snapshot(): SectionOccupancySnapshot? = latest?.occupancy

    fun signalSnapshot(): SectionSignalSnapshot? = latest?.signals

    fun signalFor(section: SectionRef): PublishedSectionSignal? {
        val signalId = signalRules?.signalIdFor(section) ?: return null
        val snapshot = checkNotNull(latest?.signals) {
            "signal $signalId has not received its first physical baseline"
        }
        return PublishedSectionSignal(snapshot.stamp, snapshot.states.getValue(signalId))
    }

    private fun update(input: SignalEpochInput): SignalEpochResult {
        val previous = latest
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
        val signals = signalRules?.evaluate(previous?.signals, occupancy)
        latest = PublishedState(occupancy, signals)
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
        fun from(
            publication: SimpleSignalsRuntimePublication,
            signalRuleBindings: Collection<SectionSignalRuleBinding>
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
            val copiedBindings = java.util.Map.copyOf(bindings)
            val signalRules = signalRuleBindings.takeIf { it.isNotEmpty() }?.let { rules ->
                SectionSignalRuleRuntime.compile(
                    copiedBindings.values.map(Binding::section),
                    java.util.List.copyOf(rules)
                )
            }
            return SectionOccupancyRuntime(copiedBindings, signalRules)
        }
    }
}
