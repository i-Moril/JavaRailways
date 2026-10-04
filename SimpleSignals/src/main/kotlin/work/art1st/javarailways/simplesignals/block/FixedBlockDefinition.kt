package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID
import java.util.UUID

/** One authored positive traversal belonging to a fixed block, in canonical rail AB metres. */
internal data class FixedBlockTraversal(
    val railId: UUID,
    val facing: ViewDirection,
    val lowerAbMeters: Double,
    val upperAbMeters: Double
) {
    init {
        require(lowerAbMeters.isFinite() && lowerAbMeters >= 0.0) {
            "lowerAbMeters must be finite and non-negative"
        }
        require(upperAbMeters.isFinite() && upperAbMeters > lowerAbMeters) {
            "upperAbMeters must be finite and greater than lowerAbMeters"
        }
    }

    val travelStartAbMeters: Double
        get() = if (facing == ViewDirection.AB) lowerAbMeters else upperAbMeters

    val travelEndAbMeters: Double
        get() = if (facing == ViewDirection.AB) upperAbMeters else lowerAbMeters

    val lengthMeters: Double
        get() = upperAbMeters - lowerAbMeters
}

/** Plugin-owned fixed block represented by one complete ordered physical traversal. */
@ConsistentCopyVisibility
internal data class FixedBlockDefinition private constructor(
    val blockId: String,
    val revision: Long,
    val traversals: List<FixedBlockTraversal>,
    val bidirectional: Boolean = false
) {
    constructor(
        blockId: String,
        revision: Long,
        traversals: Collection<FixedBlockTraversal>,
        bidirectional: Boolean = false
    ) : this(blockId, revision, java.util.List.copyOf(traversals), bidirectional)

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(blockId)) { "blockId must be namespaced" }
        require(revision >= 0L) { "fixed-block revision must be non-negative" }
        require(traversals.isNotEmpty()) { "a fixed block must contain at least one traversal" }
    }
}
