package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

/**
 * Host-internal compiler for the existing infrastructure guarded by one topology edit.
 *
 * This is public only so the Bukkit composition module can use the Basic implementation. It is not
 * part of `basic.api` and is not an add-on contract.
 */
object InfrastructureEditFootprintCompiler {
    /**
     * Expands existing rail identities into full canonical rail intervals and every current turnout
     * or diamond conflict incident to those rails. Returns `null` when no existing rail is affected.
     */
    @JvmStatic
    fun compile(
        topology: TopologyState,
        currentRailLengthsMeters: Map<UUID, Double>,
        affectedExistingRailIds: Set<UUID>
    ): InfrastructureFootprint? {
        if (affectedExistingRailIds.isEmpty()) return null

        val orderedRailIds = affectedExistingRailIds.sortedBy(UUID::toString)
        val resources = ArrayList<InfrastructureResource>(orderedRailIds.size)
        orderedRailIds.forEach { railId ->
            require(railId in topology.railProjection) {
                "affected rail $railId is absent from topology revision ${topology.revision}"
            }
            val lengthMeters = requireNotNull(currentRailLengthsMeters[railId]) {
                "affected rail $railId has no current length"
            }
            require(lengthMeters.isFinite() && lengthMeters > 0.0) {
                "affected rail $railId must have a finite positive length"
            }
            resources += RailIntervalResource(railId, 0.0, lengthMeters)
        }

        PhysicalTopologyResources.incidentResources(topology, affectedExistingRailIds)
            .sortedWith(compareBy(StableInfrastructureResource::typeId, StableInfrastructureResource::resourceId))
            .forEach(resources::add)
        return InfrastructureFootprint(resources)
    }
}
