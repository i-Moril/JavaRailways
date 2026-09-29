package work.art1st.javarailways.core.api.physics

import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/**
 * Immutable value-only publication for one completed world physics epoch.
 *
 * [topologyRevision] is a runtime structural-topology counter, not a persistence schema or
 * infrastructure snapshot revision. Every [trains] entry was published for exactly
 * [physicsEpoch]. The list is defensively copied and sorted by stable train ID.
 *
 * @property physicsEpoch completed physics epoch represented by every train frame
 * @property topologyRevision runtime structural topology revision used by this publication
 * @property trains immutable train frames sorted by stable train ID
 * @property occupancy immutable swept-occupancy index built once from these train frames
 * @property activeContinuations immutable live turnout selections captured for the same world frame
 */
@ConsistentCopyVisibility
data class RailwayWorldFrameSnapshot private constructor(
    val physicsEpoch: Long,
    val topologyRevision: Long,
    val trains: List<TrainFrameSnapshot>,
    val occupancy: RailwayOccupancySnapshot,
    val activeContinuations: ActiveRailContinuationsSnapshot
) {
    init {
        require(physicsEpoch >= 0L) { "physicsEpoch must be non-negative" }
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
        require(trains.all { it.physicsEpoch == physicsEpoch }) {
            "all train frames must belong to physics epoch $physicsEpoch"
        }
        require(trains.map(TrainFrameSnapshot::trainId).toSet().size == trains.size) {
            "train IDs must be unique within one world frame"
        }
        require(occupancy.physicsEpoch == physicsEpoch) {
            "occupancy snapshot must belong to physics epoch $physicsEpoch"
        }
        require(occupancy.trainIds == trains.map(TrainFrameSnapshot::trainId).toSet()) {
            "occupancy snapshot must contain exactly the world frame train IDs"
        }
        require(activeContinuations.topologyRevision == topologyRevision) {
            "active continuations must belong to topology revision $topologyRevision"
        }
    }

    companion object {
        /** Validates and defensively copies one completed world publication. */
        @JvmStatic
        fun create(
            physicsEpoch: Long,
            topologyRevision: Long,
            trains: Collection<TrainFrameSnapshot>,
            activeContinuations: ActiveRailContinuationsSnapshot
        ): RailwayWorldFrameSnapshot {
            val copiedTrains = java.util.List.copyOf(trains.sortedBy(TrainFrameSnapshot::trainId))
            return RailwayWorldFrameSnapshot(
                physicsEpoch = physicsEpoch,
                topologyRevision = topologyRevision,
                trains = copiedTrains,
                occupancy = RailwayOccupancySnapshot.create(physicsEpoch, copiedTrains),
                activeContinuations = activeContinuations
            )
        }

        internal fun createWithOccupancy(
            physicsEpoch: Long,
            topologyRevision: Long,
            trains: Collection<TrainFrameSnapshot>,
            occupancy: RailwayOccupancySnapshot,
            activeContinuations: ActiveRailContinuationsSnapshot
        ): RailwayWorldFrameSnapshot = RailwayWorldFrameSnapshot(
            physicsEpoch = physicsEpoch,
            topologyRevision = topologyRevision,
            trains = java.util.List.copyOf(trains.sortedBy(TrainFrameSnapshot::trainId)),
            occupancy = occupancy,
            activeContinuations = activeContinuations
        )
    }
}
