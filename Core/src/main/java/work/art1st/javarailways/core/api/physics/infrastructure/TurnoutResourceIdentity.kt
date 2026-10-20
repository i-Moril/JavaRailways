package work.art1st.javarailways.core.api.physics.infrastructure

import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef

/** Canonical resource identity shared by every traversal direction of one physical turnout. */
object TurnoutResourceIdentity {
    const val TYPE_ID: String = "javarailways:turnout"

    fun fromPorts(ports: Collection<RailEndpointRef>): StableInfrastructureResource {
        require(ports.distinct().size == ports.size) { "a turnout resource cannot contain duplicate ports" }
        val canonicalPorts = ports.sorted()
        require(canonicalPorts.size >= 2) { "a turnout resource must contain at least two ports" }
        return StableInfrastructureResource(
            typeId = TYPE_ID,
            resourceId = canonicalPorts.joinToString("|") { port ->
                "${port.railId}@${port.endpoint.name}"
            }
        )
    }
}
