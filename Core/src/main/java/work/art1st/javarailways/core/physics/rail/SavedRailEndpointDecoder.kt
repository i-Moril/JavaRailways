package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import java.util.UUID

/** One exact, ordered endpoint state decoded from a saved rail snapshot. */
internal data class SnapshotEndpointState(
    val connectedEndpoints: List<RailEndpointRef>,
    val activeEndpoint: RailEndpointRef?
)

/** Resolves declared rail IDs against their geometry without inventing connections or selections. */
internal fun decodeSavedRailEndpoint(
    source: RailEndpointRef,
    nextRailIds: List<String>,
    activeRailId: String?,
    curveById: (UUID) -> PrimeCurve?
): SnapshotEndpointState {
    val sourceCurve = requireNotNull(curveById(source.railId)) { "snapshot source rail is missing: $source" }
    val sourcePosition = sourceCurve.getPointAt(
        if (source.endpoint == RailEndpoint.A) 0.0 else sourceCurve.totalArcLength
    ).position
    val targets = nextRailIds.map { text ->
        val targetId = parseRailId(text, source, "connection")
        val targetCurve = requireNotNull(curveById(targetId)) {
            "snapshot connection at $source references missing rail $targetId"
        }
        val distanceA = sourcePosition.distance(targetCurve.getPointAt(0.0).position)
        val distanceB = sourcePosition.distance(targetCurve.getPointAt(targetCurve.totalArcLength).position)
        require(minOf(distanceA, distanceB) <= Curve.TOLERANCE) {
            "snapshot connection endpoints are not colocated: $source -> $targetId"
        }
        RailEndpointRef(targetId, if (distanceA < distanceB) RailEndpoint.A else RailEndpoint.B)
    }
    require(targets.distinct().size == targets.size && targets.size <= 2) {
        "snapshot connections at $source must be unique and contain at most two continuations"
    }
    val active = activeRailId?.let { text ->
        val activeId = parseRailId(text, source, "active")
        requireNotNull(targets.singleOrNull { it.railId == activeId }) {
            "snapshot active rail $activeId is not connected at $source"
        }
    }
    return SnapshotEndpointState(targets, active)
}

private fun parseRailId(text: String, source: RailEndpointRef, role: String): UUID =
    try {
        UUID.fromString(text)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("snapshot $role rail ID '$text' is invalid at $source", failure)
    }
