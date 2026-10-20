package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

internal enum class TurnoutWandTargetValidity { CURRENT, CANCELLED, STALE }

/** Validate the item and topology captured by the targeting callback, before it can switch anything. */
internal fun turnoutTargetValidity(
    state: TurnoutWandState,
    sequence: Long,
    expectedWorldId: UUID,
    expectedTopology: TopologyState,
    activeState: TurnoutWandState?,
    worldId: UUID,
    topology: TopologyState?,
    targetRailId: UUID? = null
): TurnoutWandTargetValidity = when {
    activeState !== state || state.querySequence != sequence -> TurnoutWandTargetValidity.CANCELLED
    worldId != expectedWorldId || topology == null ||
        (targetRailId != null && !topology.preservesRails(expectedTopology, setOf(targetRailId))) -> TurnoutWandTargetValidity.STALE
    else -> TurnoutWandTargetValidity.CURRENT
}

/** Runtime selection: a reloaded world with the same UUID/revision must not revive this binding. */
internal data class TurnoutWandSelection(
    val worldId: UUID,
    val topology: TopologyState,
    val fromPort: RailEndpointRef
) {
    fun isCurrent(worldId: UUID, topology: TopologyState): Boolean =
        this.worldId == worldId && topology.preservesRails(this.topology, setOf(fromPort.railId))

    fun request(worldId: UUID, topology: TopologyState, toPort: RailEndpointRef): ManualTurnoutSelectionRequest? {
        if (!isCurrent(worldId, topology)) return null
        val turnout = topology.structuralNodeAt(fromPort) as? TopologyNode.Turnout ?: return null
        if (toPort !in turnout.legalContinuations.getValue(fromPort)) return null
        return ManualTurnoutSelectionRequest(topology.revision, fromPort, toPort)
    }
}

/** Select only the nearer end of the hit rail; proximity to an unrelated rail never creates a port. */
internal fun turnoutPortAt(
    topology: TopologyState,
    railId: UUID,
    arcLength: Double,
    length: Double
): RailEndpointRef? {
    if (!length.isFinite() || length <= 0.0 || !arcLength.isFinite() || arcLength !in 0.0..length) return null
    val port = RailEndpointRef(railId, if (arcLength <= length / 2.0) RailEndpoint.A else RailEndpoint.B)
    return port.takeIf { topology.structuralNodeAt(it) is TopologyNode.Turnout }
}

internal data class TurnoutWandConnection(val toPort: RailEndpointRef, val reciprocal: Boolean)

/** Read on the simulation owner after selection completes, rather than predicting the request's result. */
internal fun currentTurnoutConnection(world: World, selection: TurnoutWandSelection): TurnoutWandConnection? {
    if (!selection.isCurrent(selection.worldId, world.topologyState)) return null
    fun continuation(port: RailEndpointRef): RailEndpointRef? {
        val arriving = world.getRail(port.railId)?.getView(
            if (port.endpoint == RailEndpoint.A) ViewDirection.BA else ViewDirection.AB
        ) ?: return null
        val next = arriving.nextActiveRail ?: return null
        return RailEndpointRef(next.rail.id, if (next.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B)
    }
    val to = continuation(selection.fromPort) ?: return null
    if (selection.request(selection.worldId, world.topologyState, to) == null) return null
    return TurnoutWandConnection(to, continuation(to) == selection.fromPort)
}
