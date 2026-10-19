package work.art1st.javarailways.core.physics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WorldExecutionOwnerTest {
    @Test
    fun `physics worker cannot enter blocking teardown of its own executor`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(coordinator)
        val failure = AtomicReference<Throwable>()
        try {
            coordinator.registerRailwayEpochProcessor {
                failure.set(runCatching { coordinator.closeAndAwaitTermination() }.exceptionOrNull())
            }
            coordinator.start()
            val ticket = coordinator.stepSimulation(50.0)
            ticket.await()
            assertTrue(failure.get() is IllegalStateException)
            assertEquals(ticket.epoch, world.latestWorldFrame().physicsEpoch)
            work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                coordinator.closeAndAwaitTermination()
            }
        } finally {
            coordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `wrong thread is rejected before waiting for a busy world owner`() {
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val executor = Executors.newFixedThreadPool(2)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val owner = executor.submit {
                world.onOwnerForTest {
                    held.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            executor.submit {
                work.art1st.javarailways.core.util.AsyncTickTestSupport.run {
                    assertThrows(IllegalStateException::class.java) {
                        world.physicsCoordinator.processBetweenFrames { error("wrong thread entered owner") }
                    }
                    assertThrows(IllegalStateException::class.java) {
                        world.physicsCoordinator.withStructuralMutation { error("wrong thread entered owner") }
                    }
                }
            }.get(5, TimeUnit.SECONDS)
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `suspending again after nested different owner exits restores only the still held owner`() {
        val first = WorldExecutionOwner()
        val second = WorldExecutionOwner()
        val executor = Executors.newSingleThreadExecutor()
        try {
            first.run {
                second.run {
                    WorldExecutionOwner.runWithoutOwners {
                        executor.submit { second.run { first.run { true } } }.get(5, TimeUnit.SECONDS)
                    }
                }
                WorldExecutionOwner.runWithoutOwners {
                    executor.submit { first.run { second.run { true } } }.get(5, TimeUnit.SECONDS)
                }
                assertThrows(IllegalStateException::class.java) { WorldExecutionOwner.ensureNoOwner("test") }
            }
            WorldExecutionOwner.ensureNoOwner("test after exit")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `same world serializes host callbacks while another world publishes physics`() {
        val first = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val second = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waitingThread = AtomicReference<Thread>()
        try {
            second.physicsCoordinator.start()
            val held = executor.submit {
                first.onOwnerForTest {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = executor.submit {
                waitingThread.set(Thread.currentThread())
                first.onOwnerForTest { "completed" }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (waitingThread.get()?.let(first.executionOwner::isQueued) != true && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue(first.executionOwner.isQueued(requireNotNull(waitingThread.get())))
            assertFalse(queued.isDone)
            val ticket = second.physicsCoordinator.stepSimulation(50.0)
            ticket.await()
            assertEquals(ticket.epoch, second.latestWorldFrame().physicsEpoch)
            second.onOwnerForTest { assertEquals(ticket.epoch, second.latestWorldFrame().physicsEpoch) }
            release.countDown()
            held.get(5, TimeUnit.SECONDS)
            queued.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            listOf(first, second).forEach {
                it.physicsCoordinator.close()
                (it.tickManager as AutoCloseable).close()
            }
        }
    }

    @Test
    fun `platform wait releases every nested owner hold and restores after failure`() {
        val owner = WorldExecutionOwner()
        val executor = Executors.newSingleThreadExecutor()
        try {
            owner.run {
                owner.run {
                    assertThrows(IllegalStateException::class.java) {
                        WorldExecutionOwner.runWithoutOwners {
                            executor.submit { owner.run { true } }.get(5, TimeUnit.SECONDS)
                            error("wait failed")
                        }
                    }
                    assertTrue(owner.run { true })
                }
            }
            executor.submit { owner.run { true } }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
