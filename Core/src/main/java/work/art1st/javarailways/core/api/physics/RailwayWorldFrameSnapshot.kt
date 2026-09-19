package work.art1st.javarailways.core.api.physics

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
 */
@ConsistentCopyVisibility
data class RailwayWorldFrameSnapshot private constructor(
    val physicsEpoch: Long,
    val topologyRevision: Long,
    val trains: List<TrainFrameSnapshot>
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
    }

    companion object {
        /** Validates and defensively copies one completed world publication. */
        @JvmStatic
        fun create(
            physicsEpoch: Long,
            topologyRevision: Long,
            trains: Collection<TrainFrameSnapshot>
        ): RailwayWorldFrameSnapshot = RailwayWorldFrameSnapshot(
            physicsEpoch = physicsEpoch,
            topologyRevision = topologyRevision,
            trains = java.util.List.copyOf(trains.sortedBy(TrainFrameSnapshot::trainId))
        )
    }
}
