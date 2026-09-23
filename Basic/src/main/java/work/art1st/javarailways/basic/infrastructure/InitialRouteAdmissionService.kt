package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
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
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.util.annotation.MainThread

/**
 * Internal route-admission owner for one world. Production world/catalog binding and authenticated
 * public service registration must join this owner before it is exposed.
 * The host binds resource-owner validation and the host-held claim token when constructing this
 * world owner. A request cannot supply its own validator or own the train's live registry claim.
 */
internal class InitialRouteAdmissionService(
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
    private val pending = LinkedHashMap<RouteAdmissionHandle, PendingAttempt>()
    // A terminal result remains repeatable while its caller retains the process-local handle.
    // Completed attempts without a retained handle must not accumulate for the world's lifetime.
    private val terminal = java.util.WeakHashMap<RouteAdmissionHandle, RouteAdmissionResult>()
    private val live = java.util.TreeMap<String, LiveGrant>()
    private val activeOwnerIds = HashSet<String>()
    private var worldStalled = false

    private data class LiveGrant(
        val requester: Any,
        var snapshot: RouteGrantSnapshot,
        val tailRelease: ProtectedRouteTailRelease,
        val projection: ProjectedTopologyRoute,
        var controlledResources: Set<StableInfrastructureResource>,
        val routeProgressPath: CompiledRouteProgressPath,
        val preparationsByResource: MutableMap<InfrastructureResource, InfrastructurePreparationRequirement>,
        var pendingExtension: RouteAdmissionHandle? = null
    )

    private interface PendingAttempt {
        fun progress(): RouteAdmissionResult
    }

    private inner class PendingAdmission(
        val handle: RouteAdmissionHandle,
        val requester: Any,
        val intent: RouteResourceIntent,
        val mutation: InfrastructureLeaseRegistry.ClaimMutation,
        val compiled: CompiledRouteAdmission,
        val projection: ProjectedTopologyRoute,
        val controlledResources: Set<StableInfrastructureResource>,
        val topologyRoot: TopologyState,
        val ticket: InfrastructurePreparationTicket
    ) : PendingAttempt {
        override fun progress(): RouteAdmissionResult {
            val state = try {
                ticket.snapshot()
            } catch (failure: Exception) {
                return stalled(intent, failure, "preparation state could not be read")
            }
            return when (state.state) {
                InfrastructurePreparationState.PREPARING -> RouteAdmissionResult.Pending(handle, null)
                InfrastructurePreparationState.READY -> {
                    if (worldStalled) {
                        return rejected(RouteAdmissionFailureCode.WORLD_STALLED,
                            "world is already stalled; prepared claim retained")
                    }
                    try {
                        // Preparation may span physics epochs. Keep the reservation until the
                        // current coherent frame proves that the original route is still safe.
                        val observation = latestObservation()
                            ?: error("no coherent observation when preparation became ready")
                        check(observation.stamp.worldId == worldId &&
                            observation.stamp.physicsEpoch >= intent.observationStamp.physicsEpoch &&
                            observation.stamp.topologyRevision == topologyRoot.revision &&
                            currentTopology() === topologyRoot
                        ) { "route topology or observation changed during preparation" }
                        val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
                            ?: error("train disappeared during preparation")
                        val initialized = compiled.routeProgressPath.initializeCursor(train.tail)
                        check(initialized is RouteProgressCursorInitialization.Initialized) {
                            "train tail no longer starts at the frozen route"
                        }
                        check(observation.occupancy.blockingTrainIds(
                            compiled.occupancyGeometry.intervals, intent.trainId
                        ).isEmpty()) { "another train entered the prepared route" }
                        check(currentTopology() === topologyRoot &&
                            latestObservation()?.stamp == observation.stamp
                        ) { "topology or observation changed while checking prepared route" }
                        check(!worldStalled) { "world stalled while checking prepared route" }
                        check(registry.completeClaimMutation(mutation)) { "claim reservation disappeared" }
                        val snapshot = RouteGrantSnapshot(
                            RouteGrantId(), intent.owner, intent.trainId, intent.route.routeId,
                            intent.route.revision, observation.stamp, intent.desiredAuthority
                        )
                        val release = ProtectedRouteTailRelease(
                            worldId, intent.route.topologyRevision, intent.trainId,
                            observation.stamp.physicsEpoch, initialized.cursor,
                            LiveClaimTailRelease(
                                registry, hostClaimOwner, intent.owner,
                                CompiledTailReleaseQueue(compiled.tailReleaseEntries)
                            ), { reason, failure ->
                                markWorldStalled(reason, failure)
                            }
                        )
                        live[intent.owner.ownerId] = LiveGrant(
                            requester, snapshot, release, projection, controlledResources,
                            compiled.routeProgressPath,
                            compiled.preparations.associateByTo(LinkedHashMap()) { it.resource }
                        )
                        RouteAdmissionResult.Granted(snapshot)
                    } catch (failure: Exception) {
                        stalled(intent, failure, "ready preparation could not publish a grant")
                    }
                }
                InfrastructurePreparationState.ABORTED_SAFE -> {
                    try {
                        check(registry.rollbackClaimMutation(mutation).status == InfrastructureMutationStatus.RELEASED) {
                            "safe preparation abort could not remove its claim"
                        }
                        activeOwnerIds.remove(intent.owner.ownerId)
                        rejected(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE,
                            "resource preparation safely aborted: ${state.detail}")
                    } catch (failure: Exception) {
                        stalled(intent, failure, "safe preparation abort could not remove its claim")
                    }
                }
                InfrastructurePreparationState.UNSAFE -> stalled(
                    intent, IllegalStateException(state.detail), "resource preparation became unsafe"
                )
            }
        }
    }

    private data class ReservedDelta(
        val result: InfrastructureMutationResult,
        val complete: () -> Boolean,
        val rollback: () -> InfrastructureMutationResult
    )

    private inner class PendingExtension(
        val handle: RouteAdmissionHandle,
        val intent: RouteResourceIntent,
        val grant: LiveGrant,
        val compiled: CompiledRouteAdmission,
        val deltaEntries: List<ResolvedTailReleaseEntry>,
        val newlyControlledResources: Set<StableInfrastructureResource>,
        val topologyRoot: TopologyState,
        val reservation: ReservedDelta,
        val ticket: InfrastructurePreparationTicket
    ) : PendingAttempt {
        override fun progress(): RouteAdmissionResult {
            val state = try {
                ticket.snapshot()
            } catch (failure: Exception) {
                return stalled(intent, failure, "extension preparation state could not be read")
            }
            if (worldStalled) {
                return rejected(RouteAdmissionFailureCode.WORLD_STALLED,
                    "world is already stalled; extension reservation retained")
            }
            return when (state.state) {
                InfrastructurePreparationState.PREPARING -> RouteAdmissionResult.Pending(handle, grant.snapshot)
                InfrastructurePreparationState.READY -> try {
                    val observation = latestObservation()
                        ?: error("no coherent observation when extension preparation became ready")
                    check(observation.stamp.worldId == worldId &&
                        observation.stamp.physicsEpoch >= intent.observationStamp.physicsEpoch &&
                        observation.stamp.topologyRevision == topologyRoot.revision &&
                        currentTopology() === topologyRoot
                    ) { "route topology or observation changed during extension preparation" }
                    val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
                        ?: error("train disappeared during extension preparation")
                    val release = grant.tailRelease.consume(observation.stamp, train)
                    check(release !is ProtectedRouteTailReleaseUpdate.FailedClosed) {
                        "train tail no longer follows the frozen route"
                    }
                    check(observation.occupancy.blockingTrainIds(
                        compiled.occupancyGeometry.intervals, intent.trainId
                    ).isEmpty()) { "another train entered the prepared extension" }
                    check(currentTopology() === topologyRoot &&
                        latestObservation()?.stamp == observation.stamp && !worldStalled
                    ) { "world changed while checking the prepared extension" }
                    grant.tailRelease.validateExtendedSuffix(deltaEntries)
                    check(reservation.complete()) { "extension reservation disappeared" }
                    grant.tailRelease.installExtendedSuffix(deltaEntries)
                    grant.controlledResources += newlyControlledResources
                    compiled.preparations.forEach { grant.preparationsByResource[it.resource] = it }
                    grant.snapshot = grant.snapshot.copy(
                        observationStamp = observation.stamp,
                        committedAuthority = intent.desiredAuthority
                    )
                    grant.pendingExtension = null
                    RouteAdmissionResult.Granted(grant.snapshot)
                } catch (failure: Exception) {
                    stalled(intent, failure, "ready extension could not publish a wider grant")
                }
                InfrastructurePreparationState.ABORTED_SAFE -> try {
                    val observation = latestObservation()
                        ?: error("no coherent observation when extension safely aborted")
                    check(observation.stamp.worldId == worldId &&
                        observation.stamp.topologyRevision == topologyRoot.revision &&
                        currentTopology() === topologyRoot
                    ) { "world changed before safe extension abort" }
                    val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
                        ?: error("train disappeared before safe extension abort")
                    check(grant.tailRelease.consume(observation.stamp, train) !is
                        ProtectedRouteTailReleaseUpdate.FailedClosed
                    ) { "train tail could not be reconciled before safe extension abort" }
                    check(reservation.rollback().successful) { "safe extension abort could not remove its delta" }
                    val release = grant.tailRelease.rollbackExtendedHolds()
                    check(release !is LiveClaimTailReleaseResult.FailedClosed) {
                        "safe extension abort could not reconcile tail release"
                    }
                    grant.pendingExtension = null
                    rejected(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE,
                        "extension preparation safely aborted: ${state.detail}")
                } catch (failure: Exception) {
                    stalled(intent, failure, "safe extension abort could not restore its release queue")
                }
                InfrastructurePreparationState.UNSAFE -> stalled(
                    intent, IllegalStateException(state.detail), "extension preparation became unsafe"
                )
            }
        }
    }

    @MainThread
    fun admit(
        requester: Any,
        intent: RouteResourceIntent
    ): RouteAdmissionResult {
        if (worldStalled) {
            return rejected(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled after an unsafe admission")
        }
        if (intent.observationStamp.worldId != worldId) {
            return rejected(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "request belongs to another world")
        }
        if (intent.owner.ownerId in activeOwnerIds) {
            return rejected(RouteAdmissionFailureCode.OWNER_MISMATCH, "logical route owner already has a claim")
        }
        val observation = latestObservation()
            ?: return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, "no published observation")
        if (observation.stamp != intent.observationStamp) {
            return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, "observation stamp is no longer current")
        }
        val topology = currentTopology()
        if (topology.revision != intent.route.topologyRevision ||
            topology.revision != observation.stamp.topologyRevision
        ) {
            return rejected(RouteAdmissionFailureCode.STALE_TOPOLOGY, "route and current topology differ")
        }
        val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
            ?: return rejected(RouteAdmissionFailureCode.TRAIN_NOT_FOUND, "train is absent from observation")
        if (intent.desiredAuthority.distanceFromRouteStartMeters <= 0.0) {
            return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY, "initial authority must have positive length")
        }
        val projection = when (val projected = TopologyRouteProjector.project(intent.route, topology)) {
            is TopologyRouteProjectionResult.Projected -> projected.route
            is TopologyRouteProjectionResult.Rejected -> return rejected(
                if (projected.reason == TopologyRouteProjectionRejection.STALE_TOPOLOGY)
                    RouteAdmissionFailureCode.STALE_TOPOLOGY else RouteAdmissionFailureCode.INVALID_ROUTE,
                projected.detail
            )
        }
        val physicalResult = physicalCompiler.compile(
            projection, PhysicalRouteRange(0.0, intent.desiredAuthority.distanceFromRouteStartMeters)
        )
        val physical = when (val result = physicalResult) {
            is PhysicalRouteCompilation.Compiled -> result.admission
            is PhysicalRouteCompilation.Rejected -> return rejected(
                if (result.reason == PhysicalRouteCompilationRejection.STALE_TOPOLOGY)
                    RouteAdmissionFailureCode.STALE_TOPOLOGY else RouteAdmissionFailureCode.INVALID_ROUTE,
                result.detail
            )
        }
        val initialized = physical.routeProgressPath.initializeCursor(train.tail)
        if (initialized !is RouteProgressCursorInitialization.Initialized) {
            return rejected(RouteAdmissionFailureCode.INVALID_ROUTE, "frozen route does not start at train tail")
        }
        val merged = try {
            physical.withAdditions(intent.additionalResources) { addition ->
                validateAddition(requester, addition)
            }
        } catch (failure: Exception) {
            return rejected(RouteAdmissionFailureCode.INVALID_ADDITION,
                "resource owner validation failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
        val compiled = when (val result = merged) {
            is RouteAdditionsCompilation.Compiled -> result.admission
            is RouteAdditionsCompilation.Rejected -> return RouteAdmissionResult.Rejected(result.failure)
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
        val mutation = registry.beginAcquireClaim(hostClaimOwner, InfrastructureClaimRequest(
            topology.revision, intent.owner, compiled.resources
        ))
        if (mutation.result.status != InfrastructureMutationStatus.ACQUIRED) return mapMutation(mutation.result)
        activeOwnerIds += intent.owner.ownerId
        val started = try {
            preparationActuator.start(compiled.preparations)
        } catch (failure: Exception) {
            return stalled(intent, failure, "preparation could not be started")
        }
        if (started is InfrastructurePreparationStart.Rejected) {
            return try {
                check(registry.rollbackClaimMutation(mutation).status == InfrastructureMutationStatus.RELEASED)
                activeOwnerIds.remove(intent.owner.ownerId)
                rejected(RouteAdmissionFailureCode.INVALID_PREPARATION, "preparation rejected: ${started.failure.message}")
            } catch (failure: Exception) {
                stalled(intent, failure, "rejected preparation could not remove its claim")
            }
        }
        val handle = RouteAdmissionHandle()
        pending[handle] = PendingAdmission(handle, requester, intent, mutation, compiled, projection,
            physicalResult.controlledResources, topology,
            (started as InfrastructurePreparationStart.Accepted).ticket)
        return progress(handle)
    }

    /** Extends only the farther suffix of the same frozen route and keeps the existing grant live. */
    @MainThread
    fun extend(requester: Any, grantId: RouteGrantId, intent: RouteResourceIntent): RouteAdmissionResult {
        if (worldStalled) return rejected(RouteAdmissionFailureCode.WORLD_STALLED, "world is stalled")
        val grant = live[intent.owner.ownerId]
            ?: return rejected(RouteAdmissionFailureCode.EXTENSION_MISMATCH, "grant does not exist")
        if (grant.snapshot.grantId !== grantId) {
            return rejected(RouteAdmissionFailureCode.EXTENSION_MISMATCH, "grant identity does not match owner")
        }
        if (grant.requester !== requester || grant.snapshot.owner != intent.owner) {
            return rejected(RouteAdmissionFailureCode.OWNER_MISMATCH, "extension owner does not match live grant")
        }
        if (grant.pendingExtension != null) {
            return rejected(RouteAdmissionFailureCode.EXTENSION_BUSY, "grant already has a pending extension")
        }
        if (intent.observationStamp.worldId != worldId || intent.trainId != grant.snapshot.trainId ||
            intent.route != grant.projection.route
        ) {
            return rejected(RouteAdmissionFailureCode.EXTENSION_MISMATCH,
                "extension must keep the same world, train and frozen route")
        }
        val oldFrontier = grant.snapshot.committedAuthority.distanceFromRouteStartMeters
        val newFrontier = intent.desiredAuthority.distanceFromRouteStartMeters
        if (newFrontier <= oldFrontier) {
            return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                "extension authority must be farther than the committed boundary")
        }
        val observation = latestObservation()
            ?: return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, "no published observation")
        if (observation.stamp != intent.observationStamp) {
            return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION, "observation stamp is no longer current")
        }
        val topology = currentTopology()
        if (topology !== grant.projection.topology || topology.revision != observation.stamp.topologyRevision) {
            return rejected(RouteAdmissionFailureCode.STALE_TOPOLOGY, "frozen route topology changed")
        }
        val train = observation.trains.firstOrNull { it.trainId == intent.trainId }
            ?: return rejected(RouteAdmissionFailureCode.TRAIN_NOT_FOUND, "train is absent from observation")
        // Bring the existing cursor/queue up to this exact observation before deciding which
        // identities are still held. This is ordinary tail release, not extension mutation.
        if (grant.tailRelease.consume(observation.stamp, train) is ProtectedRouteTailReleaseUpdate.FailedClosed) {
            return rejected(RouteAdmissionFailureCode.WORLD_STALLED, "train tail no longer follows frozen route")
        }
        val physicalResult = physicalCompiler.compile(
            grant.projection, PhysicalRouteRange(oldFrontier, newFrontier, includeTurnoutAtStart = false),
            grant.controlledResources, grant.routeProgressPath
        )
        val physical = when (physicalResult) {
            is PhysicalRouteCompilation.Compiled -> physicalResult.admission
            is PhysicalRouteCompilation.Rejected -> return rejected(
                if (physicalResult.reason == PhysicalRouteCompilationRejection.STALE_TOPOLOGY)
                    RouteAdmissionFailureCode.STALE_TOPOLOGY else RouteAdmissionFailureCode.INVALID_ROUTE,
                physicalResult.detail
            )
        }
        val held = grant.tailRelease.remainingEntries.associateBy(ResolvedTailReleaseEntry::resource)
        for (addition in intent.additionalResources) {
            val previous = held[addition.resource] ?: continue
            if (addition.releaseAfterTailPasses.distanceFromRouteStartMeters < previous.tailClearRouteMeters) {
                return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                    "extension cannot shorten an already-held resource")
            }
            val descriptor = addition.preparation
            if (descriptor != null &&
                (grant.preparationsByResource[addition.resource] as? ResolvedAddedResourcePreparation)
                    ?.descriptor != descriptor
            ) {
                return rejected(RouteAdmissionFailureCode.INVALID_PREPARATION,
                    "extension cannot change an already-held resource preparation")
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
                RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED, "another train occupies the extension",
                blockers.map(RouteAdmissionBlocker::OccupyingTrain)
            ))
        }
        if (currentTopology() !== topology || latestObservation()?.stamp != observation.stamp) {
            return rejected(RouteAdmissionFailureCode.STALE_OBSERVATION,
                "world changed while validating extension")
        }
        val heldEntries = compiled.tailReleaseEntries.filter { it.resource in held }.map { entry ->
            entry.copy(tailClearRouteMeters = maxOf(entry.tailClearRouteMeters,
                held.getValue(entry.resource).tailClearRouteMeters))
        }
        val deltaEntries = compiled.tailReleaseEntries.filterNot { it.resource in held }
        if (deltaEntries.isEmpty()) {
            return rejected(RouteAdmissionFailureCode.INVALID_ROUTE, "extension has no new physical resources")
        }
        val acceptedProgress = grant.tailRelease.acceptedTailProgressMeters
        if (acceptedProgress != null &&
            (heldEntries.any { it.tailClearRouteMeters <= acceptedProgress } ||
                deltaEntries.any { it.tailClearRouteMeters <= acceptedProgress })
        ) {
            return rejected(RouteAdmissionFailureCode.INVALID_BOUNDARY,
                "extension contains a release boundary already passed by the train tail")
        }
        try {
            grant.tailRelease.validateExtendedHolds(heldEntries)
        } catch (failure: Exception) {
            return rejected(RouteAdmissionFailureCode.EXTENSION_MISMATCH,
                "extension hold cannot join the live release queue: ${failure.message.orEmpty()}")
        }
        val deltaResources = compiled.resources.resources.filterNot { it in held }
        val reservation = if (grant.tailRelease.resourceQueueEmpty) {
            val mutation = registry.beginAcquireClaim(hostClaimOwner,
                InfrastructureClaimRequest(topology.revision, intent.owner,
                    InfrastructureFootprint(deltaResources)))
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
        try {
            grant.tailRelease.stageExtendedHolds(heldEntries)
        } catch (failure: Exception) {
            return stalled(intent, failure, "extension could not defer held-resource release")
        }
        val preparations = compiled.preparations.filterNot {
            it is ResolvedAddedResourcePreparation && it.resource in held
        }
        val started = try {
            preparationActuator.start(preparations)
        } catch (failure: Exception) {
            return stalled(intent, failure, "extension preparation could not be started")
        }
        if (started is InfrastructurePreparationStart.Rejected) {
            return try {
                check(reservation.rollback().successful) { "rejected extension could not remove its delta" }
                check(grant.tailRelease.rollbackExtendedHolds() !is LiveClaimTailReleaseResult.FailedClosed) {
                    "rejected extension could not restore old release boundaries"
                }
                rejected(RouteAdmissionFailureCode.INVALID_PREPARATION,
                    "extension preparation rejected: ${started.failure.message}")
            } catch (failure: Exception) {
                stalled(intent, failure, "rejected extension could not safely restore its claim")
            }
        }
        val handle = RouteAdmissionHandle()
        grant.pendingExtension = handle
        pending[handle] = PendingExtension(handle, intent, grant, compiled, deltaEntries,
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

    /** One already-published observation drives only committed grants, not pending attempts. */
    @MainThread
    fun consumeObservation(observation: RailwayObservation): List<ProtectedRouteTailReleaseUpdate> {
        if (worldStalled || live.isEmpty()) return emptyList()
        val trains = observation.trains.associateBy { it.trainId }
        val updates = ArrayList<ProtectedRouteTailReleaseUpdate>(live.size)
        for (grant in live.values) {
            val update = grant.tailRelease.consume(observation.stamp, trains[grant.snapshot.trainId])
            updates += update
            if (update is ProtectedRouteTailReleaseUpdate.FailedClosed) break
        }
        return updates
    }

    /** Ends a grant only after physical tail release emptied its claim queue. */
    @MainThread
    fun endGrant(requester: Any, grantId: RouteGrantId): Boolean {
        val grant = live.values.firstOrNull { it.snapshot.grantId === grantId } ?: return false
        if (grant.requester !== requester || grant.pendingExtension != null ||
            !grant.tailRelease.resourceQueueEmpty
        ) return false
        live.remove(grant.snapshot.owner.ownerId)
        activeOwnerIds.remove(grant.snapshot.owner.ownerId)
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
