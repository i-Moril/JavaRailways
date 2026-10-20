package work.art1st.javarailways.simplesignals.block

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
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
    private val directionPolicyForWorld: (UUID) -> FixedBlockDirectionPolicy = { FixedBlockDirectionPolicy() },
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

    override fun contribute(
        worldId: UUID,
        intent: RouteResourceIntent
    ): Collection<ContributedRouteResource> {
        require(intent.observationStamp.worldId == worldId.toString()) {
            "route intent belongs to world ${intent.observationStamp.worldId}, expected $worldId"
        }
        validateRouteAdmission(worldId, intent)
        val snapshot = checkNotNull(snapshotForWorld(worldId)) {
            "SimpleSignals fixed-block runtime is unavailable for world $worldId"
        }
        val match = FixedBlockRouteMatcher.matchAuthority(
            snapshot,
            intent.route,
            intent.desiredAuthority.distanceFromRouteStartMeters,
            detectReverse = true
        )
        val windows = when (match) {
            is FixedBlockAuthorityMatchResult.Matched -> match.windows
            is FixedBlockAuthorityMatchResult.Rejected -> error(match.detail)
        }
        val directionPolicy = directionPolicyForWorld(worldId)
        val disabled = directionPolicy.disabledBlocks(snapshot, windows.map { snapshot.blocksById.getValue(it.blockId) }.distinct())
        val lastExitByBlock = linkedMapOf<String, Double>()
        directionPolicy.normalWindows(snapshot, windows, disabled).forEach { window ->
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
