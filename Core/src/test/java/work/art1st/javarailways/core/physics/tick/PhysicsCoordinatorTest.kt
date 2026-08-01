package work.art1st.javarailways.core.physics.tick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.CollisionEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PhysicsCoordinatorTest {
    @Test
    fun `physics publishes one immutable versioned frame per active train`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            maxStepMillis = 1_000_000L
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("render-dirty", rail, world, listOf(10.0))
        val initialFrame = train.publishedFrame
        train.setController(ConstantSpeedController(train, world))
        coordinator.start()
        try {
            val firstTicket = coordinator.wakeFromMainThread()
            firstTicket.await()
            val firstFrame = world.drainPublishedTrainFrames().single()

            assertEquals(firstTicket.epoch, firstFrame.physicsEpoch)
            assertTrue(firstFrame.revision > initialFrame.revision)
            assertEquals(train.id, firstFrame.trainId)
            assertNotEquals(initialFrame.cars.single().state.positionValue(), firstFrame.cars.single().state.positionValue())
            assertEquals(firstFrame, train.publishedFrame)

            val secondTicket = coordinator.wakeFromMainThread()
            secondTicket.await()
            val thirdTicket = coordinator.wakeFromMainThread()
            thirdTicket.await()
            val coalesced = world.drainPublishedTrainFrames().single()

            assertEquals(thirdTicket.epoch, coalesced.physicsEpoch)
            assertTrue(coalesced.revision > firstFrame.revision)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `stationary active train does not publish visual frames`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            maxStepMillis = 1_000_000L
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("stationary-render", rail, world, listOf(10.0))
        train.setController(MutableSpeedController(train, world, requestedSpeed = 0.0))
        coordinator.start()
        try {
            repeat(3) { coordinator.wakeFromMainThread().await() }

            assertTrue(world.drainPublishedTrainFrames().isEmpty())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `moving train publishes one terminal frame when speed reaches zero`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            maxStepMillis = 1_000_000L
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("stopping-render", rail, world, listOf(10.0))
        val controller = MutableSpeedController(train, world, requestedSpeed = 10.0)
        train.setController(controller)
        coordinator.start()
        try {
            coordinator.wakeFromMainThread().await()
            assertEquals(10.0, world.drainPublishedTrainFrames().single().speedMetersPerSecond)

            controller.requestedSpeed = 0.0
            coordinator.wakeFromMainThread().await()
            assertEquals(0.0, world.drainPublishedTrainFrames().single().speedMetersPerSecond)

            coordinator.wakeFromMainThread().await()
            assertTrue(world.drainPublishedTrainFrames().isEmpty())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `physics keeps publishing while visual frames are not consumed`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            maxStepMillis = 1_000_000L
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("slow-consumer", rail, world, listOf(10.0))
        train.setController(ConstantSpeedController(train, world))
        coordinator.start()
        try {
            repeat(32) { coordinator.wakeFromMainThread().await() }

            val metrics = world.trainFrameMailboxMetrics()
            assertEquals(1, metrics.size)
            assertEquals(31L, metrics.coalescedFrames)
            assertEquals(0L, metrics.droppedCapacityFrames)
            assertEquals(32L, world.drainPublishedTrainFrames().single().physicsEpoch)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `tick end capture returns the currently running ticket`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            maxStepMillis = 1_000_000L
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("blocking", rail, world, listOf(10.0))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        train.setController(BlockingController(train, world, entered, release))
        coordinator.start()
        try {
            val startedTicket = coordinator.wakeFromMainThread()
            entered.await()
            val duplicateWakeTicket = coordinator.wakeFromMainThread()
            val capturedAtTickEnd = coordinator.captureCurrentTicket()
            assertTrue(startedTicket === duplicateWakeTicket)
            assertTrue(startedTicket === capturedAtTickEnd)
            release.countDown()
            capturedAtTickEnd.await()
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun `active train detects static overlap once and both derail next epoch`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 2,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            maxStepMillis = 1_000_000L
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val moving = createTrainWithConfig("A", rail, world, listOf(10.0))
        val stationary = createTrainWithConfig("B", rail, world, listOf(10.0))
        moving.setController(ConstantSpeedController(moving, world))
        val listener = CollisionCounter()
        val registration = PhysicsRailEventBus.register(listener)
        coordinator.start()
        assertEquals("B", world.querySweptCollision(moving).collidingTrainId)
        try {
            coordinator.wakeFromMainThread().await()
            assertEquals(1, listener.count.get())
            coordinator.wakeFromMainThread().await()

            assertTrue(moving.isDerailed)
            assertTrue(stationary.isDerailed)
            assertEquals(1, listener.count.get())
        } finally {
            registration.close()
            coordinator.close()
        }
    }

    @Test
    fun `publishes controller movement before ticket completes`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 2,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            maxStepMillis = 1_000_000L
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        train.setController(ConstantSpeedController(train, world))
        val before = train.head.location.arcLength
        coordinator.start()
        try {
            coordinator.wakeFromMainThread().await()
            assertTrue(train.head.location.arcLength > before)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `measures delta between adjacent physics epoch starts`() {
        val clock = AtomicLong(1_000_000_000L)
        val coordinator = PhysicsCoordinator(
            workerThreads = 2,
            nanoTime = clock::get,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            maxStepMillis = 1_000_000L
        )
        CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        coordinator.start()
        try {
            val first = coordinator.wakeFromMainThread().await()
            clock.addAndGet(73_000_000L)
            val second = coordinator.wakeFromMainThread().await()

            assertEquals(PhysicsCoordinator.BOOTSTRAP_DELTA_MILLIS, first.deltaMillis)
            assertEquals(73.0, second.deltaMillis, 0.0001)
            assertEquals(2L, second.epoch)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `repeated overloads stall without returning an old successful epoch`() {
        val clock = AtomicLong(0L)
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            nanoTime = { clock.addAndGet(30_000_000L) },
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            maxStepMillis = 50L,
            overloadWindowMillis = 1_000_000L,
            overloadsBeforeStall = 3
        )
        CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        coordinator.start()
        try {
            coordinator.wakeFromMainThread().await()
            coordinator.wakeFromMainThread().await()
            assertThrows(ExecutionException::class.java) {
                coordinator.wakeFromMainThread().await()
            }
            assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
            assertThrows(PhysicsStalledException::class.java) {
                coordinator.captureCurrentTicket()
            }
        } finally {
            coordinator.close()
        }
    }

    @OptIn(AsyncEventApi::class)
    class CollisionCounter : EventListener {
        val count = AtomicInteger()

        @HandlesEvent
        fun onCollision(event: CollisionEvent) {
            count.incrementAndGet()
        }
    }

    private class BlockingController(
        train: CoreTrain,
        world: CoreWorld,
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : TrainController(train, world) {
        override fun tick(deltaT: Double) {
            entered.countDown()
            release.await()
        }
    }

    private class ConstantSpeedController(train: CoreTrain, world: CoreWorld) : TrainController(train, world) {
        override fun tick(deltaT: Double) {
            speed = 10.0
            train.moveTrainForward(activeSpeed * deltaT * 0.001)
        }
    }

    private class MutableSpeedController(
        train: CoreTrain,
        world: CoreWorld,
        @Volatile var requestedSpeed: Double
    ) : TrainController(train, world) {
        override fun tick(deltaT: Double) {
            speed = requestedSpeed
            train.moveTrainForward(activeSpeed * deltaT * 0.001)
        }
    }

    private object DummyTickManager : TickManager {
        override fun register(obj: work.art1st.javarailways.core.api.util.tick.TickingObject) = Unit
        override fun unregister(obj: work.art1st.javarailways.core.api.util.tick.TickingObject) = Unit
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit

        override fun <T> scheduleMainThreadAfterCommitTask(task: Callable<T>): Future<T> =
            FutureTask(task).also { it.run() }

        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> =
            FutureTask(task).also { it.run() }
    }
}
