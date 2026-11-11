package work.art1st.javarailways.basic.api.observation

import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import java.util.Collections
import java.util.TreeSet

/**
 * One stable boundary of a monitored rail area.
 *
 * [inwardPosition]'s rail and canonical AB arc locate the boundary. Its facing points from outside
 * to inside the area. A boundary at a connected rail endpoint should be placed on the area-side
 * rail so entering that rail crosses the boundary at the start of its traversal occurrence.
 *
 * @property boundaryId stable plugin-owned boundary identity within the world
 * @property inwardPosition rail-local position whose facing points into the area
 */
data class RailwayOccupancyBoundary(
    val boundaryId: RailObjectRef,
    val inwardPosition: RailPositionSnapshot
)

/**
 * Immutable plugin-owned geometry monitored by Basic without assigning block or interlocking policy.
 *
 * [intervals] describe the complete conservative area in per-rail canonical AB coordinates. They
 * do not create a route-wide AB direction. Every boundary must lie in one of those intervals and
 * its [RailwayOccupancyBoundary.inwardPosition] facing defines only that boundary's inward direction.
 *
 * @property areaId stable plugin-owned area identity within the world
 * @property definitionRevision plugin-owned monotonic revision for this area's definition
 * @property topologyRevision exact Core topology revision used to build this geometry
 * @property intervals complete per-rail canonical AB geometry of the area
 * @property boundaries directed boundaries associated with this area
 */
@ConsistentCopyVisibility
data class RailwayOccupancyAreaDefinition private constructor(
    val areaId: RailObjectRef,
    val definitionRevision: Long,
    val topologyRevision: Long,
    val intervals: List<RailInterval>,
    val boundaries: List<RailwayOccupancyBoundary>
) {
    init {
        require(definitionRevision >= 0L) { "definitionRevision must be non-negative" }
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
        require(intervals.isNotEmpty()) { "occupancy area must contain at least one rail interval" }
        require(boundaries.isNotEmpty()) { "occupancy area must contain at least one boundary" }
        require(boundaries.map(RailwayOccupancyBoundary::boundaryId).distinct().size == boundaries.size) {
            "boundary IDs must be unique within occupancy area $areaId"
        }
        val distinctBoundaryPositions = boundaries
            .map { it.inwardPosition.railId to it.inwardPosition.arcLengthInAbMeters }
            .distinct()
        require(distinctBoundaryPositions.size == boundaries.size) {
            "boundary positions must be unique within occupancy area $areaId"
        }
        boundaries.forEach { boundary ->
            val position = boundary.inwardPosition
            require(intervals.any { interval ->
                interval.railId == position.railId &&
                    position.arcLengthInAbMeters >= interval.minArcLengthInAbMeters &&
                    position.arcLengthInAbMeters <= interval.maxArcLengthInAbMeters
            }) {
                "boundary ${boundary.boundaryId} must lie within occupancy area $areaId"
            }
        }
    }

    /** Factory for validated immutable monitored-area definitions. */
    companion object {
        /** Validates and defensively copies one monitored-area definition. */
        @JvmStatic
        fun create(
            areaId: RailObjectRef,
            definitionRevision: Long,
            topologyRevision: Long,
            intervals: Collection<RailInterval>,
            boundaries: Collection<RailwayOccupancyBoundary>
        ): RailwayOccupancyAreaDefinition = RailwayOccupancyAreaDefinition(
            areaId = areaId,
            definitionRevision = definitionRevision,
            topologyRevision = topologyRevision,
            intervals = java.util.List.copyOf(intervals),
            boundaries = java.util.List.copyOf(boundaries)
        )
    }
}

enum class RailwayOccupancyCrossingKind {
    /** The train head crossed this boundary toward the area interior. */
    ENTER,

    /** The conservative train rear crossed this boundary away from the area interior. */
    LEFT
}

/**
 * One physical boundary crossing. Crossings for the same train retain movement order; ordering
 * between different trains is deterministic but does not claim a finer sub-epoch chronology.
 *
 * @property areaId stable identity of the crossed area
 * @property trainId stable identity of the train that crossed
 * @property boundaryId stable identity of the crossed boundary
 * @property kind whether the head entered or the conservative safety rear left
 */
data class RailwayOccupancyCrossing(
    val areaId: RailObjectRef,
    val trainId: String,
    val boundaryId: RailObjectRef,
    val kind: RailwayOccupancyCrossingKind
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
    }
}

/**
 * One changed or newly baselined monitored area after a completed railway epoch. Every entry in
 * [crossings] repeats this [areaId], so consumers may safely flatten batches without losing identity.
 *
 * When [available], [occupants] is the authoritative conservative placement at epoch end and
 * [crossings] contains only real ordered boundary movement. Train creation, removal or administrative
 * relocation can change that snapshot without fabricating an ENTER or LEFT event. When unavailable,
 * occupants retain the last known placement, crossings are empty, and even an empty occupant set
 * is not evidence of clear track.
 *
 * @property areaId stable identity of the changed area
 * @property definitionRevision exact plugin definition revision used for this update
 * @property occupants current conservative train IDs when available, otherwise retained last-known IDs
 * @property crossings real boundary crossings derived during this epoch
 */
class RailwayAreaOccupancyUpdate(
    val areaId: RailObjectRef,
    val definitionRevision: Long,
    occupants: Collection<String>,
    crossings: Collection<RailwayOccupancyCrossing>,
    /** False after a dependency edit; occupants are retained knowledge, never proof of clear track. */
    val available: Boolean = true
) {
    val occupants: Set<String> = Collections.unmodifiableSet(TreeSet(occupants))
    val crossings: List<RailwayOccupancyCrossing> = java.util.List.copyOf(crossings)

    init {
        require(definitionRevision >= 0L) { "definitionRevision must be non-negative" }
        require(this.occupants.none { it.isBlank() }) { "occupant train IDs must not be blank" }
        require(this.crossings.all { it.areaId == areaId }) {
            "every crossing must belong to occupancy area $areaId"
        }
    }
}

/**
 * Immutable changed-area batch derived synchronously from exactly one coherent observation.
 *
 * @property stamp exact world, physics epoch and topology revision consumed
 * @property updates baselines or areas whose occupants/crossings changed in this epoch
 */
class RailwayAreaOccupancyBatch(
    val stamp: RailwayStateStamp,
    updates: Collection<RailwayAreaOccupancyUpdate>
) {
    val updates: List<RailwayAreaOccupancyUpdate> = java.util.List.copyOf(updates)

    init {
        require(this.updates.map(RailwayAreaOccupancyUpdate::areaId).distinct().size == this.updates.size) {
            "occupancy batch must not contain duplicate area updates"
        }
    }
}

/**
 * Serialized world-local derivation of monitored-area occupancy and ordered boundary crossings.
 *
 * The caller must feed exactly one coherent [RailwayObservation] per consecutive physics epoch.
 * This engine owns no signalling, block, interlocking, resource-claim, or restriction policy.
 * A prepared replacement reports every definition on its first [consume]. Exact unchanged
 * geometry retains physical continuity; new geometry establishes a baseline without crossings.
 */
interface RailwayAreaOccupancyEngine {
    /**
     * Invalidates only areas indexed by the transaction's affected rails. Call at the world
     * transaction boundary, serialized with [consume]. Invalid areas retain their last occupants
     * and emit an unavailable update without fabricated crossings. A validated replacement may
     * restore them; unrelated geometry and crossing continuity survive a world snapshot change.
     */
    fun invalidateTopology(change: work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange): Set<RailObjectRef>

    /**
     * Compiles an independent replacement off the observation path without reading mutable state.
     * Exact area identity, intervals and directed boundary identities preserve physical continuity;
     * revision provenance alone does not reset it. Availability belongs to the replacement: an
     * affected area becomes available only after compilation incorporating the transaction.
     *
     * The caller serializes all [consume] calls across this engine and its replacements on one
     * world-local owner. Preparation may overlap an old consume; publication needs no wait. The
     * first replacement observation must follow the retained cells' world/epoch exactly. Engines
     * share only retained physical state, not callbacks, policy or a replacement history.
     */
    fun prepareReplacement(definitions: Collection<RailwayOccupancyAreaDefinition>): RailwayAreaOccupancyEngine

    /** Derives changed/baselined areas from exactly the next coherent observation. */
    fun consume(observation: RailwayObservation): RailwayAreaOccupancyBatch

    /** Factory for independent monitored-area engines. */
    companion object {
        /** Creates one independent serialized engine and defensively captures [definitions]. */
        @JvmStatic
        fun create(definitions: Collection<RailwayOccupancyAreaDefinition>): RailwayAreaOccupancyEngine =
            work.art1st.javarailways.basic.observation.RailwayAreaOccupancyTracker(definitions)
    }
}
