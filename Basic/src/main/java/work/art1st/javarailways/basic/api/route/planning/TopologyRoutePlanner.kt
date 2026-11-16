package work.art1st.javarailways.basic.api.route.planning

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.route.TopologyRoutePlannerEngine
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

/**
 * A stable rail UUID and canonical A-to-B position in metres, independent of travel facing.
 * First-party planning value, without independent third-party API compatibility guarantees.
 * Construction rejects non-finite positions; existence and range are checked against a supplied
 * topology by the query methods. This value neither owns a live rail nor proves reachability.
 * @property railId stable persistent rail identity.
 * @property arcLengthInAbMeters position measured from the rail's A end in metres, independent of facing.
 */
data class OperationalRouteLocation(
    val railId: UUID,
    val arcLengthInAbMeters: Double
) {
    init {
        require(arcLengthInAbMeters.isFinite()) { "route location must be finite" }
    }
}

/**
 * Query-local forbidden position with an optional traversal facing on its own rail; null blocks
 * both directions. A blocker at the query's start or end physical position is ignored. Blockers
 * also apply to the interior of atomic replacement paths, without splitting those paths.
 * @property location canonical AB position to exclude from intermediate traversal.
 * @property facing excluded travel direction on this rail, or null for both directions.
 */
data class OperationalRouteBlocker(
    val location: OperationalRouteLocation,
    val facing: ViewDirection? = null
)

/**
 * One first-party query over a caller-captured immutable [topology]. Endpoints and blockers use
 * canonical AB metres; optional facing is interpreted on each endpoint's own rail.
 * Restrictions are reprojected against this root, even when their source revision differs.
 * [routeRevision] identifies the selected route, independently of the topology revision.
 *
 * With [requireUniquePath] false, selects the shortest permitted route by physical metres.
 * When true, proves a unique legal physical simple path, rejecting even a longer alternative;
 * [uniqueSearchLimit] bounds work for that proof, and exhaustion cannot return a partial success.
 * [blockedLocations] is retained as supplied, without a constructor copy. The caller must keep
 * that list stable during a query and must not mutate shared input concurrently with queries.
 *
 * Construction rejects a blank route ID, negative route revision or non-positive work budget.
 * Query options carry no world registration, resource ownership or persistence schema.
 * @property topology immutable normalized topology captured by the caller for this query.
 * @property restrictions neutral physical restrictions reprojected against the supplied topology.
 * @property start departure position in canonical AB metres.
 * @property end destination position in canonical AB metres.
 * @property routeId nonblank identity copied into the selected route.
 * @property routeRevision non-negative route revision, independent of topology revision.
 * @property startFacing optional departure direction on the start rail; null allows either direction.
 * @property endFacing optional arrival direction on the end rail; null allows either direction.
 * @property requireUniquePath whether to prove uniqueness of the physical simple path rather than choose a shortest route.
 * @property blockedLocations caller-owned intermediate exclusions, kept stable during the query.
 * @property uniqueSearchLimit positive work budget used only for exhaustive uniqueness proof.
 */
data class TopologyRoutePlanningInput(
    val topology: TopologyState,
    val restrictions: RoutePlanningRestrictions,
    val start: OperationalRouteLocation,
    val end: OperationalRouteLocation,
    val routeId: String,
    val routeRevision: Long,
    /** Restricts the first traversal to the authoritative physical facing when supplied. */
    val startFacing: ViewDirection? = null,
    /** Restricts arrival at the target to the caller's required physical facing when supplied. */
    val endFacing: ViewDirection? = null,
    /** Reject distinct legal physical paths, including longer alternatives. */
    val requireUniquePath: Boolean = false,
    /** Query-local forbidden positions and optional local facing, including macro interiors. */
    val blockedLocations: List<OperationalRouteBlocker> = emptyList(),
    /** Work limit for exhaustive uniqueness proof; exhaustion is a rejection, never success. */
    val uniqueSearchLimit: Int = 100_000
) {
    init {
        require(uniqueSearchLimit > 0) { "uniqueSearchLimit must be positive" }
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(routeRevision >= 0L) { "routeRevision must be non-negative" }
    }
}

/** Typed failure categories for a query; rejection never grants a partial route. */
enum class TopologyRoutePlanningRejection {
    /** Retained category; current queries reproject restrictions and do not emit this for revision mismatch. */
    STALE_RESTRICTIONS,
    /** Restriction intervals or physical paths cannot be compiled against the supplied topology. */
    INVALID_RESTRICTIONS,
    /** An endpoint/blocker is invalid, or the endpoints resolve to no positive-length route. */
    INVALID_LOCATION,
    /** More than one distinct legal physical simple path exists under the requested constraints. */
    AMBIGUOUS,
    /** The uniqueness proof exhausted its work budget before proving a result. */
    SEARCH_LIMIT_EXCEEDED,
    /** No legal path satisfies the supplied topology, restrictions, blockers and facing. */
    UNREACHABLE
}

/** Detached first-party query result; it does not authorize movement or reserve resources. */
sealed interface TopologyRoutePlanningResult {
    /**
     * Positive-length selected route with immutable view-local metre segments and the supplied
     * topology revision. Zero-length endpoint direction markers may occur among those segments.
     * @property route selected immutable traversal, without a resource grant or movement authority.
     */
    data class Planned(val route: RouteSnapshot) : TopologyRoutePlanningResult

    /**
     * Typed rejection with a nonblank diagnostic, without partial route segments.
     * @property reason category of the failed query.
     * @property detail nonblank diagnostic supplied by the planner.
     */
    data class Rejected(
        val reason: TopologyRoutePlanningRejection,
        val detail: String
    ) : TopologyRoutePlanningResult {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * First-party planning and validation queries, without independent third-party API compatibility
 * guarantees. Pure synchronous CPU work over captured values: safe on workers when inputs stay
 * stable, with no live world reads, plugin callbacks, registration, claims or topology mutation.
 * The caller owns world lifecycle/currentness checks before using a result and any later resource
 * admission. These queries introduce no persistence format or runtime registration lifecycle.
 *
 * Planning compiles one query-local restriction graph before searching by physical metre cost.
 * Normal planning has graph preparation and Dijkstra cost; uniqueness proof may explore
 * exponentially many physical simple paths and is bounded by its explicit work budget.
 */
object TopologyRoutePlanner {
    /**
     * Selects a route using [input]'s endpoint facing, restrictions, blockers and uniqueness policy.
     * Invalid locations/restrictions, unreachable targets, ambiguity and proof-budget exhaustion
     * return typed rejections with the existing diagnostic; source revision mismatch alone does
     * not reject. A successful snapshot has view-local metre segments, not canonical AB intervals.
     * @param input stable query inputs over one captured topology.
     * @return a selected route or a typed rejection, never a partial route.
     */
    @JvmStatic
    fun plan(input: TopologyRoutePlanningInput): TopologyRoutePlanningResult =
        TopologyRoutePlannerEngine.plan(input)

    /**
     * Tests whether [location] has one unambiguous rail projection within [topology]'s rail and
     * edge bounds. This checks only the point, not operating restrictions or route reachability.
     * @param topology caller-captured immutable topology to query.
     * @param location stable rail identity and canonical AB metre coordinate.
     * @return true only when the point resolves within the supplied topology.
     */
    @JvmStatic
    fun isValidLocation(topology: TopologyState, location: OperationalRouteLocation): Boolean =
        TopologyRoutePlannerEngine.isValidLocation(topology, location)

    /**
     * Reprojects the frozen view-local metre segments in [route] onto [topology], checking actual
     * rail intervals and legal connections. Returns null when valid or the existing diagnostic;
     * the source topology revision alone does not reject. Does not search or choose another path.
     * @param topology caller-captured immutable topology used for reprojection.
     * @param route already-selected route with view-local metre segments.
     * @return null if physically valid, otherwise the projection diagnostic.
     */
    @JvmStatic
    fun invalidRouteReason(topology: TopologyState, route: RouteSnapshot): String? =
        TopologyRoutePlannerEngine.invalidRouteReason(topology, route)

    /**
     * Compiles [restrictions]' stable spans and physical paths against [topology], reprojecting
     * rather than rejecting solely for a different source revision. Returns null when valid or
     * the existing diagnostic. Performs graph compilation without route search or world changes.
     * @param topology caller-captured immutable topology used for restriction compilation.
     * @param restrictions neutral spans and complete physical paths to reproject.
     * @return null if the restrictions compile, otherwise the compiler diagnostic.
     */
    @JvmStatic
    fun invalidRestrictionsReason(topology: TopologyState, restrictions: RoutePlanningRestrictions): String? =
        TopologyRoutePlannerEngine.invalidRestrictionsReason(topology, restrictions)
}
