package work.art1st.mineRails.basic.api.ato.snapshot

import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import java.util.Collections
import java.util.UUID

/** Immutable segment of an already selected route, measured in the segment's facing direction. */
data class RouteSegmentSnapshot(
    val railId: UUID,
    val facing: ViewDirection,
    val startArcLengthMeters: Double,
    val endArcLengthMeters: Double
) {
    init {
        require(startArcLengthMeters.isFinite() && startArcLengthMeters >= 0.0) {
            "startArcLengthMeters must be finite and non-negative"
        }
        require(endArcLengthMeters.isFinite() && endArcLengthMeters >= startArcLengthMeters) {
            "endArcLengthMeters must be finite and not precede startArcLengthMeters"
        }
    }

    val lengthMeters: Double
        get() = endArcLengthMeters - startArcLengthMeters
}

/**
 * Immutable selected route used by one planning pass.
 *
 * [revision] changes whenever the selected path or turnout state relevant to the route changes.
 * The constructor defensively copies [segments].
 */
@ConsistentCopyVisibility
data class RouteSnapshot private constructor(
    val routeId: String,
    val revision: Long,
    val segments: List<RouteSegmentSnapshot>
) {
    constructor(
        routeId: String,
        revision: Long,
        segments: Collection<RouteSegmentSnapshot>
    ) : this(routeId, revision, immutableCopy(segments))

    init {
        require(routeId.isNotBlank()) { "RouteSnapshot routeId must not be blank" }
        require(revision >= 0L) { "RouteSnapshot revision must be non-negative" }
    }

    val totalLengthMeters: Double
        get() = segments.sumOf(RouteSegmentSnapshot::lengthMeters)

    private companion object {
        fun immutableCopy(source: Collection<RouteSegmentSnapshot>): List<RouteSegmentSnapshot> =
            Collections.unmodifiableList(ArrayList(source))
    }
}
