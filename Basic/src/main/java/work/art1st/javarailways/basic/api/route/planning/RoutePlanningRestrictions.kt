package work.art1st.javarailways.basic.api.route.planning

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.Collections
import java.util.UUID

/** One positive physical rail interval traversed in [facing], measured in canonical AB metres. */
data class DirectedRailSpan(
    /** Persistent UUID of the physical rail. */
    val railId: UUID,
    /** Direction in which the interval may be traversed. */
    val facing: ViewDirection,
    /** Inclusive lower interval bound measured from canonical endpoint A, in metres. */
    val lowerAbMeters: Double,
    /** Inclusive upper interval bound measured from canonical endpoint A, in metres. */
    val upperAbMeters: Double
) {
    init {
        require(lowerAbMeters.isFinite() && lowerAbMeters >= 0.0) {
            "lowerAbMeters must be finite and non-negative"
        }
        require(upperAbMeters.isFinite() && upperAbMeters > lowerAbMeters) {
            "upperAbMeters must be finite and greater than lowerAbMeters"
        }
    }

    /** Positive physical length of this interval, in metres. */
    val lengthMeters: Double
        get() = upperAbMeters - lowerAbMeters
}

/** One complete physical path that the route search must select atomically. */
@ConsistentCopyVisibility
data class PhysicalTraversalPath private constructor(
    /** Ordered positive spans from this path's entry to its exit. */
    val spans: List<DirectedRailSpan>
) {
    constructor(spans: Collection<DirectedRailSpan>) : this(immutableList(spans))

    init {
        require(spans.isNotEmpty()) { "a physical traversal path must contain at least one span" }
    }
}

/**
 * Replaces free traversal through [replacedTraversals] with the complete paths in [permittedPaths].
 *
 * The replacement is a neutral query-graph operation. It does not identify a protection region,
 * signal, interlocking or resource and therefore carries no signalling-system policy type.
 */
@ConsistentCopyVisibility
data class PathReplacement private constructor(
    /** Directed intervals whose ordinary query-graph edges are removed. */
    val replacedTraversals: List<DirectedRailSpan>,
    /** Complete paths that remain available through the removed intervals. */
    val permittedPaths: List<PhysicalTraversalPath>
) {
    constructor(
        replacedTraversals: Collection<DirectedRailSpan>,
        permittedPaths: Collection<PhysicalTraversalPath>
    ) : this(immutableList(replacedTraversals), immutableList(permittedPaths))

    init {
        require(replacedTraversals.isNotEmpty()) {
            "a path replacement must suppress at least one directed traversal"
        }
        require(permittedPaths.isNotEmpty()) {
            "a path replacement must permit at least one complete physical path"
        }
    }
}

/** Immutable restrictions using stable rail positions, tagged with their source topology revision. */
@ConsistentCopyVisibility
data class RoutePlanningRestrictions private constructor(
    /** Runtime topology revision against which every rail position was compiled. */
    val topologyRevision: Long,
    /** Directed intervals unavailable to both ordinary traversal and replacement paths. */
    val forbiddenTraversals: List<DirectedRailSpan>,
    /** Query-local replacements for portions of ordinary physical connectivity. */
    val pathReplacements: List<PathReplacement>
) {
    constructor(
        topologyRevision: Long,
        forbiddenTraversals: Collection<DirectedRailSpan> = emptyList(),
        pathReplacements: Collection<PathReplacement> = emptyList()
    ) : this(
        topologyRevision,
        immutableList(forbiddenTraversals),
        immutableList(pathReplacements)
    )

    init {
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
    }

    /** Factory and composition operations for topology-bound restriction sets. */
    companion object {
        /** Creates an empty restriction set bound to [topology]. */
        fun unrestricted(topology: TopologyState): RoutePlanningRestrictions =
            RoutePlanningRestrictions(topology.revision)

        /** Combines already compiled owner contributions without invoking their source callbacks. */
        fun combine(
            topology: TopologyState,
            contributions: Collection<RoutePlanningRestrictions>
        ): RoutePlanningRestrictions {
            return RoutePlanningRestrictions(
                topologyRevision = topology.revision,
                forbiddenTraversals = contributions.flatMap(RoutePlanningRestrictions::forbiddenTraversals),
                pathReplacements = contributions.flatMap(RoutePlanningRestrictions::pathReplacements)
            )
        }
    }
}

/**
 * Compiles one owner's private policy into neutral immutable restrictions before graph search.
 * Implementations must not retain or mutate the supplied topology root. The source is never
 * invoked from inside Dijkstra expansion.
 */
fun interface RoutePlanningRestrictionSource {
    /** Returns a contribution whose actual rail positions and connections are valid in [topology]. */
    fun restrictions(topology: TopologyState): RoutePlanningRestrictions
}

private fun <T> immutableList(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))
