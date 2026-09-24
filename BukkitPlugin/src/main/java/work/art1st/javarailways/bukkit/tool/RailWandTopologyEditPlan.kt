package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditDecision
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditStatus
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact
import work.art1st.javarailways.basic.infrastructure.PhysicalTopologyReplacementOrRemovalImpactResolver
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureEditPermit
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import java.util.UUID

/** Exact pre-edit state retained by the RailWand between permit check and structural commit. */
internal class RailWandTopologyEditPlan private constructor(
    private val world: CoreWorld,
    private val topologyRoot: TopologyState,
    private val affectedRails: Map<UUID, CoreRail>,
    val impact: TopologyEditImpact
) {
    val topologyRevision: Long = topologyRoot.revision

    fun isCurrent(candidateWorld: CoreWorld): Boolean =
        candidateWorld === world && candidateWorld.topologyState === topologyRoot &&
            affectedRails.all { (railId, rail) -> candidateWorld.getRail(railId) === rail }

    fun requireCurrent(candidateWorld: CoreWorld) {
        check(isCurrent(candidateWorld)) {
            "RailWand topology edit plan is stale; rebuild the edit from the current topology"
        }
    }

    fun acquire(plugin: BukkitPlugin, worldId: UUID): RailWandEditPermitAcquisition {
        if (!isCurrent(world)) return stale("rail topology changed before edit permit acquisition")
        val owner = InfrastructureOwner("javarailways:railwand/${UUID.randomUUID()}", topologyRevision)
        val result = plugin.infrastructureResourceService.acquireEditPermit(
            plugin,
            worldId,
            TopologyEditRequest(topologyRevision, owner, impact)
        )
        val permit = result.permit().orElse(null)
            ?: return RailWandEditPermitAcquisition.Denied(result.decision())
        if (!isCurrent(world)) {
            permit.close()
            return stale("rail topology changed after edit permit acquisition")
        }
        return RailWandEditPermitAcquisition.Granted(permit)
    }

    private fun stale(detail: String) = RailWandEditPermitAcquisition.Denied(
        TopologyEditDecision(TopologyEditStatus.STALE_TOPOLOGY, detail = detail)
    )

    companion object {
        fun captureReplacementOrRemoval(
            world: CoreWorld,
            replacedOrRemovedRailIds: Collection<UUID>
        ): RailWandTopologyEditPlan {
            require(replacedOrRemovedRailIds.isNotEmpty()) {
                "RailWand topology edit plan must replace or remove an existing rail"
            }
            val topology = world.topologyState
            val impact = PhysicalTopologyReplacementOrRemovalImpactResolver.resolve(
                topology,
                replacedOrRemovedRailIds
            )
            val rails = impact.railIds.associateWith { railId ->
                requireNotNull(world.getRail(railId)) { "RailWand edit references missing rail $railId" }
            }
            val plan = RailWandTopologyEditPlan(world, topology, rails, impact)
            plan.requireCurrent(world)
            return plan
        }
    }
}

internal sealed interface RailWandEditPermitAcquisition {
    class Granted(
        private val permit: InfrastructureEditPermit
    ) : RailWandEditPermitAcquisition, AutoCloseable {
        override fun close() = permit.close()
    }

    data class Denied(val decision: TopologyEditDecision) : RailWandEditPermitAcquisition
}
