package work.art1st.javarailways.core.physics.rail.topology

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyEdgeEnd
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNodeId
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdge
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeId
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeSlice
import work.art1st.javarailways.core.physics.rail.AT_GRADE_CROSSING_TOLERANCE_METERS
import java.util.UUID

/** Pure normalizer. It never reads live rails or runtime turnout selection. */
internal object TopologyStateBuilder {
    fun build(input: TopologyInput): TopologyState {
        require(input.revision >= 0L) { "topology revision must be non-negative" }
        val rails = input.rails.associateByUniqueId()
        rails.values.forEach { rail ->
            require(rail.lengthMeters.isFinite() && rail.lengthMeters > 0.0) {
                "rail ${rail.id} length must be finite and positive"
            }
        }

        val endpoints = rails.keys.flatMapTo(linkedSetOf()) { railId ->
            RailEndpoint.entries.map { endpoint -> RailEndpointRef(railId, endpoint) }
        }
        val continuations = validateContinuations(endpoints, input.continuations)
        val endpointGroups = buildEndpointGroups(endpoints, continuations)
        val initialKinds = endpointGroups.mapValues { (_, ports) -> classify(ports, continuations) }
        val nodeKinds = addLoopAnchors(rails.keys, endpointGroups, initialKinds)

        val nodeGroups = nodeKinds.filterValues { it != NodeKind.ORDINARY }.keys.sortedBy(::groupKey)
        val nodeIdByGroup = nodeGroups.mapIndexed { index, group ->
            group to TopologyNodeId(input.revision, index)
        }.toMap()
        val nodes = nodeGroups.associate { group ->
            val id = nodeIdByGroup.getValue(group)
            val ports = endpointGroups.getValue(group).sorted()
            val node = when (nodeKinds.getValue(group)) {
                NodeKind.TERMINUS -> TopologyNode.Terminus.create(id, ports)
                NodeKind.TURNOUT -> TopologyNode.Turnout.create(
                    id = id,
                    ports = ports,
                    legalContinuations = ports.associateWith { continuations.getValue(it) }
                )
                NodeKind.LOOP_ANCHOR -> TopologyNode.LoopAnchor.create(id, ports)
                NodeKind.ORDINARY -> error("ordinary group cannot own a node")
            }
            id to node
        }

        val rawEdges = walkEdges(
            rails = rails,
            continuations = continuations,
            endpointGroups = endpointGroups,
            nodeIdByGroup = nodeIdByGroup
        )
        val edges = linkedMapOf<TrackEdgeId, TrackEdge>()
        val projection = rails.keys.sorted().associateWithTo(linkedMapOf()) { mutableListOf<TrackEdgeSlice>() }
        rawEdges.forEachIndexed { index, raw ->
            val edgeId = TrackEdgeId(input.revision, index)
            var offset = 0.0
            raw.slices.forEach { slice ->
                val endOffset = offset + slice.lengthMeters
                projection.getValue(slice.railId).add(
                    TrackEdgeSlice(
                        edgeId = edgeId,
                        edgeStartMeters = offset,
                        edgeEndMeters = endOffset,
                        railStartAbMeters = slice.startAbMeters,
                        railEndAbMeters = slice.endAbMeters
                    )
                )
                offset = endOffset
            }
            edges[edgeId] = TrackEdge.create(
                id = edgeId,
                first = TopologyEdgeEnd(nodeIdByGroup.getValue(raw.firstGroup), raw.firstPort),
                second = TopologyEdgeEnd(nodeIdByGroup.getValue(raw.secondGroup), raw.secondPort),
                lengthMeters = offset,
                railSlices = raw.slices
            )
        }

        check(projection.values.all { it.size == 1 }) { "every physical rail must project to exactly one edge slice" }
        return TopologyState.create(
            revision = input.revision,
            nodes = nodes,
            edges = edges,
            railProjection = projection,
            railIdentities = input.rails.associate { it.id to it.identity },
            atGradeCrossings = validateCrossings(
                input.atGradeCrossings,
                rails,
                endpointGroups
            )
        )
    }

    private fun validateCrossings(
        crossings: List<AtGradeRailCrossing>,
        rails: Map<UUID, TopologyInputRail>,
        endpointGroups: Map<RailEndpointRef, List<RailEndpointRef>>
    ): List<AtGradeRailCrossing> {
        val endpointGroupByPort = endpointGroups.flatMap { (group, ports) ->
            ports.map { port -> port to group }
        }.toMap()
        val retained = crossings
            .sortedWith(compareBy(
                { it.first.railId.toString() },
                { it.second.railId.toString() },
                { it.first.arcLengthAbMeters },
                { it.second.arcLengthAbMeters }
            ))
            .filterNot { crossing ->
                val firstRail = rails[crossing.first.railId]
                    ?: throw IllegalArgumentException("crossing references unknown rail ${crossing.first.railId}")
                val secondRail = rails[crossing.second.railId]
                    ?: throw IllegalArgumentException("crossing references unknown rail ${crossing.second.railId}")
                require(crossing.first.arcLengthAbMeters <= firstRail.lengthMeters + Curve.TOLERANCE) {
                    "crossing lies outside rail ${firstRail.id}"
                }
                require(crossing.second.arcLengthAbMeters <= secondRail.lengthMeters + Curve.TOLERANCE) {
                    "crossing lies outside rail ${secondRail.id}"
                }
                val firstEndpoint = endpointAt(crossing.first.arcLengthAbMeters, firstRail.lengthMeters)
                val secondEndpoint = endpointAt(crossing.second.arcLengthAbMeters, secondRail.lengthMeters)
                if (firstEndpoint == null || secondEndpoint == null) return@filterNot false
                val firstRef = RailEndpointRef(firstRail.id, firstEndpoint)
                val secondRef = RailEndpointRef(secondRail.id, secondEndpoint)
                endpointGroupByPort.getValue(firstRef) == endpointGroupByPort.getValue(secondRef)
            }
        require(retained.distinct().size == retained.size) { "duplicate at-grade crossing observation" }
        return retained
    }

    private fun endpointAt(arcLength: Double, railLength: Double): RailEndpoint? = when {
        arcLength <= AT_GRADE_CROSSING_TOLERANCE_METERS -> RailEndpoint.A
        railLength - arcLength <= AT_GRADE_CROSSING_TOLERANCE_METERS -> RailEndpoint.B
        else -> null
    }

    private fun List<TopologyInputRail>.associateByUniqueId(): Map<UUID, TopologyInputRail> {
        val result = linkedMapOf<UUID, TopologyInputRail>()
        forEach { rail ->
            require(result.putIfAbsent(rail.id, rail) == null) { "duplicate rail id: ${rail.id}" }
        }
        return result
    }

    private fun validateContinuations(
        endpoints: Set<RailEndpointRef>,
        source: Map<RailEndpointRef, Set<RailEndpointRef>>
    ): Map<RailEndpointRef, Set<RailEndpointRef>> {
        require(source.keys.all(endpoints::contains)) { "continuation map contains an unknown source endpoint" }
        val result = endpoints.associateWith { endpoint -> source[endpoint].orEmpty().toSet() }
        result.forEach { (from, targets) ->
            targets.forEach { to ->
                require(to in endpoints) { "continuation $from -> $to references an unknown endpoint" }
                require(to != from) { "continuation cannot return to the same endpoint: $from" }
                require(from in result.getValue(to)) { "continuation $from -> $to is not symmetric" }
            }
        }
        return result
    }

    private fun buildEndpointGroups(
        endpoints: Set<RailEndpointRef>,
        continuations: Map<RailEndpointRef, Set<RailEndpointRef>>
    ): Map<RailEndpointRef, List<RailEndpointRef>> {
        val unionFind = EndpointUnionFind(endpoints)
        continuations.forEach { (from, targets) -> targets.forEach { unionFind.union(from, it) } }
        return endpoints.groupBy(unionFind::find)
    }

    private fun classify(
        ports: List<RailEndpointRef>,
        continuations: Map<RailEndpointRef, Set<RailEndpointRef>>
    ): NodeKind {
        if (ports.size == 1 && continuations.getValue(ports.single()).isEmpty()) return NodeKind.TERMINUS
        if (ports.size == 2) {
            val first = ports[0]
            val second = ports[1]
            if (continuations.getValue(first) == setOf(second) &&
                continuations.getValue(second) == setOf(first)
            ) return NodeKind.ORDINARY
        }
        return NodeKind.TURNOUT
    }

    private fun addLoopAnchors(
        railIds: Set<UUID>,
        groups: Map<RailEndpointRef, List<RailEndpointRef>>,
        initialKinds: Map<RailEndpointRef, NodeKind>
    ): Map<RailEndpointRef, NodeKind> {
        val groupByEndpoint = groups.flatMap { (group, ports) -> ports.map { it to group } }.toMap()
        val groupAdjacency = groups.keys.associateWithTo(linkedMapOf()) { linkedSetOf<RailEndpointRef>() }
        railIds.forEach { railId ->
            val first = groupByEndpoint.getValue(RailEndpointRef(railId, RailEndpoint.A))
            val second = groupByEndpoint.getValue(RailEndpointRef(railId, RailEndpoint.B))
            groupAdjacency.getValue(first).add(second)
            groupAdjacency.getValue(second).add(first)
        }

        val result = initialKinds.toMutableMap()
        val remaining = groups.keys.toMutableSet()
        for (seed in groups.keys.sortedBy(::groupKey)) {
            if (seed !in remaining) continue
            val component = linkedSetOf<RailEndpointRef>()
            val queue = ArrayDeque<RailEndpointRef>()
            queue.add(seed)
            while (queue.isNotEmpty()) {
                val group = queue.removeFirst()
                if (!component.add(group)) continue
                groupAdjacency.getValue(group).sortedBy(::groupKey).forEach(queue::addLast)
            }
            remaining.removeAll(component)
            if (component.all { result.getValue(it) == NodeKind.ORDINARY }) {
                result[component.minBy(::groupKey)] = NodeKind.LOOP_ANCHOR
            }
        }
        return result
    }

    private fun walkEdges(
        rails: Map<UUID, TopologyInputRail>,
        continuations: Map<RailEndpointRef, Set<RailEndpointRef>>,
        endpointGroups: Map<RailEndpointRef, List<RailEndpointRef>>,
        nodeIdByGroup: Map<RailEndpointRef, TopologyNodeId>
    ): List<RawEdge> {
        val groupByEndpoint = endpointGroups.flatMap { (group, ports) -> ports.map { it to group } }.toMap()
        val visitedRails = linkedSetOf<UUID>()
        val edges = mutableListOf<RawEdge>()

        nodeIdByGroup.keys.sortedBy(::groupKey).forEach { firstGroup ->
            endpointGroups.getValue(firstGroup).sorted().forEach portLoop@{ firstPort ->
                if (firstPort.railId in visitedRails) return@portLoop
                var departure = firstPort
                val slices = mutableListOf<RailSliceRef>()
                while (true) {
                    check(visitedRails.add(departure.railId)) {
                        "rail ${departure.railId} was revisited before reaching a structural node"
                    }
                    val rail = rails.getValue(departure.railId)
                    val arrival = RailEndpointRef(departure.railId, departure.endpoint.opposite())
                    slices += if (departure.endpoint == RailEndpoint.A) {
                        RailSliceRef(rail.id, 0.0, rail.lengthMeters)
                    } else {
                        RailSliceRef(rail.id, rail.lengthMeters, 0.0)
                    }

                    val arrivalGroup = groupByEndpoint.getValue(arrival)
                    if (arrivalGroup in nodeIdByGroup) {
                        edges += RawEdge(firstGroup, firstPort, arrivalGroup, arrival, slices)
                        break
                    }
                    val next = continuations.getValue(arrival).singleOrNull()
                        ?: error("ordinary group has no unique continuation at $arrival")
                    departure = next
                }
            }
        }
        check(visitedRails == rails.keys) { "normalized topology did not cover every rail" }
        return edges
    }

    private fun groupKey(group: RailEndpointRef): String = "${group.railId}:${group.endpoint}"

    private enum class NodeKind {
        ORDINARY,
        TERMINUS,
        TURNOUT,
        LOOP_ANCHOR
    }

    private data class RawEdge(
        val firstGroup: RailEndpointRef,
        val firstPort: RailEndpointRef,
        val secondGroup: RailEndpointRef,
        val secondPort: RailEndpointRef,
        val slices: List<RailSliceRef>
    )

    private class EndpointUnionFind(endpoints: Set<RailEndpointRef>) {
        private val parent = endpoints.associateWithTo(hashMapOf()) { it }

        fun find(endpoint: RailEndpointRef): RailEndpointRef {
            val current = parent.getValue(endpoint)
            if (current == endpoint) return endpoint
            val root = find(current)
            parent[endpoint] = root
            return root
        }

        fun union(first: RailEndpointRef, second: RailEndpointRef) {
            val firstRoot = find(first)
            val secondRoot = find(second)
            if (firstRoot == secondRoot) return
            val smaller = minOf(firstRoot, secondRoot)
            val larger = maxOf(firstRoot, secondRoot)
            parent[larger] = smaller
        }
    }
}
