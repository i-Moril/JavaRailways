package work.art1st.javarailways.core.api.physics.train

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot

/**
 * Immutable, implementation-independent train state published for planning and event payloads.
 *
 * The snapshot deliberately contains no live World, Rail, Car, or Train references.
 */
data class TrainSnapshot(
    val trainId: String,
    /** Physics epoch or other monotonic publisher revision. */
    val revision: Long,
    val speedMetersPerSecond: Double,
    val derailed: Boolean,
    val carCount: Int,
    val head: RailPositionSnapshot,
    val tail: RailPositionSnapshot,
    val derailReason: String? = null
) {
    init {
        require(trainId.isNotBlank()) { "TrainSnapshot trainId must not be blank" }
        require(revision >= 0L) { "TrainSnapshot revision must be non-negative" }
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0) {
            "TrainSnapshot speedMetersPerSecond must be finite and non-negative"
        }
        require(carCount >= 0) { "TrainSnapshot carCount must be non-negative" }
    }

    companion object {
        /**
         * Capture the currently published state of [train].
         *
         * Head and tail keep their own operating facings from the published frame; a single
         * caller-supplied facing cannot represent a train straddling a reversed rail seam.
         * A published frame is required; every state field comes from that one frame.
         */
        fun capture(train: Train, revision: Long): TrainSnapshot {
            val frame = requireNotNull(train.publishedFrame) { "TrainSnapshot requires a published train frame" }
            return TrainSnapshot(
                trainId = frame.trainId,
                revision = revision,
                speedMetersPerSecond = frame.speedMetersPerSecond,
                derailed = frame.derailed,
                carCount = frame.cars.size,
                head = frame.head,
                tail = frame.tail,
                derailReason = frame.derailReason
            )
        }
    }
}
