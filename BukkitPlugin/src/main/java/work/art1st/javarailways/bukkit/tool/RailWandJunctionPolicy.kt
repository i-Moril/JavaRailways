package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailEndpointGeometry

/** The exact authored endpoints affected by moving the selected physical endpoint. */
internal fun railWandEndpointEditTargets(
    world: CoreWorld,
    selected: RailEndpointRef
): List<Pair<CoreRail, Char>> = world.topologyState.endpointGroupAt(selected).sorted().map { endpoint ->
    requireNotNull(world.getRail(endpoint.railId)) { "Missing authored junction rail ${endpoint.railId}" } to
        if (endpoint.endpoint == RailEndpoint.A) 'A' else 'B'
}

/** A pair of same-side legs is an authored junction even though neither has a legal exit. */
internal fun railWandJunctionLabelKey(topology: TopologyState, endpoint: RailEndpointRef): String? {
    val node = topology.structuralNodeAt(endpoint) as? TopologyNode.Turnout ?: return null
    return if (node.ports.any { node.legalContinuations.getValue(it).isEmpty() }) {
        "display.junction_incomplete"
    } else "display.junction"
}

/** Only a real through path may participate in automatic adjacent-track grade smoothing. */
internal fun canSmoothRailWandJunction(
    adjacent: PrimeCurve,
    created: PrimeCurve,
    adjacentAtStart: Boolean
): Boolean = if (adjacentAtStart) {
    RailEndpointGeometry.canContinue(
        adjacent.getPointAt(adjacent.totalArcLength), RailEndpoint.B,
        created.getPointAt(0.0), RailEndpoint.A
    )
} else {
    RailEndpointGeometry.canContinue(
        created.getPointAt(created.totalArcLength), RailEndpoint.B,
        adjacent.getPointAt(0.0), RailEndpoint.A
    )
}
