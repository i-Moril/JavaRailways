package work.art1st.javarailways.core.api.physics.rail.topology

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import kotlin.math.abs

/** One canonical endpoint of a persistent rail. */
enum class RailEndpoint {
    A,
    B;

    /** The other endpoint of the same rail. */
    fun opposite(): RailEndpoint = if (this == A) B else A
}

/** Stable rail identity plus one canonical endpoint. */
data class RailEndpointRef(
    /** Persistent UUID of the referenced physical rail. */
    val railId: UUID,
    /** Endpoint in the rail's canonical A-to-B coordinate system. */
    val endpoint: RailEndpoint
) : Comparable<RailEndpointRef> {
    /** Orders endpoints deterministically by rail UUID text and then endpoint. */
    override fun compareTo(other: RailEndpointRef): Int {
        val railComparison = railId.toString().compareTo(other.railId.toString())
        return if (railComparison != 0) railComparison else endpoint.compareTo(other.endpoint)
    }
}

/**
 * Node identity within exactly one runtime topology [revision].
 *
 * This identity is not stable across topology revisions or server restarts and must not be persisted.
 */
data class TopologyNodeId(
    /** Runtime topology revision in which this identity is valid. */
    val revision: Long,
    /** Deterministic node index within [revision]. */
    val index: Int
) {
    init {
        require(revision >= 0L) { "revision must be non-negative" }
        require(index >= 0) { "index must be non-negative" }
    }
}

/**
 * Edge identity within exactly one runtime topology [revision].
 *
 * This identity is not stable across topology revisions or server restarts and must not be persisted.
 */
data class TrackEdgeId(
    /** Runtime topology revision in which this identity is valid. */
    val revision: Long,
    /** Deterministic edge index within [revision]. */
    val index: Int
) {
    init {
        require(revision >= 0L) { "revision must be non-negative" }
        require(index >= 0) { "index must be non-negative" }
    }
}

/** A structural connectivity node in one immutable [TopologyState]. */
sealed interface TopologyNode {
    /** Revision-local identity of this node. */
    val id: TopologyNodeId

    /** Canonical physical rail endpoints incident to this node. */
    val ports: List<RailEndpointRef>

    /** End of connected track. */
    @ConsistentCopyVisibility
    data class Terminus private constructor(
        /** Revision-local identity of this terminus. */
        override val id: TopologyNodeId,
        /** The single physical rail endpoint terminating here. */
        override val ports: List<RailEndpointRef>
    ) : TopologyNode {
        init {
            require(ports.size == 1) { "a terminus must have exactly one port" }
        }

        internal companion object {
            fun create(id: TopologyNodeId, ports: Collection<RailEndpointRef>): Terminus =
                Terminus(id, immutableList(ports))
        }
    }

    /** Junction with one or more selectable physical continuations. */
    @ConsistentCopyVisibility
    data class Turnout private constructor(
        /** Revision-local identity shared by every traversal direction of this turnout. */
        override val id: TopologyNodeId,
        /** Physical rail endpoints meeting at this turnout. */
        override val ports: List<RailEndpointRef>,
        /** Legal destination ports keyed by the port from which the turnout is entered. */
        val legalContinuations: Map<RailEndpointRef, Set<RailEndpointRef>>
    ) : TopologyNode {
        internal companion object {
            fun create(
                id: TopologyNodeId,
                ports: Collection<RailEndpointRef>,
                legalContinuations: Map<RailEndpointRef, Set<RailEndpointRef>>
            ): Turnout {
                val immutablePorts = immutableList(ports)
                require(immutablePorts.size >= 2) { "a turnout must have at least two ports" }
                require(legalContinuations.keys == immutablePorts.toSet()) {
                    "turnout continuation keys must match its ports"
                }
                val immutableContinuations = immutableMap(
                    legalContinuations.mapValues { (_, targets) -> immutableSet(targets) }
                )
                return Turnout(id, immutablePorts, immutableContinuations)
            }
        }
    }

    /** Deterministic structural anchor inserted into an otherwise node-free cycle. */
    @ConsistentCopyVisibility
    data class LoopAnchor private constructor(
        /** Revision-local identity of this deterministic cycle anchor. */
        override val id: TopologyNodeId,
        /** The two ports created by cutting the otherwise node-free cycle at this anchor. */
        override val ports: List<RailEndpointRef>
    ) : TopologyNode {
        init {
            require(ports.size == 2) { "a loop anchor must have exactly two ports" }
        }

        internal companion object {
            fun create(id: TopologyNodeId, ports: Collection<RailEndpointRef>): LoopAnchor =
                LoopAnchor(id, immutableList(ports))
        }
    }
}

/** Ordered traversal of one physical rail in canonical AB metres. */
data class RailSliceRef(
    /** Persistent UUID of the physical rail containing this traversal. */
    val railId: UUID,
    /** Traversal start in metres from canonical endpoint A. */
    val startAbMeters: Double,
    /** Traversal end in metres from canonical endpoint A; may be less than [startAbMeters]. */
    val endAbMeters: Double
) {
    init {
        require(startAbMeters.isFinite() && startAbMeters >= 0.0) {
            "startAbMeters must be finite and non-negative"
        }
        require(endAbMeters.isFinite() && endAbMeters >= 0.0) {
            "endAbMeters must be finite and non-negative"
        }
        require(startAbMeters != endAbMeters) { "a rail slice must have positive length" }
    }

    /** Positive physical length of this traversal in metres. */
    val lengthMeters: Double = abs(endAbMeters - startAbMeters)
}

/** One end of a normalized track edge. */
data class TopologyEdgeEnd(
    /** Revision-local structural node reached at this edge end. */
    val nodeId: TopologyNodeId,
    /** Physical rail endpoint through which the edge meets [nodeId]. */
    val port: RailEndpointRef
)

/** Non-branching physical path between two structural nodes. */
@ConsistentCopyVisibility
data class TrackEdge private constructor(
    /** Revision-local identity of this edge. */
    val id: TrackEdgeId,
    /** First structural endpoint in the edge's canonical distance direction. */
    val first: TopologyEdgeEnd,
    /** Second structural endpoint in the edge's canonical distance direction. */
    val second: TopologyEdgeEnd,
    /** Total physical length of the edge in metres. */
    val lengthMeters: Double,
    /** Ordered rail traversals from [first] to [second]. */
    val railSlices: List<RailSliceRef>
) {
    init {
        require(lengthMeters.isFinite() && lengthMeters > 0.0) {
            "lengthMeters must be finite and positive"
        }
        require(railSlices.isNotEmpty()) { "a track edge must contain at least one rail slice" }
    }

    internal companion object {
        fun create(
            id: TrackEdgeId,
            first: TopologyEdgeEnd,
            second: TopologyEdgeEnd,
            lengthMeters: Double,
            railSlices: Collection<RailSliceRef>
        ): TrackEdge = TrackEdge(id, first, second, lengthMeters, immutableList(railSlices))
    }
}

/** Projection of one rail slice into accumulated edge distance, all in metres. */
data class TrackEdgeSlice(
    /** Revision-local edge containing the projected rail slice. */
    val edgeId: TrackEdgeId,
    /** Inclusive accumulated distance from the edge's first end, in metres. */
    val edgeStartMeters: Double,
    /** Accumulated end distance from the edge's first end, in metres. */
    val edgeEndMeters: Double,
    /** Matching slice start in metres from the physical rail's canonical endpoint A. */
    val railStartAbMeters: Double,
    /** Matching slice end in metres from the physical rail's canonical endpoint A. */
    val railEndAbMeters: Double
) {
    init {
        require(edgeStartMeters.isFinite() && edgeStartMeters >= 0.0)
        require(edgeEndMeters.isFinite() && edgeEndMeters > edgeStartMeters)
        require(railStartAbMeters.isFinite() && railStartAbMeters >= 0.0)
        require(railEndAbMeters.isFinite() && railEndAbMeters >= 0.0)
    }
}

/**
 * Immutable, value-only normalized physical connectivity for one runtime [revision].
 *
 * The state is safe to retain and read from any thread. A retained state never changes, but a
 * world may publish a newer root after a successful structural edit. IDs inside this value are
 * revision-local runtime references and are not persistence identities.
 */
@ConsistentCopyVisibility
data class TopologyState private constructor(
    /** Monotonic runtime structural revision; not a persistence schema version. */
    val revision: Long,
    /** Structural nodes keyed by identities valid only in [revision]. */
    val nodes: Map<TopologyNodeId, TopologyNode>,
    /** Normalized non-branching edges keyed by identities valid only in [revision]. */
    val edges: Map<TrackEdgeId, TrackEdge>,
    /** Reverse projection from persistent rail UUIDs to their normalized edge slices. */
    val railProjection: Map<UUID, List<TrackEdgeSlice>>
) {
    init {
        require(revision >= 0L) { "revision must be non-negative" }
        require(nodes.all { (id, node) -> id == node.id && id.revision == revision })
        require(edges.all { (id, edge) -> id == edge.id && id.revision == revision })
        require(railProjection.values.flatten().all { it.edgeId in edges })
    }

    internal companion object {
        fun create(
            revision: Long,
            nodes: Map<TopologyNodeId, TopologyNode>,
            edges: Map<TrackEdgeId, TrackEdge>,
            railProjection: Map<UUID, List<TrackEdgeSlice>>
        ): TopologyState = TopologyState(
            revision = revision,
            nodes = immutableMap(nodes),
            edges = immutableMap(edges),
            railProjection = immutableMap(
                railProjection.mapValues { (_, slices) -> immutableList(slices) }
            )
        )
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> = java.util.List.copyOf(values)

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
