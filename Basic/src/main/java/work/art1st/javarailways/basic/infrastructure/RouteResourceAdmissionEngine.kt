package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.RouteAdmissionBlocker
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.util.annotation.MainThread

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
    private val collectContributions: (RouteResourceIntent) -> List<Pair<Any, ContributedRouteResource>> = { emptyList() }
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

    private class RouteState(
        val requester: Any,
        val owner: InfrastructureOwner,
        val trainId: String,
        var projection: ProjectedTopologyRoute,
        var cursor: RouteProgressCursor,
        var routeProgressPath: CompiledRouteProgressPath,
        var committedGrant: RouteGrantSnapshot? = null,
        var tailRelease: RouteGrantTailRelease? = null,
        var controlledResources: Set<StableInfrastructureResource>,
        val preparationsByResource: MutableMap<InfrastructureResource, InfrastructurePreparationRequirement>,
        var pendingHandle: RouteAdmissionHandle? = null,
        var reversalFromTrainState: Boolean? = null
    )

    private data class ReservedDelta(
        val result: InfrastructureMutationResult,
        val complete: () -> Boolean,
        val rollback: () -> InfrastructureMutationResult
    )

    private inner class PendingRequest(
        val handle: RouteAdmissionHandle,
        val intent: RouteResourceIntent,
        val route: RouteState,
        val compiled: CompiledRouteAdmission,
        val deltaEntries: List<ResolvedTailReleaseEntry>,
        val newlyControlledResources: Set<StableInfrastructureResource>,
        val candidateProjection: ProjectedTopologyRoute,
        val appendsRoute: Boolean,
        val topologyRoot: TopologyState,
        val reservation: ReservedDelta,
        val ticket: ResourcePreparationTicket
    ) {
        fun progress(): RouteAdmissionResult {
            if (worldStalled) {
                return rejected(RouteAdmissionFailureCode.WORLD_STALLED,
                    "world is already stalled; reservation retained")
            }
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
                        observation.stamp.topologyRevision == topologyRoot.revision &&
                        currentTopology() === topologyRoot
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
                    check(currentTopology() === topologyRoot &&
                        latestObservation()?.stamp == observation.stamp && !worldStalled
                    ) { "world changed while checking the prepared route" }
                    if (oldGrant != null) {
                        checkNotNull(route.tailRelease).validateExtendedSuffix(deltaEntries)
                    }
                    check(reservation.complete()) { "resource reservation disappeared" }
                    if (oldGrant == null) {
                        route.tailRelease = RouteGrantTailRelease(
                            worldId, intent.route.topologyRevision, intent.trainId,
                            observation.stamp.physicsEpoch, route.cursor,
                            LiveClaimTailRelease(
                                registry, hostClaimOwner, intent.owner,
                                CompiledTailReleaseQueue(deltaEntries)
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
                    route.controlledResources += newlyControlledResources
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
                        check(observation.stamp.worldId == worldId &&
                            observation.stamp.topologyRevision == topologyRoot.revision &&
                            currentTopology() === topologyRoot
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
                        routes.remove(intent.owner.ownerId)
                    } else {
                        val release = checkNotNull(route.tailRelease).rollbackExtendedHolds()
                        check(release !is LiveClaimTailReleaseResult.FailedClosed) {
                            "safe request abort could not reconcile tail release"
                        }
                        route.pendingHandle = null
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
    fun request(requester: Any, intent: RouteResourceIntent): RouteAdmissionResult {
        if (worldStalled) return rejected(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled")
        if (intent.observationStamp.worldId != worldId) {
            return rejected(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "request belongs to another world")
        }
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
        if (topology.revision != intent.route.topologyRevision ||
            topology.revision != observation.stamp.topologyRevision ||
            existing != null && topology !== existing.projection.topology
        ) {
            return rejected(RouteAdmissionFailureCode.STALE_TOPOLOGY, "frozen route topology changed")
        }
        val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
            ?: return rejected(RouteAdmissionFailureCode.TRAIN_NOT_FOUND, "train is absent from observation")
        val projection = if (existing == null || appendsRoute) {
            when (val projected = TopologyRouteProjector.project(intent.route, topology)) {
                is TopologyRouteProjectionResult.Projected -> projected.route
                is TopologyRouteProjectionResult.Rejected -> return rejected(
                    if (projected.reason == TopologyRouteProjectionRejection.STALE_TOPOLOGY)
                        RouteAdmissionFailureCode.STALE_TOPOLOGY else RouteAdmissionFailureCode.INVALID_ROUTE,
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
        val physicalResult = physicalCompiler.compile(
            projection, PhysicalRouteRange(oldFrontier, newFrontier,
                includeTurnoutAtStart = existing == null || appendsRoute),
            existing?.controlledResources ?: emptySet(), progressPath
        )
        val physical = when (physicalResult) {
            is PhysicalRouteCompilation.Compiled -> physicalResult.admission
            is PhysicalRouteCompilation.Rejected -> return rejected(
                if (physicalResult.reason == PhysicalRouteCompilationRejection.STALE_TOPOLOGY)
                    RouteAdmissionFailureCode.STALE_TOPOLOGY else RouteAdmissionFailureCode.INVALID_ROUTE,
                physicalResult.detail
            )
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
            return rejected(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "route-resource contributor failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
        val contributionOwnerByResource = LinkedHashMap<StableInfrastructureResource, Any>()
        val contributions = ArrayList<ContributedRouteResource>(authenticatedContributions.size)
        for ((contributor, contribution) in authenticatedContributions) {
            if (contribution.releaseAfterTailPasses.distanceFromRouteStartMeters > newFrontier) {
                return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                    "contributed resource release boundary exceeds desired authority")
            }
            if (contributionOwnerByResource.putIfAbsent(contribution.resource, contributor) != null) {
                return rejected(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                    "duplicate contributed resource ${contribution.resource.typeId}/${contribution.resource.resourceId}")
            }
            contributions += contribution
        }
        val release = existing?.tailRelease
        val held = release?.remainingEntries?.associateBy(ResolvedTailReleaseEntry::resource).orEmpty()
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
        val merged = try {
            physical.withContributions(contributions) { contribution ->
                validateContribution(contributionOwnerByResource.getValue(contribution.resource), contribution)
            }
        } catch (failure: Exception) {
            return rejected(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "resource owner validation failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
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
        if (currentTopology() !== topology) {
            return rejected(RouteAdmissionFailureCode.STALE_TOPOLOGY, "topology changed during validation")
        }
        if (latestObservation()?.stamp != observation.stamp) {
            return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, "observation changed during validation")
        }
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
        val reservation = if (release == null || release.resourceQueueEmpty) {
            val mutation = registry.beginAcquireClaim(hostClaimOwner,
                InfrastructureClaimRequest(topology.revision, intent.owner, InfrastructureFootprint(deltaResources)))
            ReservedDelta(mutation.result, { registry.completeClaimMutation(mutation) },
                { registry.rollbackClaimMutation(mutation) })
        } else {
            val append = registry.beginAppendClaim(hostClaimOwner, intent.owner, topology.revision, deltaResources)
            ReservedDelta(append.result, { registry.completeClaimAppend(append) },
                { registry.rollbackClaimAppend(append) })
        }
        if (reservation.result.status != InfrastructureMutationStatus.ACQUIRED &&
            reservation.result.status != InfrastructureMutationStatus.REPLACED
        ) return mapMutation(reservation.result)
        val route = existing ?: RouteState(
            requester, intent.owner, intent.trainId, projection, checkNotNull(initialCursor),
            physical.routeProgressPath, controlledResources = emptySet(),
            preparationsByResource = LinkedHashMap()
        ).also { routes[intent.owner.ownerId] = it }
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
            preparationActuator.start(preparations)
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
                    routes.remove(intent.owner.ownerId)
                } else {
                    check(release!!.rollbackExtendedHolds() !is LiveClaimTailReleaseResult.FailedClosed) {
                        "rejected preparation could not restore old release boundaries"
                    }
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
            physicalResult.controlledResources, projection, appendsRoute, topology,
            reservation, (started as InfrastructurePreparationStart.Accepted).ticket)
        return progress(requester, handle)
    }

    @MainThread
    fun progress(requester: Any, handle: RouteAdmissionHandle): RouteAdmissionResult {
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
        if (route.pendingHandle != null) {
            return reversalRejected(RouteAdmissionFailureCode.REQUEST_BUSY,
                "route grant has a pending admission")
        }
        val grant = checkNotNull(route.committedGrant)
        route.reversalFromTrainState?.let { return RouteGrantReversalResult.Accepted(grant) }
        val observation = latestObservation()
            ?: return reversalRejected(RouteAdmissionFailureCode.STALE_OBSERVATION,
                "no published observation")
        if (observation.stamp.worldId != worldId ||
            observation.stamp.topologyRevision != route.projection.topology.revision ||
            currentTopology() !== route.projection.topology
        ) {
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
    ): RouteGrantReversalResult {
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
        if (topology !== route.projection.topology ||
            topology.revision != intent.route.topologyRevision ||
            topology.revision != observation.stamp.topologyRevision
        ) {
            return reversalRejected(RouteAdmissionFailureCode.STALE_TOPOLOGY,
                "frozen route topology changed during reversal")
        }
        val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
            ?: return reversalRejected(RouteAdmissionFailureCode.TRAIN_NOT_FOUND,
                "train is absent from the reversal observation")
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
                if (projected.reason == TopologyRouteProjectionRejection.STALE_TOPOLOGY)
                    RouteAdmissionFailureCode.STALE_TOPOLOGY else RouteAdmissionFailureCode.INVALID_ROUTE,
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
            PhysicalRouteRange(0.0, bodyEnd),
            previouslyCommittedControlledResources = emptySet()
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
            return reversalRejected(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "route-resource contributor failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
        val contributionOwnerByResource = LinkedHashMap<StableInfrastructureResource, Any>()
        val contributions = ArrayList<ContributedRouteResource>(authenticatedContributions.size)
        for ((contributor, contribution) in authenticatedContributions) {
            if (contribution.releaseAfterTailPasses.distanceFromRouteStartMeters > bodyEnd) {
                return reversalRejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                    "contributed reversal release boundary exceeds the observed train head")
            }
            if (contributionOwnerByResource.putIfAbsent(contribution.resource, contributor) != null) {
                return reversalRejected(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                    "duplicate contributed resource ${contribution.resource.typeId}/${contribution.resource.resourceId}")
            }
            contributions += contribution
        }
        val merged = try {
            physical.withContributions(contributions) { contribution ->
                validateContribution(contributionOwnerByResource.getValue(contribution.resource), contribution)
            }
        } catch (failure: Exception) {
            return reversalRejected(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "resource owner validation failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
        val compiled = when (merged) {
            is RouteContributionsCompilation.Compiled -> merged.admission
            is RouteContributionsCompilation.Rejected ->
                return RouteGrantReversalResult.Rejected(merged.failure)
        }

        val release = checkNotNull(route.tailRelease)
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
        if (currentTopology() !== topology || latestObservation()?.stamp != observation.stamp) {
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
            intent.route.topologyRevision,
            intent.trainId,
            observation.stamp.physicsEpoch,
            initialized.cursor,
            LiveClaimTailRelease(
                registry,
                hostClaimOwner,
                intent.owner,
                CompiledTailReleaseQueue(rebasedEntries)
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
        route.cursor = initialized.cursor
        route.routeProgressPath = physical.routeProgressPath
        route.tailRelease = newTailRelease
        route.controlledResources = physicalResult.controlledResources
        route.preparationsByResource.clear()
        compiled.preparations.forEach { route.preparationsByResource[it.resource] = it }
        route.committedGrant = grant
        route.reversalFromTrainState = null
        return RouteGrantReversalResult.Accepted(grant)
    }

    /** One already-published observation drives only routes with a committed grant. */
    @MainThread
    fun consumeObservation(observation: RailwayObservation): List<RouteGrantTailReleaseUpdate> {
        if (worldStalled || routes.values.none { it.committedGrant != null }) return emptyList()
        val trains = observation.trains.associateBy { it.trainId }
        val updates = ArrayList<RouteGrantTailReleaseUpdate>(routes.size)
        for (route in routes.values) {
            if (route.committedGrant == null) continue
            if (route.reversalFromTrainState != null) continue
            val update = checkNotNull(route.tailRelease).consume(observation.stamp, trains[route.trainId])
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
            !checkNotNull(route.tailRelease).resourceQueueEmpty
        ) return false
        routes.remove(route.owner.ownerId)
        return true
    }

    /**
     * Returns only route states that still protect a registry reservation/release-queue entry or
     * have an in-flight request. An emptied committed route state is deliberately not active: it
     * can be discarded safely when its requester unregisters even if [endGrant] was not called.
     */
    @MainThread
    fun requesterActivity(requester: Any): List<RouteAdmissionRequesterActivity> {
        val claimsByOwner = registry.claimSnapshots().associateBy { it.owner }
        return routes.values.mapNotNull { route ->
            if (route.requester !== requester) return@mapNotNull null
            routeActivity(route, claimsByOwner[route.owner]?.footprint?.resources?.size ?: 0)
        }
    }

    /** Active routes that still depend on a stable resource type owned by one provider. */
    @MainThread
    fun providerActivity(typeIds: Set<String>): List<RouteAdmissionRequesterActivity> {
        if (typeIds.isEmpty()) return emptyList()
        val claimsByOwner = registry.claimSnapshots().associateBy { it.owner }
        return routes.values.mapNotNull { route ->
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
    }

    /** Contributor membership is frozen while any route state may request another suffix. */
    @MainThread
    fun hasRouteState(): Boolean = routes.isNotEmpty()

    /** A forced policy loss cannot silently make later route extensions less restrictive. */
    @MainThread
    fun failClosedContributorLoss(contributorDescription: String): Boolean {
        if (routes.isEmpty()) return false
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
        routes.values.removeIf { it.requester === requester }
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
        InfrastructureMutationStatus.STALE_TOPOLOGY -> rejected(RouteAdmissionFailureCode.STALE_TOPOLOGY,
            result.detail.ifBlank { "resource registry topology changed" })
        InfrastructureMutationStatus.INVALID_RESOURCE,
        InfrastructureMutationStatus.RESOURCE_PROVIDER_FAILED -> rejected(RouteAdmissionFailureCode.INVALID_INFRASTRUCTURE,
            result.detail.ifBlank { "claimed infrastructure resource is unavailable" })
        else -> rejected(RouteAdmissionFailureCode.OWNER_MISMATCH,
            result.detail.ifBlank { "logical claim owner is unavailable" })
    }

    private fun isAppendOnlyContinuation(
        committed: RouteSnapshot,
        requested: RouteSnapshot
    ): Boolean = requested.routeId == committed.routeId &&
        requested.topologyRevision == committed.topologyRevision &&
        requested.revision > committed.revision &&
        requested.segments.size > committed.segments.size &&
        requested.segments.subList(0, committed.segments.size) == committed.segments

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
