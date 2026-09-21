package work.art1st.javarailways.core.api.physics.rail

import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.rail.SweptOccupancyIndex
import work.art1st.javarailways.core.physics.rail.SweptOccupancyQueryResult
import work.art1st.javarailways.core.physics.rail.SweptRailSpan

/**
 * Immutable query view over the swept train occupancy published for one completed physics epoch.
 *
 * Core builds this index once and shares the same instance with collision detection and downstream
 * consumers. Queries are in canonical rail AB metres and never retain live World, Train, or Rail
 * objects. The snapshot is safe for concurrent read-only queries, performs no blocking work, and
 * remains valid as long as its owning world frame is retained.
 *
 * @property physicsEpoch completed physics epoch that produced every indexed span
 * @property trainIds immutable stable IDs represented by the same world publication
 */
class RailwayOccupancySnapshot private constructor(
    val physicsEpoch: Long,
    val trainIds: Set<String>,
    private val index: SweptOccupancyIndex
) {
    internal val spanCount: Int
        get() = index.spanCount

    internal val bucketReferenceCount: Int
        get() = index.bucketReferenceCount

    /**
     * Returns every train overlapping any [intervals], optionally excluding one stable train ID.
     *
     * The returned list is immutable and sorted by train ID. Unlike collision broad-phase queries,
     * this safety query has no candidate cap: every indexed candidate is checked exactly. A blank
     * excluded ID is rejected instead of being interpreted as no exclusion.
     */
    fun blockingTrainIds(
        intervals: Collection<RailInterval>,
        excludedTrainId: String? = null
    ): List<String> = index.blockingTrainIds(intervals, excludedTrainId)

    internal fun queryFirstCollision(
        trainId: String,
        ownSpans: Collection<SweptRailSpan>,
        maxCandidates: Int = SweptOccupancyIndex.DEFAULT_MAX_CANDIDATES
    ): SweptOccupancyQueryResult = index.queryFirstCollision(trainId, ownSpans, maxCandidates)

    override fun equals(other: Any?): Boolean =
        other is RailwayOccupancySnapshot &&
            physicsEpoch == other.physicsEpoch &&
            trainIds == other.trainIds &&
            index.indexedSpans == other.index.indexedSpans

    override fun hashCode(): Int = 31 * (31 * physicsEpoch.hashCode() + trainIds.hashCode()) +
        index.indexedSpans.hashCode()

    companion object {
        /** Builds one immutable index from train frames belonging to [physicsEpoch]. */
        @JvmStatic
        fun create(
            physicsEpoch: Long,
            trains: Collection<TrainFrameSnapshot>
        ): RailwayOccupancySnapshot {
            val copiedTrains = java.util.List.copyOf(trains)
            require(physicsEpoch >= 0L) { "physicsEpoch must be non-negative" }
            require(copiedTrains.all { it.physicsEpoch == physicsEpoch }) {
                "all train frames must belong to physics epoch $physicsEpoch"
            }
            require(copiedTrains.map(TrainFrameSnapshot::trainId).toSet().size == copiedTrains.size) {
                "train IDs must be unique within one occupancy snapshot"
            }
            val spans = copiedTrains.flatMap { train ->
                train.sweptSpans.map { span ->
                    SweptRailSpan(train.trainId, span.railId, span.minArcAB, span.maxArcAB)
                }
            }
            return RailwayOccupancySnapshot(
                physicsEpoch,
                java.util.Collections.unmodifiableSet(
                    copiedTrains.mapTo(linkedSetOf(), TrainFrameSnapshot::trainId)
                ),
                SweptOccupancyIndex(spans)
            )
        }
    }
}
