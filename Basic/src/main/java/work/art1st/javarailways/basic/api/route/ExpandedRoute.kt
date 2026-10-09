package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.Collections
import java.util.UUID

/**
 * Detached reference to a directed physical rail in the consumer's world.
 * [railId] is that world's stable rail UUID; [direction] selects AB or BA view coordinates.
 * This value does not retain or resolve a live rail and carries no topology revision.
 *
 * @property railId Stable UUID of the physical rail in the consumer's intended world.
 * @property direction Directed view whose local coordinates are used by the traversal.
 */
data class RailViewRef(
    val railId: UUID,
    val direction: ViewDirection
) {
    companion object {
        /**
         * Captures identity and direction without retaining [view] or acquiring resources.
         * The caller must obey the supplied live view's world-thread access rules.
         *
         * @param view Live directed rail view belonging to the caller's intended world.
         * @return Detached identity and direction, without a topology revision.
         */
        fun from(view: RailView): RailViewRef = RailViewRef(view.rail.id, view.viewDirection)
    }
}

/**
 * One traversal interval in [railViewRef]'s view-local metres, increasing in travel direction.
 * For BA these are BA-view distances, not canonical AB coordinates. Construction rejects
 * non-finite, negative or descending bounds; a zero-length endpoint marker is permitted.
 * This detached value does not check the referenced rail's existence, length or connectivity.
 *
 * @property railViewRef Stable directed rail identity in the consumer's intended world.
 * @property startArcLength Inclusive start in view-local metres, finite and non-negative.
 * @property endArcLength Inclusive end in view-local metres, finite and at least the start.
 */
data class RouteSegment(
    val railViewRef: RailViewRef,
    val startArcLength: Double,
    val endArcLength: Double
) {
    init {
        require(startArcLength.isFinite() && startArcLength >= 0.0)
        require(endArcLength.isFinite() && endArcLength >= startArcLength)
    }

    /** Interval length in metres. */
    val length: Double
        get() = endArcLength - startArcLength
}

/**
 * Detached, ordered candidate traversal between two world-local WayPoint identities.
 * Construction copies [segments] into an unmodifiable list and rejects blank endpoint IDs,
 * empty traversal and non-finite or non-positive total length. Repeated rails are allowed.
 *
 * Safe to share as a value across threads; construction performs no world access or I/O.
 * This is neither a resource grant nor proof of physical connectivity. It carries no world
 * or topology revision: the consumer must bind and validate it against the intended world
 * and topology before admission. It is a runtime value, not a persistence document.
 *
 * @property fromWayPointId Nonblank origin WayPoint identity in the consumer's intended world.
 * @property toWayPointId Nonblank destination WayPoint identity in the same world.
 * @property segments Detached, unmodifiable traversal intervals in travel order.
 */
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

    /** Sum of segment lengths in metres, in traversal order. */
    val totalLength: Double
        get() = segments.sumOf(RouteSegment::length)

    private companion object {
        fun immutableCopy(source: Collection<RouteSegment>): List<RouteSegment> =
            Collections.unmodifiableList(ArrayList(source))
    }
}
