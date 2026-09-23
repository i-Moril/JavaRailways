package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.AdditionalResourceRequirement
import work.art1st.javarailways.basic.api.route.RouteAdmissionBlocker
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
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
    private val registry: InfrastructureLeaseRegistry,
    private val hostClaimOwner: Any,
    private val currentTopology: () -> TopologyState,
    private val latestObservation: () -> RailwayObservation?,
    routeBoundResources: RouteBoundResourceCatalog,
    private val validateAddition: (Any, AdditionalResourceRequirement) -> RouteAdmissionFailure?,
    private val preparationActuator: InfrastructurePreparationActuator,
    private val stallWorld: (String, Exception) -> Unit
) {
    private val physicalCompiler = PhysicalRouteResourceCompiler(routeBoundResources)
    private val pending = LinkedHashMap<RouteAdmissionHandle, PendingRequest>()
    // A terminal result remains repeatable while its caller retains the process-local handle.
    // Completed attempts without a retained handle must not accumulate for the world's lifetime.
    private val terminal = java.util.WeakHashMap<RouteAdmissionHandle, RouteAdmissionResult>()
    private val routes = java.util.TreeMap<String, RouteState>()
    private var worldStalled = false

    private class RouteState(
        val requester: Any,
        val owner: InfrastructureOwner,
        val trainId: String,
        val projection: ProjectedTopologyRoute,
        val cursor: RouteProgressCursor,
        val routeProgressPath: CompiledRouteProgressPath,
        var committedGrant: RouteGrantSnapshot? = null,
        var tailRelease: ProtectedRouteTailRelease? = null,
        var controlledResources: Set<StableInfrastructureResource>,
        val preparationsByResource: MutableMap<InfrastructureResource, InfrastructurePreparationRequirement>,
        var pendingHandle: RouteAdmissionHandle? = null
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
        val topologyRoot: TopologyState,
        val reservation: ReservedDelta,
        val ticket: InfrastructurePreparationTicket
    ) {
        fun progress(): RouteAdmissionResult {
            val state = try {
                ticket.snapshot()
            } catch (failure: Exception) {
                return stalled(intent, failure, "preparation state could not be read")
            }
            if (worldStalled) {
                return rejected(RouteAdmissionFailureCode.WORLD_STALLED,
                    "world is already stalled; reservation retained")
            }
            return when (state.state) {
                InfrastructurePreparationState.PREPARING -> RouteAdmissionResult.Pending(handle, route.committedGrant)
                InfrastructurePreparationState.READY -> try {
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
                        check(release !is ProtectedRouteTailReleaseUpdate.FailedClosed) {
                            "train tail no longer follows the frozen route"
                        }
                    }
                    check(observation.occupancy.blockingTrainIds(
                        compiled.occupancyGeometry.intervals, intent.trainId
                    ).isEmpty()) { "another train entered the prepared route" }
                    check(currentTopology() === topologyRoot &&
                        latestObservation()?.stamp == observation.stamp && !worldStalled
                    ) { "world changed while checking the prepared route" }
                    if (oldGrant != null) checkNotNull(route.tailRelease).validateExtendedSuffix(deltaEntries)
                    check(reservation.complete()) { "resource reservation disappeared" }
                    if (oldGrant == null) {
                        route.tailRelease = ProtectedRouteTailRelease(
                            worldId, intent.route.topologyRevision, intent.trainId,
                            observation.stamp.physicsEpoch, route.cursor,
                            LiveClaimTailRelease(
                                registry, hostClaimOwner, intent.owner,
                                CompiledTailReleaseQueue(deltaEntries)
                            ), { reason, failure -> markWorldStalled(reason, failure) }
                        )
                    } else {
                        checkNotNull(route.tailRelease).installExtendedSuffix(deltaEntries)
                    }
                    route.controlledResources += newlyControlledResources
                    compiled.preparations.forEach { route.preparationsByResource[it.resource] = it }
                    val grant = oldGrant?.copy(
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
                InfrastructurePreparationState.ABORTED_SAFE -> try {
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
                            ProtectedRouteTailReleaseUpdate.FailedClosed
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
                InfrastructurePreparationState.UNSAFE -> stalled(
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
        if (existing != null) {
            if (existing.requester !== requester || existing.owner != intent.owner) {
                return rejected(RouteAdmissionFailureCode.OWNER_MISMATCH, "request owner does not match route state")
            }
            if (existing.trainId != intent.trainId || existing.projection.route != intent.route) {
                return rejected(RouteAdmissionFailureCode.ROUTE_STATE_MISMATCH,
                    "request must keep the same train and frozen route")
            }
            if (existing.pendingHandle != null) {
                return rejected(RouteAdmissionFailureCode.REQUEST_BUSY, "route already has a pending request")
            }
        }
        val oldFrontier = existing?.committedGrant?.committedAuthority?.distanceFromRouteStartMeters ?: 0.0
        val newFrontier = intent.desiredAuthority.distanceFromRouteStartMeters
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
        val projection = if (existing == null) {
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
                ProtectedRouteTailReleaseUpdate.FailedClosed
            ) return rejected(RouteAdmissionFailureCode.WORLD_STALLED, "train tail no longer follows frozen route")
            existing.projection
        }
        val physicalResult = physicalCompiler.compile(
            projection, PhysicalRouteRange(oldFrontier, newFrontier,
                includeTurnoutAtStart = existing == null),
            existing?.controlledResources ?: emptySet(), existing?.routeProgressPath
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
        val release = existing?.tailRelease
        val held = release?.remainingEntries?.associateBy(ResolvedTailReleaseEntry::resource).orEmpty()
        for (addition in intent.additionalResources) {
            val previous = held[addition.resource] ?: continue
            if (addition.releaseAfterTailPasses.distanceFromRouteStartMeters < previous.tailClearRouteMeters) {
                return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                    "request cannot shorten an already-held resource")
            }
            val descriptor = addition.preparation
            if (descriptor != null &&
                (existing?.preparationsByResource?.get(addition.resource) as? ResolvedAddedResourcePreparation)
                    ?.descriptor != descriptor
            ) {
                return rejected(RouteAdmissionFailureCode.INVALID_PREPARATION,
                    "request cannot change an already-held resource preparation")
            }
        }
        val merged = try {
            physical.withAdditions(intent.additionalResources) { addition ->
                validateAddition(requester, addition)
            }
        } catch (failure: Exception) {
            return rejected(RouteAdmissionFailureCode.INVALID_ADDITION,
                "resource owner validation failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
        val compiled = when (merged) {
            is RouteAdditionsCompilation.Compiled -> merged.admission
            is RouteAdditionsCompilation.Rejected -> return RouteAdmissionResult.Rejected(merged.failure)
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
            it is ResolvedAddedResourcePreparation && it.resource in held
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
            physicalResult.controlledResources, topology,
            reservation, (started as InfrastructurePreparationStart.Accepted).ticket)
        return progress(handle)
    }

    @MainThread
    fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult {
        terminal[handle]?.let { return it }
        val attempt = pending[handle]
            ?: return rejected(RouteAdmissionFailureCode.OWNER_MISMATCH, "unknown admission handle")
        val result = attempt.progress()
        if (result !is RouteAdmissionResult.Pending) {
            pending.remove(handle)
            terminal[handle] = result
        }
        return result
    }

    /** One already-published observation drives only routes with a committed grant. */
    @MainThread
    fun consumeObservation(observation: RailwayObservation): List<ProtectedRouteTailReleaseUpdate> {
        if (worldStalled || routes.values.none { it.committedGrant != null }) return emptyList()
        val trains = observation.trains.associateBy { it.trainId }
        val updates = ArrayList<ProtectedRouteTailReleaseUpdate>(routes.size)
        for (route in routes.values) {
            if (route.committedGrant == null) continue
            val update = checkNotNull(route.tailRelease).consume(observation.stamp, trains[route.trainId])
            updates += update
            if (update is ProtectedRouteTailReleaseUpdate.FailedClosed) break
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
}
