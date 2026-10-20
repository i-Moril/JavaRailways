package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.rail.topology.TopologyInput
import work.art1st.javarailways.core.physics.rail.topology.TopologyInputRail
import work.art1st.javarailways.core.physics.rail.topology.TopologyStateBuilder
import java.util.UUID

/** Pure target calculation; CoreWorld still captures and validates all live rollback state. */
internal class RailTopologyTarget(
    val targetRoot: TopologyState,
    val expectedRailCount: Int,
    val expectedConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
    val expectedCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>
)

internal fun calculateRailTopologyTarget(
    baseRoot: TopologyState,
    baseInput: TopologyInput,
    removedRailIds: Set<UUID>,
    addedRailCurves: Map<UUID, PrimeCurve>,
    finalConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
    finalConnectionOrder: Map<RailEndpointRef, List<RailEndpointRef>>,
    finalActiveEndpoints: Map<RailEndpointRef, RailEndpointRef?>,
    rawBaseCrossings: List<AtGradeRailCrossing>,
    addedCrossings: List<AtGradeRailCrossing>
): RailTopologyTarget {
    check(baseInput.revision == baseRoot.revision)
    require(addedRailCurves.keys.intersect(removedRailIds).isEmpty())
    require(finalActiveEndpoints.keys == finalConnections.keys)
    require(finalConnectionOrder.keys == finalConnections.keys)
    val targetRailIds = (baseInput.rails.mapTo(linkedSetOf(), TopologyInputRail::id) - removedRailIds) +
        addedRailCurves.keys
    val continuations = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
    baseInput.continuations.forEach { (endpoint, targets) ->
        if (endpoint.railId !in removedRailIds) {
            continuations[endpoint] = targets.filterTo(linkedSetOf()) { it.railId !in removedRailIds }
        }
    }
    finalConnections.forEach { (endpoint, targets) ->
        require(endpoint.railId in targetRailIds && targets.all { it.railId in targetRailIds }) {
            "prepared connection references a rail outside the target inventory"
        }
        continuations[endpoint] = targets.toMutableSet()
    }
    val nextRevision = Math.addExact(baseRoot.revision, 1L)
    val targetInput = TopologyInput(
        revision = nextRevision,
        rails = baseInput.rails.filterNot { it.id in removedRailIds } +
            addedRailCurves.map { (railId, curve) -> TopologyInputRail(railId, curve.totalArcLength) },
        continuations = continuations.mapValues { (_, targets) -> targets.toSet() },
        atGradeCrossings = rawBaseCrossings.filterNot { crossing ->
            crossing.first.railId in removedRailIds || crossing.second.railId in removedRailIds
        } + addedCrossings
    )
    val expectedConnections = linkedMapOf<RailEndpointRef, Set<RailEndpointRef>>()
    baseInput.continuations.forEach { (endpoint, targets) ->
        if (endpoint.railId !in removedRailIds && targets.any { it.railId in removedRailIds }) {
            expectedConnections[endpoint] = targets.filterTo(linkedSetOf()) { it.railId !in removedRailIds }
        }
    }
    addedRailCurves.keys.forEach { railId ->
        RailEndpoint.entries.forEach { endpoint ->
            expectedConnections.putIfAbsent(
                RailEndpointRef(railId, endpoint),
                emptySet()
            )
        }
    }
    expectedConnections.putAll(finalConnections)
    val expectedCrossings = linkedMapOf<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>()
    rawBaseCrossings.asSequence()
        .filter { crossing ->
            crossing.first.railId in removedRailIds || crossing.second.railId in removedRailIds
        }
        .forEach { crossing ->
            expectedCrossings[
                AtGradeRailCrossingIndex.RailPair.of(
                    crossing.first.railId,
                    crossing.second.railId
                )
            ] = null
        }
    addedCrossings.forEach { crossing ->
        expectedCrossings[
            AtGradeRailCrossingIndex.RailPair.of(
                crossing.first.railId,
                crossing.second.railId
            )
        ] = crossing
    }
    return RailTopologyTarget(
        targetRoot = TopologyStateBuilder.build(targetInput),
        expectedRailCount = targetRailIds.size,
        expectedConnections = expectedConnections,
        expectedCrossings = expectedCrossings
    )
}
