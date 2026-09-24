package work.art1st.javarailways.core.physics

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

class CoreWorldRailTopologyTransactionTest {
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
            world.railTopologyTransaction {
                world.createRailInTransaction(existing.id, straightCurve(10.0, 20.0))
            }
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

        world.railTopologyTransaction {
            world.createRailInTransaction(UUID.randomUUID(), straightCurve(10.0, 20.0))
            world.createRailInTransaction(UUID.randomUUID(), straightCurve(20.0, 30.0))
        }
        assertEquals(2L, world.topologyRevision)

        world.removeRail(first)
        assertEquals(3L, world.topologyRevision)
    }

    @Test
    fun `runtime branch selection does not publish a structural revision`() {
        val world = createTestWorld()
        val source = world.createRailForTest(straightCurve(0.0, 10.0))
        val first = world.createRailForTest(straightCurve(10.0, 20.0))
        val second = world.createRailForTest(straightCurve(10.0, 30.0))

        world.railTopologyTransaction {
            RailView.connect(source.viewAB, first.viewBA)
            RailView.connect(source.viewAB, second.viewBA)
            source.viewAB.activeRail = first.viewAB
        }
        val structuralRevision = world.topologyRevision

        world.railTopologyTransaction {
            source.viewAB.activeRail = second.viewAB
        }

        assertEquals(structuralRevision, world.topologyRevision)
    }

    @Test
    fun `failed transaction without structural mutation keeps revision and world usable`() {
        val world = createTestWorld()
        val before = world.topologyRevision

        assertThrows(IllegalStateException::class.java) {
            world.railTopologyTransaction<Unit> { error("partial structural commit") }
        }

        assertEquals(before, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)

        world.railTopologyTransaction { }
        assertEquals(before, world.topologyRevision)
    }

    @Test
    fun `failed transaction after structural mutation keeps revision and stalls world`() {
        val world = createTestWorld()
        val before = world.topologyRevision

        assertThrows(IllegalStateException::class.java) {
            world.railTopologyTransaction {
                world.createRailInTransaction(UUID.randomUUID(), straightCurve(0.0, 10.0))
                error("partial structural commit")
            }
        }

        assertEquals(before, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.STALLED, world.physicsCoordinator.status)
        assertThrows(IllegalStateException::class.java) { world.publishWorldFrame(0L) }
        val editFailure = assertThrows(RailTopologyFaultedException::class.java) {
            world.railTopologyTransaction { }
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
    fun `caught nested transaction failure aborts outer publication`() {
        val world = createTestWorld()
        val before = world.topologyRevision

        val failure = assertThrows(IllegalStateException::class.java) {
            world.railTopologyTransaction {
                runCatching {
                    world.railTopologyTransaction<Unit> { error("nested failure") }
                }
            }
        }

        assertTrue(failure.message.orEmpty().contains("nested rail topology transaction failed"))
        assertEquals(before, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
    }

    @Test
    fun `world frame cannot observe changed topology before its revision is published`() {
        val world = createTestWorld()
        val revisionBefore = world.topologyRevision
        val changed = CountDownLatch(1)
        val release = CountDownLatch(1)

        val writer = CompletableFuture.runAsync {
            world.railTopologyTransaction {
                world.createRailInTransaction(UUID.randomUUID(), straightCurve(0.0, 10.0))
                changed.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        check(changed.await(5, TimeUnit.SECONDS))
        val reader = CompletableFuture.supplyAsync { world.publishWorldFrame(0L) }

        Thread.sleep(50)
        assertFalse(reader.isDone, "world frame crossed an incomplete topology transaction")
        release.countDown()

        val frame = reader.get(5, TimeUnit.SECONDS)
        writer.get(5, TimeUnit.SECONDS)
        assertEquals(revisionBefore + 1L, frame.topologyRevision)
        assertEquals(world.topologyRevision, frame.topologyRevision)
    }

    @Test
    fun `world readers cannot observe an infrastructure transaction mid-commit`() {
        val world = createTestWorld()
        world.createRailForTest(straightCurve(0.0, 10.0))
        val changed = CountDownLatch(1)
        val release = CountDownLatch(1)

        val writer = CompletableFuture.runAsync {
            world.railTopologyTransaction {
                world.createRailInTransaction(UUID.randomUUID(), straightCurve(10.0, 20.0))
                changed.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        check(changed.await(5, TimeUnit.SECONDS))
        val reader = CompletableFuture.supplyAsync { world.allRails.size }

        Thread.sleep(50)
        assertFalse(reader.isDone, "reader observed a partially committed rail topology")
        release.countDown()

        assertEquals(2, reader.get(5, TimeUnit.SECONDS))
        writer.get(5, TimeUnit.SECONDS)
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
