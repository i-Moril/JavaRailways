package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import java.util.UUID

class InfrastructureEditFootprintCompilerTest {
    @Test
    fun `empty affected set requires no edit footprint`() {
        val rail = UUID(0L, 1L)
        val topology = topologyState(3L, listOf(rail), emptyList())

        assertNull(InfrastructureEditFootprintCompiler.compile(topology, mapOf(rail to 10.0), emptySet()))
    }

    @Test
    fun `affected rails expand to full intervals and incident shared resources only`() {
        val trunk = UUID(0L, 11L)
        val straight = UUID(0L, 12L)
        val branch = UUID(0L, 13L)
        val crossingRail = UUID(0L, 14L)
        val unrelated = UUID(0L, 15L)
        val topology = topologyState(
            revision = 7L,
            railIds = listOf(trunk, straight, branch, crossingRail, unrelated),
            connections = listOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(straight, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)
            ),
            atGradeCrossings = listOf(
                AtGradeRailCrossing(
                    RailCrossingPoint(straight, 4.0),
                    RailCrossingPoint(crossingRail, 6.0)
                )
            )
        )
        val lengths = mapOf(
            trunk to 10.0,
            straight to 20.0,
            branch to 30.0,
            crossingRail to 40.0,
            unrelated to 50.0
        )
        val turnout = TurnoutResourceIdentity.fromPorts(listOf(
            endpoint(trunk, RailEndpoint.B),
            endpoint(straight, RailEndpoint.A),
            endpoint(branch, RailEndpoint.A)
        ))
        val diamond = PhysicalTopologyResources.routeBoundBindings(topology).single().resource

        val footprint = requireNotNull(
            InfrastructureEditFootprintCompiler.compile(topology, lengths, setOf(straight, crossingRail))
        )

        assertEquals(
            setOf(
                RailIntervalResource(straight, 0.0, 20.0),
                RailIntervalResource(crossingRail, 0.0, 40.0),
                turnout,
                diamond
            ),
            footprint.resources.toSet()
        )
        assertEquals(4, footprint.resources.size)
    }

    @Test
    fun `missing topology rail or current length is rejected before a footprint is returned`() {
        val present = UUID(0L, 21L)
        val missing = UUID(0L, 22L)
        val topology = topologyState(9L, listOf(present), emptyList())

        assertThrows(IllegalArgumentException::class.java) {
            InfrastructureEditFootprintCompiler.compile(topology, mapOf(present to 10.0), setOf(missing))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InfrastructureEditFootprintCompiler.compile(topology, emptyMap(), setOf(present))
        }
    }
}
