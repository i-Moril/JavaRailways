package work.art1st.javarailways.bukkit.tool

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.Locale

@OptIn(Heavy::class)
class RailWandJunctionPolicyTest {
    @Test
    fun `endpoint edit and warning use explicit incomplete group rather than coincident rails`() = withWorld { world ->
        val origin = Vector3d()
        val straight = world.createRailForTest(line(origin, Vector3d(15.0, 1.0, 0.0)))
        val unrelated = world.createRailForTest(line(origin, Vector3d(0.0, 0.0, 15.0)))
        val straightA = RailEndpointRef(straight.id, RailEndpoint.A)
        // The selected branch uses B here, proving the group is independent of AB direction.
        val branch = addJoinedRail(world, line(Vector3d(15.0, 1.0, 4.0), origin), RailEndpoint.B, straightA)
        val branchB = RailEndpointRef(branch.id, RailEndpoint.B)

        assertEquals(setOf(straight to 'A', branch to 'B'), railWandEndpointEditTargets(world, straightA).toSet())
        assertEquals(setOf(straight to 'A', branch to 'B'), railWandEndpointEditTargets(world, branchB).toSet())
        assertEquals(listOf(unrelated to 'A'), railWandEndpointEditTargets(
            world, RailEndpointRef(unrelated.id, RailEndpoint.A)
        ))
        assertTrue(straight.viewBA.nextRails.isEmpty())
        assertTrue(branch.viewAB.nextRails.isEmpty())
        assertEquals("display.junction_incomplete", railWandJunctionLabelKey(world.topologyState, straightA))
        assertEquals("⚠ 接点未完成", RailWandI18n.format(Locale.SIMPLIFIED_CHINESE, "display.junction_incomplete"))
        assertEquals("⚠ Incomplete junction", RailWandI18n.format(Locale.US, "display.junction_incomplete"))

        val stem = addJoinedRail(world, line(Vector3d(-15.0, -1.0, 0.0), origin), RailEndpoint.B, branchB)
        assertEquals("display.junction", railWandJunctionLabelKey(world.topologyState, straightA))
        assertEquals(setOf(straight.id, branch.id, stem.id),
            railWandEndpointEditTargets(world, straightA).mapTo(hashSetOf()) { it.first.id })
        assertTrue(unrelated.viewBA.nextRails.isEmpty())
    }

    @Test
    fun `ordinary opposing pair is not marked as a turnout`() = withWorld { world ->
        val first = world.createRailForTest(line(Vector3d(-10.0, 0.0, 0.0), Vector3d()))
        addJoinedRail(world, line(Vector3d(), Vector3d(10.0, 0.0, 0.0)), RailEndpoint.A,
            RailEndpointRef(first.id, RailEndpoint.B))

        assertNull(railWandJunctionLabelKey(world.topologyState, RailEndpointRef(first.id, RailEndpoint.B)))
    }

    @Test
    fun `first same-side sloped branch cannot enter adjacent grade smoothing from either end`() {
        val incoming = line(Vector3d(-10.0, -1.0, 0.0), Vector3d())
        val forwardBranch = line(Vector3d(), Vector3d(10.0, 1.0, 3.0))
        val returningBranch = line(Vector3d(), Vector3d(-10.0, -1.0, 3.0))
        assertTrue(canSmoothRailWandJunction(incoming, forwardBranch, adjacentAtStart = true))
        assertFalse(canSmoothRailWandJunction(incoming, returningBranch, adjacentAtStart = true))

        val outgoing = line(Vector3d(), Vector3d(10.0, 1.0, 0.0))
        val approaching = line(Vector3d(-10.0, -1.0, 3.0), Vector3d())
        val returning = line(Vector3d(10.0, 1.0, 3.0), Vector3d())
        assertTrue(canSmoothRailWandJunction(outgoing, approaching, adjacentAtStart = false))
        assertFalse(canSmoothRailWandJunction(outgoing, returning, adjacentAtStart = false))
    }

    @Test
    fun `real incomplete group survives structural undo redo and exact snapshot reload`() = withWorld { world ->
        val straight = world.createRailForTest(line(Vector3d(), Vector3d(15.0, 0.0, 0.0)))
        val straightA = RailEndpointRef(straight.id, RailEndpoint.A)
        val before = snapshot(world)
        val branch = addJoinedRail(world, line(Vector3d(), Vector3d(15.0, 0.0, 4.0)), RailEndpoint.A, straightA)
        val after = snapshot(world)
        val undo = PreparedRailWorldUndoBuilder.fromSnapshots(before, after.rails).build()
        val unrelated = world.createRailForTest(line(Vector3d(50.0, 0.0, 0.0), Vector3d(65.0, 0.0, 0.0)))

        val undone = requireNotNull(rebaseRailWorldUndo(snapshot(world), undo.expectedAfter, undo.before))
        restore(world, undone)
        assertNull(world.getRail(branch.id))
        assertEquals(setOf(straightA), world.topologyState.endpointGroupAt(straightA))
        assertTrue(world.getRail(unrelated.id) != null)

        val redone = requireNotNull(rebaseRailWorldUndo(snapshot(world), undo.before, undo.expectedAfter))
        restore(world, redone)
        val branchA = RailEndpointRef(branch.id, RailEndpoint.A)
        assertEquals(setOf(straightA, branchA), world.topologyState.endpointGroupAt(straightA))
        assertTrue(requireNotNull(world.getRail(straight.id)).viewBA.nextRails.isEmpty())
        assertTrue(requireNotNull(world.getRail(branch.id)).viewBA.nextRails.isEmpty())

        withWorld { reloaded ->
            restore(reloaded, snapshot(world))
            assertEquals(setOf(straightA, branchA), reloaded.topologyState.endpointGroupAt(branchA))
            assertEquals("display.junction_incomplete", railWandJunctionLabelKey(reloaded.topologyState, branchA))
            assertTrue(requireNotNull(reloaded.getRail(branch.id)).viewBA.nextRails.isEmpty())
        }
    }

    private fun addJoinedRail(
        world: CoreWorld,
        curve: PrimeCurve,
        endpoint: RailEndpoint,
        selected: RailEndpointRef
    ): CoreRail {
        val batch = world.prepareRailCreation(curve)
        val created = RailEndpointRef(batch.creations.single().railId, endpoint)
        val connections = RailWandExplicitConnections.capture(world, batch, addedConnections = listOf(selected to created))
        val plan = RailWandTopologyEditPlan.capturePreparedExplicitConnections(world, batch, connections)
        return IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames {
                plan.commitTopology(world) {
                    world.installPreparedRails(batch).single().also { plan.applyExplicitConnections(world, listOf(it)) }
                }
            }
        }
    }

    private fun snapshot(world: CoreWorld) = RailWorldUndoSnapshot(
        normalizeRailWandUndoRails(world.allRails.map(CoreRail::save)).sortedBy { it.id },
        emptyList(), emptyMap(), emptyMap(), emptyList(), emptyList()
    )

    private fun restore(world: CoreWorld, snapshot: RailWorldUndoSnapshot) {
        val plan = world.prepareRailWorldSnapshotRestorePreservingActiveSelections(
            normalizeRailWandUndoRails(world.allRails.map(CoreRail::save)), snapshot.rails
        )
        IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames {
                world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }
            }
        }
    }

    private fun line(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(Vector3d(to), Vector3d(tangent), Vector3d(normal), from.distance(to))
        ))
    }

    private fun withWorld(action: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        try {
            action(world)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
