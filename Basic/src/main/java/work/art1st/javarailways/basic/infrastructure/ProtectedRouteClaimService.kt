package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimResult
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.core.api.util.annotation.MainThread

/** Main-thread result of starting one protected-route admission. */
internal sealed interface ProtectedRouteAdmissionStart {
    @MainThread
    fun poll(): ProtectedRouteAdmissionProgress

    @MainThread
    fun cancel()

    /** Admission ended during start. Polling remains idempotent and cancellation is a no-op. */
    data class Terminal(val result: ProtectedRouteClaimResult) : ProtectedRouteAdmissionStart {
        override fun poll(): ProtectedRouteAdmissionProgress = ProtectedRouteAdmissionProgress.Terminal(result)
        override fun cancel() = Unit
    }

    /** Admission owns a live claim transaction until [handle] reaches a terminal result. */
    data class Pending(val handle: ProtectedRouteAdmissionHandle) : ProtectedRouteAdmissionStart {
        override fun poll(): ProtectedRouteAdmissionProgress = handle.poll()
        override fun cancel() = handle.cancel()
    }
}

/** Result of polling a pending or already-terminal protected-route admission. */
internal sealed interface ProtectedRouteAdmissionProgress {
    data object Pending : ProtectedRouteAdmissionProgress
    data class Terminal(val result: ProtectedRouteClaimResult) : ProtectedRouteAdmissionProgress
}

/**
 * Owns one claim mutation while physical infrastructure preparation is pending.
 *
 * A handle never publishes authority. It only finalizes the logical claim after physical
 * confirmation, or restores the exact previous claim after physical restoration.
 */
internal class ProtectedRouteAdmissionHandle internal constructor(
    private val registry: InfrastructureLeaseRegistry,
    private val request: ProtectedRouteRequest,
    private val claimMutation: InfrastructureLeaseRegistry.ClaimMutation,
    private val preparationTicket: InfrastructurePreparationTicket,
    private val stallWorld: (String, Exception) -> Unit,
    private val mapMutation: (InfrastructureMutationResult) -> ProtectedRouteClaimResult
) {
    val ticketId: String = preparationTicket.ticketId
    private var lastState: InfrastructurePreparationState? = null
    private var cancellationRequested = false
    private var cancellationFailure: Exception? = null
    private var terminal: ProtectedRouteClaimResult? = null

    init {
        require(ticketId.isNotBlank()) { "accepted infrastructure preparation ticket ID must not be blank" }
    }

    @MainThread
    fun poll(): ProtectedRouteAdmissionProgress {
        terminal?.let { return ProtectedRouteAdmissionProgress.Terminal(it) }
        cancellationFailure?.let { failure ->
            return finishStalled(
                failure,
                "infrastructure restoration could not be requested; claim and rollback reservation retained"
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
        if (snapshot.ticketId != ticketId) {
            return finishStalled(
                IllegalStateException(
                    "infrastructure preparation ticket identity changed from $ticketId to ${snapshot.ticketId}"
                ),
                "infrastructure preparation ticket identity changed; claim and rollback reservation retained"
            )
        }
        if (!validTransition(lastState, snapshot.state)) {
            return finishStalled(
                IllegalStateException(
                    "invalid infrastructure preparation transition ${lastState ?: "START"} -> ${snapshot.state}"
                ),
                "infrastructure preparation state contract was violated; claim and rollback reservation retained"
            )
        }
        lastState = snapshot.state
        return when (snapshot.state) {
            InfrastructurePreparationState.PREPARING,
            InfrastructurePreparationState.RESTORING -> ProtectedRouteAdmissionProgress.Pending
            InfrastructurePreparationState.READY -> {
                val completed = if (registry.completeClaimMutation(claimMutation)) {
                    mapMutation(claimMutation.result)
                } else {
                    return finishStalled(
                        IllegalStateException("claim transaction disappeared after infrastructure became ready"),
                        "infrastructure preparation ready but the claim transaction could not be finalized"
                    )
                }
                finish(completed)
            }
            InfrastructurePreparationState.RESTORED -> restoreClaim(snapshot.detail)
            InfrastructurePreparationState.UNSAFE -> finishStalled(
                IllegalStateException(snapshot.detail),
                "infrastructure preparation state is unsafe; claim and rollback reservation retained: ${snapshot.detail}"
            )
        }
    }

    @MainThread
    fun cancel() {
        if (terminal != null || cancellationRequested) return
        cancellationRequested = true
        try {
            preparationTicket.cancel()
        } catch (failure: Exception) {
            cancellationFailure = failure
        }
    }

    private fun restoreClaim(preparationDetail: String): ProtectedRouteAdmissionProgress {
        val claimRollback = registry.rollbackClaimMutation(claimMutation)
        if (claimRollback.status in CLAIM_ROLLBACK_SUCCESS_STATUSES) {
            return finish(
                ProtectedRouteClaimResult(
                    ProtectedRouteClaimStatus.PREPARATION_FAILED,
                    detail = buildString {
                        append("infrastructure preparation was restored and the admission claim was rolled back")
                        if (preparationDetail.isNotBlank()) append(": $preparationDetail")
                    }
                )
            )
        }
        return finishStalled(
            IllegalStateException(
                "infrastructure preparations were restored but claim rollback failed: " +
                    "${claimRollback.status}: ${claimRollback.detail}"
            ),
            "infrastructure preparation was restored but the safest claim was retained"
        )
    }

    private fun finish(result: ProtectedRouteClaimResult): ProtectedRouteAdmissionProgress.Terminal {
        terminal = result
        return ProtectedRouteAdmissionProgress.Terminal(result)
    }

    private fun finishStalled(
        failure: Exception,
        detail: String
    ): ProtectedRouteAdmissionProgress.Terminal {
        val stalled = ProtectedRouteClaimResult(ProtectedRouteClaimStatus.WORLD_STALLED, detail = detail)
        stallWorld(
            "protected-route admission ${request.requestId} for " +
                "${request.owner.ownerId}@${request.owner.revision} entered fail-closed recovery: $detail",
            failure
        )
        return finish(stalled)
    }

    private companion object {
        val CLAIM_ROLLBACK_SUCCESS_STATUSES = setOf(
            InfrastructureMutationStatus.RELEASED,
            InfrastructureMutationStatus.REPLACED,
            InfrastructureMutationStatus.UNCHANGED
        )

        fun validTransition(previous: InfrastructurePreparationState?, current: InfrastructurePreparationState): Boolean =
            when (previous) {
                null -> true
                InfrastructurePreparationState.PREPARING -> current in setOf(
                    InfrastructurePreparationState.PREPARING,
                    InfrastructurePreparationState.RESTORING,
                    InfrastructurePreparationState.READY,
                    InfrastructurePreparationState.RESTORED,
                    InfrastructurePreparationState.UNSAFE
                )
                InfrastructurePreparationState.RESTORING -> current in setOf(
                    InfrastructurePreparationState.RESTORING,
                    InfrastructurePreparationState.RESTORED,
                    InfrastructurePreparationState.UNSAFE
                )
                InfrastructurePreparationState.READY,
                InfrastructurePreparationState.RESTORED,
                InfrastructurePreparationState.UNSAFE -> false
            }
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
    private val preparationActuator: InfrastructurePreparationActuator,
    private val stallWorld: (String, Exception) -> Unit
) {
    @MainThread
    fun acquire(platformOwner: Any, request: ProtectedRouteRequest): ProtectedRouteAdmissionStart =
        when (val resolved = resolve(request)) {
            is ResolutionResult.Finished -> ProtectedRouteAdmissionStart.Terminal(resolved.result)
            is ResolutionResult.Plan -> startAdmission(
                resolved.value,
                registry.beginAcquireClaim(
                    platformOwner,
                    InfrastructureClaimRequest(
                        request.route.topologyRevision,
                        request.owner,
                        resolved.value.resources
                    )
                ),
                request
            )
        }

    @MainThread
    fun replace(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        request: ProtectedRouteRequest
    ): ProtectedRouteAdmissionStart = when (val resolved = resolve(request)) {
        is ResolutionResult.Finished -> ProtectedRouteAdmissionStart.Terminal(
            if (resolved.result.status == ProtectedRouteClaimStatus.NOT_REQUIRED) {
                result(
                    ProtectedRouteClaimStatus.INVALID_REQUEST,
                    "replace cannot discard an existing protected-route claim; release it explicitly"
                )
            } else {
                resolved.result
            }
        )
        is ResolutionResult.Plan -> startAdmission(
            resolved.value,
            registry.beginReplaceClaim(
                platformOwner,
                expectedOwner,
                InfrastructureClaimRequest(
                    request.route.topologyRevision,
                    request.owner,
                    resolved.value.resources
                )
            ),
            request
        )
    }

    @MainThread
    fun release(platformOwner: Any, owner: InfrastructureOwner): ProtectedRouteClaimResult =
        mapMutation(registry.releaseClaim(platformOwner, owner))

    private fun startAdmission(
        admission: CompiledRouteAdmission,
        claimMutation: InfrastructureLeaseRegistry.ClaimMutation,
        request: ProtectedRouteRequest
    ): ProtectedRouteAdmissionStart {
        val mutation = claimMutation.result
        if (!mutation.successful || mutation.status == InfrastructureMutationStatus.UNCHANGED) {
            return ProtectedRouteAdmissionStart.Terminal(mapMutation(mutation))
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
        val handle = try {
            ProtectedRouteAdmissionHandle(
                registry,
                request,
                claimMutation,
                ticket,
                stallWorld,
                ::mapMutation
            )
        } catch (failure: Exception) {
            return stalledStart(
                request,
                failure,
                "accepted preparation ticket was invalid; claim and rollback reservation retained"
            )
        }
        return when (val progress = handle.poll()) {
            ProtectedRouteAdmissionProgress.Pending -> ProtectedRouteAdmissionStart.Pending(handle)
            is ProtectedRouteAdmissionProgress.Terminal -> ProtectedRouteAdmissionStart.Terminal(progress.result)
        }
    }

    private fun rejectedStart(
        request: ProtectedRouteRequest,
        claimMutation: InfrastructureLeaseRegistry.ClaimMutation,
        failure: Exception
    ): ProtectedRouteAdmissionStart {
        val rollback = registry.rollbackClaimMutation(claimMutation)
        return if (rollback.status in CLAIM_ROLLBACK_SUCCESS_STATUSES) {
            ProtectedRouteAdmissionStart.Terminal(
                result(
                    ProtectedRouteClaimStatus.PREPARATION_FAILED,
                    "infrastructure preparation was rejected before mutation and the admission claim was restored: " +
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
                    ResolutionResult.Plan(validation.admission)
                }
            }
        }
    }

    private fun mapMutation(mutation: InfrastructureMutationResult): ProtectedRouteClaimResult =
        ProtectedRouteClaimResult(
            status = when (mutation.status) {
                InfrastructureMutationStatus.ACQUIRED -> ProtectedRouteClaimStatus.ACQUIRED
                InfrastructureMutationStatus.REPLACED -> ProtectedRouteClaimStatus.REPLACED
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
        data class Plan(val value: CompiledRouteAdmission) : ResolutionResult
    }

    private companion object {
        val CLAIM_ROLLBACK_SUCCESS_STATUSES = setOf(
            InfrastructureMutationStatus.RELEASED,
            InfrastructureMutationStatus.REPLACED,
            InfrastructureMutationStatus.UNCHANGED
        )

        fun failureDetail(failure: Exception): String =
            failure::class.simpleName + ": " + failure.message.orEmpty()
    }
}
