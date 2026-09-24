package work.art1st.javarailways.core.api.physics

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNodeId
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeId
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeSlice
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.util.UUID

class TopologyStateApiTest {
    @Test
    fun `world exposes the published root through public API with immutable collections`() {
        val coreWorld = createTestWorld()
        try {
            val first = coreWorld.createRailForTest(straightCurve(0.0, 10.0))
            val second = coreWorld.createRailForTest(straightCurve(10.0, 30.0))
            CoreRail.connect(first, second)
            val world: World = coreWorld

            val state = world.topologyState
            val edge = state.edges.values.single()

            assertSame(coreWorld.topologyState, state)
            assertEquals(coreWorld.topologyRevision, state.revision)
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (state.nodes as MutableMap<TopologyNodeId, TopologyNode>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (state.edges as MutableMap<TrackEdgeId, Any>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (edge.railSlices as MutableList<RailSliceRef>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (state.railProjection.getValue(first.id) as MutableList<TrackEdgeSlice>).clear()
            }
            assertEquals(
                state.nodes.getValue(edge.first.nodeId),
                state.structuralNodeAt(edge.first.port)
            )
            assertEquals(
                state.nodes.getValue(edge.first.nodeId),
                state.structuralNodeWithPorts(state.nodes.getValue(edge.first.nodeId).ports.reversed())
            )
            assertEquals(
                null,
                state.structuralNodeAt(RailEndpointRef(first.id, RailEndpoint.B))
            )
            assertEquals(
                RailEndpointRef(second.id, RailEndpoint.A),
                state.ordinaryContinuationAt(RailEndpointRef(first.id, RailEndpoint.B))
            )
        } finally {
            coreWorld.physicsCoordinator.close()
            (coreWorld.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `crossing reverse lookup is immutable and scoped to one rail`() {
        val coreWorld = createTestWorld()
        try {
            val first = coreWorld.createRailForTest(straightCurve(-5.0, 5.0))
            val second = coreWorld.createRailForTest(verticalCurve(-5.0, 5.0))
            val crossing = coreWorld.topologyState.atGradeCrossings.single()

            assertEquals(listOf(crossing), coreWorld.topologyState.atGradeCrossingsOn(first.id))
            assertEquals(listOf(crossing), coreWorld.topologyState.atGradeCrossingsOn(second.id))
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (coreWorld.topologyState.atGradeCrossingsOn(first.id) as MutableList<Any>).clear()
            }
            assertEquals(emptyList<Any>(), coreWorld.topologyState.atGradeCrossingsOn(UUID.randomUUID()))
        } finally {
            coreWorld.physicsCoordinator.close()
            (coreWorld.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `revision local IDs reject invalid values`() {
        assertThrows(IllegalArgumentException::class.java) { TopologyNodeId(-1L, 0) }
        assertThrows(IllegalArgumentException::class.java) { TrackEdgeId(0L, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            RailSliceRef(UUID.randomUUID(), Double.NaN, 1.0)
        }
    }

    private fun straightCurve(fromX: Double, toX: Double): PrimeCurve {
        val normal = Vector3d(0.0, 1.0, 0.0)
        val tangent = Vector3d(1.0, 0.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(fromX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(Vector3d(toX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), toX - fromX)
        ))
    }

    private fun verticalCurve(fromZ: Double, toZ: Double): PrimeCurve {
        val normal = Vector3d(0.0, 1.0, 0.0)
        val tangent = Vector3d(0.0, 0.0, 1.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(0.0, 0.0, fromZ), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(
                Vector3d(0.0, 0.0, toZ),
                Vector3d(tangent),
                Vector3d(normal),
                toZ - fromZ
            )
        ))
    }
}
