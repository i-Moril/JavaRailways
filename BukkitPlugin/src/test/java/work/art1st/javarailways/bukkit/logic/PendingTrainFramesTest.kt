package work.art1st.javarailways.bukkit.logic

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.bukkit.util.LatestAffinityTaskDispatcher
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.core.physics.train.setControllerForTest
import java.util.concurrent.TimeUnit

class PendingTrainFramesTest {
    @Test
    fun `stationary heartbeat only refreshes active extension cars without duplicating ordinary frames`() {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            TestWorldFixture.create(coordinator).use { fixture ->
                val world = fixture.world
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
                val train = createTrainWithConfig("clock", rail, world, listOf(10.0, 10.0))
                val calls = mutableListOf<List<Int>>()
                world.onOwnerForTest {
                    consumeTrainRenderFrames(world, true, { it === train.cars.last() }) { _, frame, indices ->
                        assertSame(train.publishedFrame, frame)
                        calls += indices
                    }
                }
                assertEquals(listOf(listOf(0, 1)), calls, "normal queued car frames take priority over clock refresh")
                calls.clear()
                coordinator.start()
                repeat(3) { coordinator.stepSimulation(50.0).await() }
                world.drainPublishedTrainFrames() // Discard any initial alignment notification.
                coordinator.stepSimulation(50.0).await()
                assertTrue(world.drainPublishedTrainFrames().isEmpty(), "stationary mailbox suppression remains unchanged")
                world.onOwnerForTest {
                    consumeTrainRenderFrames(world, false, { true }) { _, _, indices -> calls += indices }
                    assertTrue(calls.isEmpty(), "no heartbeat outside the chosen time bucket")
                    consumeTrainRenderFrames(world, true, { it === train.cars.last() }) { _, frame, indices ->
                        assertSame(train.publishedFrame, frame)
                        calls += indices
                    }
                }
                assertEquals(listOf(listOf(1)), calls)
                calls.clear()
                world.onOwnerForTest {
                    consumeTrainRenderFrames(world, true, { false }) { _, _, indices -> calls += indices }
                }
                assertTrue(calls.isEmpty(), "retired or ordinary models need no region heartbeat")
            }
        }
    }

    @Test
    fun `train frame coalescing waits for every chunk and skips the same frame for all cars`() {
        data class Car(val id: Int, val x: Double, val revision: Int)
        val scheduled = mutableListOf<Pair<Int, () -> Unit>>()
        val rendered = mutableListOf<Car>()
        val dispatcher = LatestAffinityTaskDispatcher<String, List<Car>> { cars, complete ->
            dispatchTrainFrameChunks(
                cars,
                locationOf = { Location(null, it.x, 0.0, 0.0) },
                schedule = { location, action -> scheduled += (location.blockX shr 4) to action }
            ) { rendered.addAll(it) }.thenRun(complete)
        }
        fun frame(revision: Int) = listOf(Car(1, 0.0, revision), Car(2, 10.0, revision), Car(3, -1.0, revision))

        dispatcher.offer("train", frame(1))
        dispatcher.offer("train", frame(2))
        dispatcher.offer("train", frame(3))
        assertEquals(listOf(0, -1), scheduled.map { it.first })

        scheduled[0].second()
        assertEquals(frame(1).take(2), rendered)
        assertEquals(2, scheduled.size, "a faster chunk must wait for the rest of the frame")

        scheduled[1].second()
        assertEquals(frame(1), rendered)
        assertEquals(listOf(0, -1, 0, -1), scheduled.map { it.first })
        scheduled[2].second()
        scheduled[3].second()
        assertEquals(frame(1) + frame(3), rendered)
        assertEquals(1L, dispatcher.metrics().coalescedValues)
        assertEquals(0, dispatcher.metrics().activeKeys)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `initial refresh survives stationary epochs for created and restored trains`(restore: Boolean) {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            TestWorldFixture.create(coordinator).use { fixture ->
                val world = fixture.world
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
                val original = createTrainWithConfig("stationary", rail, world, listOf(10.0))
                val train = if (restore) world.onOwnerForTest {
                    val saved = original.save()
                    original.destroy().toCompletableFuture().get(5, TimeUnit.SECONDS)
                    saved.load(world)
                } else original
                val initialFrame = train.publishedFrame
                coordinator.start()
                repeat(3) { coordinator.stepSimulation(50.0).await() }
                assertNotSame(initialFrame, train.publishedFrame)
                assertEquals(1, world.trainFrameMailboxMetrics().size)

                val consumed = mutableListOf<TrainFrameSnapshot>()
                world.onOwnerForTest {
                    consumePendingTrainFrames(world) { current, frame ->
                        assertSame(train, current)
                        consumed += frame
                    }
                }
                assertEquals(1, consumed.size, "initial visibility must not depend on later movement")
                assertSame(train.publishedFrame, consumed.single())
                assertEquals(0, world.trainFrameMailboxMetrics().size)
                coordinator.stepSimulation(50.0).await()
                assertTrue(world.drainPublishedTrainFrames().isEmpty(), "stationary suppression remains active")
            }
        }
    }

    @Test
    fun `terminal refresh survives later stationary epochs`() {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            TestWorldFixture.create(coordinator).use { fixture ->
                val world = fixture.world
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
                val train = createTrainWithConfig("stopping", rail, world, listOf(10.0))
                val controller = MutableSpeedController(train, 10.0)
                train.setControllerForTest(controller)
                coordinator.start()
                coordinator.stepSimulation(50.0).await()
                world.onOwnerForTest { consumePendingTrainFrames(world) { _, _ -> } }
                controller.requestedSpeed = 0.0
                coordinator.stepSimulation(50.0).await()
                val stopped = train.publishedFrame
                repeat(3) { coordinator.stepSimulation(50.0).await() }
                assertNotSame(stopped, train.publishedFrame)

                val consumed = mutableListOf<TrainFrameSnapshot>()
                world.onOwnerForTest { consumePendingTrainFrames(world) { _, frame -> consumed += frame } }
                assertEquals(1, consumed.size, "the last visible position must be delivered after stopping")
                assertSame(train.publishedFrame, consumed.single())
                assertEquals(0.0, consumed.single().speedMetersPerSecond)
                assertEquals(stopped.cars.single().state, consumed.single().cars.single().state)
            }
        }
    }

    @Test
    fun `refresh published while consuming a drained batch remains pending`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
            val train = createTrainWithConfig("pending", rail, world, listOf(10.0))
            var count = 0
            world.onOwnerForTest {
                consumePendingTrainFrames(world) { current, _ ->
                    count++
                    current.commit()
                    assertTrue(world.publishCurrentTrainFrame(current.id))
                }
            }
            assertEquals(1, count)
            assertSame(train.publishedFrame, world.drainPublishedTrainFrames().single())
        }
    }

    @Test
    fun `removed train in drained batch is skipped`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
            val first = createTrainWithConfig("first", rail, world, listOf(10.0))
            val removed = createTrainWithConfig("removed", rail, world, listOf(10.0))
            val consumed = mutableListOf<String>()
            world.onOwnerForTest {
                consumePendingTrainFrames(world) { train, _ ->
                    consumed += train.id
                    removed.destroy().toCompletableFuture().get(5, TimeUnit.SECONDS)
                }
            }
            assertEquals(listOf(first.id), consumed)
        }
    }

    private class MutableSpeedController(train: CoreTrain, @Volatile var requestedSpeed: Double) : TrainController(train) {
        override fun tick(deltaT: Double) {
            speed = requestedSpeed
            moveControlledTrain(activeSpeed * deltaT * 0.001, activeSpeed)
        }
    }
}
