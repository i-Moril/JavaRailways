package work.art1st.javarailways.core.physics.tick

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.CarFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot
import work.art1st.javarailways.core.api.render.Vector3Snapshot
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestTrainFrameMailboxTest {
    @Test
    fun `newer frame replaces pending frame for the same train`() {
        val mailbox = LatestTrainFrameMailbox(capacity = 4)

        mailbox.offer(frame("train-a", revision = 1L))
        mailbox.offer(frame("train-a", revision = 2L))

        assertEquals(listOf(2L), mailbox.drain().map { it.revision })
        assertEquals(1L, mailbox.metrics().coalescedFrames)
        assertEquals(0, mailbox.metrics().size)
    }

    @Test
    fun `stale frame cannot replace a newer pending frame`() {
        val mailbox = LatestTrainFrameMailbox(capacity = 4)

        mailbox.offer(frame("train-a", revision = 3L))
        mailbox.offer(frame("train-a", revision = 2L))

        assertEquals(3L, mailbox.drain().single().revision)
        assertEquals(1L, mailbox.metrics().droppedStaleFrames)
    }

    @Test
    fun `concurrent publishers cannot overwrite the highest revision`() {
        val mailbox = LatestTrainFrameMailbox(capacity = 4)
        val executor = Executors.newFixedThreadPool(8)
        try {
            (1L..1_000L).forEach { revision ->
                executor.submit { mailbox.offer(frame("train-a", revision)) }
            }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }

        assertEquals(1_000L, mailbox.drain().single().revision)
    }

    @Test
    fun `frame defensively copies and freezes component collections`() {
        val state = RenderableFrameSnapshot.create(
            position = Vector3Snapshot(1.0, 2.0, 3.0),
            direction = Vector3Snapshot(0.0, 0.0, 1.0),
            normal = Vector3Snapshot(0.0, 1.0, 0.0),
            velocity = Vector3Snapshot(0.0, 0.0, 0.0)
        )
        val sourceCars = mutableListOf(CarFrameSnapshot.create("car-a", 20.0, state, emptyList()))
        val railId = UUID.nameUUIDFromBytes("mailbox-test-rail".toByteArray())
        val sourceTraversal = mutableListOf(
            RailTraversalSegmentSnapshot(railId, ViewDirection.AB, 0.0, 1.0)
        )
        val sourceBodyTraversal = sourceTraversal.toMutableList()
        val sourceOccupied = mutableListOf(RailInterval(railId, 0.0, 1.0))
        val frame = frame(
            "train-a",
            revision = 1L,
            cars = sourceCars,
            headTraversal = sourceTraversal,
            bodyTraversal = sourceBodyTraversal,
            occupiedIntervals = sourceOccupied
        )

        sourceCars.clear()
        sourceTraversal.clear()
        sourceBodyTraversal.clear()
        sourceOccupied.clear()

        assertEquals("Name of train-a", frame.trainName)
        assertEquals(1, frame.cars.size)
        assertEquals(20.0, frame.cars.single().lengthMeters)
        assertEquals(1, frame.headTraversal.size)
        assertEquals(1, frame.bodyTraversal.size)
        assertEquals(1, frame.occupiedIntervals.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.cars as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.headTraversal as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.bodyTraversal as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.occupiedIntervals as MutableList).clear()
        }
    }

    @Test
    fun `mailbox evicts the oldest train when capacity is exhausted`() {
        val mailbox = LatestTrainFrameMailbox(capacity = 2)

        mailbox.offer(frame("train-a", revision = 1L))
        mailbox.offer(frame("train-b", revision = 1L))
        mailbox.offer(frame("train-c", revision = 1L))

        val drainedIds = mailbox.drain().map { it.trainId }
        assertEquals(listOf("train-b", "train-c"), drainedIds)
        assertEquals(1L, mailbox.metrics().droppedCapacityFrames)
        assertTrue(mailbox.metrics().peakSize <= 2)
    }

    private fun frame(
        trainId: String,
        revision: Long,
        cars: Collection<CarFrameSnapshot> = emptyList(),
        headTraversal: Collection<RailTraversalSegmentSnapshot> = emptyList(),
        bodyTraversal: Collection<RailTraversalSegmentSnapshot> = emptyList(),
        occupiedIntervals: Collection<RailInterval> = emptyList()
    ): TrainFrameSnapshot {
        val railId = UUID.nameUUIDFromBytes("mailbox-test-rail".toByteArray())
        val position = RailPositionSnapshot(railId, ViewDirection.AB, revision.toDouble())
        return TrainFrameSnapshot.create(
            trainId = trainId,
            trainName = "Name of $trainId",
            revision = revision,
            physicsEpoch = revision,
            speedMetersPerSecond = 0.0,
            derailed = false,
            reversed = false,
            cars = cars,
            head = position,
            tail = position,
            occupiedIntervals = occupiedIntervals,
            bodyTraversal = bodyTraversal,
            sweptSpans = emptyList(),
            headTraversal = headTraversal,
            tailTraversal = emptyList()
        )
    }
}
