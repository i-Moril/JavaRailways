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
        val blockId: String,
        val entryDirection: FixedBlockEntryDirection,
        val signal: FixedBlockSignalDefinition?
    )

    private data class Candidate(
        val blockId: String,
        val direction: FixedBlockEntryDirection
    )

    fun resolve(
        blocks: FixedBlockReadSnapshot,
        signals: Collection<FixedBlockSignalDefinition>,
        point: RailPositionSnapshot
    ): Resolved {
        val candidates = blocks.candidates(listOf(point.railId)).flatMap { block ->
            listOfNotNull(
                candidate(block.definition.blockId, FixedBlockEntryDirection.FORWARD,
                    block.traversals.first(), point),
                candidate(block.definition.blockId, FixedBlockEntryDirection.REVERSE,
                    block.reverseTraversals.first(), point)
            )
        }.distinct()
        require(candidates.isNotEmpty()) { "Click a fixed-block entrance while looking into the block" }
        require(candidates.size == 1) { "The selected boundary is shared by multiple fixed-block entrances" }
        val candidate = candidates.single()
        val matchingSignals = signals.filter {
            it.controlledBlockId == candidate.blockId && it.entryDirection == candidate.direction
        }
        require(matchingSignals.size <= 1) { "The selected fixed-block entrance has multiple primary signals" }
        return Resolved(candidate.blockId, candidate.direction, matchingSignals.singleOrNull())
    }

    fun resolve(
        blocks: FixedBlockReadSnapshot,
        signals: Collection<FixedBlockSignalDefinition>,
        binding: FixedBlockSignalWandBinding
    ): Resolved {
        require(binding.blockId in blocks.blocksById) { "The bound fixed block no longer exists" }
        val matchingSignals = signals.filter {
            it.controlledBlockId == binding.blockId && it.entryDirection == binding.entryDirection
        }
        require(matchingSignals.size <= 1) { "The bound fixed-block entrance has multiple primary signals" }
        return Resolved(binding.blockId, binding.entryDirection, matchingSignals.singleOrNull())
    }

    private fun candidate(
        blockId: String,
        direction: FixedBlockEntryDirection,
        entrance: FixedBlockTraversal,
        point: RailPositionSnapshot
    ): Candidate? = Candidate(blockId, direction).takeIf {
        entrance.railId == point.railId && entrance.facing == point.facing &&
            abs(entrance.travelStartAbMeters - point.arcLengthInAbMeters) <= SNAP_METERS
    }
}
