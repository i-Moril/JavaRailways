package work.art1st.javarailways.basic.route

import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.Collections
import java.util.UUID

/** Stable reference to one directed physical rail view. */
data class RailViewRef(
    val railId: UUID,
    val direction: ViewDirection
) {
    companion object {
        fun from(view: RailView): RailViewRef = RailViewRef(view.rail.id, view.viewDirection)
    }
}

/** One directed arc-length interval in an expanded operational route. */
data class RouteSegment(
    val railViewRef: RailViewRef,
    val startArcLength: Double,
    val endArcLength: Double
) {
    init {
        require(startArcLength.isFinite() && startArcLength >= 0.0)
        require(endArcLength.isFinite() && endArcLength >= startArcLength)
    }

    val length: Double
        get() = endArcLength - startArcLength
}

/** Immutable expanded traversal selected for one pair of operational waypoints. */
@ConsistentCopyVisibility
data class ExpandedRoute private constructor(
    val fromWayPointId: String,
    val toWayPointId: String,
    val segments: List<RouteSegment>
) {
    constructor(
        fromWayPointId: String,
        toWayPointId: String,
        segments: Collection<RouteSegment>
    ) : this(fromWayPointId, toWayPointId, immutableCopy(segments))

    init {
        require(fromWayPointId.isNotBlank() && toWayPointId.isNotBlank())
        require(segments.isNotEmpty()) { "ExpandedRoute must contain at least one segment" }
        require(segments.sumOf(RouteSegment::length).let { it.isFinite() && it > 0.0 }) {
            "ExpandedRoute total length must be finite and positive"
        }
    }

    val totalLength: Double
        get() = segments.sumOf(RouteSegment::length)

    private companion object {
        fun immutableCopy(source: Collection<RouteSegment>): List<RouteSegment> =
            Collections.unmodifiableList(ArrayList(source))
    }
}

sealed class RouteBuildResult {
    data class Success(val route: ExpandedRoute) : RouteBuildResult()
    data class Failure(val reason: String) : RouteBuildResult()
}
