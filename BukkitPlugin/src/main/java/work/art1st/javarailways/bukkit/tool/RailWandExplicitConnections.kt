package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.PreparedRailConnectionPlan
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationBatch

/** Builds exact endpoint states from caller-supplied replacements and connections; never searches geometry. */
internal object RailWandExplicitConnections {
    fun capture(
        world: CoreWorld,
        preparedBatch: PreparedRailCreationBatch,
        replacementEndpoints: Map<RailEndpointRef, RailEndpointRef> = emptyMap(),
        addedConnections: Collection<Pair<RailEndpointRef, RailEndpointRef>> = emptyList()
    ): PreparedRailConnectionPlan = PreparedRailConnectionPlan.captureExplicit(
        world,
        preparedBatch,
        replacementEndpoints,
        addedConnections
    )
}
