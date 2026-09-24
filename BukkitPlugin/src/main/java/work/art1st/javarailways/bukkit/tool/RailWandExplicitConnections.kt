package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.PreparedRailConnectionPlan
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationBatch
import work.art1st.javarailways.core.physics.rail.PreparedRailEndpointConnection
import work.art1st.javarailways.core.physics.rail.RailView
import java.util.ArrayDeque

/** Builds exact endpoint states from caller-supplied replacements and connections; never searches geometry. */
internal object RailWandExplicitConnections {
    fun capture(
        world: CoreWorld,
        preparedBatch: PreparedRailCreationBatch,
        replacementEndpoints: Map<RailEndpointRef, RailEndpointRef> = emptyMap(),
        addedConnections: Collection<Pair<RailEndpointRef, RailEndpointRef>> = emptyList()
    ): PreparedRailConnectionPlan {
        require(replacementEndpoints.keys.all { it.railId in preparedBatch.excludedExistingRailIds })
        val createdIds = preparedBatch.creations.mapTo(hashSetOf()) { it.railId }
        require(replacementEndpoints.values.all { it.railId in createdIds })
        require(replacementEndpoints.values.toSet().size == replacementEndpoints.size) {
            "Each replacement endpoint must map to a different prepared endpoint"
        }
        preparedBatch.excludedExistingRailIds.forEach { railId ->
            val rail = requireNotNull(world.getRail(railId)) { "Missing replacement source rail $railId" }
            RailEndpoint.entries.forEach { endpoint ->
                val source = RailEndpointRef(railId, endpoint)
                val hasSurvivingConnection = endpointView(rail, endpoint).mutableNextRails
                    .map(::endpointOfAwayView)
                    .any { it.railId !in preparedBatch.excludedExistingRailIds }
                require(!hasSurvivingConnection || source in replacementEndpoints) {
                    "Replacement endpoint with an external connection must be mapped: $source"
                }
            }
        }
        val states = linkedMapOf<RailEndpointRef, MutableEndpointState>()

        fun translate(endpoint: RailEndpointRef): RailEndpointRef = replacementEndpoints[endpoint] ?: endpoint
        fun loadCurrent(endpoint: RailEndpointRef): MutableEndpointState {
            states[endpoint]?.let { return it }
            if (endpoint.railId in createdIds) return MutableEndpointState().also { states[endpoint] = it }
            require(endpoint.railId !in preparedBatch.excludedExistingRailIds) {
                "Removed endpoint must be mapped before it can be connected: $endpoint"
            }
            val rail = requireNotNull(world.getRail(endpoint.railId)) { "Missing explicit connection rail ${endpoint.railId}" }
            val view = endpointView(rail, endpoint.endpoint)
            val connected = view.mutableNextRails.map(::endpointOfAwayView)
                .filterNot { it.railId in preparedBatch.excludedExistingRailIds && it !in replacementEndpoints }
                .map(::translate)
                .toMutableList()
            val active = view.activeRail?.let(::endpointOfAwayView)?.let(::translate)?.takeIf(connected::contains)
            return MutableEndpointState(connected, active).also { states[endpoint] = it }
        }

        replacementEndpoints.forEach { (oldEndpoint, newEndpoint) ->
            val oldRail = requireNotNull(world.getRail(oldEndpoint.railId))
            val oldView = endpointView(oldRail, oldEndpoint.endpoint)
            val connected = oldView.mutableNextRails.map(::endpointOfAwayView).map(::translate).toMutableList()
            val active = oldView.activeRail?.let(::endpointOfAwayView)?.let(::translate)?.takeIf(connected::contains)
            states[newEndpoint] = MutableEndpointState(connected, active)
        }

        addedConnections.forEach { (first, second) ->
            require(first != second)
            val firstState = loadCurrent(translate(first))
            val secondState = loadCurrent(translate(second))
            if (translate(second) !in firstState.connected) firstState.connected += translate(second)
            if (translate(first) !in secondState.connected) secondState.connected += translate(first)
            if (firstState.active == null) firstState.active = translate(second)
            if (secondState.active == null) secondState.active = translate(first)
        }

        // Include unchanged reciprocal endpoints so Core can validate the complete explicit component.
        val queue = ArrayDeque(states.keys)
        while (queue.isNotEmpty()) {
            val endpoint = queue.removeFirst()
            states.getValue(endpoint).connected.toList().forEach { connected ->
                if (connected !in states) {
                    loadCurrent(connected)
                    queue += connected
                }
            }
        }

        val finalConnections = states.map { (endpoint, state) ->
            if (state.connected.distinct().size > 2) {
                throw RailWandLocalizedException("failure.too_many_endpoints")
            }
            PreparedRailEndpointConnection(endpoint, state.connected.distinct(), state.active)
        }
        return PreparedRailConnectionPlan.capture(world, preparedBatch, finalConnections)
    }

    private data class MutableEndpointState(
        val connected: MutableList<RailEndpointRef> = mutableListOf(),
        var active: RailEndpointRef? = null
    )
}

private fun endpointView(rail: CoreRail, endpoint: RailEndpoint): RailView =
    if (endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB

private fun endpointOfAwayView(view: RailView): RailEndpointRef = RailEndpointRef(
    view.rail.id,
    if (view.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
)
