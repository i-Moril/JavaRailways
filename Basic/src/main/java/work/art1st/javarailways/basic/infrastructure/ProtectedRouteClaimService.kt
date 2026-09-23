package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimResult
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.core.api.util.annotation.MainThread
import java.util.TreeMap

/** Main-thread result of starting one protected-route admission. */
internal sealed interface ProtectedRouteAdmissionStart {
    @MainThread
    fun poll(): ProtectedRouteAdmissionProgress

    @MainThread
    fun abort()

    /** Admission ended during start. Polling remains idempotent and abort is a no-op. */
    data class Terminal(val result: ProtectedRouteClaimResult) : ProtectedRouteAdmissionStart {
        override fun poll(): ProtectedRouteAdmissionProgress = ProtectedRouteAdmissionProgress.Terminal(result)
        override fun abort() = Unit
    }

    /** Admission owns a live claim transaction until [handle] reaches a terminal result. */
    data class Pending(val handle: ProtectedRouteAdmissionHandle) : ProtectedRouteAdmissionStart {
        override fun poll(): ProtectedRouteAdmissionProgress = handle.poll()
        override fun abort() = handle.abort()
    }
}

/** Result of polling a pending or already-terminal protected-route admission. */
internal sealed interface ProtectedRouteAdmissionProgress {
    data object Pending : ProtectedRouteAdmissionProgress
    data class Terminal(val result: ProtectedRouteClaimResult) : ProtectedRouteAdmissionProgress
}

/** One stable-order observation dispatch made by the world-local claim service. */
internal data class ProtectedRouteTailReleaseDispatch(
    val owner: InfrastructureOwner,
    val trainId: String,
    val update: ProtectedRouteTailReleaseUpdate
)

/**
 * Owns one claim mutation while physical infrastructure preparation is pending.
 *
 * A handle never publishes authority. It only finalizes the logical claim after physical
 * confirmation, or removes this newly acquired claim after the resource reports a safe abort.
 */
internal class ProtectedRouteAdmissionHandle internal constructor(
    private val requestId: String,
    private val owner: InfrastructureOwner,
    private val preparationTicket: ResourcePreparationTicket,
    private val finishReady: () -> ProtectedRouteClaimResult,
    private val finishSafeAbort: (String) -> ProtectedRouteClaimResult,
    private val stallWorld: (String, Exception) -> Unit,
    private val operationName: String = "admission"
) {
    private var abortRequested = false
    private var abortFailure: Exception? = null
    private var terminal: ProtectedRouteAdmissionProgress.Terminal? = null

    @MainThread
    fun poll(): ProtectedRouteAdmissionProgress {
        terminal?.let { return it }
        abortFailure?.let { failure ->
            return finishStalled(
                failure,
                "infrastructure preparation could not be safely aborted; claim reservation retained"
            )
        }
        val snapshot = try {
            preparationTicket.snapshot()
        } catch (failure: Exception) {
            return finishStalled(
                failure,
                "infrastructure preparation snapshot failed; claim and rollback reservation retained"
            )
        }
        return when (snapshot.state) {
            ResourcePreparationState.PREPARING -> ProtectedRouteAdmissionProgress.Pending
            ResourcePreparationState.READY -> {
                try {
                    finish(finishReady())
                } catch (failure: Exception) {
                    finishStalled(failure, "$operationName preparation became ready but completion failed")
                }
            }
            ResourcePreparationState.ABORTED_SAFE -> try {
                finish(finishSafeAbort(snapshot.detail))
            } catch (failure: Exception) {
                finishStalled(failure, "$operationName preparation was safe but rollback failed")
            }
            ResourcePreparationState.UNSAFE -> finishStalled(
                IllegalStateException(snapshot.detail),
                "infrastructure preparation state is unsafe; claim and rollback reservation retained: ${snapshot.detail}"
            )
        }
    }

    @MainThread
    fun abort() {
        if (terminal != null || abortRequested) return
        abortRequested = true
        try {
            preparationTicket.abort()
        } catch (failure: Exception) {
            abortFailure = failure
        }
    }

    private fun finish(result: ProtectedRouteClaimResult): ProtectedRouteAdmissionProgress.Terminal =
        ProtectedRouteAdmissionProgress.Terminal(result).also { terminal = it }

    private fun finishStalled(
        failure: Exception,
        detail: String
    ): ProtectedRouteAdmissionProgress.Terminal {
        val stalled = ProtectedRouteClaimResult(ProtectedRouteClaimStatus.WORLD_STALLED, detail = detail)
        stallWorld(
            "protected-route $operationName $requestId for " +
                "${owner.ownerId}@${owner.revision} entered fail-closed recovery: $detail",
            failure
        )
        return finish(stalled)
    }

}

/**
 * Validates one protected-route request, claims its resource set, and starts its complete ordered
 * infrastructure-preparation batch. A successful claim result is returned only after the batch
 * ticket is READY; no route grant or authority is published by this transitional seam.
 */
internal class ProtectedRouteClaimService(
    private val registry: InfrastructureLeaseRegistry,
    private val currentTopologyRevision: () -> Long,
    private val latestObservation: () -> RailwayObservation?,
    private val validate: (ProtectedRouteRequest) -> ProtectedRouteValidation,
    private val compileExtension: (ProtectedRouteExtensionContext, ProtectedRouteExtensionRequest) ->
        ProtectedRouteExtensionValidation,
    private val preparationActuator: InfrastructurePreparationActuator,
    private val stallWorld: (String, Exception) -> Unit
) {
    private data class LiveGrant(
        val platformOwner: Any,
        val owner: InfrastructureOwner,
        val trainId: String,
        val controller: ProtectedRouteTailRelease,
        var extensionContext: ProtectedRouteExtensionContext?,
        var pendingExtension: ProtectedRouteAdmissionHandle? = null
    )

    private val liveGrants = TreeMap<String, LiveGrant>()

    @MainThread
    fun acquire(platformOwner: Any, request: ProtectedRouteRequest): ProtectedRouteAdmissionStart {
        liveGrants[request.owner.ownerId]?.let { existing ->
            val status = if (existing.platformOwner === platformOwner && existing.owner == request.owner) {
                ProtectedRouteClaimStatus.UNCHANGED
            } else {
                ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT
            }
            return ProtectedRouteAdmissionStart.Terminal(
                result(status, "live grant ${request.owner.ownerId} already exists")
            )
        }
        return when (val resolved = resolve(request)) {
            is ResolutionResult.Finished -> ProtectedRouteAdmissionStart.Terminal(resolved.result)
            is ResolutionResult.Plan -> startAdmission(
                resolved,
                registry.beginAcquireClaim(
                    platformOwner,
                    InfrastructureClaimRequest(
                        request.route.topologyRevision,
                        request.owner,
                        resolved.admission.resources
                    )
                ),
                platformOwner,
                request
            )
        }
    }

    @MainThread
    fun extend(platformOwner: Any, request: ProtectedRouteExtensionRequest): ProtectedRouteAdmissionStart {
        val grant = liveGrants[request.owner.ownerId]
            ?: return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.NOT_FOUND, "live grant does not exist")
            )
        if (grant.platformOwner !== platformOwner || grant.owner != request.owner) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT, "live grant owner does not match")
            )
        }
        if (grant.trainId != request.trainId) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.INVALID_REQUEST, "rolling extension train does not match live grant")
            )
        }
        if (grant.pendingExtension != null) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT, "live grant already has a pending extension")
            )
        }
        val context = grant.extensionContext ?: return ProtectedRouteAdmissionStart.Terminal(
            result(ProtectedRouteClaimStatus.INVALID_REQUEST, "live grant has no frozen-route extension context")
        )
        if (request.route != context.projection.route) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.INVALID_REQUEST, "rolling extension must retain the exact frozen route")
            )
        }
        val observation = latestObservation()
            ?: return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.STALE_OBSERVATION, "no coherent railway observation is available")
            )
        if (request.observationStamp != observation.stamp) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.STALE_OBSERVATION, "extension observation is not latest")
            )
        }
        if (request.route.topologyRevision != observation.stamp.topologyRevision) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(
                    ProtectedRouteClaimStatus.STALE_TOPOLOGY,
                    "extension route and observation use different topology revisions"
                )
            )
        }
        if (request.route.topologyRevision != currentTopologyRevision()) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.STALE_TOPOLOGY, "extension topology revision is stale")
            )
        }
        if (observation.trains.none { it.trainId == request.trainId }) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.INVALID_REQUEST, "extension train is absent from observation")
            )
        }
        val compiled = try {
            compileExtension(context, request)
        } catch (failure: Exception) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.VALIDATION_FAILED, "rolling extension validation failed: ${failureDetail(failure)}")
            )
        }
        if (compiled is ProtectedRouteExtensionValidation.Rejected) {
            return ProtectedRouteAdmissionStart.Terminal(result(compiled.status, compiled.detail))
        }
        compiled as ProtectedRouteExtensionValidation.Validated
        if (latestObservation()?.stamp != observation.stamp) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.STALE_OBSERVATION, "observation changed while compiling extension")
            )
        }
        val blockers = observation.occupancy.blockingTrainIds(
            compiled.admission.occupancyGeometry.intervals,
            request.trainId
        )
        if (blockers.isNotEmpty()) {
            return ProtectedRouteAdmissionStart.Terminal(
                ProtectedRouteClaimResult(
                    ProtectedRouteClaimStatus.PHYSICALLY_OCCUPIED,
                    occupyingTrainIds = blockers,
                    detail = "${blockers.size} train(s) physically block the route extension"
                )
            )
        }
        if (request.route.topologyRevision != currentTopologyRevision() ||
            latestObservation()?.stamp != observation.stamp
        ) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(
                    if (request.route.topologyRevision != currentTopologyRevision()) {
                        ProtectedRouteClaimStatus.STALE_TOPOLOGY
                    } else {
                        ProtectedRouteClaimStatus.STALE_OBSERVATION
                    },
                    "world state changed while checking extension"
                )
            )
        }
        try {
            grant.controller.validateCommittedSuffix(compiled.admission.tailReleaseEntries)
        } catch (failure: Exception) {
            return ProtectedRouteAdmissionStart.Terminal(
                result(ProtectedRouteClaimStatus.INVALID_REQUEST, "extension release order is invalid: ${failure.message.orEmpty()}")
            )
        }
        val reservation = registry.beginAppendClaim(
            platformOwner,
            request.owner,
            request.route.topologyRevision,
            compiled.admission.resources.resources
        )
        if (reservation.result.status != InfrastructureMutationStatus.REPLACED) {
            return ProtectedRouteAdmissionStart.Terminal(mapMutation(reservation.result))
        }
        val started = try {
            preparationActuator.start(compiled.admission.preparations)
        } catch (failure: Exception) {
            return stalledExtensionStart(request, failure, "extension preparation start failed; delta retained")
        }
        if (started is InfrastructurePreparationStart.Rejected) {
            val rollback = registry.rollbackClaimAppend(reservation)
            return if (rollback.successful) {
                ProtectedRouteAdmissionStart.Terminal(
                    result(ProtectedRouteClaimStatus.PREPARATION_FAILED, "extension preparation was rejected; delta removed")
                )
            } else {
                stalledExtensionStart(request, started.failure, "extension preparation rejection could not remove delta")
            }
        }
        val handle = ProtectedRouteAdmissionHandle(
            request.requestId,
            request.owner,
            (started as InfrastructurePreparationStart.Accepted).ticket,
            finishReady = {
                grant.controller.validateCommittedSuffix(compiled.admission.tailReleaseEntries)
                check(registry.completeClaimAppend(reservation)) {
                    "append reservation disappeared before finalization"
                }
                grant.controller.installCommittedSuffix(compiled.admission.tailReleaseEntries)
                grant.extensionContext = compiled.nextContext
                grant.pendingExtension = null
                ProtectedRouteClaimResult(ProtectedRouteClaimStatus.ACQUIRED)
            },
            finishSafeAbort = {
                val rollback = registry.rollbackClaimAppend(reservation)
                check(rollback.status == InfrastructureMutationStatus.REPLACED ||
                    rollback.status == InfrastructureMutationStatus.RELEASED
                ) { "append rollback failed: ${rollback.status}: ${rollback.detail}" }
                grant.pendingExtension = null
                ProtectedRouteClaimResult(
                    ProtectedRouteClaimStatus.PREPARATION_FAILED,
                    detail = "extension preparation was safely aborted; only its delta was removed"
                )
            },
            stallWorld = stallWorld,
            operationName = "extension"
        )
        grant.pendingExtension = handle
        return when (val progress = handle.poll()) {
            ProtectedRouteAdmissionProgress.Pending -> ProtectedRouteAdmissionStart.Pending(handle)
            is ProtectedRouteAdmissionProgress.Terminal -> ProtectedRouteAdmissionStart.Terminal(progress.result)
        }
    }

    @MainThread
    fun release(platformOwner: Any, owner: InfrastructureOwner): ProtectedRouteClaimResult {
        val liveGrant = liveGrants[owner.ownerId]
        if (liveGrant != null &&
            (liveGrant.platformOwner !== platformOwner || liveGrant.owner != owner)
        ) {
            return result(
                ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT,
                "live grant owner or expected revision does not match"
            )
        }
        val mutation = registry.releaseClaim(platformOwner, owner)
        if (mutation.status == InfrastructureMutationStatus.RELEASED) {
            liveGrants.remove(owner.ownerId)
        } else if (mutation.status == InfrastructureMutationStatus.NOT_FOUND &&
            liveGrant != null && liveGrant.controller.resourceQueueEmpty
        ) {
            liveGrants.remove(owner.ownerId)
            return result(ProtectedRouteClaimStatus.RELEASED)
        }
        return mapMutation(mutation)
    }

    /**
     * Drives every active tail-release controller from one coherent observation.
     *
     * The train list is indexed once, then controllers run in stable logical-owner order. A
     * fail-closed update stops this publication immediately. A controller remains the live grant's
     * frozen-route cursor after its current resource queue empties and is removed only by explicit
     * authenticated [release].
     */
    @MainThread
    fun consumeObservation(observation: RailwayObservation): List<ProtectedRouteTailReleaseDispatch> {
        val trainsById = HashMap<String, RailwayTrainObservation>(observation.trains.size)
        observation.trains.forEach { train -> trainsById[train.trainId] = train }

        val dispatched = mutableListOf<ProtectedRouteTailReleaseDispatch>()
        for (active in liveGrants.values) {
            val update = active.controller.consume(observation.stamp, trainsById[active.trainId])
            dispatched += ProtectedRouteTailReleaseDispatch(active.owner, active.trainId, update)
            when (update) {
                is ProtectedRouteTailReleaseUpdate.FailedClosed -> break
                is ProtectedRouteTailReleaseUpdate.Completed,
                is ProtectedRouteTailReleaseUpdate.Active,
                is ProtectedRouteTailReleaseUpdate.Unchanged -> Unit
            }
        }
        return java.util.List.copyOf(dispatched)
    }

    private fun startAdmission(
        plan: ResolutionResult.Plan,
        claimMutation: InfrastructureLeaseRegistry.ClaimMutation,
        platformOwner: Any,
        request: ProtectedRouteRequest
    ): ProtectedRouteAdmissionStart {
        val mutation = claimMutation.result
        if (!mutation.successful || mutation.status == InfrastructureMutationStatus.UNCHANGED) {
            return ProtectedRouteAdmissionStart.Terminal(mapMutation(mutation))
        }
        val admission = plan.admission
        val commitTailRelease = {
            val controller = ProtectedRouteTailRelease(
                worldId = request.observationStamp.worldId,
                topologyRevision = request.route.topologyRevision,
                trainId = request.trainId,
                initialPhysicsEpoch = request.observationStamp.physicsEpoch,
                routeProgressCursor = plan.routeProgressCursor,
                claimRelease = LiveClaimTailRelease(
                    registry,
                    platformOwner,
                    request.owner,
                    CompiledTailReleaseQueue(admission.tailReleaseEntries)
                ),
                stallWorld = stallWorld
            )
            liveGrants[request.owner.ownerId] = LiveGrant(
                platformOwner,
                request.owner,
                request.trainId,
                controller,
                plan.extensionContext
            )
        }
        val started = try {
            preparationActuator.start(admission.preparations)
        } catch (failure: Exception) {
            return stalledStart(
                request,
                failure,
                "infrastructure preparation actuator escaped while starting; claim and rollback reservation retained"
            )
        }
        if (started is InfrastructurePreparationStart.Rejected) {
            return rejectedStart(request, claimMutation, started.failure)
        }
        val ticket = (started as InfrastructurePreparationStart.Accepted).ticket
        val handle = ProtectedRouteAdmissionHandle(
            request.requestId,
            request.owner,
            ticket,
            finishReady = {
                check(registry.completeClaimMutation(claimMutation)) {
                    "claim transaction disappeared after infrastructure became ready"
                }
                commitTailRelease()
                mapMutation(claimMutation.result)
            },
            finishSafeAbort = { preparationDetail ->
                val rollback = registry.rollbackClaimMutation(claimMutation)
                check(rollback.status == InfrastructureMutationStatus.RELEASED) {
                    "claim rollback failed: ${rollback.status}: ${rollback.detail}"
                }
                ProtectedRouteClaimResult(
                    ProtectedRouteClaimStatus.PREPARATION_FAILED,
                    detail = buildString {
                        append("infrastructure preparation was safely aborted and the admission claim was rolled back")
                        if (preparationDetail.isNotBlank()) append(": $preparationDetail")
                    }
                )
            },
            stallWorld = stallWorld
        )
        return when (val progress = handle.poll()) {
            ProtectedRouteAdmissionProgress.Pending -> ProtectedRouteAdmissionStart.Pending(handle)
            is ProtectedRouteAdmissionProgress.Terminal ->
                ProtectedRouteAdmissionStart.Terminal(progress.result)
        }
    }

    private fun rejectedStart(
        request: ProtectedRouteRequest,
        claimMutation: InfrastructureLeaseRegistry.ClaimMutation,
        failure: Exception
    ): ProtectedRouteAdmissionStart {
        val rollback = registry.rollbackClaimMutation(claimMutation)
        return if (rollback.status == InfrastructureMutationStatus.RELEASED) {
            ProtectedRouteAdmissionStart.Terminal(
                result(
                    ProtectedRouteClaimStatus.PREPARATION_FAILED,
                    "infrastructure preparation was rejected before mutation and the admission claim was rolled back: " +
                        failureDetail(failure)
                )
            )
        } else {
            stalledStart(
                request,
                IllegalStateException(
                    "infrastructure preparation was rejected but claim rollback failed: ${rollback.status}: ${rollback.detail}",
                    failure
                ),
                "infrastructure preparation was rejected but the safest claim was retained"
            )
        }
    }

    private fun stalledStart(
        request: ProtectedRouteRequest,
        failure: Exception,
        detail: String
    ): ProtectedRouteAdmissionStart.Terminal {
        stallWorld(
            "protected-route admission ${request.requestId} for ${request.owner.ownerId}@${request.owner.revision} " +
                "entered fail-closed recovery: $detail",
            failure
        )
        return ProtectedRouteAdmissionStart.Terminal(result(ProtectedRouteClaimStatus.WORLD_STALLED, detail))
    }

    private fun stalledExtensionStart(
        request: ProtectedRouteExtensionRequest,
        failure: Exception,
        detail: String
    ): ProtectedRouteAdmissionStart.Terminal {
        stallWorld(
            "protected-route extension ${request.requestId} for ${request.owner.ownerId}@" +
                "${request.owner.revision} entered fail-closed recovery: $detail",
            failure
        )
        return ProtectedRouteAdmissionStart.Terminal(
            result(ProtectedRouteClaimStatus.WORLD_STALLED, detail)
        )
    }

    private fun resolve(request: ProtectedRouteRequest): ResolutionResult {
        if (request.route.topologyRevision != request.observationStamp.topologyRevision ||
            request.route.topologyRevision != currentTopologyRevision()
        ) {
            return ResolutionResult.Finished(
                result(ProtectedRouteClaimStatus.STALE_TOPOLOGY, "request topology revision is stale")
            )
        }
        val observation = latestObservation()
            ?: return ResolutionResult.Finished(
                result(ProtectedRouteClaimStatus.STALE_OBSERVATION, "no coherent railway observation is available")
            )
        if (request.observationStamp != observation.stamp) {
            return ResolutionResult.Finished(
                result(
                    ProtectedRouteClaimStatus.STALE_OBSERVATION,
                    "request observation does not match the latest coherent railway observation"
                )
            )
        }
        val requestTrain = observation.trains.firstOrNull { it.trainId == request.trainId }
        if (requestTrain == null) {
            return ResolutionResult.Finished(
                result(
                    ProtectedRouteClaimStatus.INVALID_REQUEST,
                    "request train ${request.trainId} is absent from the coherent railway observation"
                )
            )
        }
        val validation = try {
            validate(request)
        } catch (failure: Exception) {
            return ResolutionResult.Finished(
                result(
                    ProtectedRouteClaimStatus.VALIDATION_FAILED,
                    "protected-route validation failed: " + failureDetail(failure)
                )
            )
        }
        if (request.route.topologyRevision != currentTopologyRevision()) {
            return ResolutionResult.Finished(
                result(
                    ProtectedRouteClaimStatus.STALE_TOPOLOGY,
                    "topology changed while resolving protected route"
                )
            )
        }
        if (latestObservation()?.stamp != observation.stamp) {
            return ResolutionResult.Finished(
                result(
                    ProtectedRouteClaimStatus.STALE_OBSERVATION,
                    "railway observation changed while resolving protected route"
                )
            )
        }
        return when (validation) {
            ProtectedRouteValidation.NotRequired ->
                ResolutionResult.Finished(result(ProtectedRouteClaimStatus.NOT_REQUIRED))
            is ProtectedRouteValidation.Rejected ->
                ResolutionResult.Finished(result(validation.status, validation.detail))
            is ProtectedRouteValidation.Validated -> {
                val tailInitialization = validation.admission.routeProgressPath.initializeCursor(requestTrain.tail)
                if (tailInitialization !is RouteProgressCursorInitialization.Initialized) {
                    return ResolutionResult.Finished(
                        result(
                            ProtectedRouteClaimStatus.INVALID_REQUEST,
                            "frozen route does not begin at the authoritative train tail"
                        )
                    )
                }
                val occupyingTrainIds = observation.occupancy.blockingTrainIds(
                    validation.admission.occupancyGeometry.intervals,
                    request.trainId
                )
                if (request.route.topologyRevision != currentTopologyRevision()) {
                    ResolutionResult.Finished(
                        result(
                            ProtectedRouteClaimStatus.STALE_TOPOLOGY,
                            "topology changed while checking protected-route occupancy"
                        )
                    )
                } else if (latestObservation()?.stamp != observation.stamp) {
                    ResolutionResult.Finished(
                        result(
                            ProtectedRouteClaimStatus.STALE_OBSERVATION,
                            "railway observation changed while checking protected-route occupancy"
                        )
                    )
                } else if (occupyingTrainIds.isNotEmpty()) {
                    ResolutionResult.Finished(
                        ProtectedRouteClaimResult(
                            ProtectedRouteClaimStatus.PHYSICALLY_OCCUPIED,
                            occupyingTrainIds = occupyingTrainIds,
                            detail = "${occupyingTrainIds.size} train(s) physically block the protected route"
                        )
                    )
                } else {
                    ResolutionResult.Plan(
                        validation.admission,
                        tailInitialization.cursor,
                        validation.extensionContext
                    )
                }
            }
        }
    }

    private fun mapMutation(mutation: InfrastructureMutationResult): ProtectedRouteClaimResult =
        ProtectedRouteClaimResult(
            status = when (mutation.status) {
                InfrastructureMutationStatus.ACQUIRED -> ProtectedRouteClaimStatus.ACQUIRED
                InfrastructureMutationStatus.REPLACED -> error(
                    "whole-claim replacement is not a protected-route service result"
                )
                InfrastructureMutationStatus.RELEASED -> ProtectedRouteClaimStatus.RELEASED
                InfrastructureMutationStatus.UNCHANGED -> ProtectedRouteClaimStatus.UNCHANGED
                InfrastructureMutationStatus.WORLD_UNAVAILABLE -> ProtectedRouteClaimStatus.WORLD_UNAVAILABLE
                InfrastructureMutationStatus.STALE_TOPOLOGY -> ProtectedRouteClaimStatus.STALE_TOPOLOGY
                InfrastructureMutationStatus.INVALID_RESOURCE -> ProtectedRouteClaimStatus.INVALID_RESOURCE
                InfrastructureMutationStatus.RESOURCE_PROVIDER_FAILED ->
                    ProtectedRouteClaimStatus.RESOURCE_PROVIDER_FAILED
                InfrastructureMutationStatus.OWNER_REVISION_CONFLICT ->
                    ProtectedRouteClaimStatus.OWNER_REVISION_CONFLICT
                InfrastructureMutationStatus.RESOURCE_CONFLICT -> ProtectedRouteClaimStatus.RESOURCE_CONFLICT
                InfrastructureMutationStatus.NOT_FOUND -> ProtectedRouteClaimStatus.NOT_FOUND
                InfrastructureMutationStatus.SERVICE_CLOSED -> ProtectedRouteClaimStatus.SERVICE_CLOSED
            },
            blockers = mutation.blockers,
            detail = mutation.detail
        )

    private fun result(status: ProtectedRouteClaimStatus, detail: String = "") =
        ProtectedRouteClaimResult(status, detail = detail)

    private sealed interface ResolutionResult {
        data class Finished(val result: ProtectedRouteClaimResult) : ResolutionResult
        data class Plan(
            val admission: CompiledRouteAdmission,
            val routeProgressCursor: RouteProgressCursor,
            val extensionContext: ProtectedRouteExtensionContext?
        ) : ResolutionResult
    }

    private companion object {
        fun failureDetail(failure: Exception): String =
            failure::class.simpleName + ": " + failure.message.orEmpty()
    }
}
