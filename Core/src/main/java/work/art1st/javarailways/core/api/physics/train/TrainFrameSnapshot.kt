package work.art1st.javarailways.core.api.physics.train

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot
import java.util.UUID

/** Immutable render state for one car and all bogies owned by that car. */
@ConsistentCopyVisibility
data class CarFrameSnapshot private constructor(
    val carId: String,
    val lengthMeters: Double,
    val state: RenderableFrameSnapshot,
    val bogies: List<RenderableFrameSnapshot>
) {
    init {
        require(carId.isNotBlank()) { "CarFrameSnapshot carId must not be blank" }
        require(lengthMeters.isFinite() && lengthMeters > 0.0) { "CarFrameSnapshot lengthMeters must be finite and positive" }
    }

    companion object {
        fun create(
            carId: String,
            lengthMeters: Double,
            state: RenderableFrameSnapshot,
            bogies: Collection<RenderableFrameSnapshot>
        ): CarFrameSnapshot = CarFrameSnapshot(carId, lengthMeters, state, java.util.List.copyOf(bogies))
    }
}

/** Value-only copy of one occupied interval in canonical rail AB coordinates. */
data class SweptRailSpanSnapshot(
    val railId: UUID,
    val minArcAB: Double,
    val maxArcAB: Double
) {
    init {
        require(minArcAB.isFinite() && maxArcAB.isFinite()) { "span arcs must be finite" }
        require(minArcAB <= maxArcAB) { "minArcAB must be <= maxArcAB" }
    }
}

/**
 * One directed, ordered rail segment traversed by a train end during one physics epoch.
 *
 * Both arc values use the rail's canonical AB coordinate in metres. [facing] records the local
 * direction of travel, so AB segments are non-decreasing and BA segments are non-increasing.
 * Repeated rail IDs are meaningful: list order preserves distinct occurrences along the movement
 * path and must not be grouped or merged.
 *
 * @property railId stable identity of the traversed rail
 * @property facing local direction followed on that rail occurrence
 * @property startArcLengthInAbMeters traversal start in canonical AB metres
 * @property endArcLengthInAbMeters traversal end in canonical AB metres
 */
data class RailTraversalSegmentSnapshot(
    val railId: UUID,
    val facing: ViewDirection,
    val startArcLengthInAbMeters: Double,
    val endArcLengthInAbMeters: Double
) {
    init {
        require(startArcLengthInAbMeters.isFinite() && startArcLengthInAbMeters >= 0.0) {
            "startArcLengthInAbMeters must be finite and non-negative"
        }
        require(endArcLengthInAbMeters.isFinite() && endArcLengthInAbMeters >= 0.0) {
            "endArcLengthInAbMeters must be finite and non-negative"
        }
        when (facing) {
            ViewDirection.AB -> require(endArcLengthInAbMeters >= startArcLengthInAbMeters) {
                "AB traversal must not decrease in canonical AB coordinates"
            }
            ViewDirection.BA -> require(endArcLengthInAbMeters <= startArcLengthInAbMeters) {
                "BA traversal must not increase in canonical AB coordinates"
            }
        }
    }
}

/**
 * Complete value-only frame atomically published by one train after a physics epoch.
 *
 * Consumers must use only this frame for a render update and must not fill missing data from the
 * mutable live train. [sweptSpans] is a direction-independent occupancy envelope. [headTraversal]
 * and [tailTraversal] are separate ordered movement evidence for this epoch and must not be merged
 * by rail identity. Collection arguments are defensively copied before publication.
 */
@ConsistentCopyVisibility
data class TrainFrameSnapshot private constructor(
    val trainId: String,
    val trainName: String,
    /** Per-train monotonic publication revision used for stale-frame rejection. */
    val revision: Long,
    /** Physics epoch that produced this state, or zero for the initial frame. */
    val physicsEpoch: Long,
    val speedMetersPerSecond: Double,
    val derailed: Boolean,
    val reversed: Boolean,
    val cars: List<CarFrameSnapshot>,
    val head: RailPositionSnapshot,
    val tail: RailPositionSnapshot,
    val sweptSpans: List<SweptRailSpanSnapshot>,
    val headTraversal: List<RailTraversalSegmentSnapshot>,
    val tailTraversal: List<RailTraversalSegmentSnapshot>
) {
    init {
        require(trainId.isNotBlank()) { "TrainFrameSnapshot trainId must not be blank" }
        require(trainName.isNotBlank()) { "TrainFrameSnapshot trainName must not be blank" }
        require(revision >= 0L) { "TrainFrameSnapshot revision must be non-negative" }
        require(physicsEpoch >= 0L) { "TrainFrameSnapshot physicsEpoch must be non-negative" }
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0) {
            "TrainFrameSnapshot speedMetersPerSecond must be finite and non-negative"
        }
    }

    companion object {
        fun create(
            trainId: String,
            trainName: String,
            revision: Long,
            physicsEpoch: Long,
            speedMetersPerSecond: Double,
            derailed: Boolean,
            reversed: Boolean,
            cars: Collection<CarFrameSnapshot>,
            head: RailPositionSnapshot,
            tail: RailPositionSnapshot,
            sweptSpans: Collection<SweptRailSpanSnapshot>,
            headTraversal: Collection<RailTraversalSegmentSnapshot>,
            tailTraversal: Collection<RailTraversalSegmentSnapshot>
        ): TrainFrameSnapshot = TrainFrameSnapshot(
            trainId = trainId,
            trainName = trainName,
            revision = revision,
            physicsEpoch = physicsEpoch,
            speedMetersPerSecond = speedMetersPerSecond,
            derailed = derailed,
            reversed = reversed,
            cars = java.util.List.copyOf(cars),
            head = head,
            tail = tail,
            sweptSpans = java.util.List.copyOf(sweptSpans),
            headTraversal = java.util.List.copyOf(headTraversal),
            tailTraversal = java.util.List.copyOf(tailTraversal)
        )
    }
}
