package work.art1st.javarailways.bukkit.tool

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import work.art1st.javarailways.core.physics.train.createTestWorld

class RailWandTopologyEditPlanTest {
    @Test
    fun `snapshot plan derives replacement UUID and wrapper excludes unrelated rail`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(
                lineCurve(Vector3d(0.0, 0.0, 5.0), Vector3d(10.0, 0.0, 5.0))
            )
            val crossing = world.createRailForTest(
                lineCurve(Vector3d(5.0, 0.0, -5.0), Vector3d(5.0, 0.0, 5.0))
            )
            val unrelated = world.createRailForTest(straightCurve(30.0, 40.0))
            val current = world.allRails.map(CoreRail::save)
            val replacement = lineCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
            val target = current.map { saved ->
                if (saved.id == source.id.toString()) saved.copy(
                    curveAB = replacement.save() as PrimeCurveImpl.SavedPrimeCurveV1
                ) else saved
            }
            val prepared = world.prepareRailWorldSnapshotRestore(current, target)
            val plan = RailWandTopologyEditPlan.capturePreparedSnapshotRestore(world, prepared)

            assertEquals(setOf(source.id), prepared.removedOrReplacedRailIds)
            assertEquals(setOf(source.id), prepared.createdOrReplacedRailIds)
            assertEquals(setOf(source.id), plan.snapshotGeometryChangedRailIds)
            assertTrue(crossing.id in requireNotNull(plan.impact).railIds)
            assertFalse(unrelated.id in requireNotNull(plan.impact).railIds)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `plan binds the exact topology root and existing rail instances`() {
        val world = createTestWorld()
        try {
            val affected = world.createRailForTest(straightCurve(0.0, 10.0))
            val plan = RailWandTopologyEditPlan.captureReplacementOrRemoval(world, listOf(affected.id))

            assertTrue(plan.isCurrent(world))

            world.createRailForTest(straightCurve(20.0, 30.0))

            assertFalse(plan.isCurrent(world))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `prepared explicit connection impact contains selected existing rail but not nearby rail`() {
        val world = createTestWorld()
        try {
            val selected = world.createRailForTest(straightCurve(0.0, 10.0))
            val connectedPeer = world.createRailForTest(straightCurve(10.0, 30.0))
            CoreRail.connect(selected, connectedPeer)
            val crossing = world.createRailForTest(
                lineCurve(Vector3d(15.0, 0.0, -5.0), Vector3d(15.0, 0.0, 5.0))
            )
            val nearby = world.createRailForTest(straightCurve(10.5, 20.5))
            val prepared = world.prepareRailCreation(straightCurve(10.0, 20.0))

            val createdA = RailEndpointRef(prepared.creations.single().railId, RailEndpoint.A)
            val selectedB = RailEndpointRef(selected.id, RailEndpoint.B)
            val connectionPlan = RailWandExplicitConnections.capture(
                world,
                prepared,
                addedConnections = listOf(selectedB to createdA)
            )
            val plan = RailWandTopologyEditPlan.capturePreparedExplicitConnections(
                world,
                prepared,
                connectionPlan
            )

            assertTrue(selected.id in requireNotNull(plan.impact).railIds)
            assertTrue(connectedPeer.id in requireNotNull(plan.impact).railIds)
            assertTrue(crossing.id in requireNotNull(plan.impact).railIds)
            assertFalse(nearby.id in requireNotNull(plan.impact).railIds)
            assertEquals(
                setOf(selected.id, connectedPeer.id, crossing.id),
                plan.explicitConnectionAffectedExistingRailIds
            )
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `explicit replacement mapping preserves the existing endpoint connection`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(straightCurve(0.0, 10.0))
            val peer = world.createRailForTest(straightCurve(10.0, 20.0))
            CoreRail.connect(source, peer)
            val prepared = world.prepareRailCreation(straightCurve(0.0, 10.0), setOf(source.id))
            val replacementId = prepared.creations.single().railId
            val connectionPlan = RailWandExplicitConnections.capture(
                world,
                prepared,
                replacementEndpoints = mapOf(
                    RailEndpointRef(source.id, RailEndpoint.A) to RailEndpointRef(replacementId, RailEndpoint.A),
                    RailEndpointRef(source.id, RailEndpoint.B) to RailEndpointRef(replacementId, RailEndpoint.B)
                )
            )
            val plan = RailWandTopologyEditPlan.capturePreparedExplicitConnections(
                world,
                prepared,
                connectionPlan
            )

            lateinit var replacement: work.art1st.javarailways.core.physics.rail.CoreRail
            world.railTopologyTransaction {
                replacement = world.installPreparedRails(prepared).single()
                plan.applyExplicitConnections(world, listOf(replacement))
                world.removeRail(source)
            }

            assertSame(peer.viewAB, replacement.viewAB.activeRail)
            assertSame(replacement.viewBA, peer.viewBA.activeRail)
            assertEquals(setOf(source.id, peer.id), plan.explicitConnectionAffectedExistingRailIds)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `turnout replacement preserves branch order and active selection exactly`() {
        val world = createTestWorld()
        try {
            val stem = world.createRailForTest(lineCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            val straight = world.createRailForTest(lineCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))
            val branch = world.createRailForTest(lineCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 5.0)))
            CoreRail.connect(stem, straight)
            CoreRail.connect(stem, branch)
            stem.viewAB.activeRail = straight.viewAB
            val prepared = world.prepareRailCreation(straightCurve(0.0, 10.0), setOf(stem.id))
            val replacementId = prepared.creations.single().railId
            val connectionPlan = RailWandExplicitConnections.capture(
                world,
                prepared,
                replacementEndpoints = replacementMap(stem, replacementId)
            )
            val plan = RailWandTopologyEditPlan.capturePreparedExplicitConnections(
                world,
                prepared,
                connectionPlan
            )

            lateinit var replacement: CoreRail
            world.railTopologyTransaction {
                replacement = world.installPreparedRails(prepared).single()
                plan.applyExplicitConnections(world, listOf(replacement))
                world.removeRail(stem)
            }

            assertEquals(listOf(straight.viewAB, branch.viewAB), replacement.viewAB.nextRails)
            assertSame(straight.viewAB, replacement.viewAB.activeRail)
            assertSame(replacement.viewBA, straight.viewBA.activeRail)
            assertSame(replacement.viewBA, branch.viewBA.activeRail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `replacement preserves an intentionally unset active branch`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(straightCurve(0.0, 10.0))
            val peer = world.createRailForTest(straightCurve(10.0, 20.0))
            CoreRail.connect(source, peer)
            source.viewAB.activeRail = null
            val prepared = world.prepareRailCreation(straightCurve(0.0, 10.0), setOf(source.id))
            val connectionPlan = RailWandExplicitConnections.capture(
                world,
                prepared,
                replacementEndpoints = replacementMap(source, prepared.creations.single().railId)
            )

            lateinit var replacement: CoreRail
            world.railTopologyTransaction {
                replacement = world.installPreparedRails(prepared).single()
                connectionPlan.apply(world, listOf(replacement))
                world.removeRail(source)
            }

            assertEquals(listOf(peer.viewAB), replacement.viewAB.nextRails)
            assertEquals(null, replacement.viewAB.activeRail)
            assertSame(replacement.viewBA, peer.viewBA.activeRail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `segmented multi rail replacement preserves outer peers and explicit internal order`() {
        val world = createTestWorld()
        try {
            val left = world.createRailForTest(straightCurve(-5.0, 0.0))
            val first = world.createRailForTest(straightCurve(0.0, 5.0))
            val second = world.createRailForTest(straightCurve(5.0, 10.0))
            val right = world.createRailForTest(straightCurve(10.0, 15.0))
            CoreRail.connect(left, first)
            CoreRail.connect(first, second)
            CoreRail.connect(second, right)
            val prepared = world.prepareRailCreationBatch(
                listOf(straightCurve(0.0, 3.0), straightCurve(3.0, 7.0), straightCurve(7.0, 10.0)),
                setOf(first.id, second.id)
            )
            val (newFirstId, middleId, newLastId) = prepared.creations.map { it.railId }
            val connectionPlan = RailWandExplicitConnections.capture(
                world,
                prepared,
                replacementEndpoints = mapOf(
                    RailEndpointRef(first.id, RailEndpoint.A) to RailEndpointRef(newFirstId, RailEndpoint.A),
                    RailEndpointRef(second.id, RailEndpoint.B) to RailEndpointRef(newLastId, RailEndpoint.B)
                ),
                addedConnections = listOf(
                    RailEndpointRef(newFirstId, RailEndpoint.B) to RailEndpointRef(middleId, RailEndpoint.A),
                    RailEndpointRef(middleId, RailEndpoint.B) to RailEndpointRef(newLastId, RailEndpoint.A)
                )
            )

            lateinit var installed: List<CoreRail>
            world.railTopologyTransaction {
                installed = world.installPreparedRails(prepared)
                connectionPlan.apply(world, installed)
                world.removeRail(first)
                world.removeRail(second)
            }
            val (newFirst, middle, newLast) = installed

            assertSame(newFirst.viewAB, left.viewAB.activeRail)
            assertSame(left.viewBA, newFirst.viewBA.activeRail)
            assertSame(middle.viewAB, newFirst.viewAB.activeRail)
            assertSame(newFirst.viewBA, middle.viewBA.activeRail)
            assertSame(newLast.viewAB, middle.viewAB.activeRail)
            assertSame(middle.viewBA, newLast.viewBA.activeRail)
            assertSame(right.viewAB, newLast.viewAB.activeRail)
            assertSame(newLast.viewBA, right.viewBA.activeRail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private fun replacementMap(source: CoreRail, replacementId: java.util.UUID) = mapOf(
        RailEndpointRef(source.id, RailEndpoint.A) to RailEndpointRef(replacementId, RailEndpoint.A),
        RailEndpointRef(source.id, RailEndpoint.B) to RailEndpointRef(replacementId, RailEndpoint.B)
    )

    private fun straightCurve(fromX: Double, toX: Double): PrimeCurve {
        return lineCurve(Vector3d(fromX, 0.0, 0.0), Vector3d(toX, 0.0, 0.0))
    }

    private fun lineCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(
                Vector3d(to),
                Vector3d(tangent),
                Vector3d(normal),
                from.distance(to)
            )
        ))
    }
}
