package work.art1st.javarailways.basic.api.observation

import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/**
 * Value-only train state projected from one coherent Core world frame.
 *
 * Speed is metres per second. Head/tail positions preserve their local travel [RailPositionSnapshot.facing],
 * while every position and occupied/swept span arc remains in the rail's canonical AB coordinates,
 * in metres. Occupied intervals describe conservative epoch-end placement; swept spans describe
 * the complete swept occupancy published for the epoch.
 *
 * @property trainId stable technical train identity
 * @property trainName current player-facing train name
 * @property frameRevision per-train monotonic publication revision
 * @property speedMetersPerSecond published non-negative speed in metres per second
 * @property derailed whether the train was derailed at publication
 * @property reversed whether the train's consist order was reversed at publication
 * @property head immutable head position with local facing and canonical AB arc
 * @property tail immutable conservative safety-rear position with local facing and canonical AB arc
 * @property occupiedIntervals conservative placement at the end of this epoch
 * @property bodyTraversal ordered epoch-end placement from the conservative tail to the head
 * @property sweptSpans immutable occupied/swept intervals in canonical AB arcs
 * @property headTraversal ordered directed rail segments crossed by the head in this epoch
 * @property tailTraversal ordered directed rail segments crossed by the conservative safety rear
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
    val occupiedIntervals: List<RailInterval>,
    val bodyTraversal: List<RailTraversalSegmentSnapshot>,
    val sweptSpans: List<SweptRailSpanSnapshot>,
    val headTraversal: List<RailTraversalSegmentSnapshot>,
    val tailTraversal: List<RailTraversalSegmentSnapshot>
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
        /** Creates a validated value snapshot and defensively copies all collection values. */
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
            occupiedIntervals: Collection<RailInterval>,
            bodyTraversal: Collection<RailTraversalSegmentSnapshot>,
            sweptSpans: Collection<SweptRailSpanSnapshot>,
            headTraversal: Collection<RailTraversalSegmentSnapshot>,
            tailTraversal: Collection<RailTraversalSegmentSnapshot>
        ): RailwayTrainObservation = RailwayTrainObservation(
            trainId,
            trainName,
            frameRevision,
            speedMetersPerSecond,
            derailed,
            reversed,
            head,
            tail,
            java.util.List.copyOf(occupiedIntervals),
            java.util.List.copyOf(bodyTraversal),
            java.util.List.copyOf(sweptSpans),
            java.util.List.copyOf(headTraversal),
            java.util.List.copyOf(tailTraversal)
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
 * @property activeContinuations immutable live turnout selections from the same Core world frame
 */
@ConsistentCopyVisibility
data class RailwayObservation private constructor(
    val stamp: RailwayStateStamp,
    val trains: List<RailwayTrainObservation>,
    val occupancy: RailwayOccupancySnapshot,
    val activeContinuations: ActiveRailContinuationsSnapshot
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
        require(activeContinuations.topologyRevision == stamp.topologyRevision) {
            "active continuations must belong to topology revision ${stamp.topologyRevision}"
        }
    }

    companion object {
        /** Creates a defensive observation whose [occupancy] has the same epoch and train IDs. */
        @JvmStatic
        fun create(
            stamp: RailwayStateStamp,
            trains: Collection<RailwayTrainObservation>,
            occupancy: RailwayOccupancySnapshot,
            activeContinuations: ActiveRailContinuationsSnapshot
        ): RailwayObservation = RailwayObservation(
            stamp,
            java.util.List.copyOf(trains.sortedBy(RailwayTrainObservation::trainId)),
            occupancy,
            activeContinuations
        )

        /** Projects a coherent Core frame without retaining render data or mutable Core objects. */
        @JvmStatic
        fun fromCore(worldId: String, frame: RailwayWorldFrameSnapshot): RailwayObservation = create(
            RailwayStateStamp(worldId, frame.physicsEpoch, frame.topologyRevision),
            frame.trains.map(::projectTrain),
            frame.occupancy,
            frame.activeContinuations
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
                occupiedIntervals = frame.occupiedIntervals,
                bodyTraversal = frame.bodyTraversal,
                sweptSpans = frame.sweptSpans,
                headTraversal = frame.headTraversal,
                tailTraversal = frame.tailTraversal
            )
    }
}
