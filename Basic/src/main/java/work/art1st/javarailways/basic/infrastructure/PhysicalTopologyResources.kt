package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

/** Converts one immutable Core topology root into Basic-owned physical resource identities. */
internal object PhysicalTopologyResources {
    const val DIAMOND_TYPE_ID = "javarailways:diamond"
    internal const val DIAMOND_ANCHOR_HALF_LENGTH_METERS = 5.0e-3

    fun routeBoundBindings(topology: TopologyState): List<RouteBoundResourceBinding> =
        topology.atGradeCrossings.map { crossing ->
            RouteBoundResourceBinding(
                resource = diamondResource(crossing),
                anchors = listOf(
                    anchor(topology, crossing.first),
                    anchor(topology, crossing.second)
                )
            )
        }

    fun controlledResources(
        topology: TopologyState,
        routeBoundBindings: List<RouteBoundResourceBinding>
    ): Set<StableInfrastructureResource> = buildSet {
        topology.nodes.values.filterIsInstance<TopologyNode.Turnout>()
            .mapTo(this) { turnout -> TurnoutResourceIdentity.fromPorts(turnout.ports) }
        routeBoundBindings.mapTo(this, RouteBoundResourceBinding::resource)
    }

    fun diamondResource(crossing: AtGradeRailCrossing): StableInfrastructureResource =
        StableInfrastructureResource(
            DIAMOND_TYPE_ID,
            buildString {
                append(crossing.first.railId)
                append('/')
                append(crossing.second.railId)
                append('/')
                append(unsignedBits(crossing.first.arcLengthAbMeters))
                append('-')
                append(unsignedBits(crossing.second.arcLengthAbMeters))
            }
        )

    private fun anchor(topology: TopologyState, point: RailCrossingPoint): RailSliceRef {
        val projection = topology.railProjection[point.railId]?.singleOrNull()
            ?: error("crossing rail ${point.railId} must have one topology projection")
        val minimum = minOf(projection.railStartAbMeters, projection.railEndAbMeters)
        val maximum = maxOf(projection.railStartAbMeters, projection.railEndAbMeters)
        val crossingArc = point.arcLengthAbMeters.coerceIn(minimum, maximum)
        val start = maxOf(minimum, crossingArc - DIAMOND_ANCHOR_HALF_LENGTH_METERS)
        val end = minOf(maximum, crossingArc + DIAMOND_ANCHOR_HALF_LENGTH_METERS)
        require(start < end) { "crossing rail ${point.railId} has no positive-length anchor" }
        return RailSliceRef(point.railId, start, end)
    }

    private fun unsignedBits(value: Double): String =
        java.lang.Long.toUnsignedString(java.lang.Double.doubleToLongBits(value), 16)
}
