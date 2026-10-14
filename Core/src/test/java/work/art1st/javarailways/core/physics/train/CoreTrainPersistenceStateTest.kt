package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoreTrainPersistenceStateTest {
    @Test
    fun `zero distance controller tick does not toggle car position back to default`() {
        withWorld { world ->
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))
            val train = createTrainWithConfig("stable", rail, world, listOf(10.0), isLoop = false)
            train.setLocation(RailLocationWithFacing(rail.viewAB, 80.0))
            train.commit()
            val expected = Vector3d(train.cars.first().position)

            val controller = object : TrainController(train, world) {
                var ticks = 0
                override fun tick(deltaT: Double) {
                    super.tick(deltaT)
                    ticks++
                }
            }
            train.setControllerForTest(controller)
            val coordinator = world.physicsCoordinator
            coordinator.start()
            var previousFrame = train.publishedFrame
            repeat(6) { tick ->
                val ticket = coordinator.stepSimulation(50.0)
                val metrics = ticket.completion.get(5, TimeUnit.SECONDS)
                val frame = train.publishedFrame
                assertEquals(tick + 1, controller.ticks, "controller must actually run")
                assertEquals(ticket.epoch, metrics.epoch)
                assertEquals(ticket.epoch, frame.physicsEpoch)
                assertTrue(frame.physicsEpoch > previousFrame.physicsEpoch)
                assertTrue(frame.revision > previousFrame.revision)
                assertEquals(80.0, frame.head.arcLengthInAbMeters, 1e-9)
                assertEquals(70.0, frame.tail.arcLengthInAbMeters, 1e-9)
                assertEquals(Vector3d(75.0, 0.0, 0.0), frame.cars.single().state.positionVector())
                assertEquals(0.0, frame.speedMetersPerSecond)
                (frame.cars.single().bogies + frame.cars.single().state).forEach { state ->
                    assertEquals(Vector3d(), state.velocityVector())
                }
                assertEquals(0.0, train.cars.first().position.distance(expected), 1e-9, "tick $tick")
                previousFrame = frame
            }
        }
    }

    @Test
    fun `saved train load reconstructs committed car positions`() {
        withWorld { world ->
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))
            val train = createTrainWithConfig("persisted", rail, world, listOf(10.0), isLoop = false)
            train.setLocation(RailLocationWithFacing(rail.viewAB, 80.0))
            train.commit()
            val expected = Vector3d(train.cars.first().position)

            withWorld(load = { tick, physics ->
                CoreWorld.load(
                    world.saveInfrastructure(1L),
                    world.saveRuntime(1L, 1L),
                    CoreWorld.SnapshotDependency(tick, NoOpWorldParentContext, physics)
                )
            }) { loaded ->
                val loadedTrain = loaded.getTrain("persisted") ?: error("train should load")
                val actual = loadedTrain.cars.first().position

                assertTrue(expected.lengthSquared() > 1.0, "expected test train to be away from origin")
                assertEquals(0.0, actual.distance(expected), 1e-9)
                assertEquals(Vector3d(75.0, 0.0, 0.0), actual)
                assertEquals(80.0, loadedTrain.publishedFrame.head.arcLengthInAbMeters, 1e-9)
                assertEquals(70.0, loadedTrain.publishedFrame.tail.arcLengthInAbMeters, 1e-9)
            }
        }
    }

    private fun withWorld(
        load: (TickManagerImpl, PhysicsCoordinator) -> CoreWorld = { tick, physics ->
            CoreWorld.createEmpty(tick, NoOpWorldParentContext, physics)
        },
        block: (CoreWorld) -> Unit
    ) {
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            var world: CoreWorld? = null
            AutoCloseable { world?.releaseRuntimeBinding() }.use {
                PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                    val created = load(tick, physics)
                    world = created
                    block(created)
                }
            }
        }
    }
}
