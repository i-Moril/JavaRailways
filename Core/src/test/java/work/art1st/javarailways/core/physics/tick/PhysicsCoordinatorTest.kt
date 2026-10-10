package work.art1st.javarailways.core.physics.tick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.CollisionEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.RailwayEpochProcessor
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PhysicsCoordinatorTest {
    @Test
    fun `configured workers execute controller batches concurrently`() {
        val workerThreads = 4
        val controllerCount = 8
        val coordinator = PhysicsCoordinator(
            workerThreads = workerThreads,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val enteredWorkers = CountDownLatch(workerThreads)
        val releaseWorkers = CountDownLatch(1)
        val inFlight = AtomicInteger()
        val peakInFlight = AtomicInteger()
        val invocations = AtomicInteger()
        val workerNames = ConcurrentHashMap.newKeySet<String>()

        repeat(controllerCount) { index ->
            val train = createTrainWithConfig("concurrent-$index", rail, world, listOf(10.0))
            train.setController(object : TrainController(train, world) {
                override fun tick(deltaT: Double) {
                    val currentInFlight = inFlight.incrementAndGet()
                    peakInFlight.accumulateAndGet(currentInFlight, ::maxOf)
                    workerNames += Thread.currentThread().name
                    enteredWorkers.countDown()
                    try {
                        check(releaseWorkers.await(5, TimeUnit.SECONDS)) { "timed out waiting to release workers" }
                        invocations.incrementAndGet()
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            })
        }

        coordinator.start()
        try {
            val ticket = coordinator.stepSimulation(50.0)
            assertTrue(enteredWorkers.await(5, TimeUnit.SECONDS), "all configured workers should enter the epoch")
            assertEquals(workerThreads, peakInFlight.get())
            assertEquals(workerThreads, workerNames.size)
            assertTrue(workerNames.all { it.startsWith("JavaRailways-PhysicsWorker-") })

            releaseWorkers.countDown()
            ticket.await()
            assertEquals(controllerCount, invocations.get())
        } finally {
            releaseWorkers.countDown()
            coordinator.close()
        }
    }

    @Test
    fun `isolated simulation advances with caller supplied logical delta`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 2,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("simulation-step", rail, world, listOf(10.0))
        val deltas = mutableListOf<Double>()
        train.setController(object : TrainController(train, world) {
            override fun tick(deltaT: Double) { synchronized(deltas) { deltas += deltaT } }
        })
        coordinator.start()
        try {
            val first = coordinator.stepSimulation(50.0).await()
            val second = coordinator.stepSimulation(50.0).await()

            assertEquals(PhysicsWakeSource.SIMULATION, first.source)
            assertEquals(listOf(50.0, 50.0), deltas)
            assertEquals(first.epoch + 1, second.epoch)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `published frame processors run on physics workers before epoch completion`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 2,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("processor", rail, world, listOf(10.0))
        train.setController(ConstantSpeedController(train, world))
        val processedThreads = mutableListOf<String>()
        var completedEpoch = -1L
        coordinator.registerPublishedFrameProcessor(object : PublishedTrainFrameProcessor {
            override fun process(train: CoreTrain, physicsEpoch: Long) {
                synchronized(processedThreads) { processedThreads += Thread.currentThread().name }
                assertEquals(physicsEpoch, train.publishedFrame.physicsEpoch)
            }

            override fun completeEpoch(liveTrainIds: Set<String>, physicsEpoch: Long) {
                assertEquals(setOf(train.id), liveTrainIds)
                completedEpoch = physicsEpoch
            }
        })
        coordinator.start()
        try {
            val ticket = coordinator.stepSimulation(50.0)
            ticket.await()

            assertEquals(ticket.epoch, completedEpoch)
            assertEquals(1, processedThreads.size)
            assertTrue(processedThreads.single().startsWith("JavaRailways-PhysicsWorker-"))
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `physics publishes one immutable versioned frame per active train`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            epochOverloadThresholdMillis = 1_000_000L
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
    fun `ticket completes only after an exact coherent world frame is published`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val trainB = createTrainWithConfig("world-frame-b", rail, world, listOf(10.0))
        val trainA = createTrainWithConfig("world-frame-a", rail, world, listOf(10.0))
        trainA.setController(ConstantSpeedController(trainA, world))
        trainB.setController(ConstantSpeedController(trainB, world))
        coordinator.start()
        try {
            val ticket = coordinator.stepSimulation(50.0)
            ticket.await()

            val frame = world.latestWorldFrame()
            assertEquals(ticket.epoch, frame.physicsEpoch)
            assertEquals(world.topologyRevision, frame.topologyRevision)
            assertEquals(ticket.epoch, frame.occupancy.physicsEpoch)
            assertEquals(listOf(trainA.id, trainB.id).sorted(), frame.trains.map { it.trainId })
            assertTrue(frame.trains.all { it.physicsEpoch == ticket.epoch })
            val trainASpan = frame.trains.single { it.trainId == trainA.id }.sweptSpans.single()
            assertEquals(
                listOf(trainB.id),
                frame.occupancy.blockingTrainIds(
                    listOf(RailInterval(trainASpan.railId, trainASpan.minArcAB, trainASpan.maxArcAB)),
                    excludedTrainId = trainA.id
                )
            )
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (frame.trains as MutableList<TrainFrameSnapshot>).clear()
            }
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `exact ticket waits for synchronous railway epoch processor`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor { frame ->
            assertEquals(1L, frame.physicsEpoch)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting to release epoch processor" }
        })
        coordinator.start()
        try {
            val ticket = coordinator.stepSimulation(50.0)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(0L, world.latestWorldFrame().physicsEpoch)
            assertTrue(!ticket.isDone)

            release.countDown()
            ticket.await()
            assertEquals(ticket.epoch, world.latestWorldFrame().physicsEpoch)
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun `railway epoch processor failure stalls without publishing candidate frame`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor {
            error("signal recomputation failed")
        })
        coordinator.start()
        try {
            val failure = assertThrows(ExecutionException::class.java) {
                coordinator.stepSimulation(50.0).await()
            }

            assertTrue(failure.cause is PhysicsStalledException)
            assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
            assertEquals(0L, world.latestWorldFrame().physicsEpoch)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `railway epoch processor membership is static after start`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val processor = RailwayEpochProcessor { }
        coordinator.registerRailwayEpochProcessor(processor)
        coordinator.start()
        try {
            assertThrows(IllegalStateException::class.java) {
                coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor { })
            }
            assertThrows(IllegalStateException::class.java) {
                coordinator.unregisterRailwayEpochProcessor(processor)
            }
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `closed coordinator rejects new railway epoch processor`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        coordinator.close()

        assertThrows(IllegalStateException::class.java) {
            coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor { })
        }
    }

    @Test
    fun `close remains terminal after an in flight worker returns late`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("close-in-flight-worker", rail, world, listOf(10.0))
        val coordinatorThread = AtomicReference<Thread>()
        val workerThread = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ticks = AtomicInteger()
        coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor {
            coordinatorThread.set(Thread.currentThread())
        })
        train.setController(object : TrainController(train, world) {
            override fun tick(deltaT: Double) {
                if (ticks.incrementAndGet() == 1) return
                workerThread.set(Thread.currentThread())
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting to release worker" }
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting for late worker return" }
                }
            }
        })
        coordinator.start()
        try {
            coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
            val previousWorldFrame = world.latestWorldFrame()
            val previousTrainFrame = train.publishedFrame
            val ticket = coordinator.stepSimulation(50.0)
            assertTrue(entered.await(5, TimeUnit.SECONDS), "worker should be running before close")

            coordinator.close()
            val failure = assertThrows(ExecutionException::class.java) {
                ticket.completion.get(5, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is PhysicsStalledException)
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "close should interrupt the running worker")
            release.countDown()
            coordinatorThread.get().join(5_000L)
            workerThread.get().join(5_000L)

            assertTrue(!coordinatorThread.get().isAlive, "coordinator should exit after close")
            assertTrue(!workerThread.get().isAlive, "late worker should exit after release")
            assertEquals(PhysicsCoordinatorStatus.CLOSED, coordinator.status)
            assertSame(previousWorldFrame, world.latestWorldFrame())
            assertSame(previousTrainFrame, train.publishedFrame)
            assertThrows(IllegalStateException::class.java) { coordinator.captureCurrentTicket() }
            assertThrows(IllegalStateException::class.java) { coordinator.stepSimulation(50.0) }
            coordinator.close()
            assertEquals(PhysicsCoordinatorStatus.CLOSED, coordinator.status)
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `close rejects late epoch processor publication even after interruption is consumed`(overloaded: Boolean) {
        val clock = AtomicLong()
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            nanoTime = clock::get,
            epochOverloadThresholdMillis = 1L,
            overloadsBeforeStall = 1,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val coordinatorThread = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor { frame ->
            coordinatorThread.set(Thread.currentThread())
            if (frame.physicsEpoch > 1L) {
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting to release epoch processor" }
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting for late processor return" }
                }
                if (overloaded) clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(2L))
            }
        })
        coordinator.start()
        try {
            coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
            val previousFrame = world.latestWorldFrame()
            val previousMetrics = coordinator.latestMetrics()
            val ticket = coordinator.stepSimulation(50.0)
            assertTrue(entered.await(5, TimeUnit.SECONDS), "processor should be running before close")

            coordinator.close()
            val failure = assertThrows(ExecutionException::class.java) {
                ticket.completion.get(5, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is PhysicsStalledException)
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "processor should consume shutdown interruption")
            release.countDown()
            coordinatorThread.get().join(5_000L)

            assertTrue(!coordinatorThread.get().isAlive, "coordinator should exit after late processor return")
            assertEquals(PhysicsCoordinatorStatus.CLOSED, coordinator.status)
            assertSame(previousFrame, world.latestWorldFrame())
            assertSame(previousMetrics, coordinator.latestMetrics())
            assertTrue(ticket.completion.isCompletedExceptionally)
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun `candidate occupancy is revalidated after railway epoch processing`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting to release epoch processor" }
        })
        coordinator.start()
        try {
            val ticket = coordinator.stepSimulation(50.0)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            world.rebuildSweptOccupancyIndex(ticket.epoch)
            release.countDown()

            val failure = assertThrows(ExecutionException::class.java) { ticket.await() }
            assertTrue(failure.cause is PhysicsStalledException)
            assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
            assertEquals(0L, world.latestWorldFrame().physicsEpoch)
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @Test
    fun `railway epoch processor time contributes to epoch metrics`() {
        val clock = AtomicLong()
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            nanoTime = clock::get,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        coordinator.registerRailwayEpochProcessor(RailwayEpochProcessor {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(7L))
        })
        coordinator.start()
        try {
            val metrics = coordinator.stepSimulation(50.0).await()

            assertEquals(TimeUnit.MILLISECONDS.toNanos(7L), metrics.coordinatorNanos)
            assertEquals(TimeUnit.MILLISECONDS.toNanos(7L), metrics.wallNanos)
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
            epochOverloadThresholdMillis = 1_000_000L
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
            epochOverloadThresholdMillis = 1_000_000L
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
            epochOverloadThresholdMillis = 1_000_000L
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
            epochOverloadThresholdMillis = 1_000_000L
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
            assertTrue(entered.await(5, TimeUnit.SECONDS), "controller should enter the epoch")
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
    fun `begin shutdown waits for the exact running epoch and prevents later wakes`() {
        val clock = AtomicLong(1_000_000_000L)
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            nanoTime = clock::get,
            mainHeartbeatTimeoutMillis = 1L,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("shutdown-running", rail, world, listOf(10.0))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger()
        train.setController(object : TrainController(train, world) {
            override fun tick(deltaT: Double) {
                invocations.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting to release shutdown worker" }
                speed = 10.0
                train.moveTrainForward(activeSpeed * deltaT * 0.001)
            }
        })
        coordinator.start()
        try {
            val previousFrame = world.latestWorldFrame()
            val previousHead = train.head.location.arcLength
            val runningTicket = coordinator.stepSimulation(50.0)
            assertTrue(entered.await(5, TimeUnit.SECONDS), "worker should be running before shutdown begins")

            val shutdownTicket = coordinator.beginShutdown()
            assertSame(runningTicket, shutdownTicket)
            assertSame(shutdownTicket, coordinator.beginShutdown())
            assertTrue(!shutdownTicket.isDone, "beginShutdown must not complete or cancel the running epoch")
            assertSame(previousFrame, world.latestWorldFrame())
            assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
            assertThrows(IllegalStateException::class.java) { coordinator.wakeFromMainThread() }
            assertThrows(IllegalStateException::class.java) { coordinator.stepSimulation(50.0) }
            assertThrows(IllegalStateException::class.java) { coordinator.start() }
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100L))
            coordinator.timerWake()

            release.countDown()
            val metrics = shutdownTicket.completion.get(5, TimeUnit.SECONDS)
            val finalFrame = world.latestWorldFrame()
            assertEquals(shutdownTicket.epoch, metrics.epoch)
            assertEquals(shutdownTicket.epoch, finalFrame.physicsEpoch)
            assertEquals(shutdownTicket.epoch, finalFrame.occupancy.physicsEpoch)
            assertEquals(shutdownTicket.epoch, finalFrame.trains.single().physicsEpoch)
            assertEquals(previousHead + 0.5, train.head.location.arcLength, 1e-6)
            assertSame(shutdownTicket, coordinator.captureCurrentTicket())
            assertSame(shutdownTicket, coordinator.beginShutdown())

            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100L))
            repeat(3) { coordinator.timerWake() }
            assertSame(shutdownTicket, coordinator.captureCurrentTicket())
            assertSame(finalFrame, world.latestWorldFrame())
            assertSame(metrics, coordinator.latestMetrics())
            assertEquals(1, invocations.get())
            assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `idle shutdown is idempotent and cannot be reopened by start`(started: Boolean) {
        val clock = AtomicLong(1_000_000_000L)
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            nanoTime = clock::get,
            mainHeartbeatTimeoutMillis = 1L,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        try {
            if (started) {
                coordinator.start()
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100L))
                coordinator.timerWake()
                val metrics = coordinator.captureCurrentTicket().completion.get(5, TimeUnit.SECONDS)
                assertEquals(PhysicsWakeSource.TIMER_FALLBACK, metrics.source)
                assertEquals(1L, metrics.epoch)
            }
            val completedTicket = coordinator.captureCurrentTicket()
            val finalFrame = world.latestWorldFrame()

            val shutdownTicket = coordinator.beginShutdown()
            assertSame(completedTicket, shutdownTicket)
            assertTrue(shutdownTicket.isDone)
            assertEquals(if (started) 1L else 0L, shutdownTicket.completion.get(5, TimeUnit.SECONDS).epoch)
            assertSame(shutdownTicket, coordinator.beginShutdown())
            assertThrows(IllegalStateException::class.java) { coordinator.start() }
            assertThrows(IllegalStateException::class.java) { coordinator.wakeFromMainThread() }
            assertThrows(IllegalStateException::class.java) { coordinator.stepSimulation(50.0) }
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100L))
            coordinator.timerWake()
            assertSame(finalFrame, world.latestWorldFrame())
            assertSame(shutdownTicket, coordinator.captureCurrentTicket())
            assertEquals(PhysicsCoordinatorStatus.RUNNING, coordinator.status)

            coordinator.close()
            assertThrows(IllegalStateException::class.java) { coordinator.beginShutdown() }
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `failed shutdown ticket never falls back to the previous successful epoch`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("shutdown-failure", rail, world, listOf(10.0))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger()
        train.setController(object : TrainController(train, world) {
            override fun tick(deltaT: Double) {
                if (invocations.incrementAndGet() == 1) return
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting to fail shutdown worker" }
                error("shutdown worker failed")
            }
        })
        coordinator.start()
        try {
            coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
            val previousFrame = world.latestWorldFrame()
            val previousMetrics = coordinator.latestMetrics()
            val runningTicket = coordinator.stepSimulation(50.0)
            assertTrue(entered.await(5, TimeUnit.SECONDS), "worker should enter the failing epoch")
            val shutdownTicket = coordinator.beginShutdown()
            assertSame(runningTicket, shutdownTicket)
            assertTrue(!shutdownTicket.isDone)

            release.countDown()
            val failure = assertThrows(ExecutionException::class.java) {
                shutdownTicket.completion.get(5, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is PhysicsStalledException)
            assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
            assertSame(previousFrame, world.latestWorldFrame())
            assertSame(previousMetrics, coordinator.latestMetrics())
            assertThrows(PhysicsStalledException::class.java) { coordinator.beginShutdown() }
            assertThrows(PhysicsStalledException::class.java) { coordinator.captureCurrentTicket() }
            assertThrows(PhysicsStalledException::class.java) { coordinator.wakeFromMainThread() }
            coordinator.timerWake()
            assertSame(previousFrame, world.latestWorldFrame())
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
            epochOverloadThresholdMillis = 1_000_000L
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
            epochOverloadThresholdMillis = 1_000_000L
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
            epochOverloadThresholdMillis = 1_000_000L
        )
        CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        coordinator.start()
        try {
            val first = coordinator.wakeFromMainThread().await()
            clock.addAndGet(73_000_000L)
            val second = coordinator.wakeFromMainThread().await()

            assertEquals(PhysicsCoordinator.BOOTSTRAP_DELTA_MILLIS, first.deltaMillis)
            assertEquals(73.0, second.rawDeltaMillis, 0.0001)
            assertEquals(73.0, second.deltaMillis, 0.0001)
            assertEquals(0.0, second.droppedDeltaMillis, 0.0001)
            assertEquals(2L, second.epoch)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `wall clock delta is clamped to two hundred milliseconds without catch up debt`() {
        val clock = AtomicLong(1_000_000_000L)
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            nanoTime = clock::get,
            fallbackPeriodMillis = 1_000_000L,
            mainHeartbeatTimeoutMillis = 1_000_000L,
            epochOverloadThresholdMillis = 1_000_000L,
            maxPhysicsDeltaMillis = 200.0
        )
        val world = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
        val train = createTrainWithConfig("clamped-delta", rail, world, listOf(10.0))
        val deltas = mutableListOf<Double>()
        train.setController(object : TrainController(train, world) {
            override fun tick(deltaT: Double) { deltas += deltaT }
        })
        coordinator.start()
        try {
            coordinator.wakeFromMainThread().await()
            clock.addAndGet(500_000_000L)
            val clamped = coordinator.wakeFromMainThread().await()
            clock.addAndGet(20_000_000L)
            val normal = coordinator.wakeFromMainThread().await()

            assertEquals(listOf(50.0, 200.0, 20.0), deltas)
            assertEquals(500.0, clamped.rawDeltaMillis, 0.0001)
            assertEquals(200.0, clamped.deltaMillis, 0.0001)
            assertEquals(300.0, clamped.droppedDeltaMillis, 0.0001)
            assertTrue(clamped.deltaClamped)
            assertEquals(1L, clamped.clampedEpochCount)
            assertEquals(20.0, normal.rawDeltaMillis, 0.0001)
            assertEquals(20.0, normal.deltaMillis, 0.0001)
            assertEquals(0.0, normal.droppedDeltaMillis, 0.0001)
            assertTrue(!normal.deltaClamped)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `explicit simulation delta above one tick limit is rejected`() {
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            epochOverloadThresholdMillis = 1_000_000L,
            maxPhysicsDeltaMillis = 200.0,
            automaticFallback = false
        )
        CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, coordinator)
        coordinator.start()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                coordinator.stepSimulation(200.001)
            }
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
            epochOverloadThresholdMillis = 50L,
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
            check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting to release blocking controller" }
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
