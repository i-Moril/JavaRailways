package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID

/**
 * Prepared local edit and its immutable preview/undo values.
 * Commit validates the declared objects and applies the delta to current state; targetRoot is
 * never installed as the live world root, so independent edits cannot overwrite each other.
 */
class PreparedRailTopologyUpdate internal constructor(
    @get:JvmSynthetic internal val ownerWorld: CoreWorld,
    val baseRoot: TopologyState,
    val targetRoot: TopologyState,
    baseRailSnapshot: List<CoreRail.SavedRailV1>,
    targetRailSnapshot: List<CoreRail.SavedRailV1>,
    val expectedRailCount: Int,
    baseRailCount: Int,
    baseRails: Map<UUID, CoreRail?>,
    baseConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
    baseJoinedEndpoints: Map<RailEndpointRef, Set<RailEndpointRef>>,
    baseCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>,
    expectedAddedRailCurves: Map<UUID, PrimeCurve>,
    removedRailIds: Set<UUID>,
    expectedConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
    expectedJoinedEndpoints: Map<RailEndpointRef, Set<RailEndpointRef>>,
    expectedCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>
) {
    /** Preview invalidation only; live publication derives its change from the actual committed state. */
    val topologyChange: RailTopologyChange? = if (targetRoot === baseRoot) null else RailTopologyChange(
        baseRoot, targetRoot, linkedSetOf<UUID>().apply {
            addAll(removedRailIds)
            addAll(expectedAddedRailCurves.keys)
            expectedConnections.forEach { (entry, targets) ->
                if (baseConnections[entry] != targets) add(entry.railId)
            }
            expectedJoinedEndpoints.forEach { (entry, peers) ->
                if (baseJoinedEndpoints[entry] != peers) add(entry.railId)
            }
            (baseCrossings.keys + expectedCrossings.keys).forEach { pair ->
                if (baseCrossings[pair] != expectedCrossings[pair]) {
                    add(pair.first)
                    add(pair.second)
                }
            }
        }.let { seeds -> topologyAffectedRails(baseRoot, targetRoot, seeds) }
    )
    /** Complete saved rail values captured while this plan was prepared off the owner thread. */
    val baseRailSnapshot: List<CoreRail.SavedRailV1> =
        immutableSavedRails(baseRailSnapshot)

    /** Complete expected saved rail values for [targetRoot], prepared without reading live rails at commit. */
    val targetRailSnapshot: List<CoreRail.SavedRailV1> =
        immutableSavedRails(targetRailSnapshot)

    @get:JvmSynthetic
    internal val baseRailCount: Int = baseRailCount

    @get:JvmSynthetic
    internal val baseRails: Map<UUID, CoreRail?> =
        Collections.unmodifiableMap(LinkedHashMap(baseRails))

    @get:JvmSynthetic
    internal val baseConnections: Map<RailEndpointRef, Set<RailEndpointRef>> =
        Collections.unmodifiableMap(
            LinkedHashMap(baseConnections.mapValues { (_, targets) ->
                Collections.unmodifiableSet(LinkedHashSet(targets))
            })
        )

    @get:JvmSynthetic
    internal val baseJoinedEndpoints: Map<RailEndpointRef, Set<RailEndpointRef>> =
        Collections.unmodifiableMap(baseJoinedEndpoints.mapValues { (_, peers) -> Collections.unmodifiableSet(LinkedHashSet(peers)) })

    @get:JvmSynthetic
    internal val expectedJoinedEndpoints: Map<RailEndpointRef, Set<RailEndpointRef>> =
        Collections.unmodifiableMap(expectedJoinedEndpoints.mapValues { (_, peers) -> Collections.unmodifiableSet(LinkedHashSet(peers)) })

    @get:JvmSynthetic
    internal val baseCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?> =
        Collections.unmodifiableMap(LinkedHashMap(baseCrossings))

    @get:JvmSynthetic
    internal val expectedAddedRailCurves: Map<UUID, PrimeCurve> =
        Collections.unmodifiableMap(LinkedHashMap(expectedAddedRailCurves))

    @get:JvmSynthetic
    internal val inventoryMutationRailIds: Set<UUID> =
        Collections.unmodifiableSet(LinkedHashSet(removedRailIds + expectedAddedRailCurves.keys))

    @get:JvmSynthetic
    internal val expectedAbsentRailIds: Set<UUID> =
        Collections.unmodifiableSet(LinkedHashSet(removedRailIds - expectedAddedRailCurves.keys))

    @get:JvmSynthetic
    internal val expectedConnections: Map<RailEndpointRef, Set<RailEndpointRef>> =
        Collections.unmodifiableMap(
            LinkedHashMap(expectedConnections.mapValues { (_, targets) ->
                Collections.unmodifiableSet(LinkedHashSet(targets))
            })
        )

    @get:JvmSynthetic
    internal val expectedCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?> =
        Collections.unmodifiableMap(LinkedHashMap(expectedCrossings))

    init {
        require(targetRoot === baseRoot || targetRoot.revision == Math.addExact(baseRoot.revision, 1L)) {
            "prepared topology must retain the exact base root or advance exactly one revision"
        }
        require(baseRailCount >= 0)
        require(expectedRailCount >= 0)
        require(baseRailSnapshot.map(CoreRail.SavedRailV1::id).distinct().size == baseRailSnapshot.size)
        require(targetRailSnapshot.map(CoreRail.SavedRailV1::id).distinct().size == targetRailSnapshot.size)
        require(targetRailSnapshot.size == expectedRailCount)
    }

    private fun immutableSavedRails(values: List<CoreRail.SavedRailV1>): List<CoreRail.SavedRailV1> =
        Collections.unmodifiableList(values.map { saved ->
            saved.copy(
                connectionsAtA = Collections.unmodifiableList(ArrayList(saved.connectionsAtA)),
                connectionsAtB = Collections.unmodifiableList(ArrayList(saved.connectionsAtB)),
                joinedEndpointsAtA = Collections.unmodifiableList(ArrayList(saved.joinedEndpointsAtA)),
                joinedEndpointsAtB = Collections.unmodifiableList(ArrayList(saved.joinedEndpointsAtB))
            )
        })
}

/** Expand only touched physical junctions and crossings, using Core's root-owned reverse indexes. */
internal fun topologyAffectedRails(previous: TopologyState, current: TopologyState, seeds: Set<UUID>): Set<UUID> =
    linkedSetOf<UUID>().apply {
        addAll(seeds)
        for (railId in seeds) {
            work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint.entries.forEach { end ->
                val endpoint = RailEndpointRef(railId, end)
                val oldNode = previous.structuralNodeAt(endpoint)
                val newNode = current.structuralNodeAt(endpoint)
                val oldContinuation = previous.ordinaryContinuationAt(endpoint)
                val newContinuation = current.ordinaryContinuationAt(endpoint)
                val oldTurns = (oldNode as? work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode.Turnout)?.legalContinuations
                val newTurns = (newNode as? work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode.Turnout)?.legalContinuations
                if (oldNode?.ports != newNode?.ports || oldTurns != newTurns || oldContinuation != newContinuation) {
                    oldNode?.ports?.mapTo(this) { it.railId }
                    newNode?.ports?.mapTo(this) { it.railId }
                    oldContinuation?.let { add(it.railId) }
                    newContinuation?.let { add(it.railId) }
                }
            }
            val before = previous.atGradeCrossingsOn(railId)
            val after = current.atGradeCrossingsOn(railId)
            if (before != after) (before + after).forEach { crossing ->
                    add(crossing.first.railId)
                    add(crossing.second.railId)
            }
        }
    }
