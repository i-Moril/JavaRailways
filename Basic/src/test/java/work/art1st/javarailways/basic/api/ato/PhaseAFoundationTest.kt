package work.art1st.javarailways.basic.api.ato

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.command.CommandOfferResult
import work.art1st.javarailways.basic.api.ato.command.RevisionedCommandQueue
import work.art1st.javarailways.basic.api.ato.snapshot.ObservationTransition
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RailObservationSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailObjectSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhaseAFoundationTest {
    @Test
    fun `route revision and topology revision are independent non-negative identities`() {
        val route = RouteSnapshot("route", 3L, 11L, emptyList())

        assertEquals(3L, route.revision)
        assertEquals(11L, route.topologyRevision)
        assertThrows(IllegalArgumentException::class.java) {
            RouteSnapshot("route", 3L, -1L, emptyList())
        }
    }

    @Test
    fun `route and perception snapshots defensively copy collections`() {
        val railId = UUID.randomUUID()
        val mutableSegments = mutableListOf(
            RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0)
        )
        val route = RouteSnapshot("route-1", 4L, 9L, mutableSegments)
        mutableSegments.clear()
        assertEquals(1, route.segments.size)
        assertEquals(4L, route.revision)
        assertEquals(9L, route.topologyRevision)

        val train = TrainFrameSnapshot.create(
            trainId = "T1",
            trainName = "T1", physicsEpoch = 8L, reversed = false,
            cars = emptyList(), occupiedIntervals = emptyList(), bodyTraversal = emptyList(),
            sweptSpans = emptyList(), headTraversal = emptyList(), tailTraversal = emptyList(),
            revision = 8L,
            speedMetersPerSecond = 12.0,
            derailed = false,
            head = RailPositionSnapshot(railId, ViewDirection.AB, 20.0),
            tail = RailPositionSnapshot(railId, ViewDirection.AB, 5.0)
        )
        val subject = RailObjectSnapshot(
            ref = RailObjectRef("test", "signal/1"),
            lifecycleState = RailObjectLifecycleState.ACTIVE,
            position = RailPositionSnapshot(railId, ViewDirection.AB, 50.0)
        )
        val mutableObservations = mutableListOf(
            RailObservationSnapshot(subject, ObservationTransition.ENTER, 30.0)
        )
        val perception = PerceptionSnapshot(9L, train, route, mutableObservations)
        mutableObservations.clear()

        assertEquals(1, perception.observations.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (perception.observations as MutableList<RailObservationSnapshot>).clear()
        }
    }

    @Test
    fun `perception requires observations sorted by route distance`() {
        val railId = UUID.randomUUID()
        val route = RouteSnapshot(
            "route-2",
            1L,
            3L,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
        )
        val train = TrainFrameSnapshot.create(
            "T2", "T2", 1L, 1L, 0.0, false, false, emptyList(),
            RailPositionSnapshot(railId, ViewDirection.AB, 0.0),
            RailPositionSnapshot(railId, ViewDirection.AB, 0.0),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
        )
        fun observation(id: String, distance: Double) = RailObservationSnapshot(
            RailObjectSnapshot(
                RailObjectRef("test", id),
                RailObjectLifecycleState.ACTIVE,
                RailPositionSnapshot(railId, ViewDirection.AB, distance)
            ),
            ObservationTransition.UPDATE,
            distance
        )

        assertThrows(IllegalArgumentException::class.java) {
            PerceptionSnapshot(1L, train, route, listOf(observation("far", 20.0), observation("near", 10.0)))
        }
    }

    @Test
    fun `revisioned queue assigns ordered revisions across producers`() {
        val queue = RevisionedCommandQueue<Int>()
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 100).map { value ->
                executor.submit<Long> {
                    start.await()
                    queue.enqueue(value).revision
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }

            val revisions = queue.drain().map { it.revision }
            assertEquals((0L until 100L).toList(), revisions)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `revisioned queue rejects stale commands and drains in order`() {
        val queue = RevisionedCommandQueue<String>()

        assertEquals(CommandOfferResult.ACCEPTED, queue.offer(2L, "mission-2"))
        assertEquals(CommandOfferResult.STALE, queue.offer(1L, "mission-1"))
        val generated = queue.enqueue("hold")
        assertEquals(3L, generated.revision)

        val drained = queue.drain()
        assertEquals(listOf(2L, 3L), drained.map { it.revision })
        assertEquals(listOf("mission-2", "hold"), drained.map { it.command })
        assertEquals(3L, queue.latestDrainedRevision)
        assertEquals(0, queue.pendingCount)
        assertTrue(queue.drain().isEmpty())
    }
}
