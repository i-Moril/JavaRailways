package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimResult
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.core.api.util.annotation.MainThread
import java.util.TreeMap

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
 * confirmation, or restores the exact previous claim after physical restoration.
 */
internal class ProtectedRouteAdmissionHandle internal constructor(
    private val registry: InfrastructureLeaseRegistry,
    private val request: ProtectedRouteRequest,
    private val claimMutation: InfrastructureLeaseRegistry.ClaimMutation,
    private val preparationTicket: InfrastructurePreparationTicket,
    private val commitTailRelease: () -> Unit,
    private val resumePreviousTailRelease: () -> Unit,
    private val stallWorld: (String, Exception) -> Unit,
    private val mapMutation: (InfrastructureMutationResult) -> ProtectedRouteClaimResult
) {
    val ticketId: String = preparationTicket.ticketId
    private var lastState: InfrastructurePreparationState? = null
    private var cancellationRequested = false
    private var cancellationFailure: Exception? = null
    private var terminal: ProtectedRouteAdmissionProgress.Terminal? = null

    init {
        require(ticketId.isNotBlank()) { "accepted infrastructure preparation ticket ID must not be blank" }
    }

    @MainThread
    fun poll(): ProtectedRouteAdmissionProgress {
        terminal?.let { return it }
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
                try {
                    commitTailRelease()
                } catch (failure: Exception) {
                    return finishStalled(
                        failure,
                        "claim transaction was finalized but its tail-release controller could not be installed"
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
            try {
                resumePreviousTailRelease()
            } catch (failure: Exception) {
                return finishStalled(
                    failure,
                    "admission claim was restored but its previous tail-release controller could not resume"
                )
            }
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

    private fun finish(result: ProtectedRouteClaimResult): ProtectedRouteAdmissionProgress.Terminal =
        ProtectedRouteAdmissionProgress.Terminal(result).also { terminal = it }

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
    private data class ActiveTailRelease(
        val owner: InfrastructureOwner,
        val trainId: String,
        val controller: ProtectedRouteTailRelease,
        var paused: Boolean = false
    )

    private val activeTailReleases = TreeMap<String, ActiveTailRelease>()

    @MainThread
    fun acquire(platformOwner: Any, request: ProtectedRouteRequest): ProtectedRouteAdmissionStart =
        when (val resolved = resolve(request)) {
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
                request,
                previousOwner = null
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
            resolved,
            registry.beginReplaceClaim(
                platformOwner,
                expectedOwner,
                InfrastructureClaimRequest(
                    request.route.topologyRevision,
                    request.owner,
                    resolved.admission.resources
                )
            ),
            platformOwner,
            request,
            previousOwner = expectedOwner
        )
    }

    @MainThread
    fun release(platformOwner: Any, owner: InfrastructureOwner): ProtectedRouteClaimResult {
        val mutation = registry.releaseClaim(platformOwner, owner)
        if (mutation.status == InfrastructureMutationStatus.RELEASED) {
            activeTailReleases[owner.ownerId]
                ?.takeIf { it.owner == owner }
                ?.let { activeTailReleases.remove(owner.ownerId) }
        }
        return mapMutation(mutation)
    }

    /**
     * Drives every unpaused tail-release controller from one coherent observation.
     *
     * The train list is indexed once, then controllers run in stable logical-owner order. A
     * fail-closed update stops this publication immediately; completed controllers are removed
     * only after their underlying registry claim has been completely released.
     */
    @MainThread
    fun consumeObservation(observation: RailwayObservation): List<ProtectedRouteTailReleaseDispatch> {
        val trainsById = HashMap<String, RailwayTrainObservation>(observation.trains.size)
        observation.trains.forEach { train -> trainsById[train.trainId] = train }

        val dispatched = mutableListOf<ProtectedRouteTailReleaseDispatch>()
        val iterator = activeTailReleases.entries.iterator()
        while (iterator.hasNext()) {
            val active = iterator.next().value
            if (active.paused) continue
            val update = active.controller.consume(observation.stamp, trainsById[active.trainId])
            dispatched += ProtectedRouteTailReleaseDispatch(active.owner, active.trainId, update)
            when (update) {
                is ProtectedRouteTailReleaseUpdate.Completed -> iterator.remove()
                is ProtectedRouteTailReleaseUpdate.FailedClosed -> break
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
        request: ProtectedRouteRequest,
        previousOwner: InfrastructureOwner?
    ): ProtectedRouteAdmissionStart {
        val mutation = claimMutation.result
        if (!mutation.successful || mutation.status == InfrastructureMutationStatus.UNCHANGED) {
            return ProtectedRouteAdmissionStart.Terminal(mapMutation(mutation))
        }
        val pausedTailRelease = try {
            pauseTailRelease(request.owner.ownerId, previousOwner)
        } catch (failure: Exception) {
            return stalledStart(
                request,
                failure,
                "claim mutation started but the previous tail-release controller could not be paused"
            )
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
            activeTailReleases[request.owner.ownerId] = ActiveTailRelease(
                request.owner,
                request.trainId,
                controller
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
            return rejectedStart(request, claimMutation, pausedTailRelease, started.failure)
        }
        val ticket = (started as InfrastructurePreparationStart.Accepted).ticket
        val handle = try {
            ProtectedRouteAdmissionHandle(
                registry,
                request,
                claimMutation,
                ticket,
                commitTailRelease,
                { resumeTailRelease(pausedTailRelease) },
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
            is ProtectedRouteAdmissionProgress.Terminal ->
                ProtectedRouteAdmissionStart.Terminal(progress.result)
        }
    }

    private fun rejectedStart(
        request: ProtectedRouteRequest,
        claimMutation: InfrastructureLeaseRegistry.ClaimMutation,
        pausedTailRelease: ActiveTailRelease?,
        failure: Exception
    ): ProtectedRouteAdmissionStart {
        val rollback = registry.rollbackClaimMutation(claimMutation)
        return if (rollback.status in CLAIM_ROLLBACK_SUCCESS_STATUSES) {
            resumeTailRelease(pausedTailRelease)
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

    private fun pauseTailRelease(
        ownerId: String,
        previousOwner: InfrastructureOwner?
    ): ActiveTailRelease? {
        val active = activeTailReleases[ownerId] ?: return null
        if (previousOwner != null && active.owner != previousOwner) {
            throw IllegalStateException(
                "tail-release owner ${active.owner} does not match replacement owner $previousOwner"
            )
        }
        active.paused = true
        return active
    }

    private fun resumeTailRelease(active: ActiveTailRelease?) {
        if (active == null) return
        if (activeTailReleases[active.owner.ownerId] === active) active.paused = false
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
                    ResolutionResult.Plan(validation.admission, tailInitialization.cursor)
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
        data class Plan(
            val admission: CompiledRouteAdmission,
            val routeProgressCursor: RouteProgressCursor
        ) : ResolutionResult
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
