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
 * Immutable normalized topology prepared away from the world owner thread for one exact live root.
 *
 * This is process-local commit data, not a persisted format or a second topology model. The final
 * transaction validates only the declared delta and publishes [targetRoot]; it never rebuilds the
 * complete graph.
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
    baseCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>,
    expectedAddedRailCurves: Map<UUID, PrimeCurve>,
    removedRailIds: Set<UUID>,
    expectedConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
    expectedCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>
) {
    /** Fully prepared invalidation value; publishing it performs no graph or geometry work. */
    val topologyChange: RailTopologyChange? = if (targetRoot === baseRoot) null else RailTopologyChange(
        baseRoot, targetRoot, linkedSetOf<UUID>().apply {
            addAll(removedRailIds)
            addAll(expectedAddedRailCurves.keys)
            expectedConnections.forEach { (entry, targets) ->
                if (baseConnections[entry] != targets) add(entry.railId)
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
                connectionsAtB = Collections.unmodifiableList(ArrayList(saved.connectionsAtB))
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
