package work.art1st.javarailways.core.physics.tick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.RailwayEpochProcessor
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.testing.IsolateGlobalHalt
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PhysicsMaintenanceStallTest {
    @Test
    fun `maintenance awaits exact publication and suppresses main simulation and timer epochs`() {
        val clock = AtomicLong(1_000_000_000L)
        PhysicsCoordinator(1, nanoTime = clock::get, automaticFallback = false,
            mainHeartbeatTimeoutMillis = 1L, epochOverloadThresholdMillis = 1_000_000L).use { coordinator ->
            val world = CoreWorld.createEmpty(NoOpTickManager, coordinator)
            val workerEntered = CountDownLatch(1)
            val releaseWorker = CountDownLatch(1)
            val maintenanceEntered = CountDownLatch(1)
            val releaseMaintenance = CountDownLatch(1)
            val maintenanceThread = AtomicReference<Thread>()
            val processed = AtomicInteger()
            val executor = Executors.newSingleThreadExecutor()
            try {
                coordinator.registerRailwayEpochProcessor {
                    if (processed.incrementAndGet() == 1) {
                        workerEntered.countDown()
                        check(releaseWorker.await(5, TimeUnit.SECONDS))
                    }
                }
                coordinator.start()
                val ticket = coordinator.stepSimulation(50.0)
                assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
                val maintenance = coordinator.executionOwner.run {
                    executor.submit {
                        maintenanceThread.set(Thread.currentThread())
                        IsolatedSimulationThreadMarker.run {
                            coordinator.withPhysicsStalled {
                                assertTrue(ticket.isDone)
                                assertSame(ticket, coordinator.captureCurrentTicket())
                                assertEquals(ticket.epoch, world.latestWorldFrame().physicsEpoch)
                                coordinator.ensurePhysicsStalled("maintenance test")
                                maintenanceEntered.countDown()
                                check(releaseMaintenance.await(5, TimeUnit.SECONDS))
                            }
                        }
                    }.also {
                        awaitCondition { maintenanceThread.get()?.let(coordinator.executionOwner::isQueued) == true }
                    }
                }
                // The exact maintenance thread has acquired the owner and is waiting for the
                // blocked ticket, rather than merely having started its executor task.
                awaitCondition {
                    maintenanceThread.get().let {
                        !coordinator.executionOwner.isQueued(it) && it.state == Thread.State.WAITING
                    }
                }
                assertEquals(1L, maintenanceEntered.count)
                assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
                releaseWorker.countDown()
                assertTrue(maintenanceEntered.await(5, TimeUnit.SECONDS))
                assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
                assertTrue(coordinator.isUsableForOwnerOperations)
                assertThrows(IllegalStateException::class.java) {
                    coordinator.withPhysicsStalled { error("wrong thread must not wait for the busy owner") }
                }
                val published = world.latestWorldFrame()
                clock.addAndGet(TimeUnit.SECONDS.toNanos(30L))
                coordinator.timerWake()
                assertSame(ticket, coordinator.wakeFromMainThread())
                assertSame(ticket, coordinator.stepSimulation(50.0))
                assertSame(ticket, coordinator.captureCurrentTicket())
                assertSame(published, world.latestWorldFrame())
                assertEquals(1, processed.get())
                IsolatedSimulationThreadMarker.run {
                    assertThrows(IllegalStateException::class.java) {
                        coordinator.ensurePhysicsStalled("another thread without maintenance ownership")
                    }
                }
                releaseMaintenance.countDown()
                maintenance.get(5, TimeUnit.SECONDS)
                assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100L))
                coordinator.timerWake()
                val next = coordinator.captureCurrentTicket()
                val metrics = next.completion.get(5, TimeUnit.SECONDS)
                assertEquals(ticket.epoch + 1L, next.epoch)
                assertEquals(PhysicsWakeSource.TIMER_FALLBACK, metrics.source)
                assertEquals(50.0, metrics.rawDeltaMillis, "maintenance wall time must not become physics delta")
                assertEquals(2, processed.get())
            } finally {
                releaseWorker.countDown()
                releaseMaintenance.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `nested maintenance keeps queued work static processors and overload reset contracts`() =
        IsolatedSimulationThreadMarker.run {
            PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
                CoreWorld.createEmpty(NoOpTickManager, coordinator)
                val calls = mutableListOf<String>()
                val processor = RailwayEpochProcessor { calls += "physics" }
                coordinator.registerRailwayEpochProcessor(processor)
                coordinator.start()
                val queued = coordinator.submitBetweenFrames { calls += "before" }
                lateinit var nestedQueued: java.util.concurrent.CompletableFuture<Unit>
                coordinator.withPhysicsStalled {
                    assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
                    coordinator.ensurePhysicsStalled("outer")
                    assertFalse(coordinator.resetStall().get(5, TimeUnit.SECONDS))
                    assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
                    assertThrows(IllegalStateException::class.java) {
                        coordinator.unregisterRailwayEpochProcessor(processor)
                    }
                    assertThrows(IllegalStateException::class.java) {
                        coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor {})
                    }
                    val expected = IllegalArgumentException("inner preparation failed")
                    assertSame(expected, assertThrows(IllegalArgumentException::class.java) {
                        coordinator.withPhysicsStalled {
                            coordinator.ensurePhysicsStalled("inner")
                            throw expected
                        }
                    })
                    coordinator.withStructuralMutation { coordinator.ensurePhysicsStalled("nested owner operation") }
                    coordinator.ensurePhysicsStalled("outer after inner failure")
                    assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
                    nestedQueued = coordinator.submitBetweenFrames { calls += "during" }
                    assertFalse(queued.isDone)
                    assertFalse(nestedQueued.isDone)
                    assertTrue(calls.isEmpty())
                }
                assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
                coordinator.processBetweenFrames {
                    assertThrows(IllegalStateException::class.java) { coordinator.ensurePhysicsStalled("ordinary owner phase") }
                    coordinator.drainBetweenFramesOperations()
                }
                queued.get(5, TimeUnit.SECONDS)
                nestedQueued.get(5, TimeUnit.SECONDS)
                assertEquals(listOf("before", "during"), calls)
                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                assertEquals(listOf("before", "during", "physics"), calls)
            }
        }

    @Test
    fun `real overload must recover before maintenance and its failed ticket stays failed`() =
        IsolatedSimulationThreadMarker.run {
            val clock = AtomicLong(1_000_000_000L)
            PhysicsCoordinator(1, nanoTime = clock::get, automaticFallback = false,
                epochOverloadThresholdMillis = 1L, overloadsBeforeStall = 1).use { coordinator ->
                val world = CoreWorld.createEmpty(NoOpTickManager, coordinator)
                coordinator.registerRailwayEpochProcessor { clock.addAndGet(2_000_000L) }
                coordinator.start()
                val failed = coordinator.stepSimulation(50.0)
                assertTrue(assertThrows(ExecutionException::class.java) {
                    failed.completion.get(5, TimeUnit.SECONDS)
                }.cause is PhysicsOverloadException)
                val actionCalls = AtomicInteger()
                assertThrows(PhysicsOverloadException::class.java) {
                    coordinator.withPhysicsStalled { actionCalls.incrementAndGet() }
                }
                assertEquals(0, actionCalls.get())
                assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
                assertTrue(coordinator.resetStall().get(5, TimeUnit.SECONDS))
                val recovered = coordinator.captureCurrentTicket()
                assertEquals(failed.epoch, recovered.epoch)
                assertEquals(failed.epoch, world.latestWorldFrame().physicsEpoch)
                coordinator.withPhysicsStalled {
                    coordinator.ensurePhysicsStalled("maintenance after overload recovery")
                    assertSame(recovered, coordinator.captureCurrentTicket())
                    assertFalse(coordinator.resetStall().get(5, TimeUnit.SECONDS))
                    assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
                }
                assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
                assertTrue(assertThrows(ExecutionException::class.java) {
                    failed.completion.get(5, TimeUnit.SECONDS)
                }.cause is PhysicsOverloadException)
            }
        }

    @Test
    fun `failed maintenance action releases its admission without leaking maintenance identity`() =
        IsolatedSimulationThreadMarker.run {
            PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
                CoreWorld.createEmpty(NoOpTickManager, coordinator)
                coordinator.start()
                val expected = IllegalArgumentException("placement rejected")
                assertSame(expected, assertThrows(IllegalArgumentException::class.java) {
                    coordinator.withPhysicsStalled { throw expected }
                })
                assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
                assertThrows(IllegalStateException::class.java) { coordinator.ensurePhysicsStalled("after failure") }
                assertEquals(1L, coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS).epoch)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["shutdown", "close", "halt"])
    @IsolateGlobalHalt("halt")
    fun `maintenance exit cannot revive terminal physics`(transition: String) =
        IsolatedSimulationThreadMarker.run {
            PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
                CoreWorld.createEmpty(NoOpTickManager, coordinator)
                coordinator.start()
                val boundary = coordinator.stepSimulation(50.0)
                boundary.completion.get(5, TimeUnit.SECONDS)
                val queued = coordinator.submitBetweenFrames { error("terminal work must not execute") }
                coordinator.withPhysicsStalled {
                    when (transition) {
                        "shutdown" -> assertSame(boundary, coordinator.beginShutdown())
                        "close" -> coordinator.close()
                        "halt" -> coordinator.stall("maintenance invariant failure")
                    }
                }
                assertThrows(IllegalStateException::class.java) { coordinator.stepSimulation(50.0) }
                assertEquals(transition == "shutdown", coordinator.isUsableForOwnerOperations)
                assertThrows(ExecutionException::class.java) { queued.get(5, TimeUnit.SECONDS) }
                assertThrows(ExecutionException::class.java) { coordinator.resetStall().get(5, TimeUnit.SECONDS) }
                assertEquals(when (transition) {
                    "shutdown" -> PhysicsCoordinatorStatus.RUNNING
                    "close" -> PhysicsCoordinatorStatus.CLOSED
                    else -> PhysicsCoordinatorStatus.STALLED
                }, coordinator.status)
            }
        }

    @Test
    @IsolateGlobalHalt
    fun `failed in flight ticket never admits maintenance`() {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            CoreWorld.createEmpty(NoOpTickManager, coordinator)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val actionCalls = AtomicInteger()
            val executor = Executors.newSingleThreadExecutor()
            try {
                coordinator.registerRailwayEpochProcessor {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    error("failed physical epoch")
                }
                coordinator.start()
                val ticket = coordinator.stepSimulation(50.0)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val maintenance = executor.submit {
                    IsolatedSimulationThreadMarker.run {
                        coordinator.withPhysicsStalled { actionCalls.incrementAndGet() }
                    }
                }
                release.countDown()
                assertThrows(ExecutionException::class.java) { ticket.completion.get(5, TimeUnit.SECONDS) }
                assertThrows(ExecutionException::class.java) { maintenance.get(5, TimeUnit.SECONDS) }
                assertEquals(0, actionCalls.get())
                assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out awaiting maintenance barrier" }
            Thread.yield()
        }
    }

    private object NoOpTickManager : TickManager {
        override fun register(obj: TickingObject) = Unit
        override fun unregister(obj: TickingObject) = Unit
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit
        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> =
            FutureTask(task).also { it.run() }
    }
}
