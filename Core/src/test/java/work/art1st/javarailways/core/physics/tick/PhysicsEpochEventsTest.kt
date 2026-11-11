package work.art1st.javarailways.core.physics.tick

import work.art1st.javarailways.core.testing.IsolateGlobalHalt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.CollisionEvent
import work.art1st.javarailways.core.api.event.PostTickEvent
import work.art1st.javarailways.core.api.event.PreTickEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.event.processor.Priority
import work.art1st.javarailways.core.api.physics.RailwayEpochProcessor
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.core.physics.train.setControllerForTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@OptIn(AsyncEventApi::class)
class PhysicsEpochEventsTest {
    @ParameterizedTest
    @CsvSource("MAIN_THREAD,false", "MAIN_THREAD,true", "TIMER_FALLBACK,false", "TIMER_FALLBACK,true",
        "SIMULATION,false", "SIMULATION,true")
    fun `real wake paths notify once with exact committed frame including empty worlds`(
        source: PhysicsWakeSource, empty: Boolean
    ) {
        val clock = AtomicLong(1_000_000_000L)
        val coordinator = PhysicsCoordinator(1, nanoTime = clock::get, automaticFallback = false)
        withWorld(coordinator, if (source == PhysicsWakeSource.SIMULATION) WorldExecutionMode.SIMULATION
            else WorldExecutionMode.LIVE) { world ->
            val order = CopyOnWriteArrayList<String>()
            if (!empty) {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
                val train = createTrainWithConfig("same-name", rail, world, listOf(10.0))
                train.setControllerForTest(object : TrainController(train) {
                    override fun tick(deltaT: Double) { order.add("controller") }
                })
            }
            coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor { order.add("processor") })
            val observer = EpochObserver(world, before = { order.add("pre") }, after = { order.add("post") })
            PhysicsRailEventBus.register(observer).use {
                coordinator.start()
                assertEquals(0L, coordinator.captureCurrentTicket().epoch)
                assertTrue(observer.pre.isEmpty(), "bootstrap is not a computed epoch")
                val ticket = when (source) {
                    PhysicsWakeSource.MAIN_THREAD -> coordinator.wakeFromMainThread()
                    PhysicsWakeSource.SIMULATION -> coordinator.stepSimulation(75.0)
                    PhysicsWakeSource.TIMER_FALLBACK -> {
                        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100))
                        coordinator.timerWake()
                        coordinator.captureCurrentTicket()
                    }
                }
                val metrics = ticket.completion.get(5, TimeUnit.SECONDS)
                assertTrue(observer.postFinished.await(5, TimeUnit.SECONDS))
                assertNull(observer.failure.get())
                assertEquals(listOf("pre") + (if (empty) emptyList() else listOf("controller")) +
                    listOf("processor", "post"), order)
                val pre = observer.pre.single()
                val post = observer.post.single()
                assertSame(world, pre.world)
                assertSame(world, post.world)
                assertEquals(ticket.epoch, pre.epoch)
                assertEquals(pre.epoch, post.epoch)
                assertEquals(metrics.deltaMillis, pre.deltaMillis)
                assertEquals(pre.deltaMillis, post.deltaMillis)
                assertEquals(source, metrics.source)
                assertSame(world.latestWorldFrame(), post.frame)
                assertEquals(ticket.epoch, post.frame.physicsEpoch)
                assertEquals(ticket.epoch, post.frame.occupancy.physicsEpoch)
                assertTrue(post.frame.trains.all { it.physicsEpoch == ticket.epoch })
                assertEquals(if (empty) 0 else 1, post.frame.trains.size)
            }
        }
    }

    @Test
    fun `pre and post callbacks release coordinator locks and post permits topology edits`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false, epochOverloadThresholdMillis = 60_000)
        withWorld(coordinator) { world ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val observed = CopyOnWriteArrayList<Long>()
                val observer = EpochObserver(world,
                    before = {
                        observed.add(executor.submit<Long> { coordinator.captureCurrentTicket().epoch }
                            .get(5, TimeUnit.SECONDS))
                    },
                    after = { event ->
                        observed.add(executor.submit<Long> {
                            world.onOwnerForTest {
                                world.railTopologyTransaction { event.frame.physicsEpoch }
                            }
                        }.get(5, TimeUnit.SECONDS))
                    })
                PhysicsRailEventBus.register(observer).use {
                    coordinator.start()
                    val ticket = coordinator.stepSimulation(50.0)
                    ticket.completion.get(5, TimeUnit.SECONDS)
                    assertTrue(observer.postFinished.await(5, TimeUnit.SECONDS))
                    assertNull(observer.failure.get())
                    assertEquals(listOf(ticket.epoch, ticket.epoch), observed)
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun `coalesced wake reuses ticket and ticket does not wait for post listener`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false, epochOverloadThresholdMillis = 60_000)
        withWorld(coordinator) { world ->
            val preEntered = CountDownLatch(1)
            val releasePre = CountDownLatch(1)
            val postEntered = CountDownLatch(1)
            val releasePost = CountDownLatch(1)
            val observer = EpochObserver(world, before = {
                preEntered.countDown()
                check(releasePre.await(5, TimeUnit.SECONDS))
            }, after = {
                postEntered.countDown()
                check(releasePost.await(5, TimeUnit.SECONDS))
            })
            PhysicsRailEventBus.register(observer).use {
                try {
                    coordinator.start()
                    val ticket = coordinator.stepSimulation(50.0)
                    assertTrue(preEntered.await(5, TimeUnit.SECONDS))
                    assertSame(ticket, coordinator.wakeFromMainThread())
                    assertSame(ticket, coordinator.stepSimulation(50.0))
                    releasePre.countDown()
                    ticket.completion.get(5, TimeUnit.SECONDS)
                    assertTrue(postEntered.await(5, TimeUnit.SECONDS))
                    assertTrue(ticket.isDone)
                    assertEquals(1, observer.pre.size)
                    assertEquals(1, observer.post.size)
                    releasePost.countDown()
                    assertTrue(observer.postFinished.await(5, TimeUnit.SECONDS))
                    assertNull(observer.failure.get())
                } finally { releasePre.countDown(); releasePost.countDown() }
            }
        }
    }

    @Test
    fun `notifications use clamped controller delta and retain the prior immutable frame`() {
        val clock = AtomicLong(1_000_000_000L)
        val coordinator = PhysicsCoordinator(1, nanoTime = clock::get, automaticFallback = false)
        withWorld(coordinator) { world ->
            val delivered = LinkedBlockingQueue<PostTickEvent>()
            val observer = EpochObserver(world, after = { delivered.add(it) })
            PhysicsRailEventBus.register(observer).use {
                coordinator.start()
                coordinator.wakeFromMainThread().completion.get(5, TimeUnit.SECONDS)
                val first = checkNotNull(delivered.poll(5, TimeUnit.SECONDS))
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500))
                val metrics = coordinator.wakeFromMainThread().completion.get(5, TimeUnit.SECONDS)
                val second = checkNotNull(delivered.poll(5, TimeUnit.SECONDS))
                assertNull(observer.failure.get())
                assertEquals(listOf(50.0, 200.0), observer.pre.map { it.deltaMillis })
                assertEquals(200.0, second.deltaMillis)
                assertEquals(500.0, metrics.rawDeltaMillis)
                assertEquals(1L, first.frame.physicsEpoch)
                assertEquals(2L, second.frame.physicsEpoch)
                assertNotSame(first.frame, second.frame)
            }
        }
    }

    @Test
    fun `close cancels a queued epoch without pre or post`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        withWorld(coordinator) { world ->
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val coordinatorThread = AtomicReference<Thread>()
            val observer = EpochObserver(world, after = {
                coordinatorThread.set(Thread.currentThread())
                entered.countDown()
                try { check(release.await(5, TimeUnit.SECONDS)) } catch (_: InterruptedException) {
                    interrupted.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            })
            PhysicsRailEventBus.register(observer).use {
                try {
                    coordinator.start()
                    coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val queued = coordinator.stepSimulation(50.0)
                    assertEquals(2L, queued.epoch)
                    coordinator.close()
                    assertThrows(ExecutionException::class.java) { queued.completion.get(5, TimeUnit.SECONDS) }
                    assertTrue(interrupted.await(5, TimeUnit.SECONDS))
                    release.countDown()
                    coordinatorThread.get().join(5_000)
                    assertFalse(coordinatorThread.get().isAlive)
                    assertNull(observer.failure.get())
                    assertEquals(listOf(1L), observer.pre.map { it.epoch })
                    assertEquals(listOf(1L), observer.post.map { it.epoch })
                    assertEquals(1L, world.latestWorldFrame().physicsEpoch)
                } finally { release.countDown() }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["processor", "controller", "overload", "close"])
    @IsolateGlobalHalt("processor", "controller")
    fun `failed or closed epoch has pre without successful post`(phase: String) {
        val clock = AtomicLong(1_000_000_000L)
        val coordinator = PhysicsCoordinator(1, nanoTime = clock::get, automaticFallback = false,
            overloadsBeforeStall = 1)
        withWorld(coordinator) { world ->
            if (phase == "controller") {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
                val train = createTrainWithConfig("failure", rail, world, listOf(10.0))
                train.setControllerForTest(object : TrainController(train) {
                    override fun tick(deltaT: Double) { error("controller failure") }
                })
            }
            val coordinatorThread = AtomicReference<Thread>()
            coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor {
                coordinatorThread.set(Thread.currentThread())
                when (phase) {
                    "processor" -> error("processor failure")
                    "overload" -> clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100))
                    "close" -> coordinator.close()
                }
            })
            val observer = EpochObserver(world)
            PhysicsRailEventBus.register(observer).use {
                coordinator.start()
                val previous = world.latestWorldFrame()
                val ticket = coordinator.stepSimulation(50.0)
                assertThrows(ExecutionException::class.java) { ticket.completion.get(5, TimeUnit.SECONDS) }
                // close interrupts the coordinator itself; join it so absence of a later Post is observable.
                if (phase == "close") {
                    coordinatorThread.get().join(5_000)
                    assertFalse(coordinatorThread.get().isAlive)
                }
                assertEquals(listOf(ticket.epoch), observer.pre.map { it.epoch })
                assertTrue(observer.post.isEmpty())
                assertSame(previous, world.latestWorldFrame())
                assertEquals(if (phase == "close") PhysicsCoordinatorStatus.CLOSED else PhysicsCoordinatorStatus.STALLED,
                    coordinator.status)
            }
        }
    }

    @Test
    fun `ordinary listener failures do not prevent compute publication or later listeners`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        withWorld(coordinator) { world ->
            val observer = EpochObserver(world)
            val failing = object : EventListener {
                @HandlesEvent(priority = Priority.HIGHEST)
                fun pre(event: PreTickEvent) { if (event.world === world) error("pre listener") }
                @HandlesEvent(priority = Priority.HIGHEST)
                fun post(event: PostTickEvent) { if (event.world === world) error("post listener") }
            }
            PhysicsRailEventBus.register(failing).use {
                PhysicsRailEventBus.register(observer).use {
                    coordinator.start()
                    val ticket = coordinator.stepSimulation(50.0)
                    ticket.completion.get(5, TimeUnit.SECONDS)
                    assertTrue(observer.postFinished.await(5, TimeUnit.SECONDS))
                    assertNull(observer.failure.get())
                    assertEquals(1, observer.pre.size)
                    assertEquals(1, observer.post.size)
                    assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
                    assertSame(ticket, coordinator.captureCurrentTicket())
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(WorldExecutionMode::class)
    fun `default collision applies derail in both world modes`(mode: WorldExecutionMode) {
        val coordinator = PhysicsCoordinator(2, automaticFallback = false)
        withWorld(coordinator, mode) { world ->
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
            val first = createTrainWithConfig("first", rail, world, listOf(10.0))
            val second = createTrainWithConfig("second", rail, world, listOf(10.0))
            first.setControllerForTest(object : TrainController(first) {
                override fun tick(deltaT: Double) { moveControlledTrain(0.5, 10.0) }
            })
            val collisions = CopyOnWriteArrayList<CollisionEvent>()
            PhysicsRailEventBus.register(object : EventListener {
                @HandlesEvent fun collision(event: CollisionEvent) {
                    if (event.world === world) collisions.add(event)
                }
            }).use {
                coordinator.start()
                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                assertFalse(first.isDerailed)
                assertEquals(1, collisions.size)
                assertSame(world, collisions.single().world)
                assertEquals(mode, collisions.single().world.executionMode)
                world.onOwnerForTest { coordinator.drainBetweenFramesOperations() }
                assertTrue(first.isDerailed)
                assertTrue(second.isDerailed)
                assertEquals(1, collisions.size)
            }
        }
    }

    private fun withWorld(coordinator: PhysicsCoordinator, mode: WorldExecutionMode = WorldExecutionMode.LIVE,
                          action: (CoreWorld) -> Unit) {
        val world = try { createTestWorld(coordinator, mode) } catch (failure: Throwable) {
            coordinator.close()
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            run { coordinator.use { action(world) } }
        }
    }

    private class EpochObserver(val world: CoreWorld,
                                val before: (PreTickEvent) -> Unit = {},
                                val after: (PostTickEvent) -> Unit = {}) : EventListener {
        val pre = CopyOnWriteArrayList<PreTickEvent>()
        val post = CopyOnWriteArrayList<PostTickEvent>()
        val postFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        @HandlesEvent fun pre(event: PreTickEvent) {
            if (event.world !== world) return
            pre.add(event)
            try {
                ThreadGuard.ensureAsyncTickThread("PreTick test")
                before(event)
            } catch (t: Throwable) { failure.set(t) }
        }
        @HandlesEvent fun post(event: PostTickEvent) {
            if (event.world !== world) return
            post.add(event)
            try {
                ThreadGuard.ensureAsyncTickThread("PostTick test")
                after(event)
            } catch (t: Throwable) { failure.set(t) }
            finally { postFinished.countDown() }
        }
    }
}
