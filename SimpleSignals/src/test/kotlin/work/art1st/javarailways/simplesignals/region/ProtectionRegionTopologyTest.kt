package work.art1st.javarailways.simplesignals.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import java.util.UUID

class ProtectionRegionTopologyTest {
    @Test
    fun `ordinary edge slices preserve reverse-oriented endpoint connection`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val state = topologyState(
            revision = 3L,
            railIds = listOf(first, second),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.B)
            )
        )

        val topology = ProtectionRegionTopology.derive(state)

        assertSame(state, topology.topologyState)
        assertEquals(
            listOf(ProtectionTopologyConnection(second, ViewDirection.BA)),
            topology.rails.getValue(first).connectionsFromB
        )
        assertEquals(
            listOf(ProtectionTopologyConnection(first, ViewDirection.BA)),
            topology.rails.getValue(second).connectionsFromB
        )
    }

    @Test
    fun `turnout legal continuations become endpoint adjacency without selecting a branch`() {
        val trunk = UUID.randomUUID()
        val left = UUID.randomUUID()
        val right = UUID.randomUUID()
        val state = topologyState(
            revision = 5L,
            railIds = listOf(trunk, left, right),
            connections = listOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(left, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(right, RailEndpoint.A)
            )
        )

        val topology = ProtectionRegionTopology.derive(state)

        assertEquals(
            setOf(
                ProtectionTopologyConnection(left, ViewDirection.AB),
                ProtectionTopologyConnection(right, ViewDirection.AB)
            ),
            topology.rails.getValue(trunk).connectionsFromB.toSet()
        )
        assertEquals(
            listOf(ProtectionTopologyConnection(trunk, ViewDirection.BA)),
            topology.rails.getValue(left).connectionsFromA
        )
        assertEquals(
            listOf(ProtectionTopologyConnection(trunk, ViewDirection.BA)),
            topology.rails.getValue(right).connectionsFromA
        )
    }

    @Test
    fun `retained derivation keeps its original immutable Core root`() {
        val rail = UUID.randomUUID()
        val oldState = lineTopologyState(8L, rail)
        val retained = ProtectionRegionTopology.derive(oldState)
        val replacement = lineTopologyState(9L, rail)

        assertSame(oldState, retained.topologyState)
        assertEquals(8L, retained.topologyRevision)
        assertEquals(10.0, retained.rails.getValue(rail).lengthMeters)
        assertEquals(9L, replacement.revision)
    }
}
