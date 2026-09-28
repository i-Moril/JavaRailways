package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
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
    val expectedRailCount: Int,
    addedRailLengthsMeters: Map<UUID, Double>,
    removedRailIds: Set<UUID>,
    expectedConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
    expectedCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>
) {
    @get:JvmSynthetic
    internal val addedRailLengthsMeters: Map<UUID, Double> =
        Collections.unmodifiableMap(LinkedHashMap(addedRailLengthsMeters))

    @get:JvmSynthetic
    internal val expectedAbsentRailIds: Set<UUID> =
        Collections.unmodifiableSet(LinkedHashSet(removedRailIds - addedRailLengthsMeters.keys))

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
        require(targetRoot.revision == Math.addExact(baseRoot.revision, 1L)) {
            "prepared topology must advance exactly one revision"
        }
        require(expectedRailCount >= 0)
    }
}
