package work.art1st.javarailways.core.physics

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.train.createTestWorld

class CoreWorldTopologyStatePublicationTest {
    @Test
    fun `empty world starts with one revision zero topology root`() = withWorld { world ->
        val state = world.topologyState

        assertEquals(0L, world.topologyRevision)
        assertEquals(0L, state.revision)
        assertTrue(state.nodes.isEmpty())
        assertTrue(state.edges.isEmpty())
        assertTrue(state.railProjection.isEmpty())
        assertSame(state, world.topologyState)
    }

    @Test
    fun `changed transaction publishes aligned normalized topology`() =
        withWorld { world ->
            lateinit var first: CoreRail
            lateinit var second: CoreRail

            world.railTopologyTransaction {
                first = CoreRail(world, straightCurve(0.0, 10.0), mutableListOf(), mutableListOf())
                second = CoreRail(world, straightCurve(10.0, 30.0), mutableListOf(), mutableListOf())
                RailView.connect(first.viewAB, second.viewBA)
            }

            val state = world.topologyState
            assertEquals(1L, world.topologyRevision)
            assertEquals(world.topologyRevision, state.revision)
            assertEquals(2, state.nodes.values.count { it is TopologyNode.Terminus })
            val edge = state.edges.values.single()
            assertEquals(setOf(first.id, second.id), edge.railSlices.map { it.railId }.toSet())
            assertEquals(30.0, edge.lengthMeters, 1e-12)
            assertEquals(1, state.railProjection.getValue(first.id).size)
            assertEquals(1, state.railProjection.getValue(second.id).size)
        }

    @Test
    fun `asymmetric adjacency is rejected instead of being repaired in the published topology`() =
        withWorld { world ->
            val first = CoreRail(world, straightCurve(0.0, 10.0), mutableListOf(), mutableListOf())
            val second = CoreRail(world, straightCurve(10.0, 30.0), mutableListOf(), mutableListOf())
            val before = world.topologyState

            val failure = assertThrows(IllegalArgumentException::class.java) {
                world.railTopologyTransaction {
                    first.viewAB.mutableNextRails.add(second.viewAB)
                }
            }

            assertTrue(failure.message.orEmpty().contains("not symmetric"))
            assertSame(before, world.topologyState)
            assertEquals(before.revision, world.topologyRevision)
            assertEquals(PhysicsCoordinatorStatus.STALLED, world.physicsCoordinator.status)
        }

    @Test
    fun `later publication does not mutate a previously observed topology root`() = withWorld { world ->
        val first = CoreRail(world, straightCurve(0.0, 10.0), mutableListOf(), mutableListOf())
        val previous = world.topologyState
        val previousProjection = previous.railProjection

        val second = CoreRail(world, straightCurve(20.0, 30.0), mutableListOf(), mutableListOf())
        val current = world.topologyState

        assertNotSame(previous, current)
        assertEquals(1L, previous.revision)
        assertEquals(setOf(first.id), previous.railProjection.keys)
        assertSame(previousProjection, previous.railProjection)
        assertEquals(setOf(first.id, second.id), current.railProjection.keys)
        assertEquals(2L, current.revision)
    }

    @Test
    fun `runtime branch selection and successful no-op preserve topology root identity`() = withWorld { world ->
        lateinit var source: CoreRail
        lateinit var first: CoreRail
        lateinit var second: CoreRail
        world.railTopologyTransaction {
            source = CoreRail(world, straightCurve(0.0, 10.0), mutableListOf(), mutableListOf())
            first = CoreRail(world, straightCurve(10.0, 20.0), mutableListOf(), mutableListOf())
            second = CoreRail(world, straightCurve(10.0, 25.0), mutableListOf(), mutableListOf())
            RailView.connect(source.viewAB, first.viewBA)
            RailView.connect(source.viewAB, second.viewBA)
            source.viewAB.activeRail = first.viewAB
        }
        val before = world.topologyState

        world.railTopologyTransaction {
            source.viewAB.activeRail = second.viewAB
        }
        assertSame(before, world.topologyState)

        world.railTopologyTransaction { }
        assertSame(before, world.topologyState)
    }

    @Test
    fun `failure before mutation preserves topology root and keeps world healthy`() = withWorld { world ->
        val before = world.topologyState

        assertThrows(IllegalStateException::class.java) {
            world.railTopologyTransaction<Unit> { error("prepare failed") }
        }

        assertSame(before, world.topologyState)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
    }

    @Test
    fun `failure after mutation preserves topology root and faults world`() = withWorld { world ->
        val before = world.topologyState

        assertThrows(IllegalStateException::class.java) {
            world.railTopologyTransaction {
                CoreRail(world, straightCurve(0.0, 10.0), mutableListOf(), mutableListOf())
                error("commit failed")
            }
        }

        assertSame(before, world.topologyState)
        assertEquals(before.revision, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.STALLED, world.physicsCoordinator.status)
    }

    @Test
    fun `normalization failure after mutation preserves topology root and faults world`() {
        val world = createTestWorld()
        val otherWorld = createTestWorld()
        try {
            val source = CoreRail(world, straightCurve(0.0, 10.0), mutableListOf(), mutableListOf())
            val outside = CoreRail(otherWorld, straightCurve(10.0, 20.0), mutableListOf(), mutableListOf())
            val before = world.topologyState

            val failure = assertThrows(IllegalArgumentException::class.java) {
                world.railTopologyTransaction {
                    source.viewAB.mutableNextRails.add(outside.viewAB)
                }
            }

            assertTrue(failure.message.orEmpty().contains("outside the captured world"))
            assertSame(before, world.topologyState)
            assertEquals(before.revision, world.topologyRevision)
            assertEquals(PhysicsCoordinatorStatus.STALLED, world.physicsCoordinator.status)
        } finally {
            closeWorld(world)
            closeWorld(otherWorld)
        }
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        try {
            block(world)
        } finally {
            closeWorld(world)
        }
    }

    private fun closeWorld(world: CoreWorld) {
        world.physicsCoordinator.close()
        (world.tickManager as AutoCloseable).close()
    }

    private fun straightCurve(fromX: Double, toX: Double): PrimeCurve {
        val normal = Vector3d(0.0, 1.0, 0.0)
        val tangent = Vector3d(1.0, 0.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(fromX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(Vector3d(toX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), toX - fromX)
        ))
    }
}
