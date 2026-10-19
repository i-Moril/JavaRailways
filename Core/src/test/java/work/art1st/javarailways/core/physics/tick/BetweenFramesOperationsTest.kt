package work.art1st.javarailways.core.physics.tick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.TrainSwitchDirectionEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.physics.RailwayEpochProcessor
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.setControllerForTest
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.physics.train.CoreBogie
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class BetweenFramesOperationsTest {
    @Test
    fun `queued creation and deletion publish complete current epoch observations`() = withOwner {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            val world = CoreWorld.createEmpty(NoOpTickManager, NoOpWorldParentContext, coordinator)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val helper = Executors.newSingleThreadExecutor()
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                val existing = createTrainWithConfig("existing-create", rail, world, listOf(10.0))
                existing.setControllerForTest(object : TrainController(existing, world) {
                    override fun tick(deltaT: Double) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                })
                world.drainPublishedTrainFrames()
                coordinator.start()
                val ticket = coordinator.stepSimulation(50.0)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val before = world.latestWorldFrame()
                val created = coordinator.submitBetweenFrames {
                    assertTrue(ticket.isDone)
                    val head = requireNotNull(rail.viewAB.getRailLocationAt(0.0).forward(80.0))
                    val initialBogie = requireNotNull(head.forward(-2.0))
                    val car = CoreCar.create("created-car", CoreBogie.create(initialBogie),
                        CoreBogie.create(initialBogie), 10.0, world = world)
                    CoreTrain.create("created-after-epoch", listOf(car), world).also { train ->
                        train.setLocation(head)
                        assertFalse(world.latestWorldFrame().trains.any { it.trainId == train.id })
                        assertFalse(world.drainPublishedTrainFrames().any { it.trainId == train.id })
                        world.publishOwnerChanges(listOf(train))
                    }
                }
                assertFalse(created.isDone)
                assertEquals(null, world.getTrain("created-after-epoch"))
                assertSame(before, world.latestWorldFrame())
                helper.submit { release.countDown() }
                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                val train = created.get(5, TimeUnit.SECONDS)
                val frame = train.publishedFrame
                assertEquals(ticket.epoch, frame.physicsEpoch)
                assertEquals(ticket.epoch, world.latestWorldFrame().physicsEpoch)
                assertTrue(world.latestWorldFrame().trains.any { it === frame })
                assertTrue(world.latestWorldFrame().occupancy.blockingTrainIds(frame.occupiedIntervals).contains(train.id))
                assertSame(frame, world.drainPublishedTrainFrames().single { it.trainId == train.id })
                assertFalse(before.trains.any { it.trainId == train.id })

                val oldWorld = world.latestWorldFrame()
                val removed = train.destroy().toCompletableFuture()
                assertSame(train, world.getTrain(train.id))
                assertFalse(removed.isDone)
                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                removed.get(5, TimeUnit.SECONDS)
                assertEquals(null, world.getTrain(train.id))
                assertEquals(null, train.cars.single().train)
                assertFalse(world.latestWorldFrame().trains.any { it.trainId == train.id })
                assertFalse(world.latestWorldFrame().occupancy.blockingTrainIds(frame.occupiedIntervals).contains(train.id))
                assertFalse(world.drainPublishedTrainFrames().any { it.trainId == train.id })
                assertTrue(oldWorld.trains.any { it === frame })
                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                assertFalse(world.latestWorldFrame().trains.any { it.trainId == train.id })
            } finally {
                release.countDown()
                coordinator.close()
                world.releaseRuntimeBinding()
                helper.shutdownNow()
            }
        }
    }

    @Test
    fun `failed staged placement preserves snapshots and motion then one batch publishes final placements`() = withOwner {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            val world = CoreWorld.createEmpty(NoOpTickManager, NoOpWorldParentContext, coordinator)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                val first = createTrainWithConfig("first-place", rail, world, listOf(10.0))
                val second = createTrainWithConfig("second-place", rail, world, listOf(10.0))
                coordinator.processBetweenFrames {
                    second.setLocation(requireNotNull(rail.viewAB.getRailLocationAt(0.0).forward(80.0)))
                    world.publishOwnerChanges(listOf(second))
                }
                first.setControllerForTest(object : TrainController(first, world) {
                    init { speed = 10.0 }
                    override fun tick(deltaT: Double) { moveControlledTrain(0.5, 10.0) }
                })
                coordinator.start()
                val ticket = coordinator.stepSimulation(50.0)
                ticket.completion.get(5, TimeUnit.SECONDS)
                val oldFirst = first.publishedFrame
                val oldSecond = second.publishedFrame
                val oldWorld = world.latestWorldFrame()
                world.drainPublishedTrainFrames()
                coordinator.processBetweenFrames {
                    val savedFirst = first.capturePlacementSnapshot()
                    val savedSecond = second.capturePlacementSnapshot()
                    first.setLocation(requireNotNull(rail.viewAB.getRailLocationAt(0.0).forward(120.0)))
                    assertSame(oldFirst, first.publishedFrame)
                    assertThrows(Exception::class.java) { second.setLocation(rail.viewAB.getRailLocationAt(5.0)) }
                    first.restorePlacementSnapshot(savedFirst)
                    second.restorePlacementSnapshot(savedSecond)
                }
                assertSame(oldFirst, first.publishedFrame)
                assertSame(oldSecond, second.publishedFrame)
                assertSame(oldWorld, world.latestWorldFrame())
                assertTrue(world.drainPublishedTrainFrames().isEmpty())
                assertEquals(oldFirst.cars.single().state.velocityX, first.cars.single().velocity.x(), 1e-9)
                assertEquals(oldFirst.head.arcLengthInAbMeters, first.headLocation.arcLengthIn(
                    work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB), 1e-9)
                coordinator.processBetweenFrames {
                    first.setLocation(requireNotNull(rail.viewAB.getRailLocationAt(0.0).forward(120.0)))
                    second.setLocation(requireNotNull(rail.viewAB.getRailLocationAt(0.0).forward(160.0)))
                    assertTrue(world.drainPublishedTrainFrames().isEmpty())
                    world.publishOwnerChanges(listOf(first, second))
                }
                val published = world.latestWorldFrame()
                assertEquals(ticket.epoch, published.physicsEpoch)
                assertTrue(published.trains.all { it.physicsEpoch == ticket.epoch })
                assertEquals(setOf(first.id, second.id), world.drainPublishedTrainFrames().map { it.trainId }.toSet())
                assertEquals(120.0, first.headLocation.position.x(), 1e-6)
                assertEquals(160.0, second.headLocation.position.x(), 1e-6)
                assertTrue(first.publishedFrame.headTraversal.isEmpty())
                assertTrue(second.publishedFrame.headTraversal.isEmpty())
                assertEquals(0.0, first.cars.single().velocity.length(), 1e-9)
                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                assertEquals(120.5, first.headLocation.position.x(), 1e-6)
                assertEquals(160.0, second.headLocation.position.x(), 1e-6)
            } finally {
                coordinator.close()
                world.releaseRuntimeBinding()
            }
        }
    }

    @Test
    fun `bootstrap operations are FIFO and nested submission waits for next batch`() = withOwner {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            val calls = mutableListOf<String>()
            lateinit var nested: CompletableFuture<String>
            val first = coordinator.submitBetweenFrames {
                calls += "first"
                nested = coordinator.submitBetweenFrames { calls += "nested"; "nested-result" }
                "first-result"
            }
            val failure = IllegalArgumentException("rejected operation")
            val rejected = coordinator.submitBetweenFrames<String> { calls += "failed"; throw failure }
            val last = coordinator.submitBetweenFrames { calls += "last"; 42 }
            assertFalse(first.isDone)
            assertThrows(IllegalStateException::class.java) { coordinator.ensureBetweenFrames("test") }
            coordinator.processBetweenFrames { ticket ->
                assertEquals(0L, ticket.epoch)
                coordinator.drainBetweenFramesOperations()
                coordinator.ensureBetweenFrames("test")
                calls += "host"
                assertFalse(nested.isDone)
                assertThrows(IllegalStateException::class.java) { coordinator.drainBetweenFramesOperations() }
            }
            assertEquals(listOf("first", "failed", "last", "host"), calls)
            assertEquals("first-result", first.get(5, TimeUnit.SECONDS))
            assertSame(failure, assertThrows(ExecutionException::class.java) { rejected.get() }.cause)
            assertEquals(42, last.get(5, TimeUnit.SECONDS))
            coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
            assertEquals("nested-result", nested.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `queued work follows full real epoch and excludes both wake sources through host work`() = withOwner {
        val clock = AtomicLong(1_000_000_000L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        PhysicsCoordinator(1, nanoTime = clock::get, automaticFallback = false).use { coordinator ->
            val world = CoreWorld.createEmpty(NoOpTickManager, NoOpWorldParentContext, coordinator)
            val helper = Executors.newSingleThreadExecutor()
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                val train = createTrainWithConfig("frame-operation", rail, world, listOf(10.0))
                var oldTicks = 0
                var newTicks = 0
                val oldController = object : TrainController(train, world) {
                    override fun tick(deltaT: Double) {
                        oldTicks++
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
                val replacement = object : TrainController(train, world) {
                    override fun tick(deltaT: Double) { newTicks++ }
                }
                train.setControllerForTest(oldController)
                coordinator.start()
                val ticket = coordinator.stepSimulation(50.0)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertThrows(IllegalStateException::class.java) { train.setController(replacement) }
                assertSame(oldController, train.controller)
                val result = coordinator.submitBetweenFrames {
                    assertTrue(ticket.isDone)
                    assertEquals(ticket.epoch, world.latestWorldFrame().physicsEpoch)
                    val otherThreadWake = helper.submit<PhysicsTicket> {
                        clock.addAndGet(100_000_000L)
                        coordinator.timerWake()
                        coordinator.wakeFromMainThread()
                    }.get(5, TimeUnit.SECONDS)
                    assertSame(ticket, otherThreadWake)
                    assertSame(oldController, train.controller)
                    train.setController(replacement)
                    "applied"
                }
                assertFalse(result.isDone)
                assertSame(oldController, train.controller)
                assertEquals(0, newTicks)
                helper.submit { release.countDown() }
                coordinator.processBetweenFrames { completed ->
                    assertSame(ticket, completed)
                    coordinator.drainBetweenFramesOperations()
                    assertEquals("applied", result.get(5, TimeUnit.SECONDS))
                    // The host/dispatcher portion retains the same gate after the queued work.
                    clock.addAndGet(100_000_000L)
                    coordinator.timerWake()
                    assertSame(ticket, coordinator.wakeFromMainThread())
                }
                val next = coordinator.stepSimulation(50.0)
                next.completion.get(5, TimeUnit.SECONDS)
                assertEquals(ticket.epoch + 1, next.epoch)
                assertEquals(1, oldTicks)
                assertEquals(1, newTicks)
                assertSame(replacement, train.controller)
                val staleReplacement = coordinator.submitBetweenFrames {
                    check(train.controller === oldController) { "captured controller was replaced" }
                    train.setController(oldController)
                }
                val removal = coordinator.submitBetweenFrames { train.setController(null) }
                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                assertThrows(ExecutionException::class.java) { staleReplacement.get(5, TimeUnit.SECONDS) }
                removal.get(5, TimeUnit.SECONDS)
                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                assertEquals(1, oldTicks)
                assertEquals(1, newTicks)
                assertEquals(null, train.controller)
            } finally {
                release.countDown()
                coordinator.close()
                world.releaseRuntimeBinding()
                helper.shutdownNow()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["shutdown", "stall", "close", "epoch-failure"])
    fun `terminal transitions fail pending work and notify outside state lock`(transition: String) = withOwner {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            val world = CoreWorld.createEmpty(NoOpTickManager, NoOpWorldParentContext, coordinator)
            val observer = Executors.newSingleThreadExecutor()
            try {
                val pending = coordinator.submitBetweenFrames { error("must never execute") }
                val notified = pending.handle { _, _ ->
                    observer.submit<Boolean> {
                        runCatching { coordinator.captureCurrentTicket() }
                        true
                    }.get(5, TimeUnit.SECONDS)
                }
                when (transition) {
                    "shutdown" -> coordinator.beginShutdown()
                    "stall" -> coordinator.stall("test terminal transition")
                    "close" -> coordinator.close()
                    "epoch-failure" -> {
                        coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor { error("epoch failure") })
                        coordinator.start()
                        assertThrows(ExecutionException::class.java) {
                            coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                        }
                    }
                }
                assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
                assertTrue(notified.get(5, TimeUnit.SECONDS))
                assertTrue(coordinator.submitBetweenFrames { "too late" }.isCompletedExceptionally)
            } finally {
                coordinator.close()
                world.releaseRuntimeBinding()
                observer.shutdownNow()
            }
        }
    }

    @Test
    fun `shutdown during captured batch rejects its remainder and newly submitted requests`() = withOwner {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            val first = coordinator.submitBetweenFrames { coordinator.beginShutdown().epoch }
            val second = coordinator.submitBetweenFrames { error("captured work must not run after shutdown") }
            assertThrows(IllegalStateException::class.java) {
                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
            }
            assertEquals(0L, first.get(5, TimeUnit.SECONDS))
            assertThrows(ExecutionException::class.java) { second.get(5, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `queued reversal notifies coherent owner state and next worker moves without a skipped epoch`() = withOwner {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            val world = CoreWorld.createEmpty(NoOpTickManager, NoOpWorldParentContext, coordinator)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                val train = createTrainWithConfig("owner-reverse", rail, world, listOf(10.0))
                var ticks = 0
                val controller = object : TrainController(train, world) {
                    init { speed = 10.0 }
                    override fun tick(deltaT: Double) {
                        ticks++
                        moveControlledTrain(0.5, 10.0)
                    }
                }
                train.setControllerForTest(controller)
                coordinator.start()
                val first = coordinator.stepSimulation(50.0)
                first.completion.get(5, TimeUnit.SECONDS)
                val oldFrame = train.publishedFrame
                val oldWorld = world.latestWorldFrame()
                val owner = Thread.currentThread()
                var eventThread: Thread? = null
                var eventFrame: TrainFrameSnapshot? = null
                var coherent = false
                MainRailEventBus.register(object : EventListener {
                    @HandlesEvent
                    fun switched(event: TrainSwitchDirectionEvent) {
                        eventThread = Thread.currentThread()
                        eventFrame = event.train.publishedFrame
                        val observed = world.latestWorldFrame()
                        coherent = !ThreadGuard.isAsyncTickThread() &&
                            observed.physicsEpoch == first.epoch &&
                            observed.occupancy.physicsEpoch == first.epoch &&
                            observed.trains.single() === eventFrame
                    }
                }).use {
                    val reverse = controller.reverse()
                    assertFalse(reverse.isDone)
                    assertSame(oldFrame, train.publishedFrame)
                    coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                    reverse.get(5, TimeUnit.SECONDS)
                }
                assertSame(owner, eventThread)
                assertSame(train.publishedFrame, eventFrame)
                assertTrue(coherent)
                assertTrue(train.isReversed)
                assertEquals(1, ticks)
                assertEquals(first.epoch, train.publishedFrame.physicsEpoch)
                assertEquals(oldFrame.tail.arcLengthInAbMeters, train.publishedFrame.head.arcLengthInAbMeters)
                assertTrue(train.publishedFrame.headTraversal.isEmpty())
                assertFalse(oldFrame.reversed)
                assertSame(oldFrame, oldWorld.trains.single())
                val reversedHead = train.headLocation.position.x()
                val next = coordinator.stepSimulation(50.0)
                next.completion.get(5, TimeUnit.SECONDS)
                assertEquals(first.epoch + 1L, next.epoch)
                assertEquals(2, ticks)
                assertEquals(reversedHead - 0.5, train.headLocation.position.x(), 1e-6)
                assertFalse(train.publishedFrame.headTraversal.isEmpty())

                // Owner creation now publishes at the completed epoch, so reversal stays usable.
                val created = createTrainWithConfig("new-before-publication", rail, world, listOf(10.0))
                assertEquals(next.epoch, created.publishedFrame.physicsEpoch)
                val beforeReverse = train.publishedFrame
                val reversedAgain = controller.reverse()
                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                reversedAgain.get(5, TimeUnit.SECONDS)
                assertFalse(train.isReversed)
                assertTrue(beforeReverse.reversed)
                assertEquals(next.epoch, world.latestWorldFrame().physicsEpoch)
                assertTrue(world.latestWorldFrame().trains.all { it.physicsEpoch == next.epoch })
                assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
            } finally {
                world.releaseRuntimeBinding()
            }
        }
    }

    @Test
    fun `reversal parity cancellation and stale controller failures do not affect another request`() = withOwner {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            val world = CoreWorld.createEmpty(NoOpTickManager, NoOpWorldParentContext, coordinator)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0))
                val train = createTrainWithConfig("reverse-parity", rail, world, listOf(10.0))
                val controller = object : TrainController(train, world) {}
                train.setControllerForTest(controller)
                val oldFrame = train.publishedFrame
                val first = controller.reverse()
                val second = controller.reverse()
                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                first.get(5, TimeUnit.SECONDS)
                second.get(5, TimeUnit.SECONDS)
                assertSame(oldFrame, train.publishedFrame)

                val cancelledObserver = controller.reverse()
                val observer = controller.reverse()
                val last = controller.reverse()
                cancelledObserver.cancel(false)
                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                observer.get(5, TimeUnit.SECONDS)
                last.get(5, TimeUnit.SECONDS)
                assertTrue(train.isReversed)

                val stale = controller.reverse()
                train.setControllerForTest(null)
                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                assertThrows(ExecutionException::class.java) { stale.get(5, TimeUnit.SECONDS) }
                assertTrue(train.isReversed)
                train.setControllerForTest(controller)
                val pending = controller.reverse()
                coordinator.beginShutdown()
                assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
                assertTrue(controller.reverse().isCompletedExceptionally)
                assertTrue(train.isReversed)
            } finally {
                world.releaseRuntimeBinding()
            }
        }
    }

    private fun <T> withOwner(action: () -> T): T {
        val owner = Thread.currentThread()
        return ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        }).use { action() }
    }

    private object NoOpTickManager : TickManager {
        override fun register(obj: TickingObject) = Unit
        override fun unregister(obj: TickingObject) = Unit
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit
        override fun <T> scheduleMainThreadAfterCommitTask(task: Callable<T>): Future<T> =
            FutureTask(task).also { it.run() }
        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> =
            FutureTask(task).also { it.run() }
    }
}
