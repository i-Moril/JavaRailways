package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import java.util.UUID

/** One exact, ordered endpoint state decoded from a saved rail snapshot. */
internal data class SnapshotEndpointState(
    val connectedEndpoints: List<RailEndpointRef>,
    val activeEndpoint: RailEndpointRef?,
    val joinedEndpoints: Set<RailEndpointRef>
)

/** Validates declared endpoint identities; geometry never substitutes another endpoint. */
internal fun decodeSavedRailEndpoint(
    source: RailEndpointRef,
    connections: List<SavedRailEndpointRef>,
    activeConnection: SavedRailEndpointRef?,
    curveById: (UUID) -> PrimeCurve?,
    joinedEndpoints: List<SavedRailEndpointRef>
): SnapshotEndpointState {
    val sourceCurve = requireNotNull(curveById(source.railId)) { "snapshot source rail is missing: $source" }
    val sourcePosition = sourceCurve.getPointAt(
        if (source.endpoint == RailEndpoint.A) 0.0 else sourceCurve.totalArcLength
    ).position
    fun decode(value: SavedRailEndpointRef, role: String): RailEndpointRef =
        RailEndpointRef(parseRailId(value.railId, source, role), value.endpoint)
    val targets = connections.map { value ->
        val target = decode(value, "connection")
        require(target != source) { "snapshot connection cannot return to the same endpoint: $source" }
        val targetCurve = requireNotNull(curveById(target.railId)) {
            "snapshot connection at $source references missing rail ${target.railId}"
        }
        val position = targetCurve.getPointAt(
            if (target.endpoint == RailEndpoint.A) 0.0 else targetCurve.totalArcLength
        ).position
        require(sourcePosition.distance(position) <= Curve.TOLERANCE) {
            "snapshot connection endpoints are not colocated: $source -> $target"
        }
        target
    }
    require(targets.distinct().size == targets.size && targets.size <= 2) {
        "snapshot connections at $source must be unique and contain at most two continuations"
    }
    val active = activeConnection?.let { value ->
        val active = decode(value, "active")
        require(active in targets) { "snapshot active endpoint $active is not connected at $source" }
        active
    }
    val joined = joinedEndpoints.map { decode(it, "joined endpoint") }
    require(joined.distinct().size == joined.size && source !in joined) {
        "snapshot joined endpoints at $source must be unique peers"
    }
    val legal = RailEndpointGeometry.legalContinuations(listOf(source) + joined) { railId ->
        requireNotNull(curveById(railId)) { "snapshot joined endpoint at $source references missing rail $railId" }
    }
    require(targets.toSet() == legal.getValue(source)) {
        "snapshot connections do not match endpoint group geometry at $source: joined peers=$joined"
    }
    return SnapshotEndpointState(targets, active, joined.toSet())
}

private fun parseRailId(text: String, source: RailEndpointRef, role: String): UUID =
    try {
        UUID.fromString(text)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("snapshot $role rail ID '$text' is invalid at $source", failure)
    }

/** Complete graph validation shared by ordinary load and prepared snapshot restoration. */
internal fun decodeSavedRailGraph(
    savedById: Map<UUID, CoreRail.SavedRailV1>,
    curvesById: Map<UUID, PrimeCurve>
): Map<RailEndpointRef, SnapshotEndpointState> {
    val states = linkedMapOf<RailEndpointRef, SnapshotEndpointState>()
    savedById.forEach { (railId, saved) ->
        RailEndpoint.entries.forEach { endpoint ->
            val source = RailEndpointRef(railId, endpoint)
            states[source] = decodeSavedRailEndpoint(
                source,
                if (endpoint == RailEndpoint.A) saved.connectionsAtA else saved.connectionsAtB,
                if (endpoint == RailEndpoint.A) saved.activeConnectionAtA else saved.activeConnectionAtB,
                curvesById::get,
                if (endpoint == RailEndpoint.A) saved.joinedEndpointsAtA else saved.joinedEndpointsAtB
            )
        }
    }
    states.forEach { (source, state) ->
        val group = state.joinedEndpoints + source
        state.joinedEndpoints.forEach { peer ->
            require(states.getValue(peer).joinedEndpoints + peer == group) {
                "snapshot endpoint group is not complete and symmetric: $source <-> $peer"
            }
        }
        state.connectedEndpoints.forEach { target ->
            require(source in states.getValue(target).connectedEndpoints) {
                "snapshot connection is not symmetric: $source <-> $target"
            }
        }
    }
    return java.util.Collections.unmodifiableMap(states)
}
