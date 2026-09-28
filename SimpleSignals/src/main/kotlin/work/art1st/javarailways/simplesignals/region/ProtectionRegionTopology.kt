package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

internal data class ProtectionTopologyConnection(
    val railId: UUID,
    val enteringFacing: ViewDirection
)

internal class ProtectionTopologyRail private constructor(
    val railId: UUID,
    val lengthMeters: Double,
    val connectionsFromA: List<ProtectionTopologyConnection>,
    val connectionsFromB: List<ProtectionTopologyConnection>
) {
    init {
        require(lengthMeters.isFinite() && lengthMeters > 0.0)
    }

    companion object {
        fun create(
            railId: UUID,
            lengthMeters: Double,
            connectionsFromA: Collection<ProtectionTopologyConnection>,
            connectionsFromB: Collection<ProtectionTopologyConnection>
        ): ProtectionTopologyRail = ProtectionTopologyRail(
            railId,
            lengthMeters,
            java.util.List.copyOf(connectionsFromA),
            java.util.List.copyOf(connectionsFromB)
        )
    }
}

/**
 * Disposable protection-region index derived from, and retaining, exactly one published Core root.
 *
 * [rails] accelerates graph-cut and route-window queries. It is never authored or refreshed
 * independently: currentness is determined only by the retained [topologyState] root.
 */
internal class ProtectionRegionTopology private constructor(
    val topologyState: TopologyState,
    val rails: Map<UUID, ProtectionTopologyRail>
) {
    val topologyRevision: Long
        get() = topologyState.revision

    companion object {
        /** Retains the exact root without indexing rails when no protection region exists. */
        fun empty(topologyState: TopologyState): ProtectionRegionTopology =
            ProtectionRegionTopology(topologyState, emptyMap())

        fun derive(topologyState: TopologyState): ProtectionRegionTopology {
            val connections = topologyState.railProjection.keys.associateWithTo(linkedMapOf()) {
                linkedSetOf<RailEndpointRef>() to linkedSetOf<RailEndpointRef>()
            }
            val slicesByRail = linkedMapOf<UUID, RailSliceRef>()

            topologyState.edges.values.forEach { edge ->
                edge.railSlices.forEach { slice ->
                    check(slicesByRail.putIfAbsent(slice.railId, slice) == null) {
                        "rail ${slice.railId} appears more than once in topology edges"
                    }
                }
                edge.railSlices.zipWithNext().forEach { (current, next) ->
                    connect(connections, current.endEndpoint(), next.startEndpoint())
                }
            }

            topologyState.nodes.values.forEach { node ->
                when (node) {
                    is TopologyNode.Terminus -> Unit
                    is TopologyNode.Turnout -> node.legalContinuations.forEach { (from, targets) ->
                        targets.forEach { to -> connectOneWay(connections, from, to) }
                    }
                    is TopologyNode.LoopAnchor -> {
                        val (first, second) = node.ports
                        connect(connections, first, second)
                    }
                }
            }

            check(slicesByRail.keys == topologyState.railProjection.keys) {
                "topology rail projection and edge slices disagree"
            }
            val rails = slicesByRail.mapValuesTo(linkedMapOf()) { (railId, slice) ->
                val lengthMeters = max(slice.startAbMeters, slice.endAbMeters)
                check(min(slice.startAbMeters, slice.endAbMeters) == 0.0) {
                    "rail $railId topology slice does not cover canonical endpoint A"
                }
                val (fromA, fromB) = connections.getValue(railId)
                ProtectionTopologyRail.create(
                    railId = railId,
                    lengthMeters = lengthMeters,
                    connectionsFromA = fromA.map(::toConnection).sortedWith(connectionOrder),
                    connectionsFromB = fromB.map(::toConnection).sortedWith(connectionOrder)
                )
            }
            return ProtectionRegionTopology(topologyState, java.util.Map.copyOf(rails))
        }

        private fun connect(
            connections: Map<UUID, Pair<MutableSet<RailEndpointRef>, MutableSet<RailEndpointRef>>>,
            first: RailEndpointRef,
            second: RailEndpointRef
        ) {
            connectOneWay(connections, first, second)
            connectOneWay(connections, second, first)
        }

        private fun connectOneWay(
            connections: Map<UUID, Pair<MutableSet<RailEndpointRef>, MutableSet<RailEndpointRef>>>,
            from: RailEndpointRef,
            to: RailEndpointRef
        ) {
            val endpoints = connections.getValue(from.railId)
            when (from.endpoint) {
                RailEndpoint.A -> endpoints.first += to
                RailEndpoint.B -> endpoints.second += to
            }
        }

        private fun RailSliceRef.startEndpoint(): RailEndpointRef = RailEndpointRef(
            railId,
            if (startAbMeters < endAbMeters) RailEndpoint.A else RailEndpoint.B
        )

        private fun RailSliceRef.endEndpoint(): RailEndpointRef {
            val start = startEndpoint()
            return start.copy(endpoint = start.endpoint.opposite())
        }

        private fun toConnection(endpoint: RailEndpointRef): ProtectionTopologyConnection =
            ProtectionTopologyConnection(
                railId = endpoint.railId,
                enteringFacing = when (endpoint.endpoint) {
                    RailEndpoint.A -> ViewDirection.AB
                    RailEndpoint.B -> ViewDirection.BA
                }
            )

        private val connectionOrder = compareBy<ProtectionTopologyConnection>(
            { it.railId.toString() },
            ProtectionTopologyConnection::enteringFacing
        )
    }
}
