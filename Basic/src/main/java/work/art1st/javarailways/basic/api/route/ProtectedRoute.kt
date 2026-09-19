package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlocker
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import java.util.Collections

/** Stable protection-region identity plus the authored revision used by one request. */
data class ProtectionRegionRef(
    val regionId: String,
    val revision: Long
) {
    init {
        require(NAMESPACED_ID.matches(regionId)) { "regionId must be namespaced" }
        require(revision >= 0L) { "region revision must be non-negative" }
    }
}

/**
 * One direction-aware boundary on a frozen route.
 *
 * [position] uses canonical rail AB metres while [routeDistanceMeters] is measured forward from
 * the beginning of the request's [RouteSnapshot]. Keeping both values explicit prevents callers
 * from treating a BA-local distance as a canonical AB coordinate.
 */
data class DirectedRouteBoundary(
    val boundaryId: String,
    val position: RailPositionSnapshot,
    val routeDistanceMeters: Double
) {
    init {
        require(NAMESPACED_ID.matches(boundaryId)) { "boundaryId must be namespaced" }
        require(routeDistanceMeters.isFinite() && routeDistanceMeters >= 0.0) {
            "routeDistanceMeters must be finite and non-negative"
        }
    }
}

/** Direction-aware point beyond the exit that the complete train must clear. */
data class ExitClearanceEnvelope(
    val clearUntil: DirectedRouteBoundary
)

/**
 * One authored protection region traversed from [entry] to [exit].
 *
 * Resources are deliberately absent from this caller-owned value. The host resolver derives the
 * complete footprint from the frozen route and authoritative region/topology state.
 */
data class ProtectedRouteWindow(
    val region: ProtectionRegionRef,
    val entry: DirectedRouteBoundary,
    val exit: DirectedRouteBoundary,
    val exitClearance: ExitClearanceEnvelope
) {
    init {
        require(entry.routeDistanceMeters < exit.routeDistanceMeters) {
            "protected route entry must precede exit"
        }
        require(exit.routeDistanceMeters < exitClearance.clearUntil.routeDistanceMeters) {
            "exit clearance must extend beyond the protected route exit"
        }
    }
}

/**
 * Immutable all-or-nothing request for zero or more ordered protection windows on one frozen path.
 *
 * Empty [windows] is valid only when the authoritative resolver confirms that [route] traverses no
 * controlled resource. Region extents may meet at one boundary but must not overlap. Exit-clearance
 * envelopes may extend into a later window; the resolver then combines every required resource
 * into one atomic footprint.
 */
@ConsistentCopyVisibility
data class CompositeProtectedRouteRequest private constructor(
    val requestId: String,
    val owner: InfrastructureOwner,
    val trainId: String,
    val topologyRevision: Long,
    val route: RouteSnapshot,
    val windows: List<ProtectedRouteWindow>
) {
    constructor(
        requestId: String,
        owner: InfrastructureOwner,
        trainId: String,
        topologyRevision: Long,
        route: RouteSnapshot,
        windows: Collection<ProtectedRouteWindow>
    ) : this(requestId, owner, trainId, topologyRevision, route, immutableCopy(windows))

    init {
        require(NAMESPACED_ID.matches(requestId)) { "requestId must be namespaced" }
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
        require(route.segments.isNotEmpty()) { "protected route must contain at least one segment" }
        require(windows.map(ProtectedRouteWindow::region).distinct().size == windows.size) {
            "a protection region may appear at most once in one request"
        }
        require(windows.zipWithNext().all { (current, next) ->
            current.exit.routeDistanceMeters <= next.entry.routeDistanceMeters
        }) { "protection-region extents must be ordered and non-overlapping" }
        require(windows.all { window ->
            window.exitClearance.clearUntil.routeDistanceMeters <= route.totalLengthMeters
        }) { "protection window or exit clearance exceeds the frozen route" }
    }

    private companion object {
        fun immutableCopy(source: Collection<ProtectedRouteWindow>): List<ProtectedRouteWindow> =
            Collections.unmodifiableList(ArrayList(source))
    }
}

/**
 * Authoritative host resolution of a protected-route request.
 *
 * Implementations validate region identity/revision, entry and exit positions, controlled-resource
 * coverage, and exit clearance against the request's frozen topology revision. They must not mutate
 * turnouts or acquire resources. Resolution is synchronous on the host's route-admission thread;
 * implementations must return promptly and must not wait for a later world tick.
 */
fun interface ProtectedRouteRequestResolver {
    fun resolve(request: CompositeProtectedRouteRequest): ProtectedRouteResolution
}

sealed interface ProtectedRouteResolution {
    /** The route traverses no controlled resource and therefore needs no claim. */
    data object NotRequired : ProtectedRouteResolution

    /** Complete footprint derived by the authoritative resolver. */
    data class Resolved(val footprint: InfrastructureFootprint) : ProtectedRouteResolution

    /** At least one controlled resource lacks complete entry-to-exit protection. */
    data class ProtectionRequired(val detail: String) : ProtectedRouteResolution {
        init {
            require(detail.isNotBlank())
        }
    }

    /** Region, boundary, frozen path, or exit-clearance data is internally inconsistent. */
    data class Invalid(val detail: String) : ProtectedRouteResolution {
        init {
            require(detail.isNotBlank())
        }
    }
}

enum class ProtectedRouteClaimStatus {
    /** The authoritative resolver found no controlled resource on this route. */
    NOT_REQUIRED,
    /** A new complete route footprint was acquired. */
    ACQUIRED,
    /** The expected older owner revision was atomically replaced. */
    REPLACED,
    /** The exact route owner revision was released. */
    RELEASED,
    /** The identical complete request was already held. */
    UNCHANGED,
    /** A controlled resource was not enclosed by a complete protection window. */
    PROTECTION_REQUIRED,
    /** The request did not use the current authoritative topology revision. */
    STALE_TOPOLOGY,
    /** Region, boundary, route, or replacement semantics were invalid. */
    INVALID_REQUEST,
    /** The authoritative resolver threw; no footprint was submitted. */
    RESOLVER_FAILED,
    /** The world-local claim registry is unavailable. */
    WORLD_UNAVAILABLE,
    /** At least one resolved footprint member no longer exists. */
    INVALID_RESOURCE,
    /** An authoritative stable-resource provider failed during validation. */
    RESOURCE_PROVIDER_FAILED,
    /** Platform owner or logical owner revision did not match. */
    OWNER_REVISION_CONFLICT,
    /** Another live route owns an overlapping resource. */
    RESOURCE_CONFLICT,
    /** The exact claim requested for replacement or release was absent. */
    NOT_FOUND,
    /** The world-local claim service has already closed. */
    SERVICE_CLOSED
}

/** Typed claim result; every denial leaves the protected-route registry unchanged. */
class ProtectedRouteClaimResult(
    val status: ProtectedRouteClaimStatus,
    blockers: Collection<InfrastructureBlocker> = emptyList(),
    val detail: String = ""
) {
    val blockers: List<InfrastructureBlocker> =
        Collections.unmodifiableList(ArrayList(blockers))

    val successful: Boolean
        get() = status in SUCCESS_STATUSES

    init {
        require(status == ProtectedRouteClaimStatus.RESOURCE_CONFLICT || this.blockers.isEmpty()) {
            "blockers are only valid for RESOURCE_CONFLICT"
        }
    }

    private companion object {
        val SUCCESS_STATUSES = setOf(
            ProtectedRouteClaimStatus.NOT_REQUIRED,
            ProtectedRouteClaimStatus.ACQUIRED,
            ProtectedRouteClaimStatus.REPLACED,
            ProtectedRouteClaimStatus.RELEASED,
            ProtectedRouteClaimStatus.UNCHANGED
        )
    }
}

private val NAMESPACED_ID = Regex("[a-z0-9_.-]+:[A-Za-z0-9_./-]+")
