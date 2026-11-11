package work.art1st.javarailways.core.api.physics.train

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot

/** Exact stable ends for a bounded, collision-driven approach in one runtime world. */
data class CouplingApproachRequest(
    val approachingTrainId: String,
    val approachingEnd: TrainEndSide,
    val stationaryTrainId: String,
    val stationaryEnd: TrainEndSide
) {
    init {
        require(approachingTrainId.isNotBlank() && stationaryTrainId.isNotBlank())
        require(approachingTrainId != stationaryTrainId)
    }
}

/**
 * Opaque host-issued geometry proof for one exact pair of published frames.
 *
 * [headTraversal] runs from the approaching head to the rigidly solved contact head, in canonical
 * AB metres. It is a bounded selected physical continuation, not permission to move or ignore
 * another train. Core's actual collision solver still decides whether a later contact can link.
 * Call the issuing world's validation method on its serialized owner before using this value;
 * any intervening frame or relevant topology/consist change makes it stale. No persistence form.
 */
class CouplingApproachProof internal constructor(
    val request: CouplingApproachRequest,
    val approachingFrame: TrainFrameSnapshot,
    val stationaryFrame: TrainFrameSnapshot,
    val contactHead: RailPositionSnapshot,
    headTraversal: Collection<RailTraversalSegmentSnapshot>,
    protectedTraversal: Collection<RailTraversalSegmentSnapshot>,
    internal val hostEvidence: Any
) {
    val headTraversal: List<RailTraversalSegmentSnapshot> = java.util.List.copyOf(headTraversal)
    /** Complete captured approaching body, selected bridge and oriented stationary body. */
    val protectedTraversal: List<RailTraversalSegmentSnapshot> = java.util.List.copyOf(protectedTraversal)
    val distanceToContactMeters: Double = this.headTraversal.sumOf {
        kotlin.math.abs(it.endArcLengthInAbMeters - it.startArcLengthInAbMeters)
    }
}

sealed interface CouplingApproachInspection {
    data class Accepted(val proof: CouplingApproachProof) : CouplingApproachInspection
    /** No movement, publication, claim or direction is changed on rejection. */
    data class Rejected(val reason: String) : CouplingApproachInspection {
        init { require(reason.isNotBlank()) }
    }
}
