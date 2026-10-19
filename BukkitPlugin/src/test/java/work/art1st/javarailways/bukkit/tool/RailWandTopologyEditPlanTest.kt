package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.core.physics.train.setControllerForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.controller.ManualController
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.api.util.annotation.Heavy
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively

class RailWandTopologyEditPlanTest {
    @Test
    fun `failed owner edit keeps physics excluded through cleanup and releases the frame gate`() {
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        try {
            world.physicsCoordinator.start()
            val before = world.physicsCoordinator.captureCurrentTicket()
            var cleaned = false
            val failure = assertThrows(IllegalStateException::class.java) {
                IsolatedSimulationThreadMarker.run {
                    world.processRailWandEdit {
                        try {
                            assertSame(before, world.physicsCoordinator.wakeFromMainThread())
                            error("edit failed")
                        } finally {
                            world.physicsCoordinator.ensureBetweenFrames("RailWand failed edit cleanup")
                            assertSame(before, world.physicsCoordinator.wakeFromMainThread())
                            cleaned = true
                        }
                    }
                }
            }
            assertEquals("edit failed", failure.message)
            assertTrue(cleaned)
            val next = world.physicsCoordinator.stepSimulation(50.0)
            assertTimeoutPreemptively(Duration.ofSeconds(5)) { next.await() }
            assertEquals(before.epoch + 1L, world.latestWorldFrame().physicsEpoch)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `paused track preparation stays unpublished while another world advances a moving train`() =
        delayedTrackPreparation(staleBeforeCommit = false)

    @Test
    fun `delayed track candidate rejects stale owner commit without changing the current topology`() =
        delayedTrackPreparation(staleBeforeCommit = true)

    @OptIn(Heavy::class)
    private fun delayedTrackPreparation(staleBeforeCommit: Boolean) {
        val editedWorld = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val otherWorld = CoreWorld.createEmpty(
            TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), System.currentTimeMillis()),
            PhysicsCoordinator(1, automaticFallback = false)
        )
        val workers = Executors.newFixedThreadPool(2)
        val samplingEntered = CountDownLatch(1)
        val releaseSampling = CountDownLatch(1)
        try {
            val existing = editedWorld.createRailForTest(straightCurve(0.0, 10.0))
            val baseRoot = editedWorld.topologyState
            val runningRail = otherWorld.createRailForTest(straightCurve(0.0, 100.0))
            val train = createTrainWithConfig("other-world-moving", runningRail, otherWorld, listOf(10.0))
            val origin = train.publishedFrame.head.arcLengthInAbMeters
            train.setControllerForTest(ManualController(train).also { it.setThrottle(1.0) })
            otherWorld.physicsCoordinator.start()
            editedWorld.physicsCoordinator.start()
            val geometry = straightCurve(10.0, 20.0)
            // Suspend the real preparation's geometry sampling, not a lock-free callback proxy.
            val delayedGeometry = object : PrimeCurve by geometry {
                override fun stream(): Stream<Curve.Point> {
                    samplingEntered.countDown()
                    check(releaseSampling.await(10, TimeUnit.SECONDS)) { "timed out releasing track geometry preparation" }
                    return geometry.stream()
                }
            }
            val preparation = workers.submit(Callable {
                val batch = editedWorld.prepareRailCreation(delayedGeometry)
                val connections = RailWandExplicitConnections.capture(editedWorld, batch, addedConnections = listOf(
                    RailEndpointRef(existing.id, RailEndpoint.B) to
                        RailEndpointRef(batch.creations.single().railId, RailEndpoint.A)))
                batch to RailWandTopologyEditPlan.capturePreparedExplicitConnections(editedWorld, batch, connections)
            })
            assertTrue(samplingEntered.await(5, TimeUnit.SECONDS))
            assertFalse(preparation.isDone)

            val frame = workers.submit(Callable {
                // Manual control publishes the newly accelerated speed and uses it in the same epoch.
                otherWorld.physicsCoordinator.stepSimulation(200.0).await()
                val completed = otherWorld.physicsCoordinator.stepSimulation(200.0).await()
                otherWorld.latestWorldFrame().also { assertEquals(completed.epoch, it.physicsEpoch) }
            }).get(5, TimeUnit.SECONDS)

            assertEquals(2L, frame.physicsEpoch)
            assertEquals(otherWorld.topologyState.revision, frame.topologyRevision)
            val publishedTrain = frame.trains.single()
            assertEquals(train.id, publishedTrain.trainId)
            assertEquals(0.4, publishedTrain.speedMetersPerSecond, 1.0e-9)
            assertEquals(origin + 0.12, publishedTrain.head.arcLengthInAbMeters, 1.0e-9)
            assertFalse(preparation.isDone, "other-world physics must finish while sampling is still paused")
            assertSame(baseRoot, editedWorld.topologyState)
            assertEquals(listOf(existing), editedWorld.allRails.toList())
            releaseSampling.countDown()
            val (batch, plan) = preparation.get(5, TimeUnit.SECONDS)
            val createdId = batch.creations.single().railId
            assertSame(baseRoot, editedWorld.topologyState)
            assertEquals(null, editedWorld.getRail(createdId), "fully prepared candidate is still not published")
            var actionEntered = false
            val apply = {
                actionEntered = true
                val installed = IsolatedSimulationThreadMarker.run { editedWorld.installPreparedRails(batch) }
                plan.applyExplicitConnections(editedWorld, installed)
            }
            if (staleBeforeCommit) {
                // This intentional intervening edit is allowed; only the stale operation must be inert.
                editedWorld.createRailForTest(straightCurve(30.0, 40.0))
                val currentRoot = editedWorld.topologyState
                val currentRails = editedWorld.allRails.toList()
                assertFalse(plan.isCurrent(editedWorld))
                assertThrows(IllegalStateException::class.java) {
                    IsolatedSimulationThreadMarker.run {
                        editedWorld.processRailWandEdit { plan.commitTopology(editedWorld, apply) }
                    }
                }
                assertFalse(actionEntered)
                assertSame(currentRoot, editedWorld.topologyState)
                assertEquals(currentRails, editedWorld.allRails.toList())
                assertEquals(null, editedWorld.getRail(createdId))
            } else {
                IsolatedSimulationThreadMarker.run {
                    editedWorld.processRailWandEdit {
                        val ticket = editedWorld.physicsCoordinator.captureCurrentTicket()
                        assertSame(ticket, editedWorld.physicsCoordinator.wakeFromMainThread())
                        plan.commitTopology(editedWorld, apply)
                        assertSame(ticket, editedWorld.physicsCoordinator.wakeFromMainThread(),
                            "physics must remain excluded through post-topology metadata and undo work")
                    }
                }
                assertTrue(actionEntered)
                assertEquals(baseRoot.revision + 1L, editedWorld.topologyState.revision)
                assertEquals(setOf(existing.id, createdId), editedWorld.allRails.map { it.id }.toSet())
                assertSame(editedWorld.getRail(createdId)!!.viewAB, existing.viewAB.activeRail)
            }
        } finally {
            releaseSampling.countDown()
            editedWorld.physicsCoordinator.close()
            otherWorld.physicsCoordinator.close()
            workers.shutdownNow()
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "track-test workers did not terminate")
            } finally {
                (editedWorld.tickManager as AutoCloseable).close()
                (otherWorld.tickManager as AutoCloseable).close()
            }
        }
    }

    @Test
    fun `rail scoped mutation contains only explicitly edited rails`() {
        val world = createTestWorld()
        try {
            val target = world.createRailForTest(straightCurve(0.0, 10.0))
            val connectedPeer = world.createRailForTest(straightCurve(10.0, 20.0))
            IsolatedSimulationThreadMarker.run { CoreRail.connect(target, connectedPeer) }
            val crossing = world.createRailForTest(
                lineCurve(Vector3d(5.0, 0.0, -5.0), Vector3d(5.0, 0.0, 5.0))
            )

            val plan = RailWandTopologyEditPlan.captureRailScopedMutation(world, setOf(target.id))

            assertEquals(setOf(target.id), requireNotNull(plan.impact).railIds)
            assertTrue(requireNotNull(plan.impact).topologyNodeKeys.isEmpty())
            assertTrue(requireNotNull(plan.impact).resourceIds.isEmpty())
            assertFalse(connectedPeer.id in requireNotNull(plan.impact).railIds)
            assertFalse(crossing.id in requireNotNull(plan.impact).railIds)
            assertTrue(plan.isCurrent(world))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `rail scoped mutation becomes stale when topology root changes`() {
        val world = createTestWorld()
        try {
            val target = world.createRailForTest(straightCurve(0.0, 10.0))
            val plan = RailWandTopologyEditPlan.captureRailScopedMutation(world, setOf(target.id))

            world.createRailForTest(straightCurve(20.0, 30.0))

            assertFalse(plan.isCurrent(world))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

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
            val plan = RailWandTopologyEditPlan.prepareReplacementOrRemoval(world, listOf(affected.id))

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
            IsolatedSimulationThreadMarker.run { CoreRail.connect(selected, connectedPeer) }
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
            IsolatedSimulationThreadMarker.run { CoreRail.connect(source, peer) }
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
            IsolatedSimulationThreadMarker.run {
                world.processRailWandEdit {
                    plan.commitTopology(world) {
                        replacement = world.installPreparedRails(prepared).single()
                        plan.applyExplicitConnections(world, listOf(replacement))
                        world.removeRail(source)
                    }
                }
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
            IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, straight) }
            IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, branch) }
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
            IsolatedSimulationThreadMarker.run {
                world.processRailWandEdit {
                    plan.commitTopology(world) {
                        replacement = world.installPreparedRails(prepared).single()
                        plan.applyExplicitConnections(world, listOf(replacement))
                        world.removeRail(stem)
                    }
                }
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
            IsolatedSimulationThreadMarker.run { CoreRail.connect(source, peer) }
            source.viewAB.activeRail = null
            val prepared = world.prepareRailCreation(straightCurve(0.0, 10.0), setOf(source.id))
            val connectionPlan = RailWandExplicitConnections.capture(
                world,
                prepared,
                replacementEndpoints = replacementMap(source, prepared.creations.single().railId)
            )

            lateinit var replacement: CoreRail
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
                replacement = world.installPreparedRails(prepared).single()
                connectionPlan.apply(world, listOf(replacement))
                world.removeRail(source)
            } }

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
            IsolatedSimulationThreadMarker.run { CoreRail.connect(left, first) }
            IsolatedSimulationThreadMarker.run { CoreRail.connect(first, second) }
            IsolatedSimulationThreadMarker.run { CoreRail.connect(second, right) }
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
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
                installed = world.installPreparedRails(prepared)
                connectionPlan.apply(world, installed)
                world.removeRail(first)
                world.removeRail(second)
            } }
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
