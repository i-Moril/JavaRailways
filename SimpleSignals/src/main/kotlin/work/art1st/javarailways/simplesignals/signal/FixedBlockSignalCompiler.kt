package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot
import work.art1st.javarailways.simplesignals.presentation.validatePlacement

/** Immutable fixed-block signal index compiled with one world-definition publication. */
internal class CompiledFixedBlockSignals private constructor(
    inspectionLeadTimeByEntry: Map<FixedBlockSignalEntry, Double>
) {
    private val inspectionLeadTimeByEntry = java.util.Map.copyOf(inspectionLeadTimeByEntry)

    fun inspectionLeadTimeSeconds(entry: FixedBlockSignalEntry): Double =
        inspectionLeadTimeByEntry[entry] ?: 0.0

    fun hasSignalForEntry(entry: FixedBlockSignalEntry): Boolean = inspectionLeadTimeByEntry.containsKey(entry)

    companion object {
        fun compile(
            definitions: Collection<FixedBlockSignalDefinition>,
            fixedBlocks: FixedBlockReadSnapshot,
            revalidatingTopology: Boolean = false
        ): CompiledFixedBlockSignals {
            val copied = java.util.List.copyOf(definitions.sortedBy { it.signalId })
            require(copied.map(FixedBlockSignalDefinition::signalId).distinct().size == copied.size) {
                "fixed-block signal IDs must be unique"
            }
            val entries = copied.mapNotNull(FixedBlockSignalDefinition::entry)
            require(entries.distinct().size == entries.size) {
                "fixed-block entries may define at most one signal"
            }
            copied.forEach { definition ->
                if (!revalidatingTopology) definition.placement?.validatePlacement(fixedBlocks.topology, definition.signalId)
                val blockId = definition.controlledBlockId ?: return@forEach
                val block = requireNotNull(fixedBlocks.blocksById[blockId]) {
                    "signal ${definition.signalId} controls unknown block ${definition.controlledBlockId}"
                }
                require(definition.entryDirection != FixedBlockEntryDirection.REVERSE || block.definition.bidirectional) {
                    "signal ${definition.signalId} cannot bind the reverse entry of a single-direction block"
                }
            }
            return CompiledFixedBlockSignals(copied.mapNotNull { definition ->
                definition.entry?.let { it to definition.inspectionLeadTimeSeconds }
            }.toMap())
        }
    }
}
