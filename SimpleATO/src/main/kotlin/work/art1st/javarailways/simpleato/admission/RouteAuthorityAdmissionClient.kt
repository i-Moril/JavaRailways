package work.art1st.javarailways.simpleato.admission

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.simpleato.api.restriction.MovementAuthorityRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionResult

/**
 * Per-train SimpleATO client for one Basic route-admission lifecycle at a time.
 *
 * The client never compiles or inspects infrastructure resources. It submits the selected frozen
 * route unchanged, owns at most one pending handle, and publishes only the authority boundary from
 * a committed Basic grant. Before the first grant, zero route metres is the fail-closed authority.
 */
internal class RouteAuthorityAdmissionClient(
    private val trainId: String,
    private val requestId: String,
    private val owner: InfrastructureOwner,
    private val admissionService: RouteResourceAdmissionService,
    private val restrictionPublisher: TrainRestrictionPublisher
) {
    private var active: ActiveRoute? = null
    private var restrictionRevision = -1L

    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
    }

    /** Requests first authority or a farther boundary on the same frozen route. */
    @MainThread
    fun request(
        selection: FrozenRouteSelectionResult.Selected,
        desiredAuthority: RouteBoundary
    ): RouteAdmissionResult {
        require(selection.observationStamp.topologyRevision == selection.route.topologyRevision) {
            "selection observation and route must use the same topology revision"
        }
        val current = active
        if (selection.trainId != trainId) {
            if (current == null) {
                ActiveRoute(selection).also {
                    publishAuthority(it, selection.observationStamp, 0.0)
                    active = it
                }
            }
            return rejected(
                RouteAdmissionFailureCode.TRAIN_NOT_FOUND,
                "selected route belongs to train ${selection.trainId}, not $trainId"
            )
        }

        val route = if (current == null) {
            ActiveRoute(selection).also {
                publishAuthority(it, selection.observationStamp, 0.0)
                active = it
            }
        } else {
            if (current.unsafeFailure) {
                return rejected(
                    RouteAdmissionFailureCode.WORLD_STALLED,
                    "the current frozen route is latched after an unsafe admission failure"
                )
            }
            if (!current.matches(selection)) {
                return rejected(
                    RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                    "a different frozen route requires explicit lifecycle end before admission"
                )
            }
            if (current.pending != null) {
                return rejected(
                    RouteAdmissionFailureCode.REQUEST_BUSY,
                    "the current frozen route already has a pending admission"
                )
            }
            current
        }

        val intent = RouteResourceIntent(
            requestId = requestId,
            owner = owner,
            trainId = trainId,
            observationStamp = selection.observationStamp,
            route = selection.route,
            desiredAuthority = desiredAuthority
        )
        return consume(route, admissionService.request(intent))
    }

    /** Progresses the one pending Basic attempt without resubmitting its route intent. */
    @MainThread
    fun progress(): RouteAdmissionResult {
        val route = active
            ?: return rejected(RouteAdmissionFailureCode.REQUEST_BUSY, "no active frozen route")
        val handle = route.pending
            ?: return rejected(RouteAdmissionFailureCode.REQUEST_BUSY, "no admission is pending")
        return consume(route, admissionService.progress(handle))
    }

    /**
     * Ends the active route only after Basic confirms that its committed resources are clear.
     *
     * A pending or unsafe first request cannot be hidden by clearing the SimpleATO restriction.
     */
    @MainThread
    fun end(): Boolean {
        val route = active ?: return true
        if (route.pending != null || route.unsafeFailure) return false

        val grant = route.committedGrant
        val withdrawalStamp = grant?.observationStamp ?: route.selection.observationStamp
        if (grant != null) {
            if (!admissionService.endGrant(grant.grantId)) return false
            route.committedGrant = null
        }

        publish(route, emptyList(), withdrawalStamp)
        active = null
        return true
    }

    private fun consume(route: ActiveRoute, result: RouteAdmissionResult): RouteAdmissionResult {
        return when (result) {
            is RouteAdmissionResult.Granted -> {
                validateGrant(route, result.grant)
                route.pending = null
                route.committedGrant = result.grant
                publishAuthority(
                    route,
                    result.grant.observationStamp,
                    result.grant.committedAuthority.distanceFromRouteStartMeters
                )
                result
            }

            is RouteAdmissionResult.Pending -> {
                validatePending(route, result)
                route.pending = result.handle
                result
            }

            is RouteAdmissionResult.Rejected -> {
                route.pending = null
                if (result.failure.code == RouteAdmissionFailureCode.WORLD_STALLED) {
                    route.unsafeFailure = true
                }
                result
            }
        }
    }

    private fun validatePending(route: ActiveRoute, result: RouteAdmissionResult.Pending) {
        route.pending?.let { expected ->
            check(result.handle === expected) {
                "progress replaced the pending admission handle"
            }
        }
        val committed = route.committedGrant
        check((committed == null) == (result.committedGrant == null)) {
            "pending admission returned an inconsistent committed-grant state"
        }
        if (committed != null) {
            val returned = requireNotNull(result.committedGrant)
            validateGrantIdentity(route, returned)
            check(returned.grantId === committed.grantId) {
                "pending admission replaced the committed grant identity"
            }
            check(returned.committedAuthority == committed.committedAuthority) {
                "pending admission changed committed authority"
            }
        }
    }

    private fun validateGrant(route: ActiveRoute, grant: RouteGrantSnapshot) {
        validateGrantIdentity(route, grant)
        val previous = route.committedGrant
        if (previous != null) {
            check(grant.grantId === previous.grantId) {
                "same-route extension replaced the committed grant identity"
            }
            check(grant.committedAuthority.distanceFromRouteStartMeters >=
                previous.committedAuthority.distanceFromRouteStartMeters
            ) { "same-route extension reduced committed authority" }
        }
    }

    private fun validateGrantIdentity(route: ActiveRoute, grant: RouteGrantSnapshot) {
        val selection = route.selection
        check(grant.owner == owner) { "grant owner differs from the submitted owner" }
        check(grant.trainId == trainId) { "grant belongs to another train" }
        check(grant.routeId == selection.route.routeId &&
            grant.routeRevision == selection.route.revision
        ) { "grant belongs to another frozen route" }
        check(grant.observationStamp.worldId == selection.observationStamp.worldId) {
            "grant belongs to another world"
        }
        check(grant.observationStamp.topologyRevision == selection.route.topologyRevision) {
            "grant belongs to another topology revision"
        }
        check(grant.committedAuthority.distanceFromRouteStartMeters <=
            selection.route.totalLengthMeters
        ) { "grant authority exceeds the frozen route" }
    }

    private fun publishAuthority(
        route: ActiveRoute,
        stamp: RailwayStateStamp,
        boundaryMeters: Double
    ) {
        publish(
            route,
            listOf(MovementAuthorityRestriction(AUTHORITY_RESTRICTION_ID, boundaryMeters)),
            stamp
        )
    }

    private fun publish(
        route: ActiveRoute,
        restrictions: Collection<TrainRestriction>,
        stamp: RailwayStateStamp =
            route.committedGrant?.observationStamp ?: route.selection.observationStamp
    ) {
        val revision = ++restrictionRevision
        val result = restrictionPublisher.publish(
            TrainRestrictionSnapshot(
                sourceId = AUTHORITY_SOURCE_ID,
                revision = revision,
                trainId = trainId,
                observationStamp = stamp,
                route = route.restrictionRoute,
                restrictions = restrictions
            )
        )
        check(result.accepted) {
            "route-authority restriction publication failed with ${result.status}"
        }
    }

    private data class ActiveRoute(
        val selection: FrozenRouteSelectionResult.Selected,
        val restrictionRoute: TrainRestrictionRouteBinding =
            TrainRestrictionRouteBinding.from(selection.route),
        var committedGrant: RouteGrantSnapshot? = null,
        var pending: RouteAdmissionHandle? = null,
        var unsafeFailure: Boolean = false
    ) {
        fun matches(other: FrozenRouteSelectionResult.Selected): Boolean =
            selection.trainId == other.trainId &&
                selection.observationStamp.worldId == other.observationStamp.worldId &&
                selection.operatingDirectionRevision == other.operatingDirectionRevision &&
                selection.route.routeId == other.route.routeId &&
                selection.route.revision == other.route.revision &&
                selection.route.topologyRevision == other.route.topologyRevision
    }

    private companion object {
        const val AUTHORITY_SOURCE_ID = "simpleato:resource-admission"
        const val AUTHORITY_RESTRICTION_ID = "simpleato:committed-authority"

        fun rejected(code: RouteAdmissionFailureCode, detail: String): RouteAdmissionResult.Rejected =
            RouteAdmissionResult.Rejected(RouteAdmissionFailure(code, detail))
    }
}
