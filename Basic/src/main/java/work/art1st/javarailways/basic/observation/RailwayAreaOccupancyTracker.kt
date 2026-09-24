package work.art1st.javarailways.basic.observation

import work.art1st.javarailways.basic.api.observation.RailwayAreaOccupancyBatch
import work.art1st.javarailways.basic.api.observation.RailwayAreaOccupancyUpdate
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossing
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossingKind
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Serialized, world-local monitored-area state derived from consecutive coherent observations.
 *
 * This owner has no signal aspect, block or interlocking policy. Definitions are compiled into
 * rail-local indexes; each observation performs local interval/boundary queries and publishes only
 * baselines, changed occupants or real boundary crossings.
 */
internal class RailwayAreaOccupancyTracker(
    definitions: Collection<RailwayOccupancyAreaDefinition>
) {
    private var compiled = CompiledDefinitions.create(definitions)
    private var lastStamp: RailwayStateStamp? = null
    private var occupantsByArea: Map<RailObjectRef, Set<String>> = emptyMap()

    /** Replaces geometry atomically; the next observation establishes a fresh baseline. */
    fun replaceDefinitions(definitions: Collection<RailwayOccupancyAreaDefinition>) {
        compiled = CompiledDefinitions.create(definitions)
        lastStamp = null
        occupantsByArea = emptyMap()
    }

    /** Consumes exactly the next completed physics epoch. */
    fun consume(observation: RailwayObservation): RailwayAreaOccupancyBatch {
        validateStamp(observation.stamp)
        val previousStamp = lastStamp
        if (previousStamp != null) {
            require(observation.stamp.worldId == previousStamp.worldId) {
                "occupancy observation world changed from ${previousStamp.worldId} to ${observation.stamp.worldId}"
            }
            require(observation.stamp.physicsEpoch == previousStamp.physicsEpoch + 1L) {
                "occupancy observation epoch must follow ${previousStamp.physicsEpoch}: ${observation.stamp.physicsEpoch}"
            }
        }
        val actualOccupants = compiled.currentOccupants(observation)
        if (previousStamp == null) return publishBaseline(observation, actualOccupants)

        val workingOccupants = compiled.definitions.associate { definition ->
            definition.areaId to occupantsByArea.getOrDefault(definition.areaId, emptySet()).toMutableSet()
        }
        val crossingsByArea = compiled.definitions.associate { definition ->
            definition.areaId to mutableListOf<RailwayOccupancyCrossing>()
        }

        // RailwayObservation already guarantees stable train-ID order.
        observation.trains.forEach { train ->
            compiled.crossingsFor(train.headTraversal, train.tailTraversal).forEach { candidate ->
                val occupants = workingOccupants.getValue(candidate.areaId)
                val changed = when (candidate.kind) {
                    RailwayOccupancyCrossingKind.ENTER -> occupants.add(train.trainId)
                    RailwayOccupancyCrossingKind.LEFT -> occupants.remove(train.trainId)
                }
                if (changed) {
                    crossingsByArea.getValue(candidate.areaId) += RailwayOccupancyCrossing(
                        areaId = candidate.areaId,
                        trainId = train.trainId,
                        boundaryId = candidate.boundaryId,
                        kind = candidate.kind
                    )
                }
            }
        }

        val updates = compiled.definitions.mapNotNull { definition ->
            val areaId = definition.areaId
            val previous = occupantsByArea.getOrDefault(areaId, emptySet())
            val current = actualOccupants.getValue(areaId)
            val crossings = crossingsByArea.getValue(areaId)
            if (previous == current && crossings.isEmpty()) {
                null
            } else {
                RailwayAreaOccupancyUpdate(
                    areaId,
                    definition.definitionRevision,
                    current,
                    crossings
                )
            }
        }

        occupantsByArea = actualOccupants
        lastStamp = observation.stamp
        return RailwayAreaOccupancyBatch(observation.stamp, updates)
    }

    private fun publishBaseline(
        observation: RailwayObservation,
        actualOccupants: Map<RailObjectRef, Set<String>>
    ): RailwayAreaOccupancyBatch {
        occupantsByArea = actualOccupants
        lastStamp = observation.stamp
        return RailwayAreaOccupancyBatch(
            observation.stamp,
            compiled.definitions.map { definition ->
                RailwayAreaOccupancyUpdate(
                    definition.areaId,
                    definition.definitionRevision,
                    actualOccupants.getValue(definition.areaId),
                    emptyList()
                )
            }
        )
    }

    private fun validateStamp(stamp: RailwayStateStamp) {
        require(compiled.definitions.all { it.topologyRevision == stamp.topologyRevision }) {
            "occupancy definitions do not belong to topology revision ${stamp.topologyRevision}"
        }
    }

    private data class CrossingCandidate(
        val areaId: RailObjectRef,
        val boundaryId: RailObjectRef,
        val kind: RailwayOccupancyCrossingKind,
        val progress: Double
    )

    private class CompiledDefinitions(
        val definitions: List<RailwayOccupancyAreaDefinition>,
        private val boundariesByRail: Map<UUID, List<IndexedBoundary>>,
        private val intervalIndex: AreaIntervalIndex
    ) {
        fun currentOccupants(observation: RailwayObservation): Map<RailObjectRef, Set<String>> {
            val occupants = definitions.associate { it.areaId to linkedSetOf<String>() }
            observation.trains.forEach { train ->
                train.occupiedIntervals.forEach { interval ->
                    intervalIndex.matchingAreas(interval).forEach { areaId ->
                        occupants.getValue(areaId) += train.trainId
                    }
                }
            }
            return occupants.mapValues { (_, trainIds) -> trainIds.toSet() }
        }

        fun crossingsFor(
            headTraversal: List<RailTraversalSegmentSnapshot>,
            tailTraversal: List<RailTraversalSegmentSnapshot>
        ): List<CrossingCandidate> = buildList {
            collectCrossings(headTraversal, RailwayOccupancyCrossingKind.ENTER, this)
            collectCrossings(tailTraversal, RailwayOccupancyCrossingKind.LEFT, this)
        }.sortedWith(
            compareBy<CrossingCandidate> { it.progress }
                .thenBy { if (it.kind == RailwayOccupancyCrossingKind.ENTER) 0 else 1 }
                .thenBy { it.areaId.toString() }
                .thenBy { it.boundaryId.toString() }
        )

        private fun collectCrossings(
            traversal: List<RailTraversalSegmentSnapshot>,
            kind: RailwayOccupancyCrossingKind,
            target: MutableList<CrossingCandidate>
        ) {
            val totalDistance = traversal.sumOf(::segmentLength)
            if (totalDistance <= 0.0) return
            var traversed = 0.0
            traversal.forEachIndexed { index, segment ->
                val segmentLength = segmentLength(segment)
                val candidates = boundariesByRail[segment.railId].orEmpty()
                if (candidates.isNotEmpty()) {
                    val low = min(segment.startArcLengthInAbMeters, segment.endArcLengthInAbMeters)
                    val high = max(segment.startArcLengthInAbMeters, segment.endArcLengthInAbMeters)
                    var candidateIndex = lowerBound(candidates, low)
                    while (candidateIndex < candidates.size) {
                        val candidate = candidates[candidateIndex]
                        val boundaryArc = candidate.boundary.inwardPosition.arcLengthInAbMeters
                        if (boundaryArc > high) break
                        if (crosses(segment, boundaryArc, index == traversal.lastIndex, kind) &&
                            directionMatches(segment, candidate.boundary, kind)
                        ) {
                            val offset = abs(boundaryArc - segment.startArcLengthInAbMeters)
                            target += CrossingCandidate(
                                candidate.areaId,
                                candidate.boundary.boundaryId,
                                kind,
                                (traversed + offset) / totalDistance
                            )
                        }
                        candidateIndex++
                    }
                }
                traversed += segmentLength
            }
        }

        private fun directionMatches(
            segment: RailTraversalSegmentSnapshot,
            boundary: RailwayOccupancyBoundary,
            kind: RailwayOccupancyCrossingKind
        ): Boolean = when (kind) {
            RailwayOccupancyCrossingKind.ENTER -> segment.facing == boundary.inwardPosition.facing
            RailwayOccupancyCrossingKind.LEFT -> segment.facing != boundary.inwardPosition.facing
        }

        private fun crosses(
            segment: RailTraversalSegmentSnapshot,
            boundaryArc: Double,
            finalSegment: Boolean,
            kind: RailwayOccupancyCrossingKind
        ): Boolean {
            val length = segmentLength(segment)
            val offset = abs(boundaryArc - segment.startArcLengthInAbMeters)
            if (offset > length) return false
            // The start belongs to this epoch. A head reaching an inward boundary occupies it, but
            // a safety rear resting exactly on an outward boundary has not cleared it. Intermediate
            // rail endpoints did continue across their boundary into another segment.
            return offset < length || !finalSegment || kind == RailwayOccupancyCrossingKind.ENTER
        }

        private fun segmentLength(segment: RailTraversalSegmentSnapshot): Double =
            abs(segment.endArcLengthInAbMeters - segment.startArcLengthInAbMeters)

        private fun lowerBound(candidates: List<IndexedBoundary>, arc: Double): Int {
            var low = 0
            var high = candidates.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (candidates[mid].boundary.inwardPosition.arcLengthInAbMeters < arc) {
                    low = mid + 1
                } else {
                    high = mid
                }
            }
            return low
        }

        companion object {
            fun create(definitions: Collection<RailwayOccupancyAreaDefinition>): CompiledDefinitions {
                val copied = definitions.sortedBy { it.areaId.toString() }
                require(copied.map(RailwayOccupancyAreaDefinition::areaId).distinct().size == copied.size) {
                    "occupancy area IDs must be unique"
                }
                require(copied.map(RailwayOccupancyAreaDefinition::topologyRevision).distinct().size <= 1) {
                    "occupancy areas must belong to one topology revision"
                }
                val boundaries = copied
                    .flatMap { definition ->
                        definition.boundaries.map { boundary -> IndexedBoundary(definition.areaId, boundary) }
                    }
                    .groupBy { it.boundary.inwardPosition.railId }
                    .mapValues { (_, values) ->
                        values.sortedWith(
                            compareBy<IndexedBoundary> { it.boundary.inwardPosition.arcLengthInAbMeters }
                                .thenBy { it.areaId.toString() }
                                .thenBy { it.boundary.boundaryId.toString() }
                        )
                    }
                val intervals = copied.flatMap { definition ->
                    definition.intervals.map { interval -> IndexedAreaInterval(definition.areaId, interval) }
                }
                return CompiledDefinitions(copied, boundaries, AreaIntervalIndex(intervals))
            }
        }
    }

    private data class IndexedBoundary(
        val areaId: RailObjectRef,
        val boundary: RailwayOccupancyBoundary
    )

    private data class IndexedAreaInterval(
        val areaId: RailObjectRef,
        val interval: RailInterval
    )

    /** Rail-local interval index using sorted starts plus prefix maximum ends. */
    private class AreaIntervalIndex(entries: Collection<IndexedAreaInterval>) {
        private data class RailEntries(
            val values: List<IndexedAreaInterval>,
            val prefixMaximumEnds: DoubleArray
        )

        private val byRail: Map<UUID, RailEntries> = entries.groupBy { it.interval.railId }.mapValues { (_, values) ->
            val sorted = values.sortedWith(
                compareBy<IndexedAreaInterval> { it.interval.minArcLengthInAbMeters }
                    .thenBy { it.interval.maxArcLengthInAbMeters }
                    .thenBy { it.areaId.toString() }
            )
            var maximum = Double.NEGATIVE_INFINITY
            RailEntries(
                sorted,
                DoubleArray(sorted.size) { index ->
                    maximum = max(maximum, sorted[index].interval.maxArcLengthInAbMeters)
                    maximum
                }
            )
        }

        fun matchingAreas(query: RailInterval): Set<RailObjectRef> {
            val entries = byRail[query.railId] ?: return emptySet()
            val first = lowerBoundPrefix(entries.prefixMaximumEnds, query.minArcLengthInAbMeters)
            val result = linkedSetOf<RailObjectRef>()
            var index = first
            while (index < entries.values.size) {
                val candidate = entries.values[index]
                if (candidate.interval.minArcLengthInAbMeters > query.maxArcLengthInAbMeters) break
                if (candidate.interval.maxArcLengthInAbMeters >= query.minArcLengthInAbMeters) {
                    result += candidate.areaId
                }
                index++
            }
            return result
        }

        private fun lowerBoundPrefix(prefix: DoubleArray, target: Double): Int {
            var low = 0
            var high = prefix.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (prefix[mid] < target) low = mid + 1 else high = mid
            }
            return low
        }
    }
}
