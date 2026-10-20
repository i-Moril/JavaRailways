package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID

/** One stable rail identity and immutable curve in caller-supplied batch order. */
data class PreparedRailCreation(
    val railId: UUID,
    val curve: PrimeCurve
)

/**
 * Off-thread result for one atomic rail-creation batch.
 *
 * The result belongs to the exact [CoreWorld] instance and [topologyRevision] observed while its
 * spatial candidates were captured. Ordinary live creation supplies random IDs; persisted snapshot
 * restoration may supply its stable IDs through Core's internal preparation entry. It contains no
 * executor or future. Callers must install it on the world owner thread; only changes to its actual spatial candidates or replacement sources invalidate the batch.
 */
class PreparedRailCreationBatch private constructor(
    @get:JvmSynthetic internal val ownerWorld: CoreWorld,
    @get:JvmSynthetic internal val topologyRoot: TopologyState,
    creations: List<PreparedRailCreation>,
    excludedExistingRailIds: Set<UUID>,
    sampledRails: List<AtGradeRailCrossingIndex.SampledRail>,
    crossings: List<AtGradeRailCrossing>,
    internal val candidateSnapshot: AtGradeRailCrossingIndex.CandidateSnapshot
) {
    val topologyRevision: Long = topologyRoot.revision
    val creations: List<PreparedRailCreation> = Collections.unmodifiableList(ArrayList(creations))
    val excludedExistingRailIds: Set<UUID> =
        Collections.unmodifiableSet(LinkedHashSet(excludedExistingRailIds))
    @get:JvmSynthetic
    internal val sampledRails: List<AtGradeRailCrossingIndex.SampledRail> =
        Collections.unmodifiableList(ArrayList(sampledRails))
    @get:JvmSynthetic
    internal val crossings: List<AtGradeRailCrossing> = Collections.unmodifiableList(ArrayList(crossings))

    init {
        require(this.creations.isNotEmpty()) { "a prepared rail batch must not be empty" }
        require(this.creations.map(PreparedRailCreation::railId).distinct().size == this.creations.size) {
            "prepared rail IDs must be unique"
        }
        require(sampledRails.map(AtGradeRailCrossingIndex.SampledRail::railId) ==
            this.creations.map(PreparedRailCreation::railId)
        ) { "sampled rail order must match prepared creation order" }
    }

    companion object {
        /** Core-only construction seam; callers cannot manufacture geometry that skipped preparation. */
        @JvmSynthetic
        internal fun create(
            ownerWorld: CoreWorld,
            topologyRoot: TopologyState,
            creations: List<PreparedRailCreation>,
            excludedExistingRailIds: Set<UUID>,
            sampledRails: List<AtGradeRailCrossingIndex.SampledRail>,
            crossings: List<AtGradeRailCrossing>,
            candidateSnapshot: AtGradeRailCrossingIndex.CandidateSnapshot
        ): PreparedRailCreationBatch = PreparedRailCreationBatch(
            ownerWorld,
            topologyRoot,
            creations,
            excludedExistingRailIds,
            sampledRails,
            crossings,
            candidateSnapshot
        )
    }
}

/** Stable reasons for rejecting a prepared batch before it mutates the world. */
enum class PreparedRailCreationRejectionReason {
    WORLD_MISMATCH,
    STALE_TOPOLOGY,
    DIRTY_OUTER_TRANSACTION,
    INVALID_BATCH
}

class PreparedRailCreationRejectedException(
    val reason: PreparedRailCreationRejectionReason,
    message: String
) : IllegalStateException(message)
