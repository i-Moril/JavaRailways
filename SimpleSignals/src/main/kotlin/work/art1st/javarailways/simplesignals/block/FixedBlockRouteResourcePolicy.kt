package work.art1st.javarailways.simplesignals.block

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
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

    override fun contribute(
        worldId: UUID,
        intent: RouteResourceIntent
    ): Collection<ContributedRouteResource> {
        require(intent.observationStamp.worldId == worldId.toString()) {
            "route intent belongs to world ${intent.observationStamp.worldId}, expected $worldId"
        }
        val snapshot = checkNotNull(snapshotForWorld(worldId)) {
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
        return java.util.List.copyOf(
            windows.map { window ->
                ContributedRouteResource(
                    StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, window.blockId),
                    RouteBoundary(window.exitRouteDistanceMeters)
                )
            }
        )
    }
}
