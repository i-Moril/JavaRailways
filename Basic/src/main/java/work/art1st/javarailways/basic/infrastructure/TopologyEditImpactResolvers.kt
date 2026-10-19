package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

/**
 * Resolves Basic-owned pre-edit identities for an exact set of rails being removed or replaced.
 *
 * This does not guess spatial candidates. The source rails are supplied by the edit itself. Their
 * frozen endpoint connections identify the additional existing rails whose connection lists will
 * change. Old node/turnout identities are collected at source endpoints; a deterministic loop
 * anchor is also collected from the source rail's own edge because replacing any rail in that loop
 * can relocate the anchor. Old diamond identities come only from crossings involving a source rail.
 * Resolution never scans the world graph.
 */
object PhysicalTopologyReplacementOrRemovalImpactResolver {
    fun resolve(topology: TopologyState, replacedOrRemovedRailIds: Collection<UUID>): TopologyEditImpact {
        val sourceRailIds = LinkedHashSet(replacedOrRemovedRailIds)
        require(sourceRailIds.isNotEmpty()) {
            "physical topology replacement or removal requires an existing source rail"
        }
        require(sourceRailIds.all(topology.railProjection::containsKey)) {
            "physical topology replacement contains a rail outside the captured topology"
        }

        val affectedRailIds = LinkedHashSet(sourceRailIds)
        val affectedNodes = linkedSetOf<TopologyNode>()
        sourceRailIds.forEach { railId ->
            topology.loopAnchorsOn(railId).forEach(affectedNodes::add)
            RailEndpoint.entries.forEach { endpoint ->
                val port = RailEndpointRef(railId, endpoint)
                val node = topology.structuralNodeAt(port)
                if (node != null) {
                    affectedNodes += node
                    when (node) {
                        is TopologyNode.Turnout -> node.legalContinuations.getValue(port)
                            .mapTo(affectedRailIds, RailEndpointRef::railId)
                        is TopologyNode.LoopAnchor -> node.ports.asSequence()
                            .filter { candidate -> candidate != port }
                            .mapTo(affectedRailIds, RailEndpointRef::railId)
                        is TopologyNode.Terminus -> Unit
                    }
                } else {
                    topology.ordinaryContinuationAt(port)?.let { continuation ->
                        affectedRailIds += continuation.railId
                    }
                }
            }
        }
        val nodeKeys = affectedNodes.mapTo(linkedSetOf()) { node -> StableTopologyNodeKey(node.ports) }
        val resources = affectedNodes.filterIsInstance<TopologyNode.Turnout>()
            .mapTo(linkedSetOf()) { turnout -> TurnoutResourceIdentity.fromPorts(turnout.ports) }
        sourceRailIds.asSequence()
            .flatMap { railId -> topology.atGradeCrossingsOn(railId).asSequence() }
            .mapTo(resources, PhysicalTopologyResources::diamondResource)

        return TopologyEditImpact(affectedRailIds, nodeKeys, resources)
    }
}

/** Maps an already finalized Core addition plan into Basic edit-coordination identities. */
object PhysicalTopologyExplicitConnectionImpactResolver {
    fun resolve(
        topology: TopologyState,
        changedExistingEndpoints: Collection<RailEndpointRef>,
        prospectiveCrossingExistingRailIds: Collection<UUID>
    ): TopologyEditImpact? {
        val endpointSet = LinkedHashSet(changedExistingEndpoints)
        val railIds = LinkedHashSet<UUID>().apply {
            endpointSet.mapTo(this, RailEndpointRef::railId)
            addAll(prospectiveCrossingExistingRailIds)
        }
        require(railIds.all(topology.railProjection::containsKey)) {
            "physical topology connection references a rail outside the captured topology"
        }
        val nodes = endpointSet.mapNotNullTo(linkedSetOf()) { endpoint ->
            topology.structuralNodeAt(endpoint)
        }
        endpointSet.asSequence()
            .map(RailEndpointRef::railId)
            .distinct()
            .flatMap(topology::loopAnchorsOn)
            .forEach(nodes::add)
        val nodeKeys = nodes.mapTo(linkedSetOf()) { node -> StableTopologyNodeKey(node.ports) }
        val resources = nodes.filterIsInstance<TopologyNode.Turnout>()
            .mapTo(linkedSetOf()) { turnout -> TurnoutResourceIdentity.fromPorts(turnout.ports) }
        if (railIds.isEmpty() && nodeKeys.isEmpty() && resources.isEmpty()) return null
        return TopologyEditImpact(railIds, nodeKeys, resources)
    }
}

/**
 * Maps one Core-validated whole-world snapshot plan to current common edit-guard identities.
 *
 * The registry validates every submitted identity against the current topology. Target-only rails,
 * nodes, turnouts and diamonds therefore cannot enter the impact: no current dependency, claim or
 * permit can hold them. A prospective crossing is guarded through each participating rail that
 * already exists; removed crossings retain their current diamond identity.
 */
object PhysicalTopologySnapshotRestoreImpactResolver {
    fun resolve(
        current: TopologyState,
        removedOrReplacedRailIds: Set<UUID>,
        changedExistingEndpoints: Set<RailEndpointRef>,
        affectedExistingRailIds: Set<UUID>,
        removedCrossings: Set<AtGradeRailCrossing>
    ): TopologyEditImpact? {
        require(affectedExistingRailIds.all(current.railProjection::containsKey)) {
            "snapshot restore impact contains a rail outside the captured topology"
        }
        require(removedCrossings.all { crossing ->
            crossing in current.atGradeCrossingsOn(crossing.first.railId)
        }) {
            "snapshot restore impact contains a crossing outside the captured topology"
        }
        val sourceImpact = removedOrReplacedRailIds.takeIf(Set<UUID>::isNotEmpty)?.let { railIds ->
            PhysicalTopologyReplacementOrRemovalImpactResolver.resolve(current, railIds)
        }
        val connectionImpact = PhysicalTopologyExplicitConnectionImpactResolver.resolve(
            current,
            changedExistingEndpoints,
            emptyList()
        )
        val railIds = linkedSetOf<UUID>().apply {
            sourceImpact?.railIds?.let(::addAll)
            connectionImpact?.railIds?.let(::addAll)
            addAll(affectedExistingRailIds)
        }
        val nodeKeys = linkedSetOf<StableTopologyNodeKey>().apply {
            sourceImpact?.topologyNodeKeys?.let(::addAll)
            connectionImpact?.topologyNodeKeys?.let(::addAll)
        }
        val resources = linkedSetOf<StableInfrastructureResource>().apply {
            sourceImpact?.resourceIds?.let(::addAll)
            connectionImpact?.resourceIds?.let(::addAll)
            removedCrossings
                .mapTo(this, PhysicalTopologyResources::diamondResource)
        }
        if (railIds.isEmpty() && nodeKeys.isEmpty() && resources.isEmpty()) return null
        return TopologyEditImpact(railIds, nodeKeys, resources)
    }
}

private fun TopologyState.loopAnchorsOn(railId: UUID): Sequence<TopologyNode.LoopAnchor> =
    railProjection.getValue(railId).asSequence().flatMap { projection ->
        val edge = edges.getValue(projection.edgeId)
        sequenceOf(edge.first.nodeId, edge.second.nodeId)
            .map(nodes::getValue)
            .filterIsInstance<TopologyNode.LoopAnchor>()
    }
