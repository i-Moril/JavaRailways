package work.art1st.javarailways.basic.api.observation

import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/**
 * Value-only train state projected from one coherent Core world frame.
 *
 * Speed is metres per second. Head/tail positions preserve their local travel [RailPositionSnapshot.facing],
 * while every position and occupied/swept span arc remains in the rail's canonical AB coordinates,
 * in metres. The spans describe the complete swept occupancy published for this physics epoch.
 *
 * @property trainId stable technical train identity
 * @property trainName current player-facing train name
 * @property frameRevision per-train monotonic publication revision
 * @property speedMetersPerSecond published non-negative speed in metres per second
 * @property derailed whether the train was derailed at publication
 * @property reversed whether the train's consist order was reversed at publication
 * @property head immutable head position with local facing and canonical AB arc
 * @property tail immutable tail position with local facing and canonical AB arc
 * @property sweptSpans immutable occupied/swept intervals in canonical AB arcs
 */
@ConsistentCopyVisibility
data class RailwayTrainObservation private constructor(
    val trainId: String,
    val trainName: String,
    /** Per-train monotonic publication revision for stale-value rejection. */
    val frameRevision: Long,
    val speedMetersPerSecond: Double,
    val derailed: Boolean,
    val reversed: Boolean,
    val head: RailPositionSnapshot,
    val tail: RailPositionSnapshot,
    val sweptSpans: List<SweptRailSpanSnapshot>
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(trainName.isNotBlank()) { "trainName must not be blank" }
        require(frameRevision >= 0L) { "frameRevision must be non-negative" }
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0) {
            "speedMetersPerSecond must be finite and non-negative"
        }
    }

    companion object {
        /** Creates a validated value snapshot and defensively copies [sweptSpans]. */
        @JvmStatic
        fun create(
            trainId: String,
            trainName: String,
            frameRevision: Long,
            speedMetersPerSecond: Double,
            derailed: Boolean,
            reversed: Boolean,
            head: RailPositionSnapshot,
            tail: RailPositionSnapshot,
            sweptSpans: Collection<SweptRailSpanSnapshot>
        ): RailwayTrainObservation = RailwayTrainObservation(
            trainId,
            trainName,
            frameRevision,
            speedMetersPerSecond,
            derailed,
            reversed,
            head,
            tail,
            java.util.List.copyOf(sweptSpans)
        )
    }
}

/**
 * Immutable public observation for one world and one completed physics ticket.
 *
 * This snapshot contains no live World, Train, Rail, or render-car objects. [trains] is sorted by
 * stable train ID and defensively copied. All entries necessarily belong to the physics epoch in
 * [stamp].
 *
 * @property stamp coherent world identity, physics epoch, and topology revision
 * @property trains immutable train observations sorted by stable train ID
 * @property occupancy immutable Core occupancy query for the same physics epoch
 */
@ConsistentCopyVisibility
data class RailwayObservation private constructor(
    val stamp: RailwayStateStamp,
    val trains: List<RailwayTrainObservation>,
    val occupancy: RailwayOccupancySnapshot
) {
    init {
        require(trains.map(RailwayTrainObservation::trainId).toSet().size == trains.size) {
            "train IDs must be unique within one railway observation"
        }
        require(occupancy.physicsEpoch == stamp.physicsEpoch) {
            "occupancy snapshot must belong to physics epoch ${stamp.physicsEpoch}"
        }
        require(occupancy.trainIds == trains.map(RailwayTrainObservation::trainId).toSet()) {
            "occupancy snapshot must contain exactly the railway observation train IDs"
        }
    }

    companion object {
        /** Creates a defensive observation whose [occupancy] has the same epoch and train IDs. */
        @JvmStatic
        fun create(
            stamp: RailwayStateStamp,
            trains: Collection<RailwayTrainObservation>,
            occupancy: RailwayOccupancySnapshot
        ): RailwayObservation = RailwayObservation(
            stamp,
            java.util.List.copyOf(trains.sortedBy(RailwayTrainObservation::trainId)),
            occupancy
        )

        /** Projects a coherent Core frame without retaining render data or mutable Core objects. */
        @JvmStatic
        fun fromCore(worldId: String, frame: RailwayWorldFrameSnapshot): RailwayObservation = create(
            RailwayStateStamp(worldId, frame.physicsEpoch, frame.topologyRevision),
            frame.trains.map(::projectTrain),
            frame.occupancy
        )

        private fun projectTrain(frame: TrainFrameSnapshot): RailwayTrainObservation =
            RailwayTrainObservation.create(
                trainId = frame.trainId,
                trainName = frame.trainName,
                frameRevision = frame.revision,
                speedMetersPerSecond = frame.speedMetersPerSecond,
                derailed = frame.derailed,
                reversed = frame.reversed,
                head = frame.head,
                tail = frame.tail,
                sweptSpans = frame.sweptSpans
            )
    }
}
