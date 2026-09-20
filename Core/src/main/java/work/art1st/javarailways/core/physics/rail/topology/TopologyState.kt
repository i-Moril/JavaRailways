package work.art1st.javarailways.core.physics.rail.topology

import java.util.UUID

internal enum class RailEndpoint {
    A,
    B;

    fun opposite(): RailEndpoint = if (this == A) B else A
}

internal data class RailEndpointRef(
    val railId: UUID,
    val endpoint: RailEndpoint
) : Comparable<RailEndpointRef> {
    override fun compareTo(other: RailEndpointRef): Int {
        val railComparison = railId.toString().compareTo(other.railId.toString())
        return if (railComparison != 0) railComparison else endpoint.compareTo(other.endpoint)
    }
}

internal data class TopologyNodeId(val revision: Long, val index: Int)
internal data class TrackEdgeId(val revision: Long, val index: Int)

internal sealed interface TopologyNode {
    val id: TopologyNodeId
    val ports: List<RailEndpointRef>

    data class Terminus(
        override val id: TopologyNodeId,
        override val ports: List<RailEndpointRef>
    ) : TopologyNode

    data class Turnout(
        override val id: TopologyNodeId,
        override val ports: List<RailEndpointRef>,
        val legalContinuations: Map<RailEndpointRef, Set<RailEndpointRef>>
    ) : TopologyNode

    data class LoopAnchor(
        override val id: TopologyNodeId,
        override val ports: List<RailEndpointRef>
    ) : TopologyNode
}

internal data class RailSliceRef(
    val railId: UUID,
    val startAbMeters: Double,
    val endAbMeters: Double
) {
    val lengthMeters: Double = kotlin.math.abs(endAbMeters - startAbMeters)
}

internal data class TopologyEdgeEnd(
    val nodeId: TopologyNodeId,
    val port: RailEndpointRef
)

internal data class TrackEdge(
    val id: TrackEdgeId,
    val first: TopologyEdgeEnd,
    val second: TopologyEdgeEnd,
    val lengthMeters: Double,
    val railSlices: List<RailSliceRef>
)

internal data class TrackEdgeSlice(
    val edgeId: TrackEdgeId,
    val edgeStartMeters: Double,
    val edgeEndMeters: Double,
    val railStartAbMeters: Double,
    val railEndAbMeters: Double
)

/** Immutable, revision-scoped normalized connectivity. IDs are not persistent infrastructure IDs. */
internal data class TopologyState(
    val revision: Long,
    val nodes: Map<TopologyNodeId, TopologyNode>,
    val edges: Map<TrackEdgeId, TrackEdge>,
    val railProjection: Map<UUID, List<TrackEdgeSlice>>
)

internal data class TopologyInputRail(
    val id: UUID,
    val lengthMeters: Double
)

/** Pure input captured from live Core rails before normalization. */
internal data class TopologyInput(
    val revision: Long,
    val rails: List<TopologyInputRail>,
    val continuations: Map<RailEndpointRef, Set<RailEndpointRef>>
)
