package work.art1st.javarailways.basic.api.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.getViewsInOrder
import java.util.UUID

class RailwayObservationTest {
    @Test
    fun `projection preserves BA facing and canonical AB arcs without render data`() = withWorld { world ->
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("ba-observation", rail, world, listOf(10.0))
        train.setLocation(rail.viewBA.getRailLocationAt(60.0))
        train.commit()
        val coreFrame = RailwayWorldFrameSnapshot.create(
            physicsEpoch = train.publishedFrame.physicsEpoch,
            topologyRevision = world.topologyRevision,
            trains = listOf(train.publishedFrame),
            activeContinuations = ActiveRailContinuationsSnapshot.empty(world.topologyRevision)
        )

        val observation = RailwayObservation.fromCore("world-id", coreFrame)
        val projected = observation.trains.single()

        assertEquals(ViewDirection.BA, projected.head.facing)
        assertEquals(ViewDirection.BA, projected.tail.facing)
        assertEquals(40.0, projected.head.arcLengthInAbMeters, 1e-6)
        assertEquals(50.0, projected.tail.arcLengthInAbMeters, 1e-6)
        assertEquals(
            listOf(RailTraversalSegmentSnapshot(rail.id, ViewDirection.BA, 50.0, 40.0)),
            projected.bodyTraversal
        )
        assertEquals(train.publishedFrame.bodyTraversal, projected.bodyTraversal)
        assertEquals(train.publishedFrame.revision, projected.frameRevision)
        assertEquals(train.publishedFrame.occupiedIntervals, projected.occupiedIntervals)
        assertEquals(train.publishedFrame.sweptSpans, projected.sweptSpans)
        assertEquals(train.publishedFrame.headTraversal, projected.headTraversal)
        assertEquals(train.publishedFrame.tailTraversal, projected.tailTraversal)
        assertSame(coreFrame.occupancy, observation.occupancy)
        assertSame(coreFrame.activeContinuations, observation.activeContinuations)
    }

    @Test
    fun `projection preserves ordered per-epoch train-end traversal`() = withWorld { world ->
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(60.0, segmentCount = 4)
        )
        val train = createTrainWithConfig("traversal-observation", rail, world, listOf(10.0))
        val rails = getViewsInOrder(rail, isLoop = false).map { it.rail.id }
        train.moveTrainForward(30.0)
        train.commit()
        val coreFrame = RailwayWorldFrameSnapshot.create(
            physicsEpoch = train.publishedFrame.physicsEpoch,
            topologyRevision = world.topologyRevision,
            trains = listOf(train.publishedFrame),
            activeContinuations = ActiveRailContinuationsSnapshot.empty(world.topologyRevision)
        )

        val projected = RailwayObservation.fromCore("world-id", coreFrame).trains.single()

        assertTraversal(
            listOf(
                RailTraversalSegmentSnapshot(rails[1], ViewDirection.AB, 5.0, 15.0),
                RailTraversalSegmentSnapshot(rails[2], ViewDirection.AB, 0.0, 15.0),
                RailTraversalSegmentSnapshot(rails[3], ViewDirection.AB, 0.0, 5.0)
            ),
            projected.headTraversal
        )
        assertTraversal(
            listOf(
                RailTraversalSegmentSnapshot(rails[0], ViewDirection.AB, 10.0, 15.0),
                RailTraversalSegmentSnapshot(rails[1], ViewDirection.AB, 0.0, 15.0),
                RailTraversalSegmentSnapshot(rails[2], ViewDirection.AB, 0.0, 10.0)
            ),
            projected.tailTraversal
        )
        assertTraversal(
            listOf(
                RailTraversalSegmentSnapshot(rails[2], ViewDirection.AB, 10.0, 15.0),
                RailTraversalSegmentSnapshot(rails[3], ViewDirection.AB, 0.0, 5.0)
            ),
            projected.bodyTraversal
        )
        assertEquals(train.publishedFrame.bodyTraversal, projected.bodyTraversal)
        assertEquals(train.publishedFrame.headTraversal, projected.headTraversal)
        assertEquals(train.publishedFrame.tailTraversal, projected.tailTraversal)
    }

    @Test
    fun `observation defensively copies and sorts train collection`() = withWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val second = createTrainWithConfig("z-train", rail, world, listOf(10.0)).publishedFrame
        val first = createTrainWithConfig("a-train", rail, world, listOf(10.0)).publishedFrame
        val projected = RailwayObservation.fromCore(
            "world-id",
            RailwayWorldFrameSnapshot.create(
                0L,
                world.topologyRevision,
                listOf(second, first),
                ActiveRailContinuationsSnapshot.empty(world.topologyRevision)
            )
        )
        val source = projected.trains.reversed().toMutableList()
        val observation = RailwayObservation.create(
            projected.stamp, source, projected.occupancy, projected.activeContinuations
        )
        source.clear()

        assertEquals(listOf(first.trainId, second.trainId).sorted(), observation.trains.map { it.trainId })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (observation.trains as MutableList<RailwayTrainObservation>).clear()
        }
    }

    @Test
    fun `core world frame rejects mixed train epochs`() = withWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("epoch-mismatch", rail, world, listOf(10.0))

        assertThrows(IllegalArgumentException::class.java) {
            RailwayWorldFrameSnapshot.create(
                1L,
                world.topologyRevision,
                listOf(train.publishedFrame),
                ActiveRailContinuationsSnapshot.empty(world.topologyRevision)
            )
        }
    }

    @Test
    fun `core world frame rejects active continuations from another topology`() {
        assertThrows(IllegalArgumentException::class.java) {
            RailwayWorldFrameSnapshot.create(
                1L,
                8L,
                emptyList(),
                ActiveRailContinuationsSnapshot.empty(7L)
            )
        }
    }

    @Test
    fun `observation retains immutable active continuation values from the core frame`() {
        val entry = RailEndpointRef(UUID.fromString("00000000-0000-0000-0000-000000000001"), RailEndpoint.B)
        val exit = RailEndpointRef(UUID.fromString("00000000-0000-0000-0000-000000000002"), RailEndpoint.A)
        val source = linkedMapOf(entry to exit)
        val continuations = ActiveRailContinuationsSnapshot.create(7L, 3L, source)
        source.clear()
        val frame = RailwayWorldFrameSnapshot.create(4L, 7L, emptyList(), continuations)

        val observation = RailwayObservation.fromCore("world-id", frame)

        assertSame(continuations, observation.activeContinuations)
        assertEquals(exit, observation.activeContinuations.selectedContinuation(entry))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (observation.activeContinuations.selectedContinuationByEntry as MutableMap).clear()
        }
    }

    @Test
    fun `train observation copies every caller-owned interval and traversal collection`() {
        val firstRail = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondRail = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val expectedOccupied = listOf(RailInterval(firstRail, 10.0, 15.0), RailInterval(secondRail, 0.0, 5.0))
        val expectedBody = listOf(
            RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 10.0, 15.0),
            RailTraversalSegmentSnapshot(secondRail, ViewDirection.AB, 0.0, 5.0)
        )
        val expectedSwept = listOf(
            SweptRailSpanSnapshot(firstRail, 5.0, 15.0), SweptRailSpanSnapshot(secondRail, 0.0, 5.0)
        )
        val expectedHead = listOf(
            RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 14.0, 15.0),
            RailTraversalSegmentSnapshot(secondRail, ViewDirection.AB, 0.0, 5.0)
        )
        val expectedTail = listOf(RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 5.0, 10.0))
        val occupied = expectedOccupied.toMutableList()
        val body = expectedBody.toMutableList()
        val swept = expectedSwept.toMutableList()
        val head = expectedHead.toMutableList()
        val tail = expectedTail.toMutableList()

        val observation = RailwayTrainObservation.create(
            trainId = "train", trainName = "Train", frameRevision = 7L,
            speedMetersPerSecond = 2.0, derailed = false, reversed = false,
            head = RailPositionSnapshot(secondRail, ViewDirection.AB, 5.0),
            tail = RailPositionSnapshot(firstRail, ViewDirection.AB, 10.0),
            occupiedIntervals = occupied, bodyTraversal = body, sweptSpans = swept,
            headTraversal = head, tailTraversal = tail
        )
        listOf(occupied, body, swept, head, tail).forEach { it.clear() }

        assertEquals(expectedOccupied, observation.occupiedIntervals)
        assertEquals(expectedBody, observation.bodyTraversal)
        assertEquals(expectedSwept, observation.sweptSpans)
        assertEquals(expectedHead, observation.headTraversal)
        assertEquals(expectedTail, observation.tailTraversal)
        listOf(
            observation.occupiedIntervals, observation.bodyTraversal, observation.sweptSpans,
            observation.headTraversal, observation.tailTraversal
        ).forEach { retained ->
            assertThrows(UnsupportedOperationException::class.java) {
                (retained as MutableList<*>).clear()
            }
        }
    }

    private fun assertTraversal(
        expected: List<RailTraversalSegmentSnapshot>,
        actual: List<RailTraversalSegmentSnapshot>
    ) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedSegment, actualSegment) ->
            assertEquals(expectedSegment.railId, actualSegment.railId)
            assertEquals(expectedSegment.facing, actualSegment.facing)
            // Core's conservative rear may lag the solved geometry by up to its placement tolerance.
            assertEquals(expectedSegment.startArcLengthInAbMeters, actualSegment.startArcLengthInAbMeters, Curve.TOLERANCE)
            assertEquals(expectedSegment.endArcLengthInAbMeters, actualSegment.endArcLengthInAbMeters, Curve.TOLERANCE)
        }
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
