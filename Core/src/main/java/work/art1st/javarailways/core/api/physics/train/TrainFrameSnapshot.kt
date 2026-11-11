package work.art1st.javarailways.core.api.physics.train

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
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
 * One directed, ordered rail segment in an immutable train path.
 *
 * Both arc values use the rail's canonical AB coordinate in metres. [facing] records the local
 * direction of travel, so AB segments are non-decreasing and BA segments are non-increasing.
 * Repeated rail IDs are meaningful: list order preserves distinct occurrences along the movement
 * or placement path and must not be grouped or merged. [TrainFrameSnapshot.headTraversal] and
 * [TrainFrameSnapshot.tailTraversal] use this value for one-epoch movement evidence;
 * [TrainFrameSnapshot.bodyTraversal] uses it for the epoch-end tail-to-head placement.
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
 * mutable live train. [occupiedIntervals] is the conservative placement at epoch end, while
 * [sweptSpans] is the direction-independent envelope swept during the complete epoch.
 * [bodyTraversal] is the ordered epoch-end placement from the conservative safety rear to the head.
 * It may be empty only for an initial, not-yet-aligned construction frame; such a frame cannot be
 * used for automatic route admission.
 * [headTraversal] and [tailTraversal] are separate ordered movement evidence for this epoch and
 * must not be merged by rail identity. [tail], [bodyTraversal] and [tailTraversal] use the same
 * conservative safety rear: an implementation may publish a rear that lags the exact geometry, but
 * it must never lead the actual train tail in the direction of travel. Collection arguments are
 * defensively copied.
 *
 * @property occupiedIntervals conservative train placement on each rail at epoch end
 * @property bodyTraversal ordered epoch-end placement from the conservative tail to the head
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
    val occupiedIntervals: List<RailInterval>,
    val bodyTraversal: List<RailTraversalSegmentSnapshot>,
    val sweptSpans: List<SweptRailSpanSnapshot>,
    val headTraversal: List<RailTraversalSegmentSnapshot>,
    val tailTraversal: List<RailTraversalSegmentSnapshot>,
    val derailReason: String? = null,
    val composition: TrainComposition
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
            occupiedIntervals: Collection<RailInterval>,
            bodyTraversal: Collection<RailTraversalSegmentSnapshot>,
            sweptSpans: Collection<SweptRailSpanSnapshot>,
            headTraversal: Collection<RailTraversalSegmentSnapshot>,
            tailTraversal: Collection<RailTraversalSegmentSnapshot>,
            derailReason: String? = null,
            composition: TrainComposition = if (cars.isEmpty()) TrainComposition.create(emptyList(), emptyList())
                else TrainComposition.single(cars.map { it.carId }, trainId)
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
            occupiedIntervals = java.util.List.copyOf(occupiedIntervals),
            bodyTraversal = java.util.List.copyOf(bodyTraversal),
            sweptSpans = java.util.List.copyOf(sweptSpans),
            headTraversal = java.util.List.copyOf(headTraversal),
            tailTraversal = java.util.List.copyOf(tailTraversal),
            derailReason = derailReason,
            composition = composition.also { it.requireMatches(cars.map { car -> car.carId }) }
        )
    }
}
