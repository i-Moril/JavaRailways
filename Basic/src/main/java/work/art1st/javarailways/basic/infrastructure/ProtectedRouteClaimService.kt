package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimResult
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.core.api.util.annotation.MainThread

/**
 * Validates one protected-route request, claims its resource set, and commits every turnout.
 *
 * This is the host-side admission seam for the future public route-resource service. A grant is
 * not visible yet, so a successful return is the first point at which both the claim and all
 * direction-symmetric turnout selections have committed.
 */
internal class ProtectedRouteClaimService(
    private val registry: InfrastructureLeaseRegistry,
    private val currentTopologyRevision: () -> Long,
    private val latestObservation: () -> RailwayObservation?,
    private val validate: (ProtectedRouteRequest) -> ProtectedRouteValidation,
    private val turnoutCommitter: TurnoutSelectionCommitter,
    private val stallWorld: (String, Exception) -> Unit
) {
    @MainThread
    fun acquire(platformOwner: Any, request: ProtectedRouteRequest): ProtectedRouteClaimResult {
        return when (val resolved = resolve(request)) {
            is ResolutionResult.Finished -> resolved.result
            is ResolutionResult.Plan -> commitAdmission(
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
    }

    @MainThread
    fun replace(
        platformOwner: Any,
        expectedOwner: InfrastructureOwner,
        request: ProtectedRouteRequest
    ): ProtectedRouteClaimResult {
        return when (val resolved = resolve(request)) {
            is ResolutionResult.Finished -> {
                if (resolved.result.status == ProtectedRouteClaimStatus.NOT_REQUIRED) {
                    result(
                        ProtectedRouteClaimStatus.INVALID_REQUEST,
                        "replace cannot discard an existing protected-route claim; release it explicitly"
                    )
                } else {
                    resolved.result
                }
            }
            is ResolutionResult.Plan -> commitAdmission(
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
    }

    @MainThread
    fun release(platformOwner: Any, owner: InfrastructureOwner): ProtectedRouteClaimResult =
        mapMutation(registry.releaseClaim(platformOwner, owner))

    private fun commitAdmission(
        admission: CompiledRouteAdmission,
        claimMutation: InfrastructureLeaseRegistry.ClaimMutation,
        request: ProtectedRouteRequest
    ): ProtectedRouteClaimResult {
        val mutation = claimMutation.result
        if (!mutation.successful) return mapMutation(mutation)
        if (mutation.status == InfrastructureMutationStatus.UNCHANGED) {
            return mapMutation(mutation)
        }
        val turnoutCommit = try {
            turnoutCommitter.commit(admission.turnoutSelections)
        } catch (failure: Exception) {
            TurnoutCommitResult.Failed(
                failure,
                listOf(IllegalStateException("turnout committer escaped without confirming rollback"))
            )
        }
        return when (val commit = turnoutCommit) {
            TurnoutCommitResult.Committed -> {
                if (registry.completeClaimMutation(claimMutation)) {
                    mapMutation(mutation)
                } else {
                    worldStalled(
                        request,
                        IllegalStateException("claim transaction disappeared after turnout commit"),
                        "turnouts committed but the claim transaction could not be finalized"
                    )
                }
            }
            is TurnoutCommitResult.Failed -> compensateFailedTurnoutCommit(request, claimMutation, commit)
        }
    }

    private fun compensateFailedTurnoutCommit(
        request: ProtectedRouteRequest,
        claimMutation: InfrastructureLeaseRegistry.ClaimMutation,
        commit: TurnoutCommitResult.Failed
    ): ProtectedRouteClaimResult {
        if (!commit.rollbackSucceeded) {
            val failure = TurnoutRollbackException(commit.applyFailure, commit.rollbackFailures)
            return worldStalled(
                request,
                failure,
                "turnout commit failed and ${commit.rollbackFailures.size} selection rollback(s) failed; " +
                    "claim and rollback reservation retained"
            )
        }
        val claimRollback = registry.rollbackClaimMutation(claimMutation)
        if (claimRollback.status in CLAIM_ROLLBACK_SUCCESS_STATUSES) {
            return result(
                ProtectedRouteClaimStatus.TURNOUT_COMMIT_FAILED,
                "turnout commit failed and admission was restored: ${failureDetail(commit.applyFailure)}"
            )
        }
        val failure = IllegalStateException(
            "turnout selections were restored but claim rollback failed: " +
                "${claimRollback.status}: ${claimRollback.detail}",
            commit.applyFailure
        )
        return worldStalled(
            request,
            failure,
            "turnout commit failed; selections were restored but the safest claim was retained"
        )
    }

    private fun worldStalled(
        request: ProtectedRouteRequest,
        failure: Exception,
        detail: String
    ): ProtectedRouteClaimResult {
        stallWorld(
            "protected-route admission ${request.requestId} for ${request.owner.ownerId}@${request.owner.revision} " +
                "entered fail-closed recovery: $detail",
            failure
        )
        return result(ProtectedRouteClaimStatus.WORLD_STALLED, detail)
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
        if (observation.trains.none { it.trainId == request.trainId }) {
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
                    "protected-route validation failed: " +
                        failure::class.simpleName + ": " + failure.message.orEmpty()
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

private class TurnoutRollbackException(
    applyFailure: Exception,
    rollbackFailures: List<Exception>
) : IllegalStateException(
    "turnout commit failed and ${rollbackFailures.size} rollback(s) failed",
    applyFailure
) {
    init {
        rollbackFailures.forEach(::addSuppressed)
    }
}
