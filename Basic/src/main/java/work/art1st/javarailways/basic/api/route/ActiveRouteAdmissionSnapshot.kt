package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner

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
 * preparing admission deliberately has no [committedAuthority], even when it extends an older
 * grant: consumers must keep the requested range restrictive until Basic publishes the new grant.
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
    val committedAuthority: RouteBoundary?
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(requestedAuthority.distanceFromRouteStartMeters <= route.totalLengthMeters) {
            "requested authority must not exceed the frozen route"
        }
        when (state) {
            ActiveRouteAdmissionState.PREPARING -> require(committedAuthority == null) {
                "a preparing admission must not expose committed authority"
            }

            ActiveRouteAdmissionState.GRANTED -> require(committedAuthority != null) {
                "a granted admission must expose committed authority"
            }
        }
        require(
            committedAuthority == null ||
                committedAuthority.distanceFromRouteStartMeters <=
                requestedAuthority.distanceFromRouteStartMeters
        ) { "committed authority must not exceed requested authority" }
    }
}
