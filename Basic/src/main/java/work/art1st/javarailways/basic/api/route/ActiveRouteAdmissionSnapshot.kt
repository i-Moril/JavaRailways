package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner

/** Current host-owned phase of a route admission visible to read-only infrastructure consumers. */
enum class ActiveRouteAdmissionState {
    /** The requested route is retained while its complete resource set is prepared. */
    PREPARING,

    /** The route has a committed Basic grant at the snapshot's authority boundary. */
    GRANTED
}

/**
 * Immutable, process-local observation of one active route admission.
 *
 * [route] is the exact frozen route currently being prepared or held by the committed grant. A
 * preparing extension retains its previous [committedAuthority]: only the new requested suffix
 * remains ungranted. [releasedThroughRouteMeters] advances only after Basic successfully releases
 * a resource queue prefix. It is a proven lower bound on the train tail, not a statement that every
 * resource overlapping that earlier route range has been released; long held resources remain claimed.
 * [requestedAuthority] bounds the part of the frozen route addressed by this admission, so a
 * consumer must not treat later infrastructure on the same route as requested. Rejected and
 * otherwise terminal attempts are not represented by this value.
 *
 * This snapshot contains only stable values. It does not expose the admission handle, grant ID,
 * requester object, resource registry, train object, or mutable world state, and it has no
 * persistence or restart meaning.
 */
data class ActiveRouteAdmissionSnapshot(
    val owner: InfrastructureOwner,
    val trainId: String,
    val route: RouteSnapshot,
    val state: ActiveRouteAdmissionState,
    val requestedAuthority: RouteBoundary,
    val committedAuthority: RouteBoundary?,
    val releasedThroughRouteMeters: Double = 0.0
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(requestedAuthority.distanceFromRouteStartMeters <= route.totalLengthMeters) {
            "requested authority must not exceed the frozen route"
        }
        when (state) {
            ActiveRouteAdmissionState.PREPARING -> Unit

            ActiveRouteAdmissionState.GRANTED -> require(committedAuthority != null) {
                "a granted admission must expose committed authority"
            }
        }
        require(
            committedAuthority == null ||
                committedAuthority.distanceFromRouteStartMeters <=
                requestedAuthority.distanceFromRouteStartMeters
        ) { "committed authority must not exceed requested authority" }
        require(releasedThroughRouteMeters.isFinite() && releasedThroughRouteMeters >= 0.0) {
            "released route prefix must be finite and non-negative"
        }
    }
}

/** Latest value for one logical owner; null means that owner no longer has an active admission. */
data class ActiveRouteAdmissionUpdate(
    val owner: InfrastructureOwner,
    val current: ActiveRouteAdmissionSnapshot?
) {
    init { require(current == null || current.owner == owner) { "admission update owner mismatch" } }
}
