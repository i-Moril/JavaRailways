package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.curve.Curve
import java.util.TreeSet
import java.util.UUID
import kotlin.math.max

/**
 * Immutable rail-indexed view of every train's swept occupancy in one coherent observation.
 *
 * Each rail is sorted by canonical AB start arc and carries a prefix maximum end arc. Queries use
 * that prefix to binary-search past entries that cannot overlap, then inspect only candidates whose
 * starts are within the requested interval. The retained [stamp] lets the admission owner reject an
 * index that is no longer the exact physical/topology epoch required by a request.
 */
internal class RailwayOccupancyIndex(observation: RailwayObservation) {
    val stamp: RailwayStateStamp = observation.stamp

    private val spansByRail: Map<UUID, RailSpanIndex> = observation.trains
        .flatMap { train ->
            train.sweptSpans.map { span ->
                IndexedSpan(train.trainId, span.railId, span.minArcAB, span.maxArcAB)
            }
        }
        .groupBy(IndexedSpan::railId)
        .mapValues { (_, spans) -> RailSpanIndex(spans) }

    /**
     * Returns stable-sorted IDs of trains occupying any [intervals], excluding [trainId].
     *
     * Closed intervals that touch within [Curve.TOLERANCE] count as occupied to fail safe.
     */
    fun blockingTrainIds(
        intervals: Collection<RailIntervalResource>,
        trainId: String
    ): List<String> {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        val blocking = TreeSet<String>()
        intervals.forEach { interval ->
            spansByRail[interval.railId]?.forEachOverlapping(
                interval.startAbMeters,
                interval.endAbMeters
            ) { span ->
                if (span.trainId != trainId) blocking += span.trainId
            }
        }
        return java.util.List.copyOf(blocking)
    }

    private data class IndexedSpan(
        val trainId: String,
        val railId: UUID,
        val startAbMeters: Double,
        val endAbMeters: Double
    )

    private class RailSpanIndex(spans: List<IndexedSpan>) {
        private val sorted = java.util.List.copyOf(
            spans.sortedWith(
                compareBy<IndexedSpan>(IndexedSpan::startAbMeters)
                    .thenBy(IndexedSpan::endAbMeters)
                    .thenBy(IndexedSpan::trainId)
            )
        )
        private val prefixMaximumEnd = DoubleArray(sorted.size).also { prefix ->
            sorted.indices.forEach { index ->
                prefix[index] = max(
                    sorted[index].endAbMeters,
                    if (index == 0) Double.NEGATIVE_INFINITY else prefix[index - 1]
                )
            }
        }

        fun forEachOverlapping(
            startAbMeters: Double,
            endAbMeters: Double,
            action: (IndexedSpan) -> Unit
        ) {
            val minimumEnd = startAbMeters - Curve.TOLERANCE
            var low = 0
            var high = sorted.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (prefixMaximumEnd[middle] >= minimumEnd) high = middle else low = middle + 1
            }

            var index = low
            while (index < sorted.size && sorted[index].startAbMeters <= endAbMeters + Curve.TOLERANCE) {
                if (sorted[index].endAbMeters >= minimumEnd) action(sorted[index])
                index++
            }
        }
    }
}
