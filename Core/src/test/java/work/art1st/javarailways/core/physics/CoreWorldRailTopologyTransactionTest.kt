package work.art1st.javarailways.core.physics

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.PreparedRailEndpointConnection
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class CoreWorldRailTopologyTransactionTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `outer topology commit excludes wakes and refreshes completed epoch observation`(preparedEdit: Boolean) {
        val clock = AtomicLong(1L)
        val coordinator = PhysicsCoordinator(1, nanoTime = clock::get, automaticFallback = false)
        val world = createTestWorld(coordinator)
        val wakeThread = Executors.newSingleThreadExecutor()
        try {
            val rail = IsolatedSimulationThreadMarker.run { world.createRailForTest(straightCurve(0.0, 10.0)) }
            val oldRoot = world.topologyState
            val prepared = world.prepareRailRemovalTopologyUpdate(oldRoot, setOf(rail.id))
            coordinator.start()
            val ticket = coordinator.stepSimulation(50.0)
            ticket.completion.get(5, TimeUnit.SECONDS)
            val previous = world.latestWorldFrame()
            val edit = {
                coordinator.ensureBetweenFrames("test topology commit")
                IsolatedSimulationThreadMarker.run { world.railTopologyTransaction { world.removeRail(rail) } }
                assertSame(previous, world.latestWorldFrame(), "nested edit must not publish an intermediate world")
                clock.addAndGet(TimeUnit.SECONDS.toNanos(1))
                wakeThread.submit {
                    coordinator.timerWake()
                    assertSame(ticket, coordinator.wakeFromMainThread())
                }.get(5, TimeUnit.SECONDS)
                assertEquals(ticket.epoch, coordinator.captureCurrentTicket().epoch)
                "committed"
            }
            val result = IsolatedSimulationThreadMarker.run {
                if (preparedEdit) world.railTopologyTransaction(prepared, edit)
                else world.railTopologyTransaction(edit)
            }
            assertEquals("committed", result)
            assertEquals(oldRoot.revision + 1L, world.topologyRevision)
            assertEquals(world.topologyRevision, world.latestWorldFrame().topologyRevision)
            assertEquals(ticket.epoch, world.latestWorldFrame().physicsEpoch)
            assertEquals(previous.topologyRevision, oldRoot.revision)
            assertTrue(world.allRails.isEmpty())
            val next = coordinator.stepSimulation(50.0)
            next.completion.get(5, TimeUnit.SECONDS)
            assertEquals(ticket.epoch + 1L, next.epoch)
        } finally {
            wakeThread.shutdownNow()
            coordinator.close()
            world.releaseRuntimeBinding()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `successful changed transactions notify once before publication with only touched junction dependencies`() {
        val world = createTestWorld()
        val source = world.createRailForTest(straightCurve(0.0, 10.0))
        val straight = world.createRailForTest(straightCurve(10.0, 20.0))
        val far = world.createRailForTest(straightCurve(20.0, 30.0))
        val isolated = world.createRailForTest(straightCurve(100.0, 110.0))
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            RailView.connect(source.viewAB, straight.viewBA)
            RailView.connect(straight.viewAB, far.viewBA)
        } }
        val before = world.topologyState
        val changes = mutableListOf<work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange>()
        val registration = world.registerTopologyChangeListener { change ->
            assertTrue(world.topologyState === change.previous)
            changes += change
        }
        lateinit var branch: CoreRail
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            branch = world.createRailInTransaction(UUID.randomUUID(), straightCurve(10.0, 40.0))
            RailView.connect(source.viewAB, branch.viewBA)
        } }
        val change = changes.single()
        assertTrue(change.previous === before)
        assertTrue(change.current === world.topologyState)
        assertEquals(setOf(source.id, straight.id, branch.id), change.affectedRailIds)
        assertFalse(isolated.id in change.affectedRailIds)
        assertFalse(far.id in change.affectedRailIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (change.affectedRailIds as MutableSet).clear()
        }
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction { } }
        assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction { error("rejected before mutation") } }
        }
        assertEquals(1, changes.size)
        registration.close()
        registration.close()
        IsolatedSimulationThreadMarker.run { world.removeRail(isolated) }
        assertEquals(1, changes.size)
    }

    @Test
    fun `prepared deletion reports removed rail and former connected peer but not peer far endpoint`() {
        val world = createTestWorld()
        val removed = world.createRailForTest(straightCurve(0.0, 10.0))
        val neighbor = world.createRailForTest(straightCurve(10.0, 20.0))
        val far = world.createRailForTest(straightCurve(20.0, 30.0))
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            RailView.connect(removed.viewAB, neighbor.viewBA)
            RailView.connect(neighbor.viewAB, far.viewBA)
        } }
        val prepared = world.prepareRailRemovalTopologyUpdate(world.topologyState, setOf(removed.id))
        val changes = mutableListOf<work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange>()
        world.registerTopologyChangeListener(changes::add)
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(prepared) { world.removeRail(removed) } }
        assertEquals(setOf(removed.id, neighbor.id), changes.single().affectedRailIds)
        assertTrue(changes.single() === prepared.topologyChange)
        assertFalse(removed.id in changes.single().current.railProjection)
        assertTrue(removed.id in changes.single().previous.railProjection)
    }

    @Test
    fun `new at grade crossing notifies both physical participants`() {
        val world = createTestWorld()
        val existing = world.createRailForTest(straightCurve(-10.0, 10.0))
        val changes = mutableListOf<work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange>()
        world.registerTopologyChangeListener(changes::add)
        val added = world.createRailForTest(crossingCurve(-10.0, 10.0))
        assertEquals(setOf(existing.id, added.id), changes.single().affectedRailIds)
    }

    @Test
    fun `failed changed transaction never publishes a topology notification`() {
        val world = createTestWorld()
        var callbacks = 0
        val before = world.topologyState
        world.registerTopologyChangeListener { callbacks++ }
        assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
                world.createRailInTransaction(UUID.randomUUID(), straightCurve(0.0, 10.0))
                error("failed mutation")
            } }
        }
        assertEquals(0, callbacks)
        assertTrue(world.topologyState === before)
        assertEquals(PhysicsCoordinatorStatus.STALLED, world.physicsCoordinator.status)
    }

    @Test
    fun `CoreRail has no public constructor bypass`() {
        assertTrue(CoreRail::class.java.constructors.all { it.isSynthetic })
        assertTrue(CoreWorld::class.java.methods.none { it.name == "createRailForBootstrap" })
        val transactionFactories = CoreWorld::class.java.declaredMethods.filter {
            it.name.startsWith("createRailInTransaction")
        }
        assertEquals(1, transactionFactories.size)
        assertTrue(transactionFactories.single().isSynthetic)
    }

    @Test
    fun `transaction-only rail creation rejects calls outside a transaction`() {
        val world = createTestWorld()

        assertThrows(IllegalStateException::class.java) {
            world.createRailInTransaction(UUID.randomUUID(), straightCurve(0.0, 10.0))
        }

        assertTrue(world.allRails.isEmpty())
        assertEquals(0L, world.topologyRevision)
    }

    @Test
    fun `duplicate rail identity is rejected before mutation`() {
        val world = createTestWorld()
        val existing = world.createRailForTest(straightCurve(0.0, 10.0))
        val beforeRevision = world.topologyRevision

        assertThrows(IllegalArgumentException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
                world.createRailInTransaction(existing.id, straightCurve(10.0, 20.0))
            } }
        }

        assertEquals(listOf(existing), world.allRails.toList())
        assertEquals(beforeRevision, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
    }

    @Test
    fun `structural revision advances for direct edits and once per outer transaction`() {
        val world = createTestWorld()
        assertEquals(0L, world.topologyRevision)

        val first = world.createRailForTest(straightCurve(0.0, 10.0))
        assertEquals(1L, world.topologyRevision)
        assertTrue(world.getRail(first.id) === first)

        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            world.createRailInTransaction(UUID.randomUUID(), straightCurve(10.0, 20.0))
            world.createRailInTransaction(UUID.randomUUID(), straightCurve(20.0, 30.0))
        } }
        assertEquals(2L, world.topologyRevision)

        IsolatedSimulationThreadMarker.run { world.removeRail(first) }
        assertEquals(3L, world.topologyRevision)
    }

    @Test
    fun `runtime branch selection does not publish a structural revision`() {
        val world = createTestWorld()
        val source = world.createRailForTest(straightCurve(0.0, 10.0))
        val first = world.createRailForTest(straightCurve(10.0, 20.0))
        val second = world.createRailForTest(straightCurve(10.0, 30.0))

        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            RailView.connect(source.viewAB, first.viewBA)
            RailView.connect(source.viewAB, second.viewBA)
            source.viewAB.activeRail = first.viewAB
        } }
        val structuralRevision = world.topologyRevision

        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            source.viewAB.activeRail = second.viewAB
        } }

        assertEquals(structuralRevision, world.topologyRevision)
    }

    @Test
    fun `failed transaction without structural mutation keeps revision and world usable`() {
        val world = createTestWorld()
        val before = world.topologyRevision

        assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction<Unit> { error("partial structural commit") } }
        }

        assertEquals(before, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)

        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction { } }
        assertEquals(before, world.topologyRevision)
    }

    @Test
    fun `failed transaction after structural mutation keeps revision and stalls world`() {
        val world = createTestWorld()
        val before = world.topologyRevision

        assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
                world.createRailInTransaction(UUID.randomUUID(), straightCurve(0.0, 10.0))
                error("partial structural commit")
            } }
        }

        assertEquals(before, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.STALLED, world.physicsCoordinator.status)
        assertThrows(IllegalStateException::class.java) { world.publishWorldFrame(0L) }
        val editFailure = assertThrows(RailTopologyFaultedException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction { } }
        }
        assertEquals(
            RailTopologyFaultReason.TRANSACTION_FAILED_AFTER_STRUCTURAL_MUTATION,
            editFailure.fault.reason
        )
        assertEquals(before, editFailure.fault.lastPublishedRevision)
        assertEquals(IllegalStateException::class.qualifiedName, editFailure.fault.failureType)
        assertEquals("partial structural commit", editFailure.fault.failureMessage)
    }

    @Test
    fun `prepared failure before mutation keeps exact root and world usable`() {
        val world = createTestWorld()
        val rail = world.createRailForTest(straightCurve(0.0, 10.0))
        val root = world.topologyState
        val prepared = world.prepareRailRemovalTopologyUpdate(root, setOf(rail.id))

        assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(prepared) { error("failed before mutation") } }
        }

        assertTrue(world.topologyState === root)
        assertTrue(world.getRail(rail.id) === rail)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction { } }
    }

    @Test
    fun `prepared action rejects an undeclared rail identity before mutation`() {
        val world = createTestWorld()
        val rail = world.createRailForTest(straightCurve(0.0, 10.0))
        val root = world.topologyState
        val prepared = world.prepareRailRemovalTopologyUpdate(root, setOf(rail.id))
        val undeclaredId = UUID.randomUUID()

        val failure = assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(prepared) {
                world.createRailInTransaction(undeclaredId, straightCurve(20.0, 30.0))
            } }
        }

        assertTrue(failure.message.orEmpty().contains("undeclared rail identity"))
        assertEquals(null, world.getRail(undeclaredId))
        assertTrue(world.getRail(rail.id) === rail)
        assertTrue(world.topologyState === root)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
    }

    @Test
    fun `prepared created rail and raw crossing can roll back completely`() {
        val world = createTestWorld()
        world.createRailForTest(straightCurve(-5.0, 5.0))
        val root = world.topologyState
        val batch = world.prepareRailCreation(crossingCurve(-5.0, 5.0))
        val plan = world.capturePreparedRailConnectionPlan(batch, emptyList())
        val prepared = plan.prepareTopologyUpdate()

        val failure = assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(prepared) {
                val created = world.installPreparedRails(batch).single()
                plan.apply(world, listOf(created))
                world.removeRail(created)
                error("failed after complete creation rollback")
            } }
        }

        assertEquals("failed after complete creation rollback", failure.message)
        assertEquals(1, world.allRails.size)
        assertEquals(null, world.getRail(batch.creations.single().railId))
        assertTrue(world.topologyState === root)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
    }

    @Test
    fun `prepared topology update carries complete base and target rail snapshots`() {
        val world = createTestWorld()
        val existing = world.createRailForTest(straightCurve(0.0, 10.0))
        val batch = world.prepareRailCreation(straightCurve(20.0, 30.0))
        val connectionPlan = world.capturePreparedRailConnectionPlan(batch, emptyList())
        val prepared = connectionPlan.prepareTopologyUpdate()

        assertEquals(listOf(existing.save()), prepared.baseRailSnapshot)
        assertEquals(
            setOf(existing.id.toString(), batch.creations.single().railId.toString()),
            prepared.targetRailSnapshot.mapTo(linkedSetOf(), CoreRail.SavedRailV1::id)
        )

        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(prepared) {
            val installed = world.installPreparedRails(batch)
            connectionPlan.apply(world, installed)
        } }
        assertEquals(
            prepared.targetRailSnapshot.associateBy(CoreRail.SavedRailV1::id),
            world.allRails.map(CoreRail::save).associateBy(CoreRail.SavedRailV1::id)
        )
    }

    @Test
    fun `prepared target snapshot preserves declared connection order and active branch`() {
        val world = createTestWorld()
        val stem = world.createRailForTest(straightCurve(0.0, 10.0))
        val first = world.createRailForTest(straightCurve(10.0, 20.0))
        val second = world.createRailForTest(straightCurve(10.0, 30.0))
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            RailView.connect(stem.viewAB, first.viewBA)
            RailView.connect(stem.viewAB, second.viewBA)
            stem.viewAB.activeRail = first.viewAB
        } }
        val batch = world.prepareRailCreation(straightCurve(40.0, 50.0))
        val stemB = RailEndpointRef(stem.id, RailEndpoint.B)
        val firstA = RailEndpointRef(first.id, RailEndpoint.A)
        val secondA = RailEndpointRef(second.id, RailEndpoint.A)
        val connectionPlan = world.capturePreparedRailConnectionPlan(
            batch,
            listOf(
                PreparedRailEndpointConnection(stemB, listOf(secondA, firstA), secondA),
                PreparedRailEndpointConnection(firstA, listOf(stemB), stemB),
                PreparedRailEndpointConnection(secondA, listOf(stemB), stemB)
            )
        )
        val prepared = connectionPlan.prepareTopologyUpdate()
        val targetStem = prepared.targetRailSnapshot.single { it.id == stem.id.toString() }

        assertEquals(listOf(second.id.toString(), first.id.toString()), targetStem.nextRailAtB)
        assertEquals(second.id.toString(), targetStem.activeTrackAtB)
        assertThrows(UnsupportedOperationException::class.java) {
            (targetStem.nextRailAtB as MutableList<String>).add(UUID.randomUUID().toString())
        }

        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(prepared) {
            val installed = world.installPreparedRails(batch)
            connectionPlan.apply(world, installed)
        } }
        assertEquals(targetStem, stem.save())
    }

    @Test
    fun `prepared failure after complete structural rollback keeps world usable`() {
        val world = createTestWorld()
        val first = world.createRailForTest(straightCurve(0.0, 10.0))
        val second = world.createRailForTest(straightCurve(10.0, 20.0))
        val current = world.allRails.map(CoreRail::save)
        val target = connectedSnapshot(current, first.id, second.id)
        val plan = world.prepareRailWorldSnapshotRestore(current, target)
        val root = world.topologyState

        assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(plan.topologyUpdate) {
                plan.apply(world)
                first.viewAB.activeRail = null
                first.viewAB.mutableNextRails.clear()
                second.viewBA.activeRail = null
                second.viewBA.mutableNextRails.clear()
                error("failed after rollback")
            } }
        }

        assertTrue(world.topologyState === root)
        assertTrue(first.viewAB.mutableNextRails.isEmpty())
        assertTrue(second.viewBA.mutableNextRails.isEmpty())
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction { } }
    }

    @Test
    fun `prepared failure after incomplete structural rollback faults world`() {
        val world = createTestWorld()
        val first = world.createRailForTest(straightCurve(0.0, 10.0))
        val second = world.createRailForTest(straightCurve(10.0, 20.0))
        val current = world.allRails.map(CoreRail::save)
        val plan = world.prepareRailWorldSnapshotRestore(
            current,
            connectedSnapshot(current, first.id, second.id)
        )
        val revision = world.topologyRevision

        val failure = assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction(plan.topologyUpdate) {
                plan.apply(world)
                first.viewAB.activeRail = null
                first.viewAB.mutableNextRails.clear()
                error("failed after partial rollback")
            } }
        }

        assertEquals("failed after partial rollback", failure.message)
        assertEquals(revision, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.STALLED, world.physicsCoordinator.status)
        val editFailure = assertThrows(RailTopologyFaultedException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction { } }
        }
        assertEquals("failed after partial rollback", editFailure.fault.failureMessage)
    }

    @Test
    fun `caught nested transaction failure aborts outer publication`() {
        val world = createTestWorld()
        val before = world.topologyRevision

        val failure = assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
                runCatching {
                    world.railTopologyTransaction<Unit> { error("nested failure") }
                }
            } }
        }

        assertTrue(failure.message.orEmpty().contains("nested rail topology transaction failed"))
        assertEquals(before, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
    }

    @Test
    fun `world frame capture waits for topology revision publication`() {
        val world = createTestWorld()
        var primaryFailure: Throwable? = null
        try {
            val revisionBefore = world.topologyRevision
            val createdRailId = UUID.randomUUID()
            val frame = readWhileTopologyMutationIsBlocked(
                world = world,
                mutation = {
                    world.createRailInTransaction(createdRailId, straightCurve(0.0, 10.0))
                },
                read = { world.captureWorldFrame(0L) },
                prematureCompletionMessage = "world frame crossed an incomplete topology transaction"
            )

            assertEquals(revisionBefore + 1L, frame.topologyRevision)
            assertEquals(world.topologyRevision, frame.topologyRevision)
            assertEquals(world.topologyRevision, world.latestWorldFrame().topologyRevision)
            assertEquals(0L, world.latestWorldFrame().physicsEpoch)
            assertEquals(setOf(createdRailId), world.allRails.mapTo(linkedSetOf()) { it.id })
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closeWorldRuntime(world, primaryFailure)
        }
    }

    @Test
    fun `world readers cannot observe an infrastructure transaction mid-commit`() {
        val world = createTestWorld()
        var primaryFailure: Throwable? = null
        try {
            val initialRail = world.createRailForTest(straightCurve(0.0, 10.0))
            val revisionBefore = world.topologyRevision
            val createdRailId = UUID.randomUUID()
            val railCount = readWhileTopologyMutationIsBlocked(
                world = world,
                mutation = {
                    world.createRailInTransaction(createdRailId, straightCurve(10.0, 20.0))
                },
                read = { world.allRails.size },
                prematureCompletionMessage = "reader observed a partially committed rail topology"
            )

            assertEquals(2, railCount)
            assertEquals(revisionBefore + 1L, world.topologyRevision)
            assertEquals(
                setOf(initialRail.id, createdRailId),
                world.allRails.mapTo(linkedSetOf()) { it.id }
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closeWorldRuntime(world, primaryFailure)
        }
    }

    private fun <T> readWhileTopologyMutationIsBlocked(
        world: CoreWorld,
        mutation: () -> Unit,
        read: () -> T,
        prematureCompletionMessage: String
    ): T {
        val mutationReached = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val readerThread = CompletableFuture<Thread>()
        val executor = Executors.newFixedThreadPool(2)
        var writer: Future<*>? = null
        var reader: Future<T>? = null
        var primaryFailure: Throwable? = null
        try {
            val activeWriter = executor.submit {
                IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
                    mutation()
                    mutationReached.countDown()
                    check(releaseMutation.await(10, TimeUnit.SECONDS))
                } }
            }
            writer = activeWriter
            assertTrue(mutationReached.await(5, TimeUnit.SECONDS), "writer did not reach the in-progress transaction")
            val activeReader = executor.submit<T> {
                readerThread.complete(Thread.currentThread())
                read()
            }
            reader = activeReader
            awaitRailTopologyQueue(world, readerThread.get(5, TimeUnit.SECONDS))
            assertFalse(activeReader.isDone, prematureCompletionMessage)
            releaseMutation.countDown()

            return activeReader.get(5, TimeUnit.SECONDS).also {
                activeWriter.get(5, TimeUnit.SECONDS)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseMutation.countDown()
            val cleanupFailures = mutableListOf<Throwable>()
            captureCleanupFailure(cleanupFailures) { writer?.get(5, TimeUnit.SECONDS) }
            captureCleanupFailure(cleanupFailures) { reader?.get(5, TimeUnit.SECONDS) }
            executor.shutdownNow()
            captureCleanupFailure(cleanupFailures) {
                check(executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    "topology transaction test executor did not terminate"
                }
            }
            cleanupFailures.firstOrNull()?.let { cleanupFailure ->
                cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private fun captureCleanupFailure(failures: MutableList<Throwable>, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            failures += failure
        }
    }

    private fun closeWorldRuntime(world: CoreWorld, primaryFailure: Throwable?) {
        val failures = mutableListOf<Throwable>()
        captureCleanupFailure(failures) { world.physicsCoordinator.close() }
        captureCleanupFailure(failures) { (world.tickManager as AutoCloseable).close() }
        failures.firstOrNull()?.let { cleanupFailure ->
            failures.drop(1).forEach(cleanupFailure::addSuppressed)
            primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
        }
    }

    private fun awaitRailTopologyQueue(world: CoreWorld, readerThread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!world.isQueuedForRailTopologyAccess(readerThread)) {
            check(System.nanoTime() < deadline) {
                "reader thread did not queue for rail topology access"
            }
            Thread.onSpinWait()
        }
    }

    @Test
    fun `prepared edit delayed in one world does not block another world`() {
        val editedWorld = createTestWorld()
        val otherWorld = createTestWorld()
        try {
            val removed = editedWorld.createRailForTest(straightCurve(0.0, 10.0))
            otherWorld.physicsCoordinator.start()
            val prepared = editedWorld.prepareRailRemovalTopologyUpdate(
                editedWorld.topologyState,
                setOf(removed.id)
            )
            val mutationReached = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writer = CompletableFuture.runAsync {
                IsolatedSimulationThreadMarker.run {
                    editedWorld.railTopologyTransaction(prepared) {
                        editedWorld.removeRail(removed)
                        mutationReached.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }

            check(mutationReached.await(5, TimeUnit.SECONDS))
            val completedEpoch = otherWorld.physicsCoordinator.wakeFromMainThread().await().epoch
            val unrelatedFrame = otherWorld.publishWorldFrame(completedEpoch)

            assertEquals(0L, unrelatedFrame.topologyRevision)
            assertEquals(1L, unrelatedFrame.physicsEpoch)
            release.countDown()
            writer.get(5, TimeUnit.SECONDS)
            assertTrue(editedWorld.allRails.isEmpty())
            assertTrue(editedWorld.topologyState === prepared.targetRoot)
        } finally {
            editedWorld.physicsCoordinator.close()
            (editedWorld.tickManager as AutoCloseable).close()
            otherWorld.physicsCoordinator.close()
            (otherWorld.tickManager as AutoCloseable).close()
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

    private fun crossingCurve(fromZ: Double, toZ: Double): PrimeCurve {
        val normal = Vector3d(0.0, 1.0, 0.0)
        val tangent = Vector3d(0.0, 0.0, 1.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(0.0, 0.0, fromZ), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(Vector3d(0.0, 0.0, toZ), Vector3d(tangent), Vector3d(normal), toZ - fromZ)
        ))
    }

    private fun connectedSnapshot(
        current: List<CoreRail.SavedRailV1>,
        firstId: UUID,
        secondId: UUID
    ): List<CoreRail.SavedRailV1> = current.map { saved ->
        when (saved.id) {
            firstId.toString() -> saved.copy(
                nextRailAtB = listOf(secondId.toString()),
                activeTrackAtB = secondId.toString()
            )
            secondId.toString() -> saved.copy(
                nextRailAtA = listOf(firstId.toString()),
                activeTrackAtA = firstId.toString()
            )
            else -> saved
        }
    }
}
