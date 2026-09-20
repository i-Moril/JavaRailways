package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureBlocker
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import java.util.Collections

/** Stable protection-region identity plus the authored revision used by one request. */
data class ProtectionRegionRef(
    val regionId: String,
    val revision: Long
) {
    init {
        require(NAMESPACED_ROUTE_ID.matches(regionId)) { "regionId must be namespaced" }
        require(revision >= 0L) { "region revision must be non-negative" }
    }
}

/**
 * One direction-aware boundary on a frozen route.
 *
 * [position] uses canonical rail AB metres while [routeDistanceMeters] is measured forward from
 * the beginning of the request's [RouteSnapshot]. Keeping both values explicit prevents callers
 * from treating a BA-local distance as a canonical AB coordinate. [RailPositionSnapshot.facing]
 * is the train's travel direction while crossing this route point: it matches a region boundary's
 * inward facing at entry and is opposite that inward facing at exit.
 */
data class DirectedRouteBoundary(
    val boundaryId: String,
    val position: RailPositionSnapshot,
    val routeDistanceMeters: Double
) {
    init {
        require(NAMESPACED_ROUTE_ID.matches(boundaryId)) { "boundaryId must be namespaced" }
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
 * This caller-owned value identifies the selected route window. Basic validates it and collects
 * the one resource set used by the claim registry.
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
 * Immutable all-or-nothing request for at most one throat protection region on one frozen path.
 *
 * A null [window] is valid only when authoritative validation confirms that [route] traverses no
 * controlled resource. A route that cannot safely wait before another throat must use one larger
 * authored protection region; runtime requests never combine multiple regions.
 */
data class ProtectedRouteRequest(
    val requestId: String,
    val owner: InfrastructureOwner,
    val trainId: String,
    val route: RouteSnapshot,
    val window: ProtectedRouteWindow?
) {
    init {
        require(NAMESPACED_ROUTE_ID.matches(requestId)) { "requestId must be namespaced" }
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(route.segments.isNotEmpty()) { "protected route must contain at least one segment" }
        require(window == null || window.exitClearance.clearUntil.routeDistanceMeters <= route.totalLengthMeters) {
            "protection window or exit clearance exceeds the frozen route"
        }
    }
}

enum class ProtectedRouteClaimStatus {
    /** Authoritative validation found no controlled resource on this route. */
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
    /** Host route validation threw; no resource set was submitted. */
    VALIDATION_FAILED,
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

internal val NAMESPACED_ROUTE_ID = Regex("[a-z0-9_.-]+:[A-Za-z0-9_./-]+")
