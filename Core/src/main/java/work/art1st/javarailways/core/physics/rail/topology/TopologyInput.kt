package work.art1st.javarailways.core.physics.rail.topology

import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import java.util.UUID

internal data class TopologyInputRail(
    val id: UUID,
    val lengthMeters: Double,
    val identity: UUID = UUID.randomUUID()
)

/** Pure input captured from live Core rails before normalization. */
internal data class TopologyInput(
    val revision: Long,
    val rails: List<TopologyInputRail>,
    val continuations: Map<RailEndpointRef, Set<RailEndpointRef>>,
    val atGradeCrossings: List<AtGradeRailCrossing> = emptyList(),
    val joinedEndpoints: Map<RailEndpointRef, Set<RailEndpointRef>> = continuations
)
