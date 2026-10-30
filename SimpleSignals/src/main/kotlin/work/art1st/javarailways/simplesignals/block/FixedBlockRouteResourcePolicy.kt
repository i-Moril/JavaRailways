package work.art1st.javarailways.simplesignals.block

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.FormationRouteContext
import work.art1st.javarailways.basic.api.route.FormationRoutePurpose
import work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult
import work.art1st.javarailways.basic.api.route.FormationResourceClearance
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProvider
import work.art1st.javarailways.bukkit.api.infrastructure.RouteResourceContributor
import java.util.UUID

internal const val FIXED_BLOCK_RESOURCE_TYPE = "simplesignals:fixed-block"

/** Bridges plugin-owned fixed blocks into the host's one generic route-resource transaction. */
internal class FixedBlockRouteResourcePolicy(
    private val plugin: Plugin,
    private val validateRouteAdmission: (UUID, RouteResourceIntent) -> Unit = { _, _ -> },
    private val snapshotForWorld: (UUID) -> FixedBlockReadSnapshot?
) : InfrastructureResourceProvider, RouteResourceContributor {
    override fun exists(worldId: UUID, resourceId: String): Boolean =
        snapshotForWorld(worldId)?.blocksById?.containsKey(resourceId) == true

    override fun validateRouteContribution(
        contributor: Plugin,
        worldId: UUID,
        resourceId: String,
        preparation: ResourcePreparationDescriptor?
    ): RouteAdmissionFailureCode? = when {
        preparation != null -> RouteAdmissionFailureCode.INVALID_PREPARATION
        contributor !== plugin -> RouteAdmissionFailureCode.INVALID_CONTRIBUTION
        !exists(worldId, resourceId) -> RouteAdmissionFailureCode.INVALID_CONTRIBUTION
        else -> null
    }

    private fun policy(worldId: UUID) = FixedBlockDomainResourcePolicy(worldId,
        { validateRouteAdmission(worldId, it) }, { snapshotForWorld(worldId) })

    override fun authorizeFormationRoute(worldId: UUID, context: FormationRouteContext,
        intent: RouteResourceIntent): RouteAdmissionFailureCode? = policy(worldId).authorizeFormationRoute(context, intent)?.code

    override fun authorizeFormationResource(worldId: UUID, resourceId: String, context: FormationRouteContext,
        intent: RouteResourceIntent): FormationRoutePolicyResult = policy(worldId).authorizeFormationResource(resourceId, context, intent)

    override fun contribute(worldId: UUID, intent: RouteResourceIntent): Collection<ContributedRouteResource> =
        policy(worldId).contribute(intent)
}

/** The same fixed-block resource calculation for LIVE authentication and detached simulation. */
internal class FixedBlockDomainResourcePolicy(
    private val worldId: UUID,
    private val validateRouteAdmission: (RouteResourceIntent) -> Unit,
    private val snapshot: () -> FixedBlockReadSnapshot?
) {
    fun exists(resourceId: String): Boolean = snapshot()?.blocksById?.containsKey(resourceId) == true

    /** Same territory and matcher gates as ordinary admission, without partner-lock exemptions. */
    fun authorizeFormationRoute(context: FormationRouteContext, intent: RouteResourceIntent): RouteAdmissionFailure? = try {
        require(intent.formationContext === context) { "formation context does not match intent" }
        if (context.purpose != FormationRoutePurpose.COUPLING_APPROACH)
            require(context.outputs.count { it.trainId == intent.trainId } == 1) { "formation route must name one exact output" }
        contribute(if (context.purpose == FormationRoutePurpose.COUPLING_APPROACH) intent
            else intent.copy(desiredAuthority = RouteBoundary(intent.route.totalLengthMeters)))
        null
    } catch (failure: Exception) {
        RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            "SimpleSignals formation route rejected: ${failure.message ?: failure.javaClass.simpleName}")
    }

    fun authorizeFormationResource(resourceId: String, context: FormationRouteContext,
        intent: RouteResourceIntent): FormationRoutePolicyResult {
        authorizeFormationRoute(context, intent)?.let { return FormationRoutePolicyResult.Rejected(it) }
        val resource = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, resourceId)
        if (context.purpose == FormationRoutePurpose.COUPLING_APPROACH ||
            resource !in context.retainedStableResources || !exists(resourceId))
            return FormationRoutePolicyResult.Rejected(RouteAdmissionFailure(
                RouteAdmissionFailureCode.INVALID_CONTRIBUTION, "not a retained SimpleSignals output resource"))
        return try {
            // Complete route, never the short output-body authority: child release must remain conservative.
            val boundary = contribute(intent.copy(desiredAuthority = RouteBoundary(intent.route.totalLengthMeters)))
                .singleOrNull { it.resource == resource }?.releaseAfterTailPasses
            FormationRoutePolicyResult.Accepted(listOf(FormationResourceClearance(resource, boundary)))
        } catch (failure: Exception) {
            FormationRoutePolicyResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "SimpleSignals clearance failed: ${failure.message ?: failure.javaClass.simpleName}"))
        }
    }

    fun contribute(intent: RouteResourceIntent): List<ContributedRouteResource> {
        require(intent.observationStamp.worldId == worldId.toString()) {
            "route intent belongs to world ${intent.observationStamp.worldId}, expected $worldId"
        }
        validateRouteAdmission(intent)
        val snapshot = checkNotNull(snapshot()) {
            "SimpleSignals fixed-block runtime is unavailable for world $worldId"
        }
        val match = FixedBlockRouteMatcher.matchAuthority(
            snapshot,
            intent.route,
            intent.desiredAuthority.distanceFromRouteStartMeters
        )
        val windows = when (match) {
            is FixedBlockAuthorityMatchResult.Matched -> match.windows
            is FixedBlockAuthorityMatchResult.Rejected -> error(match.detail)
        }
        val lastExitByBlock = linkedMapOf<String, Double>()
        windows.forEach { window ->
            lastExitByBlock.merge(window.blockId, window.exitRouteDistanceMeters, ::maxOf)
        }
        return java.util.List.copyOf(
            lastExitByBlock.map { (blockId, lastExit) ->
                ContributedRouteResource(
                    StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, blockId),
                    RouteBoundary(lastExit)
                )
            }
        )
    }
}
