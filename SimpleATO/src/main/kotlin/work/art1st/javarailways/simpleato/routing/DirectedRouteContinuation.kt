package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import kotlin.math.abs
import kotlin.math.min

/** Exact occurrence-preserving path operations; no geometric search or topology traversal. */
internal object DirectedRouteContinuation {
    fun after(segments: List<RouteSegmentSnapshot>, distanceMeters: Double): List<RouteSegmentSnapshot> {
        var remaining = distanceMeters
        return buildList {
            for (segment in segments) {
                if (remaining >= segment.lengthMeters - EPSILON) {
                    remaining = (remaining - segment.lengthMeters).coerceAtLeast(0.0)
                } else {
                    add(if (remaining > EPSILON) segment.copy(
                        startArcLengthMeters = segment.startArcLengthMeters + remaining
                    ) else segment)
                    remaining = 0.0
                }
            }
        }
    }

    /** Returns only the new part of [candidate], rejecting divergent overlapping geometry. */
    fun unmatchedSuffix(
        existing: List<RouteSegmentSnapshot>,
        candidate: List<RouteSegmentSnapshot>
    ): List<RouteSegmentSnapshot> {
        var oldIndex = 0
        var newIndex = 0
        var oldOffset = 0.0
        var newOffset = 0.0
        var matched = 0.0
        while (oldIndex < existing.size && newIndex < candidate.size) {
            val old = existing[oldIndex]
            val next = candidate[newIndex]
            require(old.railId == next.railId && old.facing == next.facing &&
                abs(old.startArcLengthMeters + oldOffset - next.startArcLengthMeters - newOffset) <= EPSILON
            ) { "terminal route continuations disagree on their directed overlap" }
            val length = min(old.lengthMeters - oldOffset, next.lengthMeters - newOffset)
            matched += length
            oldOffset += length
            newOffset += length
            if (oldOffset >= old.lengthMeters - EPSILON) { oldIndex++; oldOffset = 0.0 }
            if (newOffset >= next.lengthMeters - EPSILON) { newIndex++; newOffset = 0.0 }
        }
        return after(candidate, matched)
    }

    private const val EPSILON = 1.0e-6
}
