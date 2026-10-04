package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.route.planning.OperatingDirectionOverlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import java.util.UUID

class RoutePlanningRestrictionRuntimeTest {
    @Test
    fun `compile invokes each captured source once in stable order and includes operating direction`() {
        val rail = rail(1)
        val topology = topology(7L, rail)
        val calls = mutableListOf<String>()
        val runtime = RoutePlanningRestrictionRuntime()
        val registered = mutableListOf(
            binding("test:z", calls, span(rail.id, ViewDirection.AB, 12.0, 18.0)),
            binding("test:a", calls, span(rail.id, ViewDirection.AB, 4.0, 8.0))
        )
        runtime.replaceSources(registered)
        registered.clear()

        val compiled = runtime.compile(
            topology,
            OperatingDirectionOverlay(topology.revision, mapOf(rail.id to ViewDirection.AB))
        )

        assertEquals(listOf("test:a", "test:z"), calls)
        assertEquals(3, compiled.forbiddenTraversals.size)
        assertEquals(ViewDirection.BA, compiled.forbiddenTraversals[0].facing)
        assertEquals(4.0, compiled.forbiddenTraversals[1].lowerAbMeters, 0.0)
        assertEquals(12.0, compiled.forbiddenTraversals[2].lowerAbMeters, 0.0)
    }

    @Test
    fun `invalid replacement preserves prior snapshot and source failure is identified`() {
        val rail = rail(1)
        val topology = topology(9L, rail)
        val calls = mutableListOf<String>()
        val retained = binding("test:retained", calls, span(rail.id, ViewDirection.AB, 2.0, 3.0))
        val runtime = RoutePlanningRestrictionRuntime()
        runtime.replaceSources(listOf(retained))

        assertThrows(IllegalArgumentException::class.java) {
            runtime.replaceSources(listOf(retained, retained))
        }
        runtime.compile(topology, OperatingDirectionOverlay(topology.revision, emptyMap()))
        assertEquals(listOf("test:retained"), calls)

        runtime.replaceSources(
            listOf(
                RoutePlanningRestrictionBinding("test:broken") {
                    throw IllegalStateException("broken table")
                }
            )
        )
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runtime.compile(topology, OperatingDirectionOverlay(topology.revision, emptyMap()))
        }
        assertTrue(failure.message.orEmpty().contains("test:broken"))
        assertTrue(failure.message.orEmpty().contains("broken table"))
    }

    private fun binding(
        sourceId: String,
        calls: MutableList<String>,
        forbidden: DirectedRailSpan
    ) = RoutePlanningRestrictionBinding(sourceId) { topology ->
        calls += sourceId
        RoutePlanningRestrictions(topology.revision, forbiddenTraversals = listOf(forbidden))
    }

    private fun span(
        railId: UUID,
        facing: ViewDirection,
        lower: Double,
        upper: Double
    ) = DirectedRailSpan(railId, facing, lower, upper)

    private fun topology(revision: Long, rail: TestTopologyRail): TopologyState = buildTestTopology(
        revision = revision,
        rails = listOf(rail),
        continuations = emptyMap<RailEndpointRef, Set<RailEndpointRef>>()
    )

    private fun rail(number: Int) = TestTopologyRail(UUID(0L, number.toLong()), 20.0)
}
