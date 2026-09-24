package work.art1st.javarailways.bukkit.tool

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureEditRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.infrastructure.InfrastructureEditFootprintCompiler
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureEditPermitResult
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureLeaseService
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.UUID

/** Bukkit composition boundary that holds one edit permit across a complete RailWand commit. */
internal class RailInfrastructureEditGuard internal constructor(
    private val acquirePermit: (UUID, InfrastructureEditRequest) -> InfrastructureEditPermitResult
) {
    constructor(plugin: Plugin, leases: InfrastructureLeaseService) : this(
        { worldId, request -> leases.acquireEditPermit(plugin, worldId, request) }
    )

    sealed interface Result {
        data class Granted(val permit: AutoCloseable) : Result
        data class Denied(val reason: InfrastructureMutationResult) : Result
    }

    fun acquire(
        worldId: UUID,
        world: CoreWorld,
        affectedExistingRailIds: Set<UUID>,
        ownerId: String
    ): Result {
        val topology = world.topologyState
        if (!topology.railProjection.keys.containsAll(affectedExistingRailIds)) {
            return staleTopology("an affected rail disappeared before the edit permit was acquired")
        }
        val affectedWithConnectionNeighbors = connectionNeighborhood(world, affectedExistingRailIds)
        if (world.topologyState !== topology) {
            return staleTopology("rail topology changed while the edit footprint was being collected")
        }
        val currentRailLengths = LinkedHashMap<UUID, Double>(affectedWithConnectionNeighbors.size)
        affectedWithConnectionNeighbors.forEach { railId ->
            val rail = world.getRail(railId)
                ?: return staleTopology("an affected rail disappeared while the edit footprint was being collected")
            currentRailLengths[railId] = rail.curve.totalArcLength
        }
        val footprint = InfrastructureEditFootprintCompiler.compile(
            topology,
            currentRailLengths,
            affectedWithConnectionNeighbors
        ) ?: return Result.Granted(AutoCloseable {})
        if (world.topologyState !== topology) {
            return staleTopology("rail topology changed before the edit permit was acquired")
        }

        val decision = acquirePermit(
            worldId,
            InfrastructureEditRequest(
                topology.revision,
                InfrastructureOwner(ownerId, topology.revision),
                footprint
            )
        )
        val permit = decision.permit().orElse(null)
            ?: return Result.Denied(decision.result())
        if (world.topologyState !== topology) {
            permit.close()
            return Result.Denied(InfrastructureMutationResult(
                InfrastructureMutationStatus.STALE_TOPOLOGY,
                detail = "rail topology changed after the edit permit was acquired"
            ))
        }
        return Result.Granted(permit)
    }

    private fun staleTopology(detail: String): Result.Denied = Result.Denied(
        InfrastructureMutationResult(InfrastructureMutationStatus.STALE_TOPOLOGY, detail = detail)
    )

    private fun connectionNeighborhood(world: CoreWorld, seeds: Set<UUID>): Set<UUID> {
        if (seeds.isEmpty()) return emptySet()
        val rails = world.allRails.toList()
        return buildSet {
            addAll(seeds)
            rails.forEach { rail ->
                val connectedIds = sequenceOf(rail.viewAB, rail.viewBA)
                    .flatMap { view -> view.nextRails.asSequence() }
                    .map { next -> next.rail.id }
                    .toSet()
                if (rail.id in seeds || connectedIds.any(seeds::contains)) {
                    add(rail.id)
                    addAll(connectedIds)
                }
            }
        }
    }
}
