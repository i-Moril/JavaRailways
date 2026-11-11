package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

class RailConnectTest {
    @Test
    fun `save stores connections at semantic endpoints`() = withWorld { world ->
        val first = world.createRailForTest(straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
        val second = world.createRailForTest(straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))

        IsolatedSimulationThreadMarker.run { CoreRail.connect(first, second) }

        val firstSaved = first.save()
        val secondSaved = second.save()
        assertEquals(emptyList<SavedRailEndpointRef>(), firstSaved.connectionsAtA)
        assertEquals(listOf(SavedRailEndpointRef(second.id.toString(), RailEndpoint.A)), firstSaved.connectionsAtB)
        assertNull(firstSaved.activeConnectionAtA)
        assertEquals(SavedRailEndpointRef(second.id.toString(), RailEndpoint.A), firstSaved.activeConnectionAtB)
        assertEquals(listOf(SavedRailEndpointRef(first.id.toString(), RailEndpoint.B)), secondSaved.connectionsAtA)
        assertEquals(emptyList<SavedRailEndpointRef>(), secondSaved.connectionsAtB)
        assertEquals(SavedRailEndpointRef(first.id.toString(), RailEndpoint.B), secondSaved.activeConnectionAtA)
        assertNull(secondSaved.activeConnectionAtB)
    }

    @Test
    fun `connecting the same adjacent rails twice does not duplicate next rails`() = withWorld { world ->
        val first = world.createRailForTest(straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
        val second = world.createRailForTest(straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))

        val beforeConnect = world.topologyState

        IsolatedSimulationThreadMarker.run { CoreRail.connect(first, second) }
        val afterConnect = world.topologyState
        IsolatedSimulationThreadMarker.run { CoreRail.connect(first, second) }

        assertEquals(1, first.viewAB.nextRails.size)
        assertEquals(0, first.viewBA.nextRails.size)
        assertEquals(0, second.viewAB.nextRails.size)
        assertEquals(1, second.viewBA.nextRails.size)
        assertEquals(second.viewAB, first.viewAB.nextActiveRail)
        assertEquals(first.viewBA, second.viewBA.nextActiveRail)
        assertEquals(beforeConnect.revision + 1L, afterConnect.revision)
        assertEquals(afterConnect.revision, world.topologyRevision)
        assertSame(afterConnect, world.topologyState)
        assertEquals(1, afterConnect.edges.size)
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            run {
                PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                    val created = CoreWorld.createEmpty(tick, physics)
                    block(created)
                }
            }
        }
    }

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, Vector3d(tangent), Vector3d(normal), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, Vector3d(tangent), Vector3d(normal), length, Curve.SegmentType.LINE_END)
        ))
    }
}
