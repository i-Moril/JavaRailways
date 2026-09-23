package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
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
 * First-admission owner for one world. This is not the public admission service yet: same-route
 * extension and production world/catalog binding must join the same owner before it is exposed.
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
    private val pending = LinkedHashMap<RouteAdmissionHandle, PendingAdmission>()
    private val terminal = LinkedHashMap<RouteAdmissionHandle, RouteAdmissionResult>()
    private val live = java.util.TreeMap<String, LiveGrant>()
    private val activeOwnerIds = HashSet<String>()
    private var worldStalled = false

    private data class LiveGrant(
        val requester: Any,
        val snapshot: RouteGrantSnapshot,
        val tailRelease: ProtectedRouteTailRelease
    )

    private inner class PendingAdmission(
        val handle: RouteAdmissionHandle,
        val requester: Any,
        val intent: RouteResourceIntent,
        val mutation: InfrastructureLeaseRegistry.ClaimMutation,
        val compiled: CompiledRouteAdmission,
        val cursor: RouteProgressCursor,
        val ticket: InfrastructurePreparationTicket
    ) {
        fun progress(): RouteAdmissionResult {
            val state = try {
                ticket.snapshot()
            } catch (failure: Exception) {
                return stalled(intent, failure, "preparation state could not be read")
            }
            return when (state.state) {
                InfrastructurePreparationState.PREPARING -> RouteAdmissionResult.Pending(handle, null)
                InfrastructurePreparationState.READY -> {
                    try {
                        check(registry.completeClaimMutation(mutation)) { "claim reservation disappeared" }
                        val snapshot = RouteGrantSnapshot(
                            RouteGrantId(), intent.owner, intent.trainId, intent.route.routeId,
                            intent.route.revision, intent.observationStamp, intent.desiredAuthority
                        )
                        val release = ProtectedRouteTailRelease(
                            worldId, intent.route.topologyRevision, intent.trainId,
                            intent.observationStamp.physicsEpoch, cursor,
                            LiveClaimTailRelease(
                                registry, hostClaimOwner, intent.owner,
                                CompiledTailReleaseQueue(compiled.tailReleaseEntries)
                            ), { reason, failure ->
                                worldStalled = true
                                stallWorld(reason, failure)
                            }
                        )
                        live[intent.owner.ownerId] = LiveGrant(requester, snapshot, release)
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
        val physical = when (val result = physicalCompiler.compile(
            projection, PhysicalRouteRange(0.0, intent.desiredAuthority.distanceFromRouteStartMeters)
        )) {
            is PhysicalRouteCompilation.Compiled -> result.admission
            is PhysicalRouteCompilation.Rejected -> return rejected(
                if (result.reason == PhysicalRouteCompilationRejection.STALE_TOPOLOGY)
                    RouteAdmissionFailureCode.STALE_TOPOLOGY else RouteAdmissionFailureCode.INVALID_ROUTE,
                result.detail
            )
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
        val initialized = compiled.routeProgressPath.initializeCursor(train.tail)
        if (initialized !is RouteProgressCursorInitialization.Initialized) {
            return rejected(RouteAdmissionFailureCode.INVALID_ROUTE, "frozen route does not start at train tail")
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
        pending[handle] = PendingAdmission(handle, requester, intent, mutation, compiled, initialized.cursor,
            (started as InfrastructurePreparationStart.Accepted).ticket)
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
        if (grant.requester !== requester || !grant.tailRelease.resourceQueueEmpty) return false
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
        worldStalled = true
        stallWorld("route admission ${intent.requestId}: $detail; safest claim retained", cause)
        return rejected(RouteAdmissionFailureCode.WORLD_STALLED, detail)
    }

    private fun rejected(code: RouteAdmissionFailureCode, detail: String) =
        RouteAdmissionResult.Rejected(RouteAdmissionFailure(code, detail))
}
