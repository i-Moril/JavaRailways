package work.art1st.javarailways.core.api.physics.rail

import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.physics.rail.SweptOccupancyIndex
import work.art1st.javarailways.core.physics.rail.SweptOccupancyQueryResult
import work.art1st.javarailways.core.physics.rail.SweptRailSpan
import work.art1st.javarailways.core.physics.train.CollisionEnvelope

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
    private val index: SweptOccupancyIndex,
    internal val collisionEnvelopes: Map<String, CollisionEnvelope>,
    internal val collisionTopologyRevision: Long?,
    internal val collisionActiveContinuations: ActiveRailContinuationsSnapshot?
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
        acceptConnector: (String) -> Boolean = { true }
    ): SweptOccupancyQueryResult = index.queryFirstCollision(trainId,
        ownSpans + collisionEnvelopes[trainId]?.sweptSpans.orEmpty(),
        acceptConnector = acceptConnector, connectorPriority = { other ->
            val own = collisionEnvelopes[trainId]
            val incoming = collisionEnvelopes[other]
            if (own != null && incoming != null) own.nearestGap(incoming) else 0.0
        })

    override fun equals(other: Any?): Boolean =
        other is RailwayOccupancySnapshot &&
            physicsEpoch == other.physicsEpoch &&
            trainIds == other.trainIds &&
            index.indexedSpans == other.index.indexedSpans && collisionEnvelopes == other.collisionEnvelopes &&
            collisionTopologyRevision == other.collisionTopologyRevision &&
            collisionActiveContinuations == other.collisionActiveContinuations

    override fun hashCode(): Int = 31 * (31 * physicsEpoch.hashCode() + trainIds.hashCode()) +
        index.indexedSpans.hashCode() + 31 * collisionEnvelopes.hashCode() +
        collisionTopologyRevision.hashCode() + 31 * collisionActiveContinuations.hashCode()

    companion object {
        /** Builds one immutable index from train frames belonging to [physicsEpoch]. */
        @JvmStatic
        fun create(
            physicsEpoch: Long,
            trains: Collection<TrainFrameSnapshot>
        ): RailwayOccupancySnapshot = create(physicsEpoch, trains, emptyMap(), null, null)

        internal fun create(physicsEpoch: Long, trains: Collection<TrainFrameSnapshot>,
                            envelopes: Map<String, CollisionEnvelope>, topologyRevision: Long?,
                            activeContinuations: ActiveRailContinuationsSnapshot?): RailwayOccupancySnapshot {
            val copiedTrains = java.util.List.copyOf(trains)
            require(physicsEpoch >= 0L) { "physicsEpoch must be non-negative" }
            require(copiedTrains.all { it.physicsEpoch == physicsEpoch }) {
                "all train frames must belong to physics epoch $physicsEpoch"
            }
            require(copiedTrains.map(TrainFrameSnapshot::trainId).toSet().size == copiedTrains.size) {
                "train IDs must be unique within one occupancy snapshot"
            }
            val framesById = copiedTrains.associateBy(TrainFrameSnapshot::trainId)
            require(envelopes.all { (id, envelope) -> framesById[id] === envelope.frame }) {
                "collision envelopes must belong to the indexed source frames"
            }
            require(envelopes.values.all { it.activeContinuations === activeContinuations }) {
                "collision envelopes must use the occupancy selection snapshot"
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
                SweptOccupancyIndex(spans + envelopes.values.flatMap { it.sweptSpans }),
                java.util.Map.copyOf(envelopes), topologyRevision, activeContinuations
            )
        }
    }
}
