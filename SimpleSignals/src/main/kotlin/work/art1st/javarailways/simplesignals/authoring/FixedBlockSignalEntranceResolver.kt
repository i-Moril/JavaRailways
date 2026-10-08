package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import kotlin.math.abs

/** Resolves only an authored entrance; a click in the block body never guesses a controlled entry. */
internal object FixedBlockSignalEntranceResolver {
    const val SNAP_METERS = 0.75

    data class Resolved(
        val signal: FixedBlockSignalDefinition,
        val blockId: String,
        val entryDirection: FixedBlockEntryDirection
    )

    private data class Candidate(
        val blockId: String,
        val direction: FixedBlockEntryDirection,
        val bidirectional: Boolean
    )

    fun resolve(
        blocks: FixedBlockReadSnapshot,
        signals: Collection<FixedBlockSignalDefinition>,
        point: RailPositionSnapshot
    ): Resolved {
        val candidates = blocks.candidates(listOf(point.railId)).flatMap { block ->
            listOfNotNull(
                candidate(block.definition.blockId, FixedBlockEntryDirection.FORWARD, true,
                    block.traversals.first(), point),
                candidate(block.definition.blockId, FixedBlockEntryDirection.REVERSE,
                    block.definition.bidirectional, block.reverseTraversals.first(), point)
            )
        }.distinct()
        require(candidates.isNotEmpty()) { "Click a fixed-block entrance while looking into the block" }
        require(candidates.size == 1) { "The selected boundary is shared by multiple fixed-block entrances" }
        val candidate = candidates.single()
        require(candidate.bidirectional) { "The selected fixed block does not allow reverse entry" }
        val signal = signals.singleOrNull {
            it.controlledBlockId == candidate.blockId && it.entryDirection == candidate.direction
        } ?: throw IllegalArgumentException("This fixed-block entrance has no primary signal to update")
        return Resolved(signal, candidate.blockId, candidate.direction)
    }

    private fun candidate(
        blockId: String,
        direction: FixedBlockEntryDirection,
        bidirectional: Boolean,
        entrance: FixedBlockTraversal,
        point: RailPositionSnapshot
    ): Candidate? = Candidate(blockId, direction, bidirectional).takeIf {
        entrance.railId == point.railId && entrance.facing == point.facing &&
            abs(entrance.travelStartAbMeters - point.arcLengthInAbMeters) <= SNAP_METERS
    }
}
