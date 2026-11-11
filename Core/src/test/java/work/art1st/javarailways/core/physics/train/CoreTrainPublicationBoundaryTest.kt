package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.TimeUnit

class CoreTrainPublicationBoundaryTest {
    @Test
    fun `public Train frame includes current conservative coupler spacing`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            val train = createTrainWithConfig("public-consist-frame", rail, world, listOf(10.0, 10.0), connectorLength = 1.0)
            val publicTrain: work.art1st.javarailways.core.api.physics.train.Train = train
            val frame = requireNotNull(publicTrain.publishedFrame)
            org.junit.jupiter.api.Assertions.assertSame(train.publishedFrame, frame)
            val bodyLength = frame.bodyTraversal.sumOf { kotlin.math.abs(it.endArcLengthInAbMeters - it.startArcLengthInAbMeters) }
            assertTrue(bodyLength > frame.cars.sumOf { it.lengthMeters }, "coupler gaps must not disappear from clearance geometry")
            assertEquals(frame.head, work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot.capture(train.headLocation, ViewDirection.AB))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `train frame preserves ordered head and tail traversal across rail boundaries`() {
        val world = createTestWorld()
        try {
            val first = createStraightRailFromSegments(
                world,
                PrimeCurveGenerator.buildStraight(60.0, segmentCount = 4)
            )
            val second = requireNotNull(first.viewAB.activeRail).rail
            val third = requireNotNull(second.viewAB.activeRail).rail
            val fourth = requireNotNull(third.viewAB.activeRail).rail
            val train = createTrainWithConfig("ordered-traversal", first, world, listOf(10.0))

            train.moveTrainForward(30.0)
            train.commit()

            val frame = train.publishedFrame
            assertEquals(listOf(second.id, third.id, fourth.id), frame.headTraversal.map { it.railId })
            assertEquals(listOf(first.id, second.id, third.id), frame.tailTraversal.map { it.railId })
            assertEquals(listOf(ViewDirection.AB, ViewDirection.AB, ViewDirection.AB), frame.headTraversal.map { it.facing })
            assertEquals(5.0, frame.headTraversal.first().startArcLengthInAbMeters, 1e-6)
            assertEquals(15.0, frame.headTraversal.first().endArcLengthInAbMeters, 1e-6)
            assertEquals(0.0, frame.headTraversal[1].startArcLengthInAbMeters, 1e-6)
            assertEquals(15.0, frame.headTraversal[1].endArcLengthInAbMeters, 1e-6)
            assertEquals(0.0, frame.headTraversal.last().startArcLengthInAbMeters, 1e-6)
            assertEquals(5.0, frame.headTraversal.last().endArcLengthInAbMeters, 1e-6)
            assertTrue(frame.sweptSpans.any { it.railId == first.id })
            assertFalse(frame.occupiedIntervals.any { it.railId == first.id })
            assertEquals(train.occupiedRailIntervals, frame.occupiedIntervals)
            assertEquals(frame.tail.railId, frame.bodyTraversal.first().railId)
            assertEquals(frame.head.railId, frame.bodyTraversal.last().railId)
            assertEquals(frame.tail.arcLengthInAbMeters, frame.bodyTraversal.first().startArcLengthInAbMeters, 1e-6)
            assertEquals(frame.head.arcLengthInAbMeters, frame.bodyTraversal.last().endArcLengthInAbMeters, 1e-6)

            train.moveTrainForward(0.0)
            train.commit()

            assertTrue(train.publishedFrame.headTraversal.isEmpty())
            assertTrue(train.publishedFrame.tailTraversal.isEmpty())
            assertTrue(train.publishedFrame.bodyTraversal.isNotEmpty())
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `BA traversal keeps travel order while canonical AB arcs decrease`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(
                world,
                PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
            )
            val train = createTrainWithConfig("ba-traversal", rail, world, listOf(10.0))
            train.world.onOwnerForTest {
                train.setLocation(rail.viewBA.getRailLocationAt(60.0))
                train.commit()
            }

            train.moveTrainForward(10.0)
            train.commit()

            val headTraversal = train.publishedFrame.headTraversal.single()
            val tailTraversal = train.publishedFrame.tailTraversal.single()
            assertEquals(ViewDirection.BA, headTraversal.facing)
            assertEquals(ViewDirection.BA, tailTraversal.facing)
            assertEquals(40.0, headTraversal.startArcLengthInAbMeters, 1e-6)
            assertEquals(30.0, headTraversal.endArcLengthInAbMeters, 1e-6)
            assertTrue(tailTraversal.startArcLengthInAbMeters > tailTraversal.endArcLengthInAbMeters)
            assertEquals(ViewDirection.BA, train.publishedFrame.bodyTraversal.single().facing)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `published rail positions preserve BA facing with canonical AB arc lengths`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(
                world,
                PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
            )
            val train = createTrainWithConfig("ba-frame", rail, world, listOf(10.0))

            train.world.onOwnerForTest {
                train.setLocation(rail.viewBA.getRailLocationAt(60.0))
                train.commit()
            }

            val frame = train.publishedFrame
            assertEquals(ViewDirection.BA, frame.head.facing)
            assertEquals(ViewDirection.BA, frame.tail.facing)
            assertEquals(train.head.location.arcLengthIn(ViewDirection.AB), frame.head.arcLengthInAbMeters)
            assertEquals(train.tail.location.arcLengthIn(ViewDirection.AB), frame.tail.arcLengthInAbMeters)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `queued reverse remains private until owner applies coherent state`() = withWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val train = createTrainWithConfig("reverse-publication", rail, world, listOf(10.0))
        val oldFrame = train.publishedFrame
        val oldCar = oldFrame.cars.single()
        val publishedHead = train.headLocation
        val publishedTail = train.tailLocation
        val controller = object : TrainController(train) {}
        train.setControllerForTest(controller)

        val reversed = controller.reverse()

        assertFalse(train.isReversed)
        assertFalse(train.cars.single().isReversed)
        assertEquals(publishedHead, train.headLocation)
        assertEquals(publishedTail, train.tailLocation)
        assertSame(oldFrame, train.publishedFrame)
        assertFalse(oldFrame.reversed)
        assertEquals(RailPositionSnapshot(rail.id, ViewDirection.AB, 20.0), oldFrame.head)
        assertEquals(RailPositionSnapshot(rail.id, ViewDirection.AB, 10.0), oldFrame.tail)

        IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames { world.physicsCoordinator.drainBetweenFramesOperations() }
        }
        reversed.get(5, TimeUnit.SECONDS)

        assertTrue(train.isReversed)
        assertTrue(train.cars.single().isReversed)
        assertEquals(publishedTail.position, train.headLocation.position)
        assertEquals(publishedHead.position, train.tailLocation.position)
        assertFalse(oldFrame.reversed)
        assertEquals(ViewDirection.AB, oldFrame.head.facing)

        val reversedFrame = train.publishedFrame

        assertSame(reversedFrame, train.publishedFrame)
        assertNotSame(oldFrame, reversedFrame)
        assertEquals(oldFrame.revision + 1L, reversedFrame.revision)
        assertEquals(oldFrame.physicsEpoch, reversedFrame.physicsEpoch)
        assertTrue(reversedFrame.reversed)
        assertFalse(reversedFrame.derailed)
        assertEquals(RailPositionSnapshot(rail.id, ViewDirection.BA, 10.0), reversedFrame.head)
        assertEquals(RailPositionSnapshot(rail.id, ViewDirection.BA, 20.0), reversedFrame.tail)
        // Operating reversal preserves the car's physical A-to-B rendering direction and identity.
        assertEquals(oldCar.carId, reversedFrame.cars.single().carId)
        assertEquals(oldCar.state, reversedFrame.cars.single().state)
        assertEquals(listOf(20.0, 10.0), reversedFrame.cars.single().bogies.map { it.positionX })
        reversedFrame.cars.single().bogies.forEach { bogie ->
            assertEquals(1.0, bogie.directionX, 1e-6)
            assertEquals(0.0, bogie.velocityX, 1e-6)
        }
        assertEquals(15.0, reversedFrame.cars.single().state.positionX, 1e-6)
        assertEquals(1.0, reversedFrame.cars.single().state.directionX, 1e-6)
        assertTrue(reversedFrame.headTraversal.isEmpty())
        assertTrue(reversedFrame.tailTraversal.isEmpty())
        assertEquals(ViewDirection.BA, reversedFrame.bodyTraversal.single().facing)

        val restored = controller.reverse()
        assertSame(reversedFrame, train.publishedFrame)
        IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames { world.physicsCoordinator.drainBetweenFramesOperations() }
        }
        restored.get(5, TimeUnit.SECONDS)

        val restoredFrame = train.publishedFrame
        assertEquals(reversedFrame.revision + 1L, restoredFrame.revision)
        assertEquals(oldFrame.physicsEpoch, restoredFrame.physicsEpoch, "owner reversal retains the last physics epoch")
        assertFalse(restoredFrame.reversed)
        assertEquals(oldFrame.head, restoredFrame.head)
        assertEquals(oldFrame.tail, restoredFrame.tail)
        assertEquals(oldCar.state, restoredFrame.cars.single().state)
        assertEquals(listOf(20.0, 10.0), restoredFrame.cars.single().bogies.map { it.positionX })
        assertFalse(oldFrame.reversed)
        assertEquals(RailPositionSnapshot(rail.id, ViewDirection.AB, 20.0), oldFrame.head)
        assertEquals(RailPositionSnapshot(rail.id, ViewDirection.AB, 10.0), oldFrame.tail)
        assertEquals(15.0, oldFrame.cars.single().state.positionX, 1e-6)
        assertEquals(1.0, oldFrame.cars.single().state.directionX, 1e-6)
        assertTrue(reversedFrame.reversed)
        assertEquals(RailPositionSnapshot(rail.id, ViewDirection.BA, 10.0), reversedFrame.head)
    }

    @Test
    fun `physics derail remains private until train state is published`() = withWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val train = createTrainWithConfig("derail-publication", rail, world, listOf(10.0))
        val oldFrame = train.publishedFrame
        val nextEpoch = oldFrame.physicsEpoch + 1L

        train.markDerailedFromPhysics("test derail")

        assertFalse(train.isDerailed)
        assertEquals(null, train.derailReason)
        assertSame(oldFrame, train.publishedFrame)
        assertFalse(oldFrame.derailed)

        train.publishPhysicsState()

        assertTrue(train.isDerailed)
        assertEquals("test derail", train.derailReason)
        assertSame(oldFrame, train.publishedFrame)
        assertFalse(oldFrame.derailed)

        val derailedFrame = train.publishFrame(nextEpoch)

        assertSame(derailedFrame, train.publishedFrame)
        assertNotSame(oldFrame, derailedFrame)
        assertEquals(oldFrame.revision + 1L, derailedFrame.revision)
        assertEquals(nextEpoch, derailedFrame.physicsEpoch)
        assertTrue(derailedFrame.derailed)
        assertFalse(derailedFrame.reversed)
        assertEquals(RailPositionSnapshot(rail.id, ViewDirection.AB, 20.0), derailedFrame.head)
        assertEquals(RailPositionSnapshot(rail.id, ViewDirection.AB, 10.0), derailedFrame.tail)
        assertEquals(oldFrame.cars, derailedFrame.cars)
        assertEquals(oldFrame.bodyTraversal, derailedFrame.bodyTraversal)
        assertFalse(oldFrame.derailed)
        assertEquals(0L, oldFrame.physicsEpoch)

        train.commit()
        assertEquals(derailedFrame.revision + 1L, train.publishedFrame.revision)
        assertEquals(nextEpoch, train.publishedFrame.physicsEpoch)
        assertTrue(train.publishedFrame.derailed)
        assertFalse(oldFrame.derailed)
        assertEquals("test derail", train.derailReason)
    }

    private inline fun withWorld(action: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        var primaryFailure: Throwable? = null
        try {
            action(world)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                world.physicsCoordinator.close()
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
            try {
                (world.tickManager as AutoCloseable).close()
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
            cleanupFailure?.let { failure -> primaryFailure?.addSuppressed(failure) ?: throw failure }
        }
    }
}
