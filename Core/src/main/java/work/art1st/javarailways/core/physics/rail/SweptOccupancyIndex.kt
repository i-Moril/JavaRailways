package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import java.util.TreeSet
import java.util.UUID
import kotlin.math.floor

/** A train's swept occupied interval expressed in the rail's canonical AB arc coordinates. */
data class SweptRailSpan(
    val trainId: String,
    val railId: UUID,
    val minArcAB: Double,
    val maxArcAB: Double,
    val collisionOnly: Boolean = false
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(minArcAB.isFinite() && maxArcAB.isFinite()) { "span arcs must be finite" }
        require(minArcAB <= maxArcAB) { "minArcAB must be <= maxArcAB" }
    }

    fun overlaps(other: SweptRailSpan, tolerance: Double = Curve.TOLERANCE): Boolean {
        if (railId != other.railId) return false
        return minArcAB <= other.maxArcAB + tolerance && other.minArcAB <= maxArcAB + tolerance
    }
}

data class SweptOccupancyQueryResult(
    val collidingTrainId: String?,
    val checkedCandidates: Int
)

/**
 * Immutable broad-phase index using fixed arc-length buckets on each rail.
 *
 * Construction is expected O(K*H), where K is span count and H is the average number of buckets
 * covered by a span. Query cost includes visited bucket references, candidate span collection and
 * deduplication, per-candidate span comparisons for body-overlap/separation ordering, sorting the
 * local candidates, and exact comparisons until the first accepted collision or exhaustion. Dense
 * buckets or trains with many spans increase that cost; there is no candidate-count cutoff.
 */
class SweptOccupancyIndex(
    spans: Collection<SweptRailSpan>,
    val bucketSize: Double = DEFAULT_BUCKET_SIZE
) {
    private data class BucketKey(val railId: UUID, val bucket: Long)

    private val buckets: Map<BucketKey, List<SweptRailSpan>>
    internal val indexedSpans: List<SweptRailSpan> = java.util.List.copyOf(
        spans.sortedWith(
            compareBy<SweptRailSpan> { it.railId }
                .thenBy { it.trainId }
                .thenBy { it.minArcAB }
                .thenBy { it.maxArcAB }
        )
    )
    val spanCount: Int = indexedSpans.size
    val bucketReferenceCount: Int

    init {
        require(bucketSize.isFinite() && bucketSize > 0.0) { "bucketSize must be finite and > 0" }
        val mutableBuckets = HashMap<BucketKey, MutableList<SweptRailSpan>>()
        var references = 0
        indexedSpans.forEach { span ->
            for (bucket in bucketRange(span)) {
                mutableBuckets.getOrPut(BucketKey(span.railId, bucket)) { ArrayList() }.add(span)
                references++
            }
        }
        buckets = mutableBuckets.mapValues { (_, values) -> values.toList() }
        bucketReferenceCount = references
    }

    fun queryFirstCollision(
        trainId: String,
        ownSpans: Collection<SweptRailSpan>,
        tolerance: Double = Curve.TOLERANCE,
        acceptConnector: (String) -> Boolean = { true },
        connectorPriority: (String) -> Double = { 0.0 }
    ): SweptOccupancyQueryResult {
        require(tolerance >= 0.0 && tolerance.isFinite()) { "tolerance must be finite and >= 0" }

        val candidateSpans = LinkedHashMap<String, MutableList<SweptRailSpan>>()
        for (ownSpan in ownSpans) {
            for (bucket in bucketRange(ownSpan, tolerance)) {
                for (candidate in buckets[BucketKey(ownSpan.railId, bucket)].orEmpty()) {
                    if (candidate.trainId == trainId) continue
                    candidateSpans.getOrPut(candidate.trainId) { ArrayList() }.add(candidate)
                }
            }
        }

        // Check likely physical collisions first. This controls query order only; every candidate
        // is still checked until a collision is accepted or the finite candidate set is exhausted.
        val orderedCandidates = candidateSpans.entries
            .map { (candidateTrainId, rawSpans) ->
                val spans = rawSpans.distinct()
                // Closed endpoint contact is only a candidate; it still needs exact acceptance.
                // Keep any positive body intersection unconditional, without a penetration gap.
                val bodyOverlap = ownSpans.any { own -> !own.collisionOnly && spans.any { other ->
                    !other.collisionOnly && own.railId == other.railId &&
                        minOf(own.maxArcAB, other.maxArcAB) > maxOf(own.minArcAB, other.minArcAB)
                } }
                CandidateTrain(
                    trainId = candidateTrainId,
                    spans = spans,
                    separation = minimumSeparation(ownSpans, spans),
                    bodyOverlap = bodyOverlap,
                    connectorPriority = if (bodyOverlap) 0.0 else connectorPriority(candidateTrainId)
                )
            }
            .sortedWith(compareBy<CandidateTrain> { !it.bodyOverlap }.thenBy { it.connectorPriority }
                .thenBy { it.separation }.thenBy { it.trainId })

        var checked = 0
        for (candidate in orderedCandidates) {
            checked++
            if (ownSpans.any { own -> candidate.spans.any { other -> own.overlaps(other, tolerance) } }) {
                if (!candidate.bodyOverlap && !acceptConnector(candidate.trainId)) continue
                return SweptOccupancyQueryResult(candidate.trainId, checked)
            }
        }
        return SweptOccupancyQueryResult(null, checked)
    }

    /** Returns every other train whose indexed span overlaps any requested interval. */
    fun blockingTrainIds(
        intervals: Collection<RailInterval>,
        excludedTrainId: String?,
        tolerance: Double = Curve.TOLERANCE
    ): List<String> {
        require(excludedTrainId == null || excludedTrainId.isNotBlank()) {
            "excludedTrainId must be null or non-blank"
        }
        require(tolerance >= 0.0 && tolerance.isFinite()) { "tolerance must be finite and >= 0" }

        val candidates = LinkedHashSet<SweptRailSpan>()
        intervals.forEach { interval ->
            for (bucket in bucketRange(interval, tolerance)) {
                buckets[BucketKey(interval.railId, bucket)]?.let(candidates::addAll)
            }
        }
        val blockers = TreeSet<String>()
        candidates.forEach { candidate ->
            if (!candidate.collisionOnly && candidate.trainId != excludedTrainId && intervals.any { interval ->
                    candidate.railId == interval.railId &&
                        candidate.minArcAB <= interval.maxArcLengthInAbMeters + tolerance &&
                        interval.minArcLengthInAbMeters <= candidate.maxArcAB + tolerance
                }
            ) {
                blockers += candidate.trainId
            }
        }
        return java.util.List.copyOf(blockers)
    }

    private fun minimumSeparation(ownSpans: Collection<SweptRailSpan>, candidates: Collection<SweptRailSpan>): Double {
        var minimum = Double.POSITIVE_INFINITY
        for (own in ownSpans) {
            for (candidate in candidates) {
                if (own.railId != candidate.railId) continue
                val separation = when {
                    own.maxArcAB < candidate.minArcAB -> candidate.minArcAB - own.maxArcAB
                    candidate.maxArcAB < own.minArcAB -> own.minArcAB - candidate.maxArcAB
                    else -> 0.0
                }
                if (separation < minimum) minimum = separation
            }
        }
        return minimum
    }

    private data class CandidateTrain(
        val trainId: String,
        val spans: List<SweptRailSpan>,
        val separation: Double,
        val bodyOverlap: Boolean,
        val connectorPriority: Double
    )

    private fun bucketRange(span: SweptRailSpan, tolerance: Double = 0.0): LongRange =
        bucketRange(span.minArcAB, span.maxArcAB, tolerance)

    private fun bucketRange(interval: RailInterval, tolerance: Double): LongRange =
        bucketRange(interval.minArcLengthInAbMeters, interval.maxArcLengthInAbMeters, tolerance)

    private fun bucketRange(minArcAB: Double, maxArcAB: Double, tolerance: Double): LongRange {
        val first = floor((minArcAB - tolerance) / bucketSize).toLong()
        val last = floor((maxArcAB + tolerance) / bucketSize).toLong()
        return first..last
    }

    companion object {
        const val DEFAULT_BUCKET_SIZE = 32.0

        val EMPTY = SweptOccupancyIndex(emptyList())
    }
}
