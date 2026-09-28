package work.art1st.javarailways.core.physics.rail

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class PreparedRailWorldSnapshotPlanTest {
    @Test
    fun `Core derives surviving endpoint remap after same UUID geometry reversal`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            val peer = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))
            CoreRail.connect(source, peer)
            val current = world.allRails.map(CoreRail::save)
            val reversed = line(Vector3d(10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
            val target = current.map { saved ->
                if (saved.id == source.id.toString()) saved.copy(
                    curveAB = reversed.save() as PrimeCurveImpl.SavedPrimeCurveV1,
                    nextRailAtA = saved.nextRailAtB,
                    nextRailAtB = saved.nextRailAtA,
                    activeTrackAtA = saved.activeTrackAtB,
                    activeTrackAtB = saved.activeTrackAtA
                ) else saved
            }

            val plan = world.prepareRailWorldSnapshotRestore(current, target)

            assertEquals(setOf(RailEndpointRef(peer.id, RailEndpoint.A)), plan.changedExistingEndpoints)
            world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }
            val restored = requireNotNull(world.getRail(source.id))
            assertEquals(target.first { it.id == source.id.toString() }.nextRailAtA, restored.save().nextRailAtA)
            assertEquals(target.first { it.id == peer.id.toString() }.nextRailAtA, peer.save().nextRailAtA)
            assertEquals(Vector3d(10.0, 0.0, 0.0), restored.curve.getPointAt(0.0).position)
            assertEquals(
                Vector3d(0.0, 0.0, 0.0),
                restored.curve.getPointAt(restored.curve.totalArcLength).position
            )
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `connection-only snapshot restore derives endpoints and removes endpoint pseudo crossing`() {
        val world = createTestWorld()
        try {
            val first = world.createRailForTest(line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            val second = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(10.0, 0.0, 10.0)))
            assertEquals(1, world.topologyState.atGradeCrossings.size)
            val current = world.allRails.map(CoreRail::save)
            val target = current.map { saved ->
                when (saved.id) {
                    first.id.toString() -> saved.copy(
                        nextRailAtB = listOf(second.id.toString()),
                        activeTrackAtB = second.id.toString()
                    )
                    second.id.toString() -> saved.copy(
                        nextRailAtA = listOf(first.id.toString()),
                        activeTrackAtA = first.id.toString()
                    )
                    else -> saved
                }
            }

            val plan = world.prepareRailWorldSnapshotRestore(current, target)
            val revision = world.topologyRevision

            assertEquals(
                setOf(
                    RailEndpointRef(first.id, RailEndpoint.B),
                    RailEndpointRef(second.id, RailEndpoint.A)
                ),
                plan.changedExistingEndpoints
            )
            world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }
            assertEquals(target.associateBy { it.id }, world.allRails.map(CoreRail::save).associateBy { it.id })
            assertTrue(world.topologyState.atGradeCrossings.isEmpty())
            assertEquals(revision + 1L, world.topologyRevision)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `active-only snapshot restore retains exact topology root and revision`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(
                line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
            )
            val first = world.createRailForTest(
                line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0))
            )
            val second = world.createRailForTest(
                line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 5.0))
            )
            CoreRail.connect(source, first)
            CoreRail.connect(source, second)
            source.viewAB.activeRail = first.viewAB
            val current = world.allRails.map(CoreRail::save)
            val target = current.map { saved ->
                if (saved.id == source.id.toString()) {
                    saved.copy(activeTrackAtB = second.id.toString())
                } else {
                    saved
                }
            }
            val root = world.topologyState
            val plan = world.prepareRailWorldSnapshotRestore(current, target)

            assertSame(root, plan.topologyUpdate.targetRoot)
            world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }

            assertSame(root, world.topologyState)
            assertEquals(root.revision, world.topologyRevision)
            assertSame(second.viewAB, source.viewAB.activeRail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `same UUID replacement restores reciprocal connection and active selection`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            val peer = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))
            CoreRail.connect(source, peer)
            val current = world.allRails.map(CoreRail::save)
            val curved = PrimeCurve.fromPoints(listOf(
                Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
                Curve.RawPoint(Vector3d(5.0, 0.0, 1.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 5.1),
                Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.2)
            ))
            val target = current.map { saved ->
                if (saved.id == source.id.toString()) saved.copy(
                    curveAB = curved.save() as PrimeCurveImpl.SavedPrimeCurveV1
                ) else saved
            }
            val plan = world.prepareRailWorldSnapshotRestore(current, target)

            world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }

            val restored = requireNotNull(world.getRail(source.id))
            assertSame(peer.viewAB, restored.viewAB.activeRail)
            assertSame(restored.viewBA, peer.viewBA.activeRail)
            assertEquals(target.first { it.id == source.id.toString() }.nextRailAtB, restored.save().nextRailAtB)
            assertEquals(target.first { it.id == peer.id.toString() }.nextRailAtA, peer.save().nextRailAtA)
            assertEquals(1.0, restored.curve.getPointAt(5.1).position.z(), 1.0e-9)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `same stable UUID replacement installs prepared geometry and exact crossing`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(line(Vector3d(0.0, 0.0, 5.0), Vector3d(10.0, 0.0, 5.0)))
            val crossing = world.createRailForTest(line(Vector3d(5.0, 0.0, -5.0), Vector3d(5.0, 0.0, 5.0)))
            val unrelated = world.createRailForTest(line(Vector3d(30.0, 0.0, 0.0), Vector3d(40.0, 0.0, 0.0)))
            val current = world.allRails.map(CoreRail::save)
            val targetCurve = line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
            val target = current.map { saved ->
                if (saved.id == source.id.toString()) saved.copy(
                    curveAB = targetCurve.save() as PrimeCurveImpl.SavedPrimeCurveV1
                ) else saved
            }
            val plan = world.prepareRailWorldSnapshotRestore(current, target)

            assertEquals(setOf(source.id, crossing.id), plan.affectedExistingRailIds)
            assertFalse(unrelated.id in plan.affectedExistingRailIds)
            val oldSource = source
            world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }

            val restored = requireNotNull(world.getRail(source.id))
            assertNotSame(oldSource, restored)
            assertSame(crossing, world.getRail(crossing.id))
            assertSame(unrelated, world.getRail(unrelated.id))
            assertEquals(1, world.topologyState.atGradeCrossings.size)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `same UUID replacement requires the exact prepared curve before publication`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(
                line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
            )
            val current = world.allRails.map(CoreRail::save)
            val targetCurve = line(Vector3d(0.0, 0.0, 0.0), Vector3d(0.0, 0.0, 10.0))
            val target = current.map { saved ->
                saved.copy(curveAB = targetCurve.save() as PrimeCurveImpl.SavedPrimeCurveV1)
            }
            val root = world.topologyState
            val plan = world.prepareRailWorldSnapshotRestore(current, target)

            assertThrows(IllegalStateException::class.java) {
                world.railTopologyTransaction(plan.topologyUpdate) { }
            }

            assertSame(source, world.getRail(source.id))
            assertSame(root, world.topologyState)
            assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `stale plan rejects before replacing its stable UUID`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            val current = world.allRails.map(CoreRail::save)
            val replacement = PrimeCurve.fromPoints(listOf(
                Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
                Curve.RawPoint(Vector3d(5.0, 0.0, 1.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 5.1),
                Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.2)
            ))
            val target = listOf(current.single().copy(
                curveAB = replacement.save() as PrimeCurveImpl.SavedPrimeCurveV1
            ))
            val plan = world.prepareRailWorldSnapshotRestore(current, target)
            world.createRailForTest(line(Vector3d(20.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0)))

            assertFalse(plan.isCurrent(world))
            assertThrows(IllegalStateException::class.java) {
                world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }
            }
            assertSame(source, world.getRail(source.id))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `replacement plan becomes stale when source active selection changes`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            val first = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))
            val second = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 5.0)))
            CoreRail.connect(source, first)
            CoreRail.connect(source, second)
            source.viewAB.activeRail = first.viewAB
            val current = world.allRails.map(CoreRail::save)
            val replacement = PrimeCurve.fromPoints(listOf(
                Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
                Curve.RawPoint(Vector3d(5.0, 0.0, 1.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 5.1),
                Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.2)
            ))
            val target = current.map { saved ->
                if (saved.id == source.id.toString()) saved.copy(
                    curveAB = replacement.save() as PrimeCurveImpl.SavedPrimeCurveV1
                ) else saved
            }
            val plan = world.prepareRailWorldSnapshotRestore(current, target)

            source.viewAB.activeRail = second.viewAB

            assertFalse(plan.isCurrent(world))
            assertSame(source, world.getRail(source.id))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `RailWand undo plan ignores a later turnout selection and preserves it`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            val first = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))
            val second = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 5.0)))
            CoreRail.connect(source, first)
            CoreRail.connect(source, second)
            source.viewAB.activeRail = first.viewAB
            val current = world.allRails.map(CoreRail::save)
            val replacement = PrimeCurve.fromPoints(listOf(
                Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
                Curve.RawPoint(Vector3d(5.0, 0.0, 1.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 5.1),
                Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.2)
            ))
            val target = current.map { saved ->
                saved.copy(
                    curveAB = if (saved.id == source.id.toString()) {
                        replacement.save() as PrimeCurveImpl.SavedPrimeCurveV1
                    } else saved.curveAB,
                    activeTrackAtA = null,
                    activeTrackAtB = null
                )
            }
            val plan = world.prepareRailWorldSnapshotRestorePreservingActiveSelections(current, target)

            source.viewAB.activeRail = second.viewAB

            assertTrue(plan.isCurrent(world))
            world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }
            val restored = requireNotNull(world.getRail(source.id))
            assertSame(second.viewAB, restored.viewAB.activeRail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `RailWand undo falls back to first target connection when selected branch is removed`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(line(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)))
            val first = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)))
            val second = world.createRailForTest(line(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 5.0)))
            CoreRail.connect(source, first)
            CoreRail.connect(source, second)
            source.viewAB.activeRail = second.viewAB
            val current = world.allRails.map(CoreRail::save)
            val target = current.map { saved ->
                when (saved.id) {
                    source.id.toString() -> saved.copy(
                        nextRailAtB = listOf(first.id.toString()),
                        activeTrackAtA = null,
                        activeTrackAtB = null
                    )
                    second.id.toString() -> saved.copy(
                        nextRailAtA = emptyList(),
                        activeTrackAtA = null,
                        activeTrackAtB = null
                    )
                    else -> saved.copy(activeTrackAtA = null, activeTrackAtB = null)
                }
            }
            val plan = world.prepareRailWorldSnapshotRestorePreservingActiveSelections(current, target)

            world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }

            assertEquals(listOf(first.id.toString()), source.save().nextRailAtB)
            assertSame(first.viewAB, source.viewAB.activeRail)
            assertEquals(emptyList<String>(), second.save().nextRailAtA)
            assertEquals(null, second.viewBA.activeRail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `occupied replacement is rejected without partial mutation`() {
        val world = createTestWorld()
        try {
            val source = world.createRailForTest(line(Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0)))
            createTrainWithConfig("snapshot-occupied", source, world, listOf(8.0))
            val current = world.allRails.map(CoreRail::save)
            val replacement = line(Vector3d(0.0, 0.0, 1.0), Vector3d(30.0, 0.0, 1.0))
            val target = listOf(current.single().copy(
                curveAB = replacement.save() as PrimeCurveImpl.SavedPrimeCurveV1
            ))
            val plan = world.prepareRailWorldSnapshotRestore(current, target)

            assertThrows(IllegalArgumentException::class.java) {
                world.railTopologyTransaction(plan.topologyUpdate) { plan.apply(world) }
            }
            assertSame(source, world.getRail(source.id))
            assertEquals(current.single(), source.save())
            assertTrue(world.topologyState.atGradeCrossings.isEmpty())
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private fun line(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(to), Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), from.distance(to))
        ))
    }
}
