package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class TrainNameTest {
    @Test
    fun `train names are unique per world and lookup uses the player name`() = withWorld { world ->
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val first = createTrainWithConfig("id-1", rail, world, listOf(8.0), isLoop = false)
        val second = createTrainWithConfig("id-2", rail, world, listOf(8.0), isLoop = false)

        world.rebuildSweptOccupancyIndex(0L)
        val previousWorldFrame = world.publishWorldFrame(0L)
        val previousFrame = first.publishedFrame
        assertThrows(IllegalStateException::class.java) {
            world.renameTrain(first, "Outside boundary")
        }
        val renamed = world.physicsCoordinator.submitBetweenFrames {
            world.renameTrain(first, "Local 1")
        }
        assertFalse(renamed.isDone)
        assertEquals("id-1", first.name)
        world.physicsCoordinator.processBetweenFrames {
            world.physicsCoordinator.drainBetweenFramesOperations()
        }
        renamed.get(5, TimeUnit.SECONDS)
        assertEquals(first, world.getTrain("Local 1"))
        assertEquals(first, world.getTrain("id-1"))
        assertSame(previousFrame, first.publishedFrame)
        assertSame(previousWorldFrame, world.latestWorldFrame())
        assertEquals(emptyList<Any>(), world.drainPublishedTrainFrames())

        val duplicate = world.physicsCoordinator.submitBetweenFrames {
            world.renameTrain(second, "Local 1")
        }
        world.physicsCoordinator.processBetweenFrames {
            world.physicsCoordinator.drainBetweenFramesOperations()
        }
        val failure = assertThrows(ExecutionException::class.java) {
            duplicate.get(5, TimeUnit.SECONDS)
        }
        assertEquals(IllegalArgumentException::class.java, failure.cause!!::class.java)
        assertEquals("id-2", second.name)
        assertSame(first, world.getTrain("Local 1"))

        world.physicsCoordinator.start()
        world.physicsCoordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
        assertEquals("Local 1", first.publishedFrame.trainName)
        assertEquals(previousFrame.revision + 1, first.publishedFrame.revision)
        assertEquals(1L, first.publishedFrame.physicsEpoch)
        assertSame(first.publishedFrame, world.latestWorldFrame().trains.single { it.trainId == first.id })
        assertEquals("Local 1", world.drainPublishedTrainFrames().single().trainName)
        assertEquals("id-1", previousFrame.trainName)
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try {
            createTestWorld(coordinator)
        } catch (failure: Throwable) {
            coordinator.close()
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use { IsolatedSimulationThreadMarker.run { block(world) } }
            }
        }
    }
}
