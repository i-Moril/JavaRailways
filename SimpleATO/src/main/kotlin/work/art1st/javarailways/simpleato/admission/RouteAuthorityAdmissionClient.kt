package work.art1st.javarailways.simpleato.admission

import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.CouplingContactRestriction
import work.art1st.javarailways.simpleato.api.restriction.MovementAuthorityRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionResult

/**
 * Per-train SimpleATO client for one Basic route-admission lifecycle at a time.
 *
 * The client never compiles or inspects infrastructure resources. It submits the selected frozen
 * route unchanged, owns at most one pending handle, and publishes only the authority boundary from
 * a committed Basic grant. Before the first grant, a zero-speed hold expresses the absence of permission; route origin is not a physical stop boundary.
 * Full-route grants retain their physical boundary and any explicit speed ceiling; the mission's
 * operational stop remains a separate constraint.
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

    /** Current Basic-backed state used by the Dispatcher to plan the next rolling extension. */
    val state: RouteAuthorityAdmissionState
        get() = active?.let { route ->
            RouteAuthorityAdmissionState(
                committedAuthority = route.committedGrant?.committedAuthority,
                pending = route.pending != null,
                reversalPending = route.reversalPending
            )
        } ?: RouteAuthorityAdmissionState.EMPTY

    /** Exact Basic publication retained by this client, never an operationally clipped boundary. */
    val committedGrantSnapshot: RouteGrantSnapshot? get() = active?.committedGrant

    /**
     * Installs a READY grant returned by the fixed-formation admission path. Basic, not this
     * client, retains the old source owners. The coordinator keeps physical outputs fenced
     * until mission, ledger and these restrictions are installed; adopting is not resuming.
     */
    @MainThread
    fun adoptFormationGrant(selection: FrozenRouteSelectionResult.Selected, grant: RouteGrantSnapshot) {
        require(selection.trainId == trainId) { "formation selection belongs to another train" }
        requireDirectionLimit(selection)
        grant.couplingContactAuthority?.let {
            requireNotNull(selection.operatingDirectionLimit)
                .requireLegalThrough(it.contactBoundary.distanceFromRouteStartMeters)
        }
        validateGrantIdentity(selection, grant)
        active?.let { current ->
            check(current.pending == null && !current.reversalPending && !current.unsafeFailure) {
                "cannot replace a pending or unsafe admission with formation authority"
            }
        }
        val replacement = ActiveRoute(selection, committedGrant = grant)
        active = replacement
        publishAuthority(replacement, grant.observationStamp, grant.committedAuthority.distanceFromRouteStartMeters)
    }

    /**
     * Projects an already committed prefix onto an append candidate before its rolling extension.
     * This changes only the controller restriction binding, never Basic's grant or selected path.
     * Dispatcher must protect both the selected prefix and completed candidate before calling it.
     */
    @MainThread
    fun prepareContinuation(selection: FrozenRouteSelectionResult.Selected) {
        requireDirectionLimit(selection)
        val current = active ?: return
        val grant = current.committedGrant ?: return
        val binding = FrozenRouteBinding.from(selection.route)
        check(!current.unsafeFailure && !current.reversalPending && current.pending == null) {
            "a pending or unsafe admission cannot prepare an operating continuation"
        }
        require(current.matches(selection)) { "operating continuation belongs to a different train or world" }
        if (current.restrictionRoute == binding) {
            val directionEnd = minOf(current.directionEndMeters,
                requireNotNull(selection.operatingDirectionLimit).endRouteDistanceMeters)
            if (current.stationTerminalAuthority != selection.stationTerminalAuthority || current.directionEndMeters != directionEnd) {
                current.stationTerminalAuthority = selection.stationTerminalAuthority
                current.directionEndMeters = directionEnd
                publishAuthority(current, selection.observationStamp, grant.committedAuthority.distanceFromRouteStartMeters)
            }
            return
        }
        val committed = current.selection.route
        require(current.matches(selection) &&
            selection.route.revision > committed.revision &&
            selection.route.segments.size > committed.segments.size &&
            selection.route.segments.take(committed.segments.size) == committed.segments
        ) { "prepared continuation must preserve the exact committed route prefix" }
        check(kotlin.math.abs(grant.committedAuthority.distanceFromRouteStartMeters -
            committed.totalLengthMeters) <= COMPLETE_ROUTE_EPSILON_METERS) {
            "a physical continuation requires authority through the previous route end"
        }
        current.stationTerminalAuthority = selection.stationTerminalAuthority
        current.restrictionRoute = binding
        current.directionEndMeters = minOf(current.directionEndMeters,
            requireNotNull(selection.operatingDirectionLimit).endRouteDistanceMeters)
        publishAuthority(current, selection.observationStamp, grant.committedAuthority.distanceFromRouteStartMeters)
    }

    /** Requests first authority or a farther boundary on the same frozen route. */
    @MainThread
    fun request(
        selection: FrozenRouteSelectionResult.Selected,
        desiredAuthority: RouteBoundary
    ): RouteAdmissionResult {
        requireDirectionLimit(selection)
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
            if (current.reversalPending) {
                return rejected(
                    RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                    "the reversed route must be rebased before requesting authority"
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

        // A new operating target must not inherit a previous station's reduced braking margin,
        // even when its next resource request remains pending on the same physical route.
        val terminal = selection.stationTerminalAuthority.takeIf {
            route.restrictionRoute == FrozenRouteBinding.from(selection.route)
        }
        if (route.stationTerminalAuthority != terminal) {
            route.stationTerminalAuthority = terminal
            route.committedGrant?.let { grant ->
                publishAuthority(route, selection.observationStamp, grant.committedAuthority.distanceFromRouteStartMeters)
            }
        }

        val intent = RouteResourceIntent(
            requestId = requestId,
            owner = owner,
            trainId = trainId,
            observationStamp = selection.observationStamp,
            route = selection.route,
            desiredAuthority = desiredAuthority
        )
        return consume(route, selection, admissionService.request(intent))
    }

    /** Progresses the one pending Basic attempt without resubmitting its route intent. */
    @MainThread
    fun progress(): RouteAdmissionResult {
        val route = active
            ?: return rejected(RouteAdmissionFailureCode.REQUEST_BUSY, "no active frozen route")
        val pending = route.pending
            ?: return rejected(RouteAdmissionFailureCode.REQUEST_BUSY, "no admission is pending")
        return consume(route, pending.selection, admissionService.progress(pending.handle))
    }

    /** Suspends Basic tail release before the Dispatcher reverses the stopped physical train. */
    @MainThread
    fun suspendReleaseForReversal(): RouteGrantReversalResult {
        val route = active
            ?: return reversalRejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                "no active route grant can be suspended for reversal")
        if (route.pending != null) {
            return reversalRejected(RouteAdmissionFailureCode.REQUEST_BUSY,
                "the current route has a pending admission")
        }
        if (route.unsafeFailure) {
            return reversalRejected(RouteAdmissionFailureCode.WORLD_STALLED,
                "the current frozen route is latched after an unsafe admission failure")
        }
        val grant = route.committedGrant
            ?: return reversalRejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                "no committed route grant can be suspended for reversal")
        return when (val result = admissionService.suspendReleaseForReversal(grant.grantId)) {
            is RouteGrantReversalResult.Accepted -> {
                check(result.grant.grantId === grant.grantId) {
                    "reversal suspension replaced the committed grant identity"
                }
                check(result.grant == grant) {
                    "reversal suspension changed the committed grant"
                }
                route.reversalPending = true
                result
            }
            is RouteGrantReversalResult.Rejected -> {
                if (result.failure.code == RouteAdmissionFailureCode.WORLD_STALLED) {
                    route.unsafeFailure = true
                }
                result
            }
        }
    }

    /** Rebinds the retained grant to the new tail and publishes zero remaining body authority. */
    @MainThread
    fun rebaseAfterReversal(
        selection: FrozenRouteSelectionResult.Selected,
        observedHeadBoundary: RouteBoundary
    ): RouteAdmissionResult {
        requireDirectionLimit(selection)
        requireNotNull(selection.operatingDirectionLimit)
            .requireLegalThrough(observedHeadBoundary.distanceFromRouteStartMeters)
        val route = active
            ?: return rejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                "no active route grant can be rebased after reversal")
        if (!route.reversalPending) {
            return rejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                "tail release was not suspended before reversal")
        }
        if (selection.trainId != trainId) {
            return rejected(RouteAdmissionFailureCode.TRAIN_NOT_FOUND,
                "selected reversed route belongs to train ${selection.trainId}, not $trainId")
        }
        val previous = checkNotNull(route.committedGrant)
        val intent = RouteResourceIntent(
            requestId = requestId,
            owner = owner,
            trainId = trainId,
            observationStamp = selection.observationStamp,
            route = selection.route,
            desiredAuthority = observedHeadBoundary
        )
        return when (val result = admissionService.rebaseAfterReversal(intent)) {
            is RouteGrantReversalResult.Accepted -> {
                val grant = result.grant
                validateGrantIdentity(selection, grant)
                check(grant.grantId === previous.grantId) {
                    "reversal rebase replaced the committed grant identity"
                }
                check(grant.committedAuthority == observedHeadBoundary) {
                    "reversal rebase changed the observed-head authority boundary"
                }
                route.selection = selection
                route.stationTerminalAuthority = selection.stationTerminalAuthority
                route.restrictionRoute = FrozenRouteBinding.from(selection.route)
                route.directionEndMeters = requireNotNull(selection.operatingDirectionLimit).endRouteDistanceMeters
                route.committedGrant = grant
                route.reversalPending = false
                publishAuthority(
                    route,
                    grant.observationStamp,
                    grant.committedAuthority.distanceFromRouteStartMeters
                )
                RouteAdmissionResult.Granted(grant)
            }
            is RouteGrantReversalResult.Rejected -> {
                if (result.failure.code == RouteAdmissionFailureCode.WORLD_STALLED) {
                    route.unsafeFailure = true
                }
                RouteAdmissionResult.Rejected(result.failure)
            }
        }
    }

    /**
     * Ends the active route only after Basic confirms that its committed resources are clear.
     *
     * A pending or unsafe first request cannot be hidden by clearing the SimpleATO restriction.
     */
    @MainThread
    fun end(): Boolean {
        val route = active ?: return true
        if (route.pending != null || route.reversalPending || route.unsafeFailure) return false

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

    private fun consume(
        route: ActiveRoute,
        requestedSelection: FrozenRouteSelectionResult.Selected,
        result: RouteAdmissionResult
    ): RouteAdmissionResult {
        return when (result) {
            is RouteAdmissionResult.Granted -> {
                validateGrant(route, requestedSelection, result.grant)
                route.pending = null
                route.committedGrant = result.grant
                route.selection = requestedSelection
                route.stationTerminalAuthority = requestedSelection.stationTerminalAuthority
                route.restrictionRoute = FrozenRouteBinding.from(requestedSelection.route)
                route.directionEndMeters = requireNotNull(requestedSelection.operatingDirectionLimit).endRouteDistanceMeters
                publishAuthority(
                    route,
                    result.grant.observationStamp,
                    result.grant.committedAuthority.distanceFromRouteStartMeters
                )
                result
            }

            is RouteAdmissionResult.Pending -> {
                validatePending(route, requestedSelection, result)
                route.pending = PendingAttempt(result.handle, requestedSelection)
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

    private fun validatePending(
        route: ActiveRoute,
        requestedSelection: FrozenRouteSelectionResult.Selected,
        result: RouteAdmissionResult.Pending
    ) {
        route.pending?.let { expected ->
            check(result.handle === expected.handle) {
                "progress replaced the pending admission handle"
            }
            check(requestedSelection == expected.selection) {
                "progress changed the pending frozen route"
            }
        }
        val committed = route.committedGrant
        check((committed == null) == (result.committedGrant == null)) {
            "pending admission returned an inconsistent committed-grant state"
        }
        if (committed != null) {
            val returned = requireNotNull(result.committedGrant)
            validateGrantIdentity(route.selection, returned)
            check(returned.grantId === committed.grantId) {
                "pending admission replaced the committed grant identity"
            }
            check(returned.committedAuthority == committed.committedAuthority &&
                returned.maximumSpeedMetersPerSecond == committed.maximumSpeedMetersPerSecond &&
                returned.couplingContactAuthority === committed.couplingContactAuthority) {
                "pending admission changed committed authority, contact proof or speed ceiling"
            }
        }
    }

    private fun validateGrant(
        route: ActiveRoute,
        requestedSelection: FrozenRouteSelectionResult.Selected,
        grant: RouteGrantSnapshot
    ) {
        validateGrantIdentity(requestedSelection, grant)
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

    private fun validateGrantIdentity(
        selection: FrozenRouteSelectionResult.Selected,
        grant: RouteGrantSnapshot
    ) {
        check(grant.owner == owner) { "grant owner differs from the submitted owner" }
        check(grant.trainId == trainId) { "grant belongs to another train" }
        check(grant.routeId == selection.route.routeId &&
            grant.routeRevision == selection.route.revision
        ) { "grant belongs to another frozen route" }
        check(grant.observationStamp.worldId == selection.observationStamp.worldId) {
            "grant belongs to another world"
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
        val restrictions = buildList<TrainRestriction> {
            val contact = route.committedGrant?.couplingContactAuthority
            val stationMargin = route.stationTerminalAuthority?.takeIf {
                route.committedGrant != null && boundaryMeters + COMPLETE_ROUTE_EPSILON_METERS >= it.requiredEndRouteDistanceMeters &&
                    route.directionEndMeters + COMPLETE_ROUTE_EPSILON_METERS >= it.requiredEndRouteDistanceMeters
            }?.let { AtoSafetyProfile.STATION_AUTHORITY_MARGIN_METERS }
            if (route.committedGrant == null) add(CurrentSpeedRestriction(AUTHORITY_RESTRICTION_ID, 0.0))
            else if (contact == null) add(MovementAuthorityRestriction(AUTHORITY_RESTRICTION_ID, boundaryMeters, stationMargin))
            else {
                check(contact.contactBoundary.distanceFromRouteStartMeters == boundaryMeters &&
                    route.restrictionRoute == FrozenRouteBinding.from(route.selection.route)) {
                    "contact authority cannot be projected onto a different route"
                }
                add(CouplingContactRestriction(AUTHORITY_RESTRICTION_ID, contact))
            }
            // A complete physical grant may protect a suffix that the train cannot enter.
            // A fully legal path needs no duplicate constraint at its physical end; in particular
            // it must not replace Basic's separately proved zero-gap coupling contact semantics.
            if (route.directionEndMeters < boundaryMeters) {
                add(MovementAuthorityRestriction(DIRECTION_RESTRICTION_ID, route.directionEndMeters, stationMargin))
            }
            route.committedGrant?.maximumSpeedMetersPerSecond?.let { maximumSpeed ->
                add(CurrentSpeedRestriction(SPEED_RESTRICTION_ID, maximumSpeed))
            }
        }
        publish(
            route,
            restrictions,
            stamp
        )
    }

    private fun requireDirectionLimit(selection: FrozenRouteSelectionResult.Selected) {
        selection.stationTerminalAuthority?.let {
            require(it.requiredEndRouteDistanceMeters <= selection.route.totalLengthMeters + COMPLETE_ROUTE_EPSILON_METERS) {
                "station terminal authority exceeds its exact frozen route"
            }
            requireNotNull(selection.operatingDirectionLimit).requireLegalThrough(it.requiredEndRouteDistanceMeters)
        }
        requireNotNull(selection.operatingDirectionLimit) {
            "an exact operating direction result is required before route admission"
        }.requireMatches(selection)
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

    private data class PendingAttempt(
        val handle: RouteAdmissionHandle,
        val selection: FrozenRouteSelectionResult.Selected
    )

    private data class ActiveRoute(
        var selection: FrozenRouteSelectionResult.Selected,
        var stationTerminalAuthority: work.art1st.javarailways.simpleato.routing.StationTerminalAuthority? = selection.stationTerminalAuthority,
        var directionEndMeters: Double = requireNotNull(selection.operatingDirectionLimit).endRouteDistanceMeters,
        var restrictionRoute: FrozenRouteBinding =
            FrozenRouteBinding.from(selection.route),
        var committedGrant: RouteGrantSnapshot? = null,
        var pending: PendingAttempt? = null,
        var reversalPending: Boolean = false,
        var unsafeFailure: Boolean = false
    ) {
        fun matches(other: FrozenRouteSelectionResult.Selected): Boolean =
            selection.trainId == other.trainId &&
                selection.observationStamp.worldId == other.observationStamp.worldId &&
                selection.route.routeId == other.route.routeId
    }

    private companion object {
        const val AUTHORITY_SOURCE_ID = "simpleato:resource-admission"
        const val AUTHORITY_RESTRICTION_ID = "simpleato:committed-authority"
        const val SPEED_RESTRICTION_ID = "simpleato:admitted-speed"
        const val DIRECTION_RESTRICTION_ID = "simpleato:operating-direction"
        const val COMPLETE_ROUTE_EPSILON_METERS = 1.0e-6

        fun rejected(code: RouteAdmissionFailureCode, detail: String): RouteAdmissionResult.Rejected =
            RouteAdmissionResult.Rejected(RouteAdmissionFailure(code, detail))

        fun reversalRejected(
            code: RouteAdmissionFailureCode,
            detail: String
        ): RouteGrantReversalResult.Rejected =
            RouteGrantReversalResult.Rejected(RouteAdmissionFailure(code, detail))
    }
}

internal data class RouteAuthorityAdmissionState(
    val committedAuthority: RouteBoundary?,
    val pending: Boolean,
    val reversalPending: Boolean
) {
    companion object {
        val EMPTY = RouteAuthorityAdmissionState(null, false, false)
    }
}
