package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.basic.observation.invalidTopologyReason
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionState
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.RouteAdmissionBlocker
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.FixedFormationChangeIntent
import work.art1st.javarailways.basic.api.route.FormationChangeHandle
import work.art1st.javarailways.basic.api.route.FormationChangeResult
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.util.annotation.MainThread
import java.util.Collections
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.ProjectedTopologyRoute
import work.art1st.javarailways.basic.infrastructure.RouteBoundResourceCatalog
import work.art1st.javarailways.basic.infrastructure.TopologyRouteProjectionResult
import work.art1st.javarailways.basic.infrastructure.TopologyRouteProjector

/**
 * Internal route-admission owner for one world. Production world/catalog binding and authenticated
 * public service registration must join this owner before it is exposed.
 * The host binds resource-owner validation and the host-held claim token when constructing this
 * world owner. A request cannot supply its own validator or own the train's live registry claim.
 */
internal class RouteResourceAdmissionEngine(
    private val worldId: String,
    private val registry: InfrastructureCoordinationRegistry,
    private val hostClaimOwner: Any,
    private val currentTopology: () -> TopologyState,
    private val latestObservation: () -> RailwayObservation?,
    routeBoundResources: RouteBoundResourceCatalog,
    private val validateContribution: (Any, ContributedRouteResource) -> RouteAdmissionFailure?,
    private val preparationActuator: InfrastructurePreparationActuator,
    private val stallWorld: (String, Exception) -> Unit,
    private val collectContributions: (RouteResourceIntent) -> List<Pair<Any, ContributedRouteResource>> = { emptyList() },
    private val publishAdmissionUpdate: (ActiveRouteAdmissionUpdate) -> Unit = {},
    private val formationWorld: work.art1st.javarailways.core.api.physics.World? = null,
    authorizeFormationRoute: (
        work.art1st.javarailways.basic.api.route.FormationRouteContext,
        RouteResourceIntent
    ) -> work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult = { _, _ ->
        work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult.Rejected(
            RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "formation policy is unavailable"))
    }
) {
    private val physicalCompiler = PhysicalRouteResourceCompiler(routeBoundResources)
    private val pending = LinkedHashMap<RouteAdmissionHandle, PendingRequest>()
    // A terminal result remains repeatable while its caller retains the process-local handle.
    // Completed attempts without a retained handle must not accumulate for the world's lifetime.
    private data class TerminalRequest(
        val requester: Any,
        val result: RouteAdmissionResult
    )

    private val terminal = java.util.WeakHashMap<RouteAdmissionHandle, TerminalRequest>()
    private val routes = java.util.TreeMap<String, RouteState>()
    private var worldStalled = false
    private val formations = PreparedFormationAdmission(formationWorld, worldId, latestObservation,
        { reason, failure -> markWorldStalled(reason, failure) }, registry, hostClaimOwner,
        preparationActuator, { intent, movement, contact -> compileFormationRoute(intent, movement, contactProof = contact) },
        { intent, movement, cursor -> compileFormationRoute(intent, movement, cursor) },
        authorizeFormationRoute, ::publishFormationApproach,
        ::formationOutputBoundaries, ::publishFormationOutput, ::detachFormationOutput, ::completeFormationOutputs,
        ::endFormationForManual,
        { requester, owner, trainId, sources ->
            val existing = routes[owner.ownerId]
            (existing == null && registry.claimSnapshots().none { it.owner.ownerId == owner.ownerId }) || (existing != null && existing.requester === requester && existing.owner == owner &&
                existing.trainId == trainId && sources.any { it.grant.owner == owner && it.grant.trainId == trainId })
        }, { owner -> routes[owner.ownerId]?.topologyInvalidated != false })
    private val dependencyLock = Any()
    private val routesByRail = hashMapOf<java.util.UUID, MutableSet<RouteState>>()
    private val validationsByRail = hashMapOf<java.util.UUID, MutableSet<Validation>>()
    private class Validation(val rails: Set<java.util.UUID>) { @Volatile var invalidated = false }

    /** Called before a successful transaction publishes. No compilation, claim mutation or waits. */
    fun invalidateTopology(change: work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange) =
        synchronized(dependencyLock) {
            change.affectedRailIds.forEach { rail ->
                routesByRail[rail]?.forEach { it.topologyInvalidated = true }
                validationsByRail[rail]?.forEach { it.invalidated = true }
            }
        }

    private fun <T> validateTopology(route: RouteSnapshot, additionalRails: Collection<java.util.UUID> = emptyList(), action: (Validation) -> T): T {
        val validation = Validation(route.segments.mapTo(hashSetOf()) { it.railId }.also { it.addAll(additionalRails) })
        synchronized(dependencyLock) {
            validation.rails.forEach { validationsByRail.getOrPut(it, ::hashSetOf).add(validation) }
        }
        return try { action(validation) } finally {
            synchronized(dependencyLock) {
                validation.rails.forEach { rail ->
                    validationsByRail[rail]?.let { entries ->
                        entries.remove(validation)
                        if (entries.isEmpty()) validationsByRail.remove(rail)
                    }
                }
            }
        }
    }

    private fun indexRoute(route: RouteState, selected: RouteSnapshot = route.projection.route,
                           validation: Validation? = null) = synchronized(dependencyLock) {
        unindexRoute(route)
        route.indexedRails = selected.segments.mapTo(hashSetOf()) { it.railId }
        route.indexedRails.forEach { routesByRail.getOrPut(it, ::hashSetOf).add(route) }
        if (validation?.invalidated == true) route.topologyInvalidated = true
    }

    private fun unindexRoute(route: RouteState) = synchronized(dependencyLock) {
        route.indexedRails.forEach { rail -> routesByRail[rail]?.let { entries ->
            entries.remove(route)
            if (entries.isEmpty()) routesByRail.remove(rail)
        } }
        route.indexedRails = emptySet()
    }

    private fun removeRoute(ownerId: String) {
        routes.remove(ownerId)?.let(::unindexRoute)
    }

    private class RouteState(
        val requester: Any,
        val owner: InfrastructureOwner,
        val trainId: String,
        var projection: ProjectedTopologyRoute,
        var cursor: RouteProgressCursor,
        var routeProgressPath: CompiledRouteProgressPath,
        var committedGrant: RouteGrantSnapshot? = null,
        var tailRelease: RouteGrantTailRelease? = null,
        val preparationsByResource: MutableMap<InfrastructureResource, InfrastructurePreparationRequirement>,
        var pendingHandle: RouteAdmissionHandle? = null,
        var reversalFromTrainState: Boolean? = null,
        var publishedAdmission: ActiveRouteAdmissionSnapshot? = null
    ) {
        @Volatile var topologyInvalidated = false
        var indexedRails: Set<java.util.UUID> = emptySet()
        var formationChange: FormationChangeHandle? = null
        var formationContext: work.art1st.javarailways.basic.api.route.FormationRouteContext? = null
    }

    private data class ReservedDelta(
        val result: InfrastructureMutationResult,
        val complete: () -> Boolean,
        val rollback: () -> InfrastructureMutationResult
    )

    private sealed interface ContributionValidation {
        data class Accepted(val contributions: List<ContributedRouteResource>) : ContributionValidation
        data class Rejected(val failure: RouteAdmissionFailure) : ContributionValidation
    }

    private inner class PendingRequest(
        val handle: RouteAdmissionHandle,
        val intent: RouteResourceIntent,
        val route: RouteState,
        val compiled: CompiledRouteAdmission,
        val deltaEntries: List<ResolvedTailReleaseEntry>,
        val candidateProjection: ProjectedTopologyRoute,
        val appendsRoute: Boolean,
        val reservation: ReservedDelta,
        val ticket: ResourcePreparationTicket
    ) {
        fun progress(): RouteAdmissionResult {
            if (worldStalled) {
                return rejected(RouteAdmissionFailureCode.WORLD_STALLED,
                    "world is already stalled; reservation retained")
            }
            if (route.topologyInvalidated) return rejected(RouteAdmissionFailureCode.STALE_TOPOLOGY,
                "a route dependency changed during preparation; reservation retained")
            val state = try {
                ticket.snapshot()
            } catch (failure: Exception) {
                return stalled(intent, failure, "preparation state could not be read")
            }
            return when (state.state) {
                ResourcePreparationState.PREPARING -> RouteAdmissionResult.Pending(handle, route.committedGrant)
                ResourcePreparationState.READY -> try {
                    val observation = latestObservation()
                        ?: error("no coherent observation when preparation became ready")
                    check(observation.stamp.worldId == worldId &&
                        observation.stamp.physicsEpoch >= intent.observationStamp.physicsEpoch &&
                        !route.topologyInvalidated
                    ) { "route topology or observation changed during preparation" }
                    val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
                        ?: error("train disappeared during preparation")
                    val oldGrant = route.committedGrant
                    if (oldGrant == null) {
                        val projected = route.cursor.project(train.tail)
                        check(projected is RouteProgressCursorUpdate.Resolved &&
                            projected.state.routeDistanceMeters <= Curve.TOLERANCE
                        ) { "train tail no longer starts at the frozen route" }
                    } else {
                        val release = checkNotNull(route.tailRelease).consume(observation.stamp, train)
                        check(release !is RouteGrantTailReleaseUpdate.FailedClosed) {
                            "train tail no longer follows the frozen route"
                        }
                    }
                    check(observation.occupancy.blockingTrainIds(
                        compiled.occupancyGeometry.intervals, intent.trainId
                    ).isEmpty()) { "another train entered the prepared route" }
                    check(!route.topologyInvalidated &&
                        latestObservation()?.stamp == observation.stamp && !worldStalled
                    ) { "world changed while checking the prepared route" }
                    if (oldGrant != null) {
                        checkNotNull(route.tailRelease).validateExtendedSuffix(deltaEntries)
                    }
                    check(reservation.complete()) { "resource reservation disappeared" }
                    if (oldGrant == null) {
                        route.tailRelease = RouteGrantTailRelease(
                            worldId, intent.trainId,
                            observation.stamp.physicsEpoch, route.cursor,
                            LiveClaimTailRelease(
                                registry, hostClaimOwner, intent.owner,
                                CompiledTailReleaseQueue(deltaEntries), { protectedClaimRemainder(intent.owner) }
                            ), { reason, failure -> markWorldStalled(reason, failure) }
                        )
                    } else {
                        if (appendsRoute) {
                            route.cursor.installExtension(compiled.routeProgressPath)
                            route.projection = candidateProjection
                            route.routeProgressPath = compiled.routeProgressPath
                        }
                        checkNotNull(route.tailRelease).installExtendedSuffix(deltaEntries)
                    }
                    compiled.preparations.forEach { route.preparationsByResource[it.resource] = it }
                    val grant = oldGrant?.copy(
                        routeId = intent.route.routeId,
                        routeRevision = intent.route.revision,
                        observationStamp = observation.stamp,
                        committedAuthority = intent.desiredAuthority
                    ) ?: RouteGrantSnapshot(
                        RouteGrantId(), intent.owner, intent.trainId, intent.route.routeId,
                        intent.route.revision, observation.stamp, intent.desiredAuthority
                    )
                    route.committedGrant = grant
                    route.pendingHandle = null
                    RouteAdmissionResult.Granted(grant)
                } catch (failure: Exception) {
                    stalled(intent, failure, "ready preparation could not publish a grant")
                }
                ResourcePreparationState.ABORTED_SAFE -> try {
                    if (route.committedGrant != null) {
                        val observation = latestObservation()
                            ?: error("no coherent observation when request safely aborted")
                        check(observation.stamp.worldId == worldId && !route.topologyInvalidated
                        ) { "world changed before safe request abort" }
                        val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
                            ?: error("train disappeared before safe request abort")
                        check(checkNotNull(route.tailRelease).consume(observation.stamp, train) !is
                            RouteGrantTailReleaseUpdate.FailedClosed
                        ) { "train tail could not be reconciled before safe request abort" }
                    }
                    val rolledBack = reservation.rollback()
                    check(if (route.committedGrant == null)
                        rolledBack.status == InfrastructureMutationStatus.RELEASED else rolledBack.successful
                    ) { "safe request abort could not remove its new resources" }
                    if (route.committedGrant == null) {
                        removeRoute(intent.owner.ownerId)
                    } else {
                        val release = checkNotNull(route.tailRelease).rollbackExtendedHolds()
                        check(release !is LiveClaimTailReleaseResult.FailedClosed) {
                            "safe request abort could not reconcile tail release"
                        }
                        route.pendingHandle = null
                        indexRoute(route)
                    }
                    rejected(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE,
                        "resource preparation safely aborted: ${state.detail}")
                } catch (failure: Exception) {
                    stalled(intent, failure, "safe request abort could not restore its resources")
                }
                ResourcePreparationState.UNSAFE -> stalled(
                    intent, IllegalStateException(state.detail), "resource preparation became unsafe"
                )
            }
        }
    }

    /** Adds the next requested route range to the same owner state, whether or not it has a grant yet. */
    @MainThread
    fun request(requester: Any, intent: RouteResourceIntent): RouteAdmissionResult =
        validateTopology(intent.route) { requestValidated(requester, intent, it) }

    private fun requestValidated(requester: Any, intent: RouteResourceIntent, validation: Validation): RouteAdmissionResult {
        if (worldStalled) return rejected(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled")
        if (intent.formationContext != null) return rejected(RouteAdmissionFailureCode.OWNER_MISMATCH,
            "formation context is accepted only through its prepared event")
        if (intent.observationStamp.worldId != worldId) {
            return rejected(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "request belongs to another world")
        }
        if (!formations.retainedOwnerAvailable(requester, intent.owner)) return rejected(RouteAdmissionFailureCode.OWNER_MISMATCH,
            "logical owner belongs to another retained formation requester or revision")
        val existing = routes[intent.owner.ownerId]
        var appendsRoute = false
        if (existing != null) {
            if (existing.requester !== requester || existing.owner != intent.owner) {
                return rejected(RouteAdmissionFailureCode.OWNER_MISMATCH, "request owner does not match route state")
            }
            if (existing.trainId != intent.trainId) {
                return rejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                    "request must keep the same train")
            }
            if (existing.formationChange != null) {
                return rejected(RouteAdmissionFailureCode.REQUEST_BUSY,
                    "source grant is retained by a prepared formation event")
            }
            if (existing.reversalFromTrainState != null) {
                return rejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                    "route must be rebased after reversal before requesting more authority")
            }
            if (existing.projection.route != intent.route) {
                if (!isAppendOnlyContinuation(existing.projection.route, intent.route)) {
                    return rejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                        "request changed the committed route prefix or did not append a continuation")
                }
                appendsRoute = true
            }
            if (existing.pendingHandle != null) {
                return rejected(RouteAdmissionFailureCode.REQUEST_BUSY, "route already has a pending request")
            }
        }
        val oldFrontier = existing?.committedGrant?.committedAuthority?.distanceFromRouteStartMeters ?: 0.0
        val newFrontier = intent.desiredAuthority.distanceFromRouteStartMeters
        if (appendsRoute && kotlin.math.abs(
                oldFrontier - checkNotNull(existing).projection.route.totalLengthMeters
            ) > Curve.TOLERANCE
        ) {
            return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                "route continuation requires authority committed to the prior route end")
        }
        if (newFrontier <= oldFrontier) {
            return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                "requested authority must be farther than the committed boundary")
        }
        val observation = latestObservation()
            ?: return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, "no published observation")
        if (observation.stamp != intent.observationStamp) {
            return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, "observation stamp is no longer current")
        }
        val topology = currentTopology()
        if (existing?.topologyInvalidated == true || validation.invalidated) {
            return rejected(RouteAdmissionFailureCode.STALE_TOPOLOGY, "a frozen route dependency changed")
        }
        val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
            ?: return rejected(RouteAdmissionFailureCode.TRAIN_NOT_FOUND, "train is absent from observation")
        if (observation.stamp.topologyRevision != topology.revision) {
            train.invalidTopologyReason(topology)?.let {
                return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, it)
            }
        }
        val projection = if (existing == null || appendsRoute) {
            when (val projected = TopologyRouteProjector.project(intent.route, topology)) {
                is TopologyRouteProjectionResult.Projected -> projected.route
                is TopologyRouteProjectionResult.Rejected -> return rejected(
                    RouteAdmissionFailureCode.INVALID_ROUTE,
                    projected.detail
                )
            }
        } else {
            // Tail release is independent of this request and keeps using the committed prefix.
            if (checkNotNull(existing.tailRelease).consume(observation.stamp, train) is
                RouteGrantTailReleaseUpdate.FailedClosed
            ) return rejected(RouteAdmissionFailureCode.WORLD_STALLED, "train tail no longer follows frozen route")
            existing.projection
        }
        val progressPath = when {
            existing == null -> null
            !appendsRoute -> existing.routeProgressPath
            else -> CompiledRouteProgressPath.compile(projection)
        }
        val release = existing?.tailRelease
        val held = release?.remainingEntries?.associateBy(ResolvedTailReleaseEntry::resource).orEmpty()
        val physicalResult = physicalCompiler.compile(
            projection, PhysicalRouteRange(oldFrontier, newFrontier,
                includeTurnoutAtStart = existing == null || appendsRoute),
            progressPath,
            held.keys
        )
        val physical = when (physicalResult) {
            is PhysicalRouteCompilation.Compiled -> physicalResult.admission
            is PhysicalRouteCompilation.Rejected -> return RouteAdmissionResult.Rejected(RouteAdmissionFailure(
                when (physicalResult.reason) {
                    PhysicalRouteCompilationRejection.STALE_TOPOLOGY -> RouteAdmissionFailureCode.STALE_TOPOLOGY
                    PhysicalRouteCompilationRejection.INVALID_ROUTE -> RouteAdmissionFailureCode.INVALID_ROUTE
                    PhysicalRouteCompilationRejection.REQUEST_RANGE_TOO_LONG -> RouteAdmissionFailureCode.REQUEST_RANGE_TOO_LONG
                }, physicalResult.detail,
                retryAtOrBefore = physicalResult.retryAtOrBeforeMeters?.let(::RouteBoundary)
            ))
        }
        val initialCursor = if (existing == null) {
            val initialized = physical.routeProgressPath.initializeCursor(train.tail)
            if (initialized !is RouteProgressCursorInitialization.Initialized) {
                return rejected(RouteAdmissionFailureCode.INVALID_ROUTE, "frozen route does not start at train tail")
            }
            initialized.cursor
        } else null
        val authenticatedContributions = try {
            collectContributions(intent)
        } catch (failure: Exception) {
            java.util.logging.Logger.getLogger(RouteResourceAdmissionEngine::class.java.name).log(
                java.util.logging.Level.WARNING, "Route resource contributor failed world=$worldId train=${intent.trainId}", failure)
            return rejected(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "route-resource contributor failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
        val acceptedTailProgress = release?.acceptedTailProgressMeters
        // Contributors see the complete requested prefix, including resources already cleared
        // behind this route's tail. Validate those values, but do not reacquire their identities.
        val contributions = when (val validated = validateContributions(
            authenticatedContributions,
            newFrontier,
            "contributed resource release boundary exceeds desired authority"
        ) { contribution ->
            contribution.resource in held || acceptedTailProgress == null ||
                contribution.releaseAfterTailPasses.distanceFromRouteStartMeters > acceptedTailProgress
        }) {
            is ContributionValidation.Accepted -> validated.contributions
            is ContributionValidation.Rejected -> return RouteAdmissionResult.Rejected(validated.failure)
        }
        for (contribution in contributions) {
            val previous = held[contribution.resource] ?: continue
            if (contribution.releaseAfterTailPasses.distanceFromRouteStartMeters < previous.tailClearRouteMeters) {
                return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                    "request cannot shorten an already-held resource")
            }
            val descriptor = contribution.preparation
            if (descriptor != null &&
                (existing?.preparationsByResource?.get(contribution.resource) as? ResolvedContributedResourcePreparation)
                    ?.descriptor != descriptor
            ) {
                return rejected(RouteAdmissionFailureCode.INVALID_PREPARATION,
                    "request cannot change an already-held resource preparation")
            }
        }
        val merged = physical.withContributions(contributions)
        val compiled = when (merged) {
            is RouteContributionsCompilation.Compiled -> merged.admission
            is RouteContributionsCompilation.Rejected -> return RouteAdmissionResult.Rejected(merged.failure)
        }
        val blockers = observation.occupancy.blockingTrainIds(compiled.occupancyGeometry.intervals, intent.trainId)
        if (blockers.isNotEmpty()) {
            return RouteAdmissionResult.Rejected(RouteAdmissionFailure(
                RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED, "another train occupies the requested route",
                blockers.map(RouteAdmissionBlocker::OccupyingTrain)
            ))
        }
        if (validation.invalidated || existing?.topologyInvalidated == true) {
            return rejected(RouteAdmissionFailureCode.STALE_TOPOLOGY, "a route dependency changed during validation")
        }
        if (latestObservation()?.stamp != observation.stamp) {
            return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, "observation changed during validation")
        }
        if (formations.overlapsRetained(compiled.resources.resources))
            return rejected(RouteAdmissionFailureCode.REQUEST_BUSY, "ordinary route overlaps retained formation protection")
        val heldEntries = compiled.tailReleaseEntries.filter { it.resource in held }.map { entry ->
            entry.copy(tailClearRouteMeters = maxOf(entry.tailClearRouteMeters,
                held.getValue(entry.resource).tailClearRouteMeters))
        }
        val deltaEntries = compiled.tailReleaseEntries.filterNot { it.resource in held }
        if (deltaEntries.isEmpty()) {
            return rejected(RouteAdmissionFailureCode.INVALID_ROUTE, "request has no new physical resources")
        }
        val acceptedProgress = release?.acceptedTailProgressMeters
        if (acceptedProgress != null &&
            (heldEntries.any { it.tailClearRouteMeters <= acceptedProgress } ||
                deltaEntries.any { it.tailClearRouteMeters <= acceptedProgress })
        ) {
            return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                "request contains a release boundary already passed by the train tail")
        }
        if (release != null) {
            try {
                release.validateExtendedHolds(heldEntries)
            } catch (failure: Exception) {
                return rejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                    "resource hold cannot join the live release queue: ${failure.message.orEmpty()}")
            }
        }
        val deltaResources = compiled.resources.resources.filterNot { it in held }
        val reservation = if ((release == null || release.resourceQueueEmpty) && formations.retainedResources(intent.owner).isEmpty()) {
            val mutation = registry.beginAcquireClaim(hostClaimOwner,
                InfrastructureClaimRequest(intent.owner, InfrastructureFootprint(deltaResources)))
            ReservedDelta(mutation.result, { registry.completeClaimMutation(mutation) },
                { registry.rollbackClaimMutation(mutation) })
        } else {
            val append = registry.beginAppendClaim(hostClaimOwner, intent.owner, deltaResources)
            ReservedDelta(append.result, { registry.completeClaimAppend(append) },
                { registry.rollbackClaimAppend(append) })
        }
        if (reservation.result.status != InfrastructureMutationStatus.ACQUIRED &&
            reservation.result.status != InfrastructureMutationStatus.REPLACED
        ) return mapMutation(reservation.result)
        val route = existing ?: RouteState(
            requester, intent.owner, intent.trainId, projection, checkNotNull(initialCursor),
            physical.routeProgressPath,
            preparationsByResource = LinkedHashMap()
        ).also { routes[intent.owner.ownerId] = it }
        indexRoute(route, intent.route, validation)
        if (route.topologyInvalidated) return rejected(RouteAdmissionFailureCode.STALE_TOPOLOGY,
            "a route dependency changed during reservation; reservation retained")
        if (release != null) {
            try {
                release.stageExtendedHolds(heldEntries)
            } catch (failure: Exception) {
                return stalled(intent, failure, "request could not defer held-resource release")
            }
        }
        val preparations = compiled.preparations.filterNot {
            it is ResolvedContributedResourcePreparation && it.resource in held
        }
        val started = try {
            registry.withClaim(hostClaimOwner, intent.owner) { preparationActuator.start(preparations) }
        } catch (failure: Exception) {
            return stalled(intent, failure, "preparation could not be started")
        }
        if (started is InfrastructurePreparationStart.Rejected) {
            return try {
                val rolledBack = reservation.rollback()
                check(if (route.committedGrant == null)
                    rolledBack.status == InfrastructureMutationStatus.RELEASED else rolledBack.successful
                ) { "rejected preparation could not remove its new resources" }
                if (route.committedGrant == null) {
                    removeRoute(intent.owner.ownerId)
                } else {
                    check(release!!.rollbackExtendedHolds() !is LiveClaimTailReleaseResult.FailedClosed) {
                        "rejected preparation could not restore old release boundaries"
                    }
                    indexRoute(route)
                }
                rejected(RouteAdmissionFailureCode.INVALID_PREPARATION,
                    "preparation rejected: ${started.failure.message}")
            } catch (failure: Exception) {
                stalled(intent, failure, "rejected preparation could not safely restore its resources")
            }
        }
        val handle = RouteAdmissionHandle()
        route.pendingHandle = handle
        pending[handle] = PendingRequest(handle, intent, route, compiled, deltaEntries,
            projection, appendsRoute,
            reservation, (started as InfrastructurePreparationStart.Accepted).ticket)
        return progress(requester, handle)
    }

    @MainThread
    fun progress(requester: Any, handle: RouteAdmissionHandle): RouteAdmissionResult {
        formations.progress(requester, handle)?.let { return it }
        terminal[handle]?.let { completed ->
            return if (completed.requester === requester) completed.result else rejected(
                RouteAdmissionFailureCode.OWNER_MISMATCH,
                "admission handle belongs to another requester"
            )
        }
        val attempt = pending[handle]
            ?: return rejected(RouteAdmissionFailureCode.OWNER_MISMATCH, "unknown admission handle")
        if (attempt.route.requester !== requester) {
            return rejected(RouteAdmissionFailureCode.OWNER_MISMATCH,
                "admission handle belongs to another requester")
        }
        val result = attempt.progress()
        if (result !is RouteAdmissionResult.Pending) {
            pending.remove(handle)
            terminal[handle] = TerminalRequest(requester, result)
        }
        publishAdmission(attempt.route)
        return result
    }

    /** Retains the complete claim while the stopped train changes physical direction. */
    @MainThread
    fun suspendReleaseForReversal(
        requester: Any,
        grantId: RouteGrantId
    ): RouteGrantReversalResult {
        if (worldStalled) return reversalRejected(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled")
        val route = routes.values.firstOrNull { it.committedGrant?.grantId === grantId }
            ?: return reversalRejected(RouteAdmissionFailureCode.OWNER_MISMATCH, "unknown route grant")
        if (route.requester !== requester) {
            return reversalRejected(RouteAdmissionFailureCode.OWNER_MISMATCH,
                "route grant belongs to another requester")
        }
        route.formationChange?.let { return formations.suspendOutputReversal(requester, it, grantId) }
        if (route.pendingHandle != null) {
            return reversalRejected(RouteAdmissionFailureCode.REQUEST_BUSY,
                "route grant has a pending admission")
        }
        val grant = checkNotNull(route.committedGrant)
        route.reversalFromTrainState?.let { return RouteGrantReversalResult.Accepted(grant) }
        val observation = latestObservation()
            ?: return reversalRejected(RouteAdmissionFailureCode.STALE_OBSERVATION,
                "no published observation")
        if (observation.stamp.worldId != worldId || route.topologyInvalidated) {
            return reversalRejected(RouteAdmissionFailureCode.STALE_TOPOLOGY,
                "route topology changed before reversal")
        }
        val train = observation.trains.firstOrNull { it.trainId == route.trainId }
            ?: return reversalRejected(RouteAdmissionFailureCode.TRAIN_NOT_FOUND,
                "train is absent from the current observation")
        if (train.derailed || train.speedMetersPerSecond > STOPPED_SPEED_EPSILON_METERS_PER_SECOND) {
            return reversalRejected(RouteAdmissionFailureCode.INVALID_ROUTE,
                "tail release can only be suspended for a stopped, non-derailed train")
        }
        route.reversalFromTrainState = train.reversed
        return RouteGrantReversalResult.Accepted(grant)
    }

    /**
     * Replaces only route-relative progress metadata after a stopped reversal.
     *
     * The registry claim is not mutated here. The new body must be completely covered by resources
     * already present in the retained release queue; all replacement objects are built and checked
     * before the route state is switched in one owner-thread step.
     */
    @MainThread
    fun rebaseAfterReversal(
        requester: Any,
        intent: RouteResourceIntent
    ): RouteGrantReversalResult = validateTopology(intent.route) { rebaseValidated(requester, intent, it) }

    private fun rebaseValidated(requester: Any, intent: RouteResourceIntent, validation: Validation): RouteGrantReversalResult {
        if (worldStalled) return reversalRejected(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled")
        if (intent.observationStamp.worldId != worldId) {
            return reversalRejected(RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
                "reversal belongs to another world")
        }
        val route = routes[intent.owner.ownerId]
            ?: return reversalRejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                "reversal has no live route grant")
        if (route.requester !== requester || route.owner != intent.owner) {
            return reversalRejected(RouteAdmissionFailureCode.OWNER_MISMATCH,
                "reversal owner does not match route state")
        }
        route.formationChange?.let { return formations.rebaseOutputReversal(requester, it, intent) }
        if (route.trainId != intent.trainId) {
            return reversalRejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                "reversal must keep the same train")
        }
        val reversedFrom = route.reversalFromTrainState
            ?: return reversalRejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                "tail release was not suspended before reversal")
        if (route.pendingHandle != null) {
            return reversalRejected(RouteAdmissionFailureCode.REQUEST_BUSY,
                "route grant has a pending admission")
        }

        val observation = latestObservation()
            ?: return reversalRejected(RouteAdmissionFailureCode.STALE_OBSERVATION,
                "no published reversal observation")
        if (observation.stamp != intent.observationStamp) {
            return reversalRejected(RouteAdmissionFailureCode.STALE_OBSERVATION,
                "reversal observation stamp is no longer current")
        }
        val topology = currentTopology()
        if (route.topologyInvalidated || validation.invalidated) {
            return reversalRejected(RouteAdmissionFailureCode.STALE_TOPOLOGY,
                "frozen route topology changed during reversal")
        }
        val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
            ?: return reversalRejected(RouteAdmissionFailureCode.TRAIN_NOT_FOUND,
                "train is absent from the reversal observation")
        if (observation.stamp.topologyRevision != topology.revision) {
            train.invalidTopologyReason(topology)?.let {
                return reversalRejected(RouteAdmissionFailureCode.STALE_OBSERVATION, it)
            }
        }
        if (train.derailed || train.speedMetersPerSecond > STOPPED_SPEED_EPSILON_METERS_PER_SECOND) {
            return reversalRejected(RouteAdmissionFailureCode.INVALID_ROUTE,
                "reversal rebase requires a stopped, non-derailed train")
        }
        if (train.reversed == reversedFrom) {
            return reversalRejected(RouteAdmissionFailureCode.INVALID_ROUTE,
                "train direction did not change after tail release was suspended")
        }

        val projection = when (val projected = TopologyRouteProjector.project(intent.route, topology)) {
            is TopologyRouteProjectionResult.Projected -> projected.route
            is TopologyRouteProjectionResult.Rejected -> return reversalRejected(
                RouteAdmissionFailureCode.INVALID_ROUTE,
                projected.detail
            )
        }
        val bodyEnd = intent.desiredAuthority.distanceFromRouteStartMeters
        if (bodyEnd <= Curve.TOLERANCE) {
            return reversalRejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                "reversal route must cover the complete train body")
        }
        val physicalResult = physicalCompiler.compile(
            projection,
            PhysicalRouteRange(0.0, bodyEnd)
        )
        val physical = when (physicalResult) {
            is PhysicalRouteCompilation.Compiled -> physicalResult.admission
            is PhysicalRouteCompilation.Rejected -> return reversalRejected(
                if (physicalResult.reason == PhysicalRouteCompilationRejection.STALE_TOPOLOGY)
                    RouteAdmissionFailureCode.STALE_TOPOLOGY else RouteAdmissionFailureCode.INVALID_ROUTE,
                physicalResult.detail
            )
        }
        val initialized = physical.routeProgressPath.initializeCursor(train.tail)
        if (initialized !is RouteProgressCursorInitialization.Initialized) {
            return reversalRejected(RouteAdmissionFailureCode.INVALID_ROUTE,
                "reversed route does not start at the new train tail")
        }
        val projectedHead = initialized.cursor.project(train.head)
        if (projectedHead !is RouteProgressCursorUpdate.Resolved ||
            kotlin.math.abs(projectedHead.state.routeDistanceMeters - bodyEnd) > Curve.TOLERANCE
        ) {
            return reversalRejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                "reversal authority does not identify the observed train head")
        }

        val authenticatedContributions = try {
            collectContributions(intent)
        } catch (failure: Exception) {
            java.util.logging.Logger.getLogger(RouteResourceAdmissionEngine::class.java.name).log(
                java.util.logging.Level.WARNING, "Route resource contributor failed world=$worldId train=${intent.trainId}", failure)
            return reversalRejected(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "route-resource contributor failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
        val contributions = when (val validated = validateContributions(
            authenticatedContributions,
            bodyEnd,
            "contributed reversal release boundary exceeds the observed train head"
        ) { true }) {
            is ContributionValidation.Accepted -> validated.contributions
            is ContributionValidation.Rejected ->
                return RouteGrantReversalResult.Rejected(validated.failure)
        }
        val merged = physical.withContributions(contributions)
        val compiled = when (merged) {
            is RouteContributionsCompilation.Compiled -> merged.admission
            is RouteContributionsCompilation.Rejected ->
                return RouteGrantReversalResult.Rejected(merged.failure)
        }

        val release = checkNotNull(route.tailRelease)
        if (formations.overlapsRetained(compiled.resources.resources)) return reversalRejected(RouteAdmissionFailureCode.REQUEST_BUSY,
            "reversal overlaps retained formation protection")
        val retainedEntries = release.remainingEntries
        if (retainedEntries.isEmpty()) {
            return reversalRejected(RouteAdmissionFailureCode.INVALID_ROUTE,
                "reversal grant has no retained resources covering the train body")
        }
        val retainedResources = retainedEntries.map(ResolvedTailReleaseEntry::resource)
        val uncovered = firstUncoveredResource(compiled.resources.resources, retainedResources)
        if (uncovered != null) {
            return reversalRejected(RouteAdmissionFailureCode.INVALID_ROUTE,
                "reversed train body requires a resource absent from the retained claim: $uncovered")
        }
        for (preparation in compiled.preparations.filterIsInstance<ResolvedContributedResourcePreparation>()) {
            val previous = route.preparationsByResource[preparation.resource]
                as? ResolvedContributedResourcePreparation
            if (previous?.descriptor != preparation.descriptor) {
                return reversalRejected(RouteAdmissionFailureCode.INVALID_PREPARATION,
                    "reversal cannot change retained resource preparation ${preparation.resource.resourceId}")
            }
        }
        val blockers = observation.occupancy.blockingTrainIds(compiled.occupancyGeometry.intervals, intent.trainId)
        if (blockers.isNotEmpty()) {
            return RouteGrantReversalResult.Rejected(RouteAdmissionFailure(
                RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
                "another train occupies the reversed body route",
                blockers.map(RouteAdmissionBlocker::OccupyingTrain)
            ))
        }
        if (validation.invalidated || route.topologyInvalidated || latestObservation()?.stamp != observation.stamp) {
            return reversalRejected(RouteAdmissionFailureCode.STALE_OBSERVATION,
                "world changed while validating the reversed body route")
        }

        val stableBoundaries = compiled.tailReleaseEntries
            .mapNotNull { entry ->
                (entry.resource as? StableInfrastructureResource)?.let { it to entry.tailClearRouteMeters }
            }
            .toMap()
        val rebasedEntries = physicalCompiler.rebaseReleaseEntries(
            projection,
            PhysicalRouteRange(0.0, bodyEnd),
            retainedResources,
            stableBoundaries
        )
        val newTailRelease = RouteGrantTailRelease(
            worldId,
            intent.trainId,
            observation.stamp.physicsEpoch,
            initialized.cursor,
            LiveClaimTailRelease(
                registry,
                hostClaimOwner,
                intent.owner,
                CompiledTailReleaseQueue(rebasedEntries), { protectedClaimRemainder(intent.owner) }
            ),
            { reason, failure -> markWorldStalled(reason, failure) }
        )
        val oldGrant = checkNotNull(route.committedGrant)
        val grant = oldGrant.copy(
            routeId = intent.route.routeId,
            routeRevision = intent.route.revision,
            observationStamp = observation.stamp,
            committedAuthority = intent.desiredAuthority
        )

        route.projection = projection
        indexRoute(route, validation = validation)
        route.cursor = initialized.cursor
        route.routeProgressPath = physical.routeProgressPath
        route.tailRelease = newTailRelease
        route.preparationsByResource.clear()
        compiled.preparations.forEach { route.preparationsByResource[it.resource] = it }
        route.committedGrant = grant
        route.reversalFromTrainState = null
        publishAdmission(route)
        return RouteGrantReversalResult.Accepted(grant)
    }

    /** One already-published observation drives only routes with a committed grant. */
    @MainThread
    fun consumeObservation(observation: RailwayObservation): List<RouteGrantTailReleaseUpdate> {
        if (worldStalled || routes.values.none { it.committedGrant != null }) return emptyList()
        formations.consume(observation)
        if (worldStalled) return emptyList()
        val trains = observation.trains.associateBy { it.trainId }
        val updates = ArrayList<RouteGrantTailReleaseUpdate>(routes.size)
        for (route in routes.values) {
            if (route.committedGrant == null) continue
            if (route.topologyInvalidated) continue // Retain claims; never release from invalid progress geometry.
            if (route.reversalFromTrainState != null) continue
            if (route.formationChange != null) continue
            val update = checkNotNull(route.tailRelease).consume(observation.stamp, trains[route.trainId])
            publishAdmission(route)
            updates += update
            if (update is RouteGrantTailReleaseUpdate.FailedClosed) break
        }
        return updates
    }

    /** Ends a grant only after physical tail release emptied its claim queue. */
    @MainThread
    fun endGrant(requester: Any, grantId: RouteGrantId): Boolean {
        val route = routes.values.firstOrNull { it.committedGrant?.grantId === grantId } ?: return false
        if (route.requester !== requester || route.pendingHandle != null ||
            route.formationChange != null ||
            !checkNotNull(route.tailRelease).resourceQueueEmpty
        ) return false
        removeRoute(route.owner.ownerId)
        publishAdmission(route)
        return true
    }

    /** Administrative reset of an exact owner's committed grant after its train is stopped. */
    @MainThread
    fun forceEndRoute(requester: Any, owner: InfrastructureOwner): Boolean {
        val route = routes[owner.ownerId] ?: return true
        if (route.owner != owner || route.requester !== requester || route.formationChange != null) return false
        val observation = latestObservation() ?: return false
        if (observation.stamp.worldId != worldId ||
            observation.trains.singleOrNull { it.trainId == route.trainId }?.speedMetersPerSecond != 0.0) return false
        route.pendingHandle?.let { handle ->
            val request = pending[handle] ?: return false
            request.ticket.abort()
            when (request.ticket.snapshot().state) {
                ResourcePreparationState.PREPARING, ResourcePreparationState.UNSAFE -> return false
                ResourcePreparationState.READY -> if (!request.reservation.complete()) return false
                ResourcePreparationState.ABORTED_SAFE -> if (!request.reservation.rollback().successful) return false
            }
            pending.remove(handle)
            terminal.remove(handle)
            route.pendingHandle = null
        }
        val claim = registry.claimSnapshots().firstOrNull { it.owner == route.owner }
        if (claim != null && registry.releaseClaim(hostClaimOwner, route.owner).status != InfrastructureMutationStatus.RELEASED) return false
        removeRoute(route.owner.ownerId)
        route.committedGrant = null
        route.tailRelease = null
        publishAdmission(route)
        return true
    }

    @MainThread
    fun prepareFormationChange(requester: Any, intent: FixedFormationChangeIntent): FormationChangeResult {
        if (worldStalled) return FormationChangeResult.Rejected(RouteAdmissionFailure(
            RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled"))
        val ids = when (intent) {
            is FixedFormationChangeIntent.Couple -> listOf(intent.approachingGrantId, intent.stationaryGrantId)
            is FixedFormationChangeIntent.Split -> listOf(intent.sourceGrantId)
        }
        val bindings = ids.map { id ->
            val route = routes.values.firstOrNull { it.committedGrant?.grantId === id }
            if (route == null || route.requester !== requester) return FormationChangeResult.Rejected(
                RouteAdmissionFailure(RouteAdmissionFailureCode.OWNER_MISMATCH, "unknown or foreign source grant"))
            if (route.formationChange != null || route.tailRelease == null || route.pendingHandle != null || route.reversalFromTrainState != null)
                return FormationChangeResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.REQUEST_BUSY, "source grant is retained or busy"))
            FormationSourceGrant(checkNotNull(route.committedGrant),
                checkNotNull(route.tailRelease).remainingEntries.map { it.resource },
                route.preparationsByResource.toMap(),
                { route.pendingHandle != null || route.reversalFromTrainState != null || route.formationChange != null },
                { route.topologyInvalidated }, { route.formationChange = it })
        }
        return formations.prepare(requester, intent, bindings)
    }

    @MainThread
    fun sealFormationPublication(requester: Any, handle: FormationChangeHandle): FormationChangeResult =
        formations.seal(requester, handle)

    @MainThread
    fun formationChangeState(requester: Any, handle: FormationChangeHandle): FormationChangeResult =
        formations.snapshot(requester, handle)

    @MainThread
    fun abortFormationChange(requester: Any, handle: FormationChangeHandle): FormationChangeResult =
        formations.abort(requester, handle)

    @MainThread
    fun requestCouplingApproach(requester: Any, handle: FormationChangeHandle,
                                intent: RouteResourceIntent): RouteAdmissionResult =
        if (worldStalled) rejected(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled")
        else formations.requestApproach(requester, handle, intent)

    @MainThread
    fun bindFormationOutputs(requester: Any, handle: FormationChangeHandle, intents: List<RouteResourceIntent>): FormationChangeResult =
        if (worldStalled) FormationChangeResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled"))
        else formations.bindOutputs(requester, handle, intents)

    @MainThread
    fun requestFormationOutput(requester: Any, handle: FormationChangeHandle, intent: RouteResourceIntent): RouteAdmissionResult {
        if (worldStalled) return rejected(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled")
        if (formations.outputDetached(requester, handle, intent.trainId)) return request(requester, intent)
        return formations.requestOutput(requester, handle, intent)
    }

    @MainThread
    fun resumeFormationOutput(requester: Any, handle: FormationChangeHandle, grant: RouteGrantId): FormationChangeResult =
        if (worldStalled) FormationChangeResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled"))
        else formations.resumeOutput(requester, handle, grant)

    @MainThread
    fun finishFormationForManual(requester: Any, handle: FormationChangeHandle, grant: RouteGrantId): FormationChangeResult =
        if (worldStalled) FormationChangeResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled"))
        else formations.finishForManual(requester, handle, grant)

    private fun formationOutputBoundaries(compiled: FormationRouteCompilation.Compiled,
                                          resources: Collection<InfrastructureResource>): Map<InfrastructureResource, Double?> {
        val full = physicalCompiler.compile(compiled.projection, PhysicalRouteRange(0.0, compiled.intent.route.totalLengthMeters))
        check(full is PhysicalRouteCompilation.Compiled)
        val physical = full.admission.tailReleaseEntries.associate { it.resource to it.tailClearRouteMeters }
        val contributed = compiled.admission.tailReleaseEntries.associate { it.resource to it.tailClearRouteMeters }
        return resources.associateWith { resource -> when (resource) {
            is RailIntervalResource -> compiled.projection.occurrences.mapNotNull { occurrence ->
                if (occurrence.segment.railId != resource.railId) null else {
                    val low = maxOf(minOf(occurrence.canonicalStartAbMeters, occurrence.canonicalEndAbMeters), resource.startAbMeters)
                    val high = minOf(maxOf(occurrence.canonicalStartAbMeters, occurrence.canonicalEndAbMeters), resource.endAbMeters)
                    if (low > high) null else occurrence.routeStartMeters +
                        if (occurrence.segment.facing == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB)
                            high - occurrence.canonicalStartAbMeters else occurrence.canonicalStartAbMeters - low
                }
            }.maxOrNull()
            is StableInfrastructureResource -> physical[resource] ?: contributed[resource]
        } }
    }

    private fun publishFormationOutput(requester: Any, handle: FormationChangeHandle, output: FormationOutputRoute) {
        val compiled = output.compiled
        val old = routes[output.grant.owner.ownerId]
        old?.let(::unindexRoute)
        val route = RouteState(requester, output.grant.owner, output.grant.trainId, compiled.projection,
            output.cursor, compiled.admission.routeProgressPath, output.grant,
            preparationsByResource = compiled.admission.preparations.associateByTo(linkedMapOf()) { it.resource })
        route.formationChange = handle
        route.formationContext = compiled.intent.formationContext
        routes[route.owner.ownerId] = route
        indexRoute(route)
        publishAdmission(route)
    }

    private fun completeFormationOutputs(requester: Any, sources: List<FormationSourceGrant>, outputs: List<FormationOutputRoute>) {
        val owners = outputs.map { it.grant.owner }.toSet()
        sources.filter { it.grant.owner !in owners }.forEach { source ->
            routes.remove(source.grant.owner.ownerId)?.let { route ->
                unindexRoute(route)
                publishAdmissionUpdate(ActiveRouteAdmissionUpdate(route.owner, null))
            }
        }
    }

    private fun endFormationForManual(requester: Any, handle: FormationChangeHandle,
        sources: List<FormationSourceGrant>, outputs: List<FormationOutputRoute>) {
        val owners = (sources.map { it.grant.owner } + outputs.map { it.grant.owner }).distinct()
        val ending = owners.mapNotNull { owner -> routes[owner.ownerId] }.onEach { route ->
            check(route.requester === requester && route.formationChange === handle && route.pendingHandle == null)
        }
        ending.forEach { route ->
            removeRoute(route.owner.ownerId)
            publishAdmissionUpdate(ActiveRouteAdmissionUpdate(route.owner, null))
        }
    }

    /** Exact original remainder is owned by formation groups, not this route's release queue. */
    private fun protectedClaimRemainder(owner: InfrastructureOwner): Collection<InfrastructureResource> {
        val originals = formations.retainedResources(owner)
        if (originals.isEmpty()) return emptyList()
        val pendingDelta = routes[owner.ownerId]?.pendingHandle?.let(pending::get)?.deltaEntries.orEmpty().map { it.resource }
        return originals + pendingDelta
    }

    private fun detachFormationOutput(requester: Any, output: FormationOutputRoute) {
        val route = checkNotNull(routes[output.grant.owner.ownerId])
        check(route.requester === requester && !route.topologyInvalidated)
        val entries = output.exclusive.map { ResolvedTailReleaseEntry(it.key, it.value) }.sortedWith(TAIL_RELEASE_ENTRY_ORDER)
        route.tailRelease = RouteGrantTailRelease(worldId, output.grant.trainId,
            checkNotNull(latestObservation()).stamp.physicsEpoch, output.cursor,
            LiveClaimTailRelease(registry, hostClaimOwner, output.grant.owner,
                if (entries.isEmpty()) null else CompiledTailReleaseQueue(entries),
                { protectedClaimRemainder(output.grant.owner) }), ::markWorldStalled)
        route.formationChange = null
        route.formationContext = null
        publishAdmission(route)
    }

    private fun publishFormationApproach(compiled: FormationRouteCompilation.Compiled, grant: RouteGrantSnapshot) {
        val route = checkNotNull(routes[grant.owner.ownerId])
        check(route.formationChange != null && route.owner == grant.owner && route.trainId == grant.trainId)
        route.projection = compiled.projection
        route.routeProgressPath = compiled.admission.routeProgressPath
        route.committedGrant = grant
        route.formationContext = compiled.intent.formationContext
        compiled.admission.preparations.forEach { route.preparationsByResource[it.resource] = it }
        indexRoute(route)
        publishAdmission(route)
    }

    private fun compileFormationRoute(intent: RouteResourceIntent, movementFrom: Double?,
                                      priorCursor: RouteProgressCursorState? = null,
                                      contactProof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof? = null): FormationRouteCompilation =
        validateTopology(intent.route, contactProof?.protectedTraversal.orEmpty().map { it.railId }) { validation ->
            fun reject(code: RouteAdmissionFailureCode, detail: String) =
                FormationRouteCompilation.Rejected(RouteAdmissionFailure(code, detail))
            val observation = latestObservation()
                ?: return@validateTopology reject(RouteAdmissionFailureCode.STALE_OBSERVATION, "no coherent observation")
            if (worldStalled || intent.observationStamp.worldId != worldId || intent.observationStamp != observation.stamp)
                return@validateTopology reject(RouteAdmissionFailureCode.STALE_OBSERVATION, "formation observation is stale")
            val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
                ?: return@validateTopology reject(RouteAdmissionFailureCode.TRAIN_NOT_FOUND, "formation train is absent")
            val topology = currentTopology()
            train.invalidTopologyReason(topology)?.let {
                return@validateTopology reject(RouteAdmissionFailureCode.STALE_TOPOLOGY, it)
            }
            val projected = TopologyRouteProjector.project(intent.route, topology)
            if (projected !is TopologyRouteProjectionResult.Projected)
                return@validateTopology reject(RouteAdmissionFailureCode.INVALID_ROUTE,
                    (projected as TopologyRouteProjectionResult.Rejected).detail)
            val path = CompiledRouteProgressPath.compile(projected.route)
            val initial = path.initializeCursor(train.tail)
            val headCursor = if (priorCursor != null) RouteProgressCursor(path.occurrences,
                priorCursor.occurrenceIndex, priorCursor.routeDistanceMeters)
            else (initial as? RouteProgressCursorInitialization.Initialized)?.cursor
                ?: return@validateTopology reject(RouteAdmissionFailureCode.INVALID_ROUTE, "actual tail does not initialize the route")
            val tail = headCursor.update(train.tail)
            if (tail !is RouteProgressCursorUpdate.Resolved)
                return@validateTopology reject(RouteAdmissionFailureCode.INVALID_ROUTE, "output tail left its authoritative route cursor")
            val floorStart = if (priorCursor == null) 0.0 else tail.state.routeDistanceMeters
            // Contact authority still ends at contactHead. The physical link also publishes both
            // connectors and the target body, which may cover a bridge beyond that head. Compile
            // the Core-certified complete corridor with the ordinary compiler/contributors now,
            // before permitting contact; otherwise actual output binding can find an unclaimed gap.
            val policyIntent: RouteResourceIntent
            val protection: ProjectedTopologyRoute
            if (contactProof == null) {
                policyIntent = intent
                protection = projected.route
            } else {
                val segments = contactProof.protectedTraversal.map { span ->
                    val rail = topology.railProjection[span.railId]?.singleOrNull()
                        ?: return@validateTopology reject(RouteAdmissionFailureCode.STALE_TOPOLOGY, "contact protection rail is absent")
                    val length = kotlin.math.abs(rail.railEndAbMeters - rail.railStartAbMeters)
                    work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot(span.railId, span.facing,
                        if (span.facing == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB) span.startArcLengthInAbMeters else length - span.startArcLengthInAbMeters,
                        if (span.facing == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB) span.endArcLengthInAbMeters else length - span.endArcLengthInAbMeters)
                }
                val route = RouteSnapshot("${intent.route.routeId}/contact-protection", intent.route.revision, intent.route.topologyRevision, segments)
                policyIntent = intent.copy(route = route, desiredAuthority = RouteBoundary(route.totalLengthMeters))
                protection = when (val result = TopologyRouteProjector.project(route, topology)) {
                    is TopologyRouteProjectionResult.Projected -> result.route
                    is TopologyRouteProjectionResult.Rejected -> return@validateTopology reject(RouteAdmissionFailureCode.INVALID_ROUTE, result.detail)
                }
            }
            val physicalResult = physicalCompiler.compile(protection,
                PhysicalRouteRange(floorStart, policyIntent.desiredAuthority.distanceFromRouteStartMeters))
            if (physicalResult !is PhysicalRouteCompilation.Compiled)
                return@validateTopology reject(RouteAdmissionFailureCode.INVALID_ROUTE,
                    (physicalResult as PhysicalRouteCompilation.Rejected).detail)
            val head = headCursor.project(train.head)
            if (head !is RouteProgressCursorUpdate.Resolved ||
                head.state.routeDistanceMeters > intent.desiredAuthority.distanceFromRouteStartMeters + Curve.TOLERANCE)
                return@validateTopology reject(RouteAdmissionFailureCode.INVALID_BOUNDARY, "authority precedes observed head")
            val collected = try { collectContributions(policyIntent) } catch (failure: Exception) {
                return@validateTopology reject(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                    "formation contributor failed: ${failure.message}")
            }
            val contributions = validateContributions(collected, policyIntent.desiredAuthority.distanceFromRouteStartMeters,
                "formation contribution exceeds requested authority") { true }
            if (contributions is ContributionValidation.Rejected)
                return@validateTopology FormationRouteCompilation.Rejected(contributions.failure)
            val physicalAdmission = physicalResult.admission
            val admission = if (contactProof == null) physicalAdmission else CompiledRouteAdmission.create(
                physicalAdmission.resources, physicalAdmission.preparations, physicalAdmission.occupancyGeometry,
                physicalAdmission.tailReleaseEntries, path)
            val merged = admission.withContributions((contributions as ContributionValidation.Accepted).contributions.filter {
                priorCursor == null || it.releaseAfterTailPasses.distanceFromRouteStartMeters > floorStart
            })
            if (merged is RouteContributionsCompilation.Rejected)
                return@validateTopology FormationRouteCompilation.Rejected(merged.failure)
            if (formations.overlapsRetained((merged as RouteContributionsCompilation.Compiled).admission.resources.resources,
                    intent.formationContext?.eventId)) return@validateTopology reject(RouteAdmissionFailureCode.REQUEST_BUSY,
                "formation route overlaps another event's retained protection")
            val occupancy = if (movementFrom != null &&
                intent.desiredAuthority.distanceFromRouteStartMeters > movementFrom + 1e-8) {
                when (val movement = physicalCompiler.compile(projected.route,
                    PhysicalRouteRange(movementFrom, intent.desiredAuthority.distanceFromRouteStartMeters))) {
                    is PhysicalRouteCompilation.Compiled -> movement.admission.occupancyGeometry.intervals
                    is PhysicalRouteCompilation.Rejected -> return@validateTopology reject(
                        RouteAdmissionFailureCode.INVALID_ROUTE, movement.detail)
                }
            } else emptyList()
            val blockers = observation.occupancy.blockingTrainIds(occupancy, intent.trainId).filterNot { blocker ->
                contactProof != null && blocker == contactProof.request.stationaryTrainId &&
                    formationWorld?.validateCouplingApproachGeometry(contactProof) == true &&
                    certifiedContactBoundaryOnly(contactProof, observation, occupancy)
            }
            if (blockers.isNotEmpty()) return@validateTopology FormationRouteCompilation.Rejected(RouteAdmissionFailure(
                RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED, "another train occupies the formation movement",
                blockers.map(RouteAdmissionBlocker::OccupyingTrain)))
            if (contactProof != null) {
                val protectedIntervals = physicalAdmission.occupancyGeometry.intervals
                val protectedBlockers = observation.occupancy.blockingTrainIds(protectedIntervals, intent.trainId).filterNot { blocker ->
                    blocker == contactProof.request.stationaryTrainId &&
                        formationWorld?.validateCouplingApproachGeometry(contactProof) == true &&
                        certifiedStationaryBodyOnly(contactProof, observation, protectedIntervals)
                }
                if (protectedBlockers.isNotEmpty()) return@validateTopology FormationRouteCompilation.Rejected(RouteAdmissionFailure(
                    RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED, "another train occupies the coupling protection corridor",
                    protectedBlockers.map(RouteAdmissionBlocker::OccupyingTrain)))
            }
            if (validation.invalidated || latestObservation() !== observation)
                return@validateTopology reject(RouteAdmissionFailureCode.STALE_OBSERVATION, "formation changed during validation")
            FormationRouteCompilation.Compiled(intent, observation, train, projected.route,
                (merged as RouteContributionsCompilation.Compiled).admission, head.state.routeDistanceMeters, occupancy, policyIntent)
        }

    /** Protection may retain the exact captured peer body; movement keeps its stricter contact-only check. */
    private fun certifiedStationaryBodyOnly(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof,
        observation: RailwayObservation,
        intervals: Collection<work.art1st.javarailways.core.api.physics.rail.RailInterval>
    ): Boolean {
        val peer = observation.trains.singleOrNull { it.trainId == proof.request.stationaryTrainId } ?: return false
        if (peer.speedMetersPerSecond != 0.0 || peer.derailed || peer.head != proof.stationaryFrame.head ||
            peer.tail != proof.stationaryFrame.tail || peer.bodyTraversal != proof.stationaryFrame.bodyTraversal) return false
        val captured = proof.stationaryFrame.occupiedIntervals.map {
            RailIntervalResource(it.railId, it.minArcLengthInAbMeters, it.maxArcLengthInAbMeters)
        }
        return intervals.all { interval -> peer.sweptSpans.filter { it.railId == interval.railId }.all { span ->
            val start = maxOf(interval.minArcLengthInAbMeters, span.minArcAB)
            val end = minOf(interval.maxArcLengthInAbMeters, span.maxArcAB)
            start > end || uncoveredResources(listOf(RailIntervalResource(interval.railId, start, end)), captured).isEmpty()
        } }
    }

    /** Only the certified terminal contact may meet its stopped peer's boundary. No body overlap exemption. */
    private fun certifiedContactBoundaryOnly(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof,
        observation: RailwayObservation,
        intervals: Collection<work.art1st.javarailways.core.api.physics.rail.RailInterval>
    ): Boolean {
        val peer = observation.trains.singleOrNull { it.trainId == proof.request.stationaryTrainId } ?: return false
        if (peer.speedMetersPerSecond != 0.0 || peer.derailed || peer.head != proof.stationaryFrame.head ||
            peer.tail != proof.stationaryFrame.tail || peer.bodyTraversal != proof.stationaryFrame.bodyTraversal) return false
        val contact = proof.contactHead
        val near = intervals.flatMap { interval -> peer.sweptSpans.filter { span ->
            span.railId == interval.railId && span.minArcAB <= interval.maxArcLengthInAbMeters + Curve.TOLERANCE &&
                interval.minArcLengthInAbMeters <= span.maxArcAB + Curve.TOLERANCE
        }.map { interval to it } }
        return near.isNotEmpty() && near.all { (interval, span) ->
            interval.railId == contact.railId && if (contact.facing == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB) {
                kotlin.math.abs(interval.maxArcLengthInAbMeters - contact.arcLengthInAbMeters) <= 1e-8 &&
                    span.minArcAB >= contact.arcLengthInAbMeters - 1e-8
            } else {
                kotlin.math.abs(interval.minArcLengthInAbMeters - contact.arcLengthInAbMeters) <= 1e-8 &&
                    span.maxArcAB <= contact.arcLengthInAbMeters + 1e-8
            }
        }
    }

    @OptIn(work.art1st.javarailways.core.api.util.annotation.AsyncEventApi::class)
    fun observeFormationCollision(event: work.art1st.javarailways.core.api.event.CollisionEvent) = formations.collision(event)

    fun disableFormationContact() = formations.disableContactPermissions()

    @MainThread
    fun guardFormationLink(event: work.art1st.javarailways.core.api.event.TrainLinkEvent) {
        if (worldStalled) { event.reject("admission world is stalled"); return }
        val controlled = routes.values.filter { it.trainId == event.firstTrain.id || it.trainId == event.secondTrain.id }
        if (controlled.any { it.committedGrant != null && it.formationChange == null })
            event.reject("live route grants require a prepared formation event")
        formations.guardLink(event)
    }

    @MainThread
    fun guardFormationSplit(event: work.art1st.javarailways.core.api.event.TrainSplitEvent) {
        if (worldStalled) { event.reject("admission world is stalled"); return }
        if (routes.values.any { it.trainId == event.train.id && it.committedGrant != null && it.formationChange == null })
            event.reject("live route grants require a prepared formation event")
        formations.guardSplit(event)
    }

    @MainThread
    fun observedFormationLink(event: work.art1st.javarailways.core.api.event.TrainLinkSuccessEvent) = formations.observedLink(event)

    @MainThread
    fun observedFormationSplit(event: work.art1st.javarailways.core.api.event.TrainSplitSuccessEvent) = formations.observedSplit(event)

    /**
     * Returns only route states that still protect a registry reservation/release-queue entry or
     * have an in-flight request. An emptied committed route state is deliberately not active: it
     * can be discarded safely when its requester unregisters even if [endGrant] was not called.
     */
    @MainThread
    fun requesterActivity(requester: Any): List<RouteAdmissionRequesterActivity> {
        val claimsByOwner = registry.claimSnapshots().associateBy { it.owner }
        val ordinary = routes.values.mapNotNull { route ->
            if (route.requester !== requester) return@mapNotNull null
            routeActivity(route, claimsByOwner[route.owner]?.footprint?.resources?.size ?: 0)
        }
        return ordinary + formations.retainedActivity(requester).filter { retained -> ordinary.none { it.owner == retained.owner } }
    }

    /** Active routes that still depend on a stable resource type owned by one provider. */
    @MainThread
    fun providerActivity(typeIds: Set<String>): List<RouteAdmissionRequesterActivity> {
        if (typeIds.isEmpty()) return emptyList()
        val claimsByOwner = registry.claimSnapshots().associateBy { it.owner }
        val ordinary = routes.values.mapNotNull { route ->
            val claimed = claimsByOwner[route.owner]?.footprint?.resources.orEmpty()
            val committed = route.tailRelease?.remainingEntries.orEmpty().map { it.resource }
            val pendingResources = route.pendingHandle?.let(pending::get)
                ?.compiled?.resources?.resources.orEmpty()
            val matchingTypes = (claimed + committed + pendingResources)
                .asSequence()
                .filterIsInstance<StableInfrastructureResource>()
                .map(StableInfrastructureResource::typeId)
                .filter(typeIds::contains)
                .toSortedSet()
            if (matchingTypes.isEmpty()) return@mapNotNull null
            val activity = routeActivity(route, claimed.size) ?: return@mapNotNull null
            activity.copy(resourceTypeIds = matchingTypes)
        }
        return ordinary + formations.retainedActivity(typeIds = typeIds).filter { retained -> ordinary.none { it.owner == retained.owner } }
    }

    /**
     * Returns a detached, owner-ordered view of requests that are still preparing or granted.
     * Terminal attempts are absent even when their opaque handle remains weakly inspectable.
     */
    @MainThread
    fun activeAdmissions(): List<ActiveRouteAdmissionSnapshot> {
        val snapshots = routes.values.mapNotNull(::admissionSnapshot)
        return Collections.unmodifiableList(ArrayList(snapshots))
    }

    private fun admissionSnapshot(route: RouteState): ActiveRouteAdmissionSnapshot? {
        if (routes[route.owner.ownerId] !== route) return null
        val activePending = route.pendingHandle?.let(pending::get)
        val grant = route.committedGrant
        if (activePending == null && (grant == null || route.tailRelease?.resourceQueueEmpty == true)) return null
        return ActiveRouteAdmissionSnapshot(
            route.owner, route.trainId, activePending?.intent?.route ?: route.projection.route,
            if (activePending == null) ActiveRouteAdmissionState.GRANTED else ActiveRouteAdmissionState.PREPARING,
            activePending?.intent?.desiredAuthority ?: checkNotNull(grant).committedAuthority,
            grant?.committedAuthority,
            if (route.formationContext == null) route.tailRelease?.releasedThroughRouteMeters ?: 0.0 else 0.0,
            route.formationContext
        )
    }

    private fun publishAdmission(route: RouteState) {
        val snapshot = admissionSnapshot(route)
        if (snapshot == route.publishedAdmission) return
        route.publishedAdmission = snapshot
        publishAdmissionUpdate(ActiveRouteAdmissionUpdate(route.owner, snapshot))
    }

    /** Contributor membership is frozen while any route state may request another suffix. */
    @MainThread
    fun hasRouteState(): Boolean = routes.isNotEmpty() || formations.hasRetainedResources()

    /** A forced policy loss cannot silently make later route extensions less restrictive. */
    @MainThread
    fun failClosedContributorLoss(contributorDescription: String): Boolean {
        if (!hasRouteState()) return false
        markWorldStalled(
            "route-resource contributor $contributorDescription disappeared while route states remain; " +
                "future admissions disabled",
            IllegalStateException("route-resource contributor disappeared before routes ended")
        )
        return true
    }

    /** Latches the whole admission owner after loss of a non-route safety dependency. */
    @MainThread
    fun failClosedInfrastructureDependencyLoss(dependencyDescription: String) {
        markWorldStalled(
            "$dependencyDescription disappeared while an infrastructure dependency remains active",
            IllegalStateException("infrastructure dependency owner disappeared")
        )
    }

    /** Removes requester references only when no pending request or registry resource remains. */
    @MainThread
    fun discardInactiveRequester(requester: Any): Boolean {
        if (requesterActivity(requester).isNotEmpty()) return false
        val removed = routes.values.filter { it.requester === requester }
        removed.forEach { removeRoute(it.owner.ownerId); publishAdmission(it) }
        terminal.entries.removeIf { it.value.requester === requester }
        return true
    }

    /**
     * A platform requester disappeared without first unregistering safely. Claims and reservations
     * remain untouched; the existing world Stall owner receives one precise diagnostic.
     */
    @MainThread
    fun failClosedRequesterLoss(
        requester: Any,
        requesterDescription: String
    ): List<RouteAdmissionRequesterActivity> {
        val activity = requesterActivity(requester)
        if (activity.isEmpty()) {
            discardInactiveRequester(requester)
            return emptyList()
        }
        val detail = activity.joinToString { active ->
            "${active.owner.ownerId}(train=${active.trainId}, pending=${active.pendingRequest}, " +
                "heldResources=${active.heldResourceCount})"
        }
        markWorldStalled(
            "route-admission requester $requesterDescription disappeared with active resources: " +
                "$detail; host-owned claims retained",
            IllegalStateException("route-admission requester disappeared before releasing resources")
        )
        return activity
    }

    /** Retains routes that still use resources whose provider disappeared, then stalls the world. */
    @MainThread
    fun failClosedProviderLoss(
        typeIds: Set<String>,
        providerDescription: String
    ): List<RouteAdmissionRequesterActivity> {
        val activity = providerActivity(typeIds)
        if (activity.isEmpty()) return emptyList()
        val detail = activity.joinToString { active ->
            "${active.owner.ownerId}(train=${active.trainId}, types=${active.resourceTypeIds.joinToString()})"
        }
        markWorldStalled(
            "infrastructure provider $providerDescription disappeared with active resources: " +
                "$detail; host-owned claims retained",
            IllegalStateException("infrastructure provider disappeared before its resources were released")
        )
        return activity
    }

    private fun routeActivity(
        route: RouteState,
        claimResourceCount: Int
    ): RouteAdmissionRequesterActivity? {
        val releaseQueueCount = route.tailRelease?.remainingEntries?.size ?: 0
        // Either side being nonempty is safety-relevant. In the ordinary case they agree; using
        // the larger value also prevents lifecycle cleanup from hiding an invariant mismatch.
        val heldResourceCount = maxOf(claimResourceCount, releaseQueueCount)
        val pendingRequest = route.pendingHandle != null
        if (!pendingRequest && heldResourceCount == 0) return null
        return RouteAdmissionRequesterActivity(route.owner, route.trainId, pendingRequest, heldResourceCount)
    }

    private fun mapMutation(result: InfrastructureMutationResult): RouteAdmissionResult = when (result.status) {
        InfrastructureMutationStatus.RESOURCE_CONFLICT -> RouteAdmissionResult.Rejected(RouteAdmissionFailure(
            RouteAdmissionFailureCode.RESOURCE_CONFLICT,
            result.detail.ifBlank { "another owner holds the requested resources" },
            result.blockers.flatMap { blocker -> blocker.overlappingResources.map { resource ->
                RouteAdmissionBlocker.ConflictingResource(resource, blocker.owner)
            } }
        ))
        InfrastructureMutationStatus.WORLD_UNAVAILABLE,
        InfrastructureMutationStatus.SERVICE_CLOSED -> rejected(RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            result.detail.ifBlank { "resource registry is unavailable" })
        InfrastructureMutationStatus.INVALID_RESOURCE,
        InfrastructureMutationStatus.RESOURCE_PROVIDER_FAILED -> rejected(RouteAdmissionFailureCode.INVALID_INFRASTRUCTURE,
            result.detail.ifBlank { "claimed infrastructure resource is unavailable" })
        else -> rejected(RouteAdmissionFailureCode.OWNER_MISMATCH,
            result.detail.ifBlank { "logical claim owner is unavailable" })
    }

    /**
     * Applies the contribution rules shared by ordinary and reversal requests. Contributor values
     * are checked against the request range, deduplicated by physical identity, and validated by the
     * host-owned callback. Only the callback call itself converts an exception into a rejection so
     * later merge invariants are not mislabeled as owner-validation failures.
     */
    private fun validateContributions(
        authenticated: List<Pair<Any, ContributedRouteResource>>,
        maxRouteMeters: Double,
        boundaryDetail: String,
        retain: (ContributedRouteResource) -> Boolean
    ): ContributionValidation {
        val seen = HashSet<StableInfrastructureResource>()
        val retained = ArrayList<ContributedRouteResource>(authenticated.size)
        for ((contributor, contribution) in authenticated) {
            if (contribution.releaseAfterTailPasses.distanceFromRouteStartMeters > maxRouteMeters) {
                return ContributionValidation.Rejected(RouteAdmissionFailure(
                    RouteAdmissionFailureCode.INVALID_BOUNDARY,
                    boundaryDetail
                ))
            }
            if (!seen.add(contribution.resource)) {
                return ContributionValidation.Rejected(RouteAdmissionFailure(
                    RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                    "duplicate contributed resource " +
                        "${contribution.resource.typeId}/${contribution.resource.resourceId}"
                ))
            }
            val failure = try {
                validateContribution(contributor, contribution)
            } catch (failure: Exception) {
                return ContributionValidation.Rejected(RouteAdmissionFailure(
                    RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                    "resource owner validation failed: ${failure.message ?: failure.javaClass.simpleName}"
                ))
            }
            if (failure != null) return ContributionValidation.Rejected(failure)
            if (retain(contribution)) retained += contribution
        }
        return ContributionValidation.Accepted(retained)
    }

    private fun firstUncoveredResource(
        required: Collection<InfrastructureResource>,
        retained: Collection<InfrastructureResource>
    ): InfrastructureResource? {
        val retainedStable = retained.filterIsInstance<StableInfrastructureResource>().toSet()
        val retainedRails = retained.filterIsInstance<RailIntervalResource>()
            .groupBy { it.railId }
            .mapValues { (_, intervals) -> intervals.sortedBy { it.startAbMeters } }
        return required.firstOrNull { resource ->
            when (resource) {
                is StableInfrastructureResource -> resource !in retainedStable
                is RailIntervalResource -> {
                    var coveredUntil = resource.startAbMeters
                    for (held in retainedRails[resource.railId].orEmpty()) {
                        if (held.endAbMeters < coveredUntil - Curve.TOLERANCE) continue
                        if (held.startAbMeters > coveredUntil + Curve.TOLERANCE) break
                        coveredUntil = maxOf(coveredUntil, held.endAbMeters)
                        if (coveredUntil >= resource.endAbMeters - Curve.TOLERANCE) break
                    }
                    coveredUntil < resource.endAbMeters - Curve.TOLERANCE
                }
            }
        }
    }

    private fun stalled(intent: RouteResourceIntent, cause: Exception, detail: String): RouteAdmissionResult {
        markWorldStalled("route admission ${intent.requestId}: $detail; safest claim retained", cause)
        return rejected(RouteAdmissionFailureCode.WORLD_STALLED, detail)
    }

    private fun markWorldStalled(reason: String, cause: Exception) {
        if (worldStalled) return
        worldStalled = true
        formations.disableContactPermissions()
        stallWorld(reason, cause)
    }

    private fun rejected(code: RouteAdmissionFailureCode, detail: String) =
        RouteAdmissionResult.Rejected(RouteAdmissionFailure(code, detail))

    private fun reversalRejected(code: RouteAdmissionFailureCode, detail: String) =
        RouteGrantReversalResult.Rejected(RouteAdmissionFailure(code, detail))

    private companion object {
        const val STOPPED_SPEED_EPSILON_METERS_PER_SECOND = 1.0e-6
    }
}

/** Internal composition diagnostic for one requester's resource-bearing route state. */
data class RouteAdmissionRequesterActivity(
    val owner: InfrastructureOwner,
    val trainId: String,
    val pendingRequest: Boolean,
    val heldResourceCount: Int,
    val resourceTypeIds: Set<String> = emptySet()
) {
    init {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(heldResourceCount >= 0) { "heldResourceCount must be non-negative" }
        require(pendingRequest || heldResourceCount > 0) { "activity must protect a request or resource" }
        require(resourceTypeIds.all { it.isNotBlank() }) { "resource type IDs must not be blank" }
    }
}
