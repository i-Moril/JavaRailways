package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.setControllerForTest
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.runtime.OperationalWorldRuntime
import work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class DefaultRailwayObservationServiceTest {
    @Test
    fun `latest and subscriptions receive each completed epoch once`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val worldId = UUID.randomUUID()
        val epochs = mutableListOf<Long>()
        val subscription = service.subscribe(plugin("signal"), worldId) { callbackWorldId, observation ->
            assertEquals(worldId, callbackWorldId)
            epochs += observation.stamp.physicsEpoch
        }

        assertEquals(1L, service.publish(worldId, frame(1L), 1L)?.stamp?.physicsEpoch)
        assertEquals(null, service.publish(worldId, frame(1L), 1L))
        assertEquals(2L, service.publish(worldId, frame(2L), 2L)?.stamp?.physicsEpoch)

        assertEquals(listOf(1L, 2L), epochs)
        assertEquals(2L, service.latest(worldId).orElseThrow().stamp.physicsEpoch)
        subscription.close()
        subscription.close()
        service.publish(worldId, frame(3L), 3L)
        assertEquals(listOf(1L, 2L), epochs)
    }

    @Test
    fun `listener failures are isolated and owner cleanup is identity scoped`() {
        val errorCount = AtomicInteger()
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.level.intValue() >= Level.SEVERE.intValue()) errorCount.incrementAndGet()
                }
                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        val service = DefaultRailwayObservationService(logger)
        val worldId = UUID.randomUUID()
        val failingOwner = plugin("failing")
        val healthyOwner = plugin("healthy")
        val healthyCalls = AtomicInteger()
        service.subscribe(failingOwner, worldId) { _, _ -> error("listener failure") }
        service.subscribe(healthyOwner, worldId) { _, _ -> healthyCalls.incrementAndGet() }

        service.publish(worldId, frame(1L), 1L)
        service.unregisterOwner(failingOwner)
        service.unregisterOwner(failingOwner)
        service.publish(worldId, frame(2L), 2L)

        assertEquals(1, errorCount.get())
        assertEquals(2, healthyCalls.get())
    }

    @Test
    fun `world removal and close clear retained state and registrations`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val worldId = UUID.randomUUID()
        val calls = AtomicInteger()
        service.subscribe(plugin("signal"), worldId) { _, _ -> calls.incrementAndGet() }
        service.publish(worldId, frame(1L), 1L)

        service.removeWorld(worldId)
        assertTrue(service.latest(worldId).isEmpty)

        service.close()
        service.close()
        assertEquals(null, service.publish(worldId, frame(2L), 2L))
        assertTrue(service.latest(worldId).isEmpty)
        assertEquals(1, calls.get())
        assertThrows(IllegalStateException::class.java) {
            service.subscribe(plugin("late"), worldId) { _, _ -> Unit }
        }
    }

    @Test
    fun `current owner views retain one notification per physical epoch across topology changes`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val worldId = UUID.randomUUID()
        val epochs = mutableListOf<Long>()
        service.subscribe(plugin("signal"), worldId) { _, observation -> epochs += observation.stamp.physicsEpoch }
        val firstFrame = frame(1L)
        val first = service.current(worldId, firstFrame)
        assertSame(first, service.current(worldId, firstFrame))
        assertTrue(epochs.isEmpty())
        assertSame(first, service.publish(worldId, firstFrame, 1L))

        val editedFrame = frame(1L, topologyRevision = 5L)
        val edited = service.current(worldId, editedFrame)
        assertEquals(5L, edited?.stamp?.topologyRevision)
        assertNull(service.publish(worldId, editedFrame, 1L))
        assertSame(edited, service.latest(worldId).orElseThrow())
        assertEquals(listOf(1L), epochs)

        val nextFrame = frame(2L, topologyRevision = 5L)
        val next = service.current(worldId, nextFrame)
        assertSame(next, service.publish(worldId, nextFrame, 2L))
        assertEquals(listOf(1L, 2L), epochs)
        assertThrows(IllegalArgumentException::class.java) { service.current(worldId, editedFrame) }
        assertSame(next, service.latest(worldId).orElseThrow())
        service.close()
        assertNull(service.current(worldId, frame(3L)))
    }

    @Test
    fun `maintenance placement immediately replaces current occupancy without another epoch notification`() {
        val world = createTestWorld()
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val worldId = UUID.randomUUID()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(180.0, segmentCount = 1))
            val train = createTrainWithConfig("placed", rail, world, listOf(10.0))
            world.onOwnerForTest { world.publishOwnerChanges(listOf(train)) }
            val beforeFrame = world.latestWorldFrame()
            val previousIntervals = beforeFrame.trains.single().occupiedIntervals
            val targetInterval = RailInterval(rail.id, 90.0, 100.0)
            var notifications = 0
            service.subscribe(plugin("signal"), worldId) { _, _ -> notifications++ }
            val before = requireNotNull(service.publish(worldId, beforeFrame, beforeFrame.physicsEpoch))
            assertEquals(listOf(train.id), before.occupancy.blockingTrainIds(previousIntervals))
            assertTrue(before.occupancy.blockingTrainIds(listOf(targetInterval)).isEmpty())

            world.onOwnerForTest {
                train.setLocation(rail.viewAB.getRailLocationAt(100.0))
                world.publishOwnerChanges(listOf(train))
            }
            val afterFrame = world.latestWorldFrame()
            val current = requireNotNull(service.current(worldId, afterFrame))
            assertEquals(before.stamp.physicsEpoch, current.stamp.physicsEpoch)
            assertTrue(current.occupancy.blockingTrainIds(previousIntervals).isEmpty())
            assertEquals(listOf(train.id), current.occupancy.blockingTrainIds(listOf(targetInterval)))
            assertSame(current, service.latest(worldId).orElseThrow())
            assertSame(current, service.current(worldId, afterFrame))
            assertNull(service.publish(worldId, afterFrame, afterFrame.physicsEpoch))
            assertEquals(1, notifications)
            assertEquals(listOf(train.id), before.occupancy.blockingTrainIds(previousIntervals))
        } finally {
            service.close()
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `publication rejects a frame from a different ticket epoch`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        assertThrows(IllegalArgumentException::class.java) {
            service.publish(UUID.randomUUID(), frame(2L), 1L)
        }
    }

    @Test
    fun `conversion paused inside fromCore leaves another world physics publication and domain progressing`() {
        val guard = ThreadGuardHost.registerPlatformBridge(BukkitThreadGuardBridge)
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val first = ObservationWorld()
        val second = ObservationWorld()
        val workers = Executors.newFixedThreadPool(2)
        val barrier = ConversionBarrier(first.world.latestWorldFrame())
        val secondNotifications = mutableListOf<Long>()
        service.subscribe(plugin("signal"), second.id) { _, observation ->
            secondNotifications += observation.stamp.physicsEpoch
        }
        try {
            val paused = workers.submit<RailwayObservation?> {
                BukkitThreadGuardBridge.runInSimulationContext(first.world) {
                    service.current(first.id, barrier.frame)
                }
            }
            assertTrue(barrier.entered.await(5, TimeUnit.SECONDS))
            workers.submit { second.advance(service) }.get(5, TimeUnit.SECONDS)
            assertEquals(1L, second.world.latestWorldFrame().physicsEpoch)
            assertEquals(second.initialArc + 0.25, second.train.publishedFrame.head.arcLengthInAbMeters, 0.005)
            assertEquals(1, second.domainPhases)
            assertEquals(listOf(1L), secondNotifications)
            assertEquals(1L, service.latest(second.id).orElseThrow().stamp.physicsEpoch)
            assertTrue(service.latest(first.id).isEmpty)
            assertEquals(1L, barrier.release.count)

            barrier.release.countDown()
            assertSame(paused.get(5, TimeUnit.SECONDS), service.current(first.id, barrier.frame))
        } finally {
            barrier.release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            first.close()
            second.close()
            service.close()
            guard.close()
        }
    }

    @Test
    fun `same world current and publish serialize one actual conversion and retain epoch notification identity`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val train = createTrainWithConfig("observed", rail, world, listOf(10.0))
        world.onOwnerForTest { world.publishOwnerChanges(listOf(train)) }
        val worldId = UUID.randomUUID()
        val barrier = ConversionBarrier(world.latestWorldFrame())
        val workers = Executors.newFixedThreadPool(2)
        val ownerThread = AtomicReference<Thread>()
        val contenderThread = AtomicReference<Thread>()
        val notifications = mutableListOf<RailwayObservation>()
        service.subscribe(plugin("signal"), worldId) { _, observation -> notifications += observation }
        try {
            val current = workers.submit<RailwayObservation?> {
                ownerThread.set(Thread.currentThread())
                service.current(worldId, barrier.frame)
            }
            assertTrue(barrier.entered.await(5, TimeUnit.SECONDS))
            val published = workers.submit<RailwayObservation?> {
                contenderThread.set(Thread.currentThread())
                service.publish(worldId, barrier.frame, barrier.frame.physicsEpoch)
            }
            awaitBlocked(contenderThread, ownerThread.get())
            barrier.release.countDown()
            val observation = current.get(5, TimeUnit.SECONDS)
            assertSame(observation, published.get(5, TimeUnit.SECONDS))
            assertSame(observation, notifications.single())
            assertEquals(1, barrier.reads.get())
            assertNull(service.publish(worldId, barrier.frame, barrier.frame.physicsEpoch))
            assertSame(observation, service.current(worldId, barrier.frame))
        } finally {
            barrier.release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            service.close()
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `listeners can query the same world and unregister through another thread`() {
        val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
        val worker = Executors.newSingleThreadExecutor()
        val worldId = UUID.randomUUID()
        val frame = frame(1L)
        val owner = plugin("signal")
        val completed = AtomicBoolean()
        service.subscribe(owner, worldId) { _, observation ->
            worker.submit {
                assertSame(observation, service.current(worldId, frame))
                service.unregisterOwner(owner)
                completed.set(true)
            }.get(5, TimeUnit.SECONDS)
        }
        try {
            service.publish(worldId, frame, 1L)
            assertTrue(completed.get())
            service.publish(worldId, frame(2L), 2L)
        } finally {
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            service.close()
        }
    }

    @Test
    fun `remove and close reject late conversion while world subscriptions retain their logical UUID`() {
        for (closeService in listOf(false, true)) {
            val service = DefaultRailwayObservationService(Logger.getAnonymousLogger())
            val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
            val train = createTrainWithConfig("retiring", rail, world, listOf(10.0))
            world.onOwnerForTest { world.publishOwnerChanges(listOf(train)) }
            val worldId = UUID.randomUUID()
            val barrier = ConversionBarrier(world.latestWorldFrame())
            val worker = Executors.newSingleThreadExecutor()
            val retained = AtomicInteger()
            val removed = AtomicInteger()
            val unsubscribed = service.subscribe(plugin("unsubscribe"), worldId) { _, _ -> removed.incrementAndGet() }
            val owner = plugin("disabled")
            service.subscribe(owner, worldId) { _, _ -> removed.incrementAndGet() }
            service.subscribe(plugin("retained"), worldId) { _, _ -> retained.incrementAndGet() }
            try {
                val pending = worker.submit<RailwayObservation?> {
                    service.publish(worldId, barrier.frame, barrier.frame.physicsEpoch)
                }
                assertTrue(barrier.entered.await(5, TimeUnit.SECONDS))
                // These lifecycle calls must finish while the actual converter remains paused.
                unsubscribed.close()
                service.unregisterOwner(owner)
                if (closeService) service.close() else service.removeWorld(worldId)
                assertTrue(service.latest(worldId).isEmpty)
                val replacement = service.publish(worldId, frame(1L), 1L)
                if (closeService) assertNull(replacement)
                else {
                    assertEquals(1, retained.get())
                    assertSame(replacement, service.latest(worldId).orElseThrow())
                }
                barrier.release.countDown()
                assertNull(pending.get(5, TimeUnit.SECONDS))
                assertEquals(0, removed.get())
                assertEquals(if (closeService) 0 else 1, retained.get())
                if (closeService) assertTrue(service.latest(worldId).isEmpty)
                else assertSame(replacement, service.latest(worldId).orElseThrow())
            } finally {
                barrier.release.countDown()
                worker.shutdownNow()
                worker.awaitTermination(5, TimeUnit.SECONDS)
                service.close()
                world.physicsCoordinator.close()
                (world.tickManager as AutoCloseable).close()
            }
        }
    }

    /**
     * Core factories defensively copy lists. Interpose only the immutable list read in this fixture
     * so the real fromCore projection can pause without adding a production converter/test hook.
     */
    private class ConversionBarrier(val frame: RailwayWorldFrameSnapshot) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()

        init {
            val trains = frame.trains
            check(trains.isNotEmpty())
            val blocked = AtomicBoolean()
            val values = object : AbstractList<TrainFrameSnapshot>() {
                override val size: Int get() = trains.size
                override fun get(index: Int): TrainFrameSnapshot {
                    reads.incrementAndGet()
                    if (blocked.compareAndSet(false, true)) {
                        check(Thread.currentThread().stackTrace.any {
                            it.className.contains("RailwayObservation") && it.methodName == "fromCore"
                        })
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    return trains[index]
                }
            }
            RailwayWorldFrameSnapshot::class.java.getDeclaredField("trains").apply {
                isAccessible = true
                set(frame, values)
            }
        }
    }

    private fun awaitBlocked(thread: AtomicReference<Thread>, owner: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val threads = ManagementFactory.getThreadMXBean()
        while (System.nanoTime() < deadline) {
            val info = thread.get()?.let { threads.getThreadInfo(it.threadId()) }
            if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == owner.threadId()) return
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        error("same-world publication did not wait for the active conversion")
    }

    private class ObservationWorld : AutoCloseable {
        val id = UUID.randomUUID()
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        private val runtime: OperationalWorldRuntime
        val train: work.art1st.javarailways.core.physics.train.CoreTrain
        val initialArc: Double
        var domainPhases = 0

        init {
            if (BasicPlugin.instanceOrNull() == null) BasicPlugin.INSTANCE = BasicPlugin(101)
            if (SimpleATOPlugin.instanceOrNull() == null) SimpleATOPlugin.INSTANCE = SimpleATOPlugin(102)
            runtime = OperationalWorldRuntime.create(world, id.toString(),
                basicDataFactory = { BasicWorldData() }, dispatcherFactory = { domain ->
                    TimetableDispatcher(
                        routeAdmissionContextProvider = { null },
                        signalRestrictionSnapshots = domain.signalRestrictions,
                        inspectionRuntime = domain.inspectionRuntime,
                        infrastructureDependencyService = object : InfrastructureDependencyService {
                            override fun set(request: work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest): Nothing =
                                error("idle dispatcher must not create dependencies")
                            override fun remove(owner: work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner): Nothing =
                                error("idle dispatcher must not remove dependencies")
                        })
                })
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
            train = createTrainWithConfig("moving", rail, world, listOf(10.0))
            train.setControllerForTest(object : TrainController(train) {
                init {
                    speed = 5.0
                }
            })
            world.onOwnerForTest { world.publishOwnerChanges(listOf(train)) }
            initialArc = train.publishedFrame.head.arcLengthInAbMeters
            world.physicsCoordinator.start()
        }

        fun advance(service: DefaultRailwayObservationService) = BukkitThreadGuardBridge.runInSimulationContext(world) {
            world.tickManager.preTick(50L)
            val ticket = world.physicsCoordinator.stepSimulation(50.0)
            ticket.await()
            world.physicsCoordinator.processBetweenFrames {
                runtime.afterPublication(ticket.epoch, publishObservation = {
                    val frame = world.latestWorldFrame()
                    val current = service.current(id, frame)
                    assertSame(current, service.publish(id, frame, ticket.epoch))
                }, beforeDispatcher = { domainPhases++ })
            }
        }

        override fun close() = BukkitThreadGuardBridge.runInSimulationContext(world) {
            world.physicsCoordinator.closeAndAwaitTermination()
            (world.tickManager as TickManagerImpl).closeAndAwaitTermination(world.runtimeId)
            runtime.close()
        }
    }

    private fun frame(epoch: Long, topologyRevision: Long = 4L) = RailwayWorldFrameSnapshot.create(
        epoch,
        topologyRevision,
        emptyList(),
        ActiveRailContinuationsSnapshot.empty(topologyRevision)
    )

    private fun plugin(name: String): Plugin {
        lateinit var proxy: Plugin
        proxy = Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getName" -> name
                "isEnabled" -> true
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "Plugin($name)"
                else -> primitiveDefault(method.returnType)
            }
        } as Plugin
        return proxy
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
