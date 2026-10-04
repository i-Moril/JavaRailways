package work.art1st.javarailways.basic.api.route.planning

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.Collections
import java.util.UUID
import kotlin.math.abs

/** Immutable sparse rail-direction snapshot bound to one topology revision. */
class OperatingDirectionOverlay(
    val topologyRevision: Long,
    allowedDirections: Map<UUID, ViewDirection>
) {
    val allowedDirections: Map<UUID, ViewDirection> =
        Collections.unmodifiableMap(LinkedHashMap(allowedDirections))

    init {
        require(topologyRevision >= 0L) { "topology revision must be non-negative" }
    }

    fun toRestrictions(topology: TopologyState): RoutePlanningRestrictions? {
        if (topology.revision != topologyRevision) return null
        val forbidden = ArrayList<DirectedRailSpan>(allowedDirections.size)
        for ((railId, permitted) in allowedDirections.entries.sortedBy { it.key }) {
            val projection = topology.railProjection[railId]?.singleOrNull() ?: return null
            val railLength = abs(projection.railEndAbMeters - projection.railStartAbMeters)
            forbidden += DirectedRailSpan(
                railId = railId,
                facing = when (permitted) {
                    ViewDirection.AB -> ViewDirection.BA
                    ViewDirection.BA -> ViewDirection.AB
                },
                lowerAbMeters = 0.0,
                upperAbMeters = railLength
            )
        }
        return RoutePlanningRestrictions(topology.revision, forbiddenTraversals = forbidden)
    }
}
