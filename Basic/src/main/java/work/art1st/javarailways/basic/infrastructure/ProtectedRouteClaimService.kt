package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimResult
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus

/**
 * Validates one protected-route request and atomically submits its single resource set.
 *
 * This is the host-side admission seam for the future public route-resource service. It does not
 * mutate turnout selections; callers may do so only after a successful claim and must roll the
 * selection back before releasing the claim if their later commit fails.
 */
internal class ProtectedRouteClaimService(
    private val registry: InfrastructureLeaseRegistry,
    private val currentTopologyRevision: () -> Long,
    private val validate: (ProtectedRouteRequest) -> ProtectedRouteValidation
) {
    fun acquire(platformOwner: Any, request: ProtectedRouteRequest): ProtectedRouteClaimResult {
        return when (val resolved = resolve(request)) {
            is ResolutionResult.Finished -> resolved.result
            is ResolutionResult.Resources -> mapMutation(
                registry.acquireClaim(
                    platformOwner,
                    InfrastructureClaimRequest(request.route.topologyRevision, request.owner, resolved.value)
                )
            )
        }
    }

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
            is ResolutionResult.Resources -> mapMutation(
                registry.replaceClaim(
                    platformOwner,
                    expectedOwner,
                    InfrastructureClaimRequest(request.route.topologyRevision, request.owner, resolved.value)
                )
            )
        }
    }

    fun release(platformOwner: Any, owner: InfrastructureOwner): ProtectedRouteClaimResult =
        mapMutation(registry.releaseClaim(platformOwner, owner))

    private fun resolve(request: ProtectedRouteRequest): ResolutionResult {
        if (request.route.topologyRevision != currentTopologyRevision()) {
            return ResolutionResult.Finished(
                result(ProtectedRouteClaimStatus.STALE_TOPOLOGY, "request topology revision is stale")
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
        return when (validation) {
            ProtectedRouteValidation.NotRequired ->
                ResolutionResult.Finished(result(ProtectedRouteClaimStatus.NOT_REQUIRED))
            is ProtectedRouteValidation.Rejected ->
                ResolutionResult.Finished(result(validation.status, validation.detail))
            is ProtectedRouteValidation.Validated ->
                ResolutionResult.Resources(validation.resources)
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
        data class Resources(val value: InfrastructureFootprint) : ResolutionResult
    }
}
