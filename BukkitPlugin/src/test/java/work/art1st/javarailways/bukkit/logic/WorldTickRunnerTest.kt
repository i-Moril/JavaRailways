package work.art1st.javarailways.bukkit.logic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.safety.SpeedSafetyMonitor
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.runtime.OperationalWorldRuntime
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorldTickRunnerTest {
    @Test
    fun `paused world leaves another live world and simulation advancing and coalesces its own wakes`() {
        val guard = work.art1st.javarailways.core.api.threading.host.ThreadGuardHost.registerPlatformBridge(BukkitThreadGuardBridge)
        val first = Fixture(WorldExecutionMode.LIVE)
        val second = Fixture(WorldExecutionMode.LIVE)
        val simulation = Fixture(WorldExecutionMode.SIMULATION)
        val executor = Executors.newSingleThreadExecutor()
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tickEntered = CountDownLatch(1)
        try {
            val held = executor.submit {
                first.world.onOwnerForTest {
                    paused.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            first.beforeTick = { tickEntered.countDown() }
            first.runner.offer(WorldTickWake(1L, 50L))
            assertTrue(tickEntered.await(5, TimeUnit.SECONDS))
            (2L..1000L).forEach { first.runner.offer(WorldTickWake(it, it * 50L)) }
            second.runner.offer(WorldTickWake(1L, 50L))
            simulation.runner.offer(WorldTickWake(1L, 50L))
            second.completed(1L).get(5, TimeUnit.SECONDS)
            simulation.completed(1L).get(5, TimeUnit.SECONDS)
            assertEquals(0L, first.world.latestWorldFrame().physicsEpoch)
            second.assertMoved(1L, 0.25)
            simulation.assertMoved(1L, 0.25)
            release.countDown()
            held.get(5, TimeUnit.SECONDS)
            first.completed(1000L).get(5, TimeUnit.SECONDS)
            first.assertMoved(2L, 0.5)
            // Retiring A's pending work cannot stop B or a separate simulation.
            first.close()
            second.runner.offer(WorldTickWake(2L, 100L))
            simulation.runner.offer(WorldTickWake(2L, 100L))
            second.completed(2L).get(5, TimeUnit.SECONDS)
            simulation.completed(2L).get(5, TimeUnit.SECONDS)
            second.assertMoved(2L, 0.5)
            simulation.assertMoved(2L, 0.5)
        } finally {
            release.countDown()
            executor.shutdownNow()
            first.close()
            second.close()
            simulation.close()
            guard.close()
        }
    }

    private class Fixture(mode: WorldExecutionMode) : AutoCloseable {
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false), mode)
        private val runtime: OperationalWorldRuntime
        private val train: CoreTrain
        private val initialX: Double
        private val completions = java.util.concurrent.ConcurrentHashMap<Long, CompletableFuture<Unit>>()
        var beforeTick: () -> Unit = {}
        val runner: WorldTickRunner
        private var closed = false

        init {
            if (BasicPlugin.instanceOrNull() == null) BasicPlugin.INSTANCE = BasicPlugin(101)
            if (SimpleATOPlugin.instanceOrNull() == null) SimpleATOPlugin.INSTANCE = SimpleATOPlugin(102)
            runtime = OperationalWorldRuntime.create(world, world.runtimeId,
                basicDataFactory = { BasicWorldData() }, dispatcherFactory = { domain ->
                    TimetableDispatcher(
                        routeAdmissionContextProvider = { null },
                        signalRestrictionSnapshots = domain.signalRestrictions,
                        inspectionRuntime = domain.inspectionRuntime,
                        infrastructureDependencyService = object : work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService {
                            override fun set(request: work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest): Nothing =
                                error("idle dispatcher must not set timetable dependencies")
                            override fun remove(owner: work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner): Nothing =
                                error("idle dispatcher must not remove timetable dependencies")
                        })
                })
            runtime.attachSpeedSafety(SpeedSafetyMonitor.Config(false, 0.0, 1))
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
            train = createTrainWithConfig("same-name", rail, world, listOf(10.0))
            train.setControllerForTest(object : TrainController(train) {
                init {
                    speed = 5.0
                }
            })
            initialX = train.publishedFrame.head.arcLengthInAbMeters
            world.physicsCoordinator.start()
            runner = WorldTickRunner(world.runtimeId, { wake ->
                beforeTick()
                BukkitThreadGuardBridge.runInSimulationContext(world) {
                    world.tickManager.preTick(wake.millis)
                    val ticket = world.physicsCoordinator.stepSimulation(50.0)
                    ticket.await()
                    world.physicsCoordinator.processBetweenFrames {
                        world.physicsCoordinator.drainBetweenFramesOperations()
                        runtime.afterPublication(it.epoch, publishObservation = {
                            assertEquals(ticket.epoch, world.latestWorldFrame().physicsEpoch)
                        })
                    }
                }
                completed(wake.index).complete(Unit)
            }, { failure -> completions.values.forEach { it.completeExceptionally(failure) } })
        }

        fun completed(index: Long): CompletableFuture<Unit> = completions.computeIfAbsent(index) { CompletableFuture() }

        fun assertMoved(epoch: Long, distance: Double) {
            assertEquals(epoch, world.latestWorldFrame().physicsEpoch)
            assertEquals(epoch, train.publishedFrame.physicsEpoch)
            assertEquals(initialX + distance, train.publishedFrame.head.arcLengthInAbMeters, 0.005)
            assertFalse(train.publishedFrame.derailed)
        }

        override fun close() {
            if (closed) return
            closed = true
            runner.close()
            world.executionOwner.run {
                runtime.close()
                world.physicsCoordinator.close()
                (world.tickManager as AutoCloseable).close()
            }
        }
    }
}
