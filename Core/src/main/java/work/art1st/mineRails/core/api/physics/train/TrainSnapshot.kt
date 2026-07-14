package work.art1st.mineRails.core.api.physics.train

import work.art1st.mineRails.core.api.physics.rail.RailPositionSnapshot
import work.art1st.mineRails.core.api.physics.rail.ViewDirection

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
    val tail: RailPositionSnapshot
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
        /** Capture the currently published state of [train]. */
        fun capture(train: Train, revision: Long, facing: ViewDirection): TrainSnapshot = TrainSnapshot(
            trainId = train.id,
            revision = revision,
            speedMetersPerSecond = train.speed,
            derailed = train.isDerailed,
            carCount = train.length,
            head = RailPositionSnapshot.capture(train.headLocation, facing),
            tail = RailPositionSnapshot.capture(train.tailLocation, facing)
        )
    }
}
