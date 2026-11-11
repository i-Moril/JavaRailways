package work.art1st.javarailways.core.physics.rail.topology

import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

/** One physical rail accepted by [buildTestTopology]. */
data class TestTopologyRail(
    val id: UUID,
    val lengthMeters: Double,
    val identity: UUID = UUID.randomUUID()
)

/**
 * Builds a production-normalized topology for tests in downstream modules.
 *
 * The continuation map uses canonical physical endpoints and must be symmetric, exactly like the
 * Core structural input. This fixture deliberately delegates to the production normalizer instead
 * of exposing a second test-only graph model.
 */
fun buildTestTopology(
    revision: Long,
    rails: Collection<TestTopologyRail>,
    continuations: Map<RailEndpointRef, Set<RailEndpointRef>>,
    atGradeCrossings: List<AtGradeRailCrossing> = emptyList()
): TopologyState = TopologyStateBuilder.build(
    TopologyInput(
        revision = revision,
        rails = rails.map { rail ->
            TopologyInputRail(rail.id, rail.lengthMeters, rail.identity)
        },
        continuations = continuations,
        atGradeCrossings = atGradeCrossings
    )
)
