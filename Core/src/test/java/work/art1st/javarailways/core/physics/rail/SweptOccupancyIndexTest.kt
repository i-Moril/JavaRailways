package work.art1st.javarailways.core.physics.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import java.util.UUID

class SweptOccupancyIndexTest {
    private val railA = UUID.randomUUID()
    private val railB = UUID.randomUUID()

    @Test
    fun `finds partial overlap and ignores self bucket entries`() {
        val index = SweptOccupancyIndex(
            listOf(
                span("A", railA, 0.0, 80.0),
                span("B", railA, 70.0, 120.0)
            )
        )

        val result = index.queryFirstCollision("A", listOf(span("A", railA, 0.0, 80.0)))

        assertEquals("B", result.collidingTrainId)
        assertEquals(1, result.checkedCandidates)
        assertFalse(result.candidateLimitReached)
    }

    @Test
    fun `finds containment overlap`() {
        val index = SweptOccupancyIndex(
            listOf(
                span("outer", railA, 0.0, 200.0),
                span("inner", railA, 50.0, 60.0)
            )
        )

        assertEquals(
            "inner",
            index.queryFirstCollision("outer", listOf(span("outer", railA, 0.0, 200.0))).collidingTrainId
        )
        assertEquals(
            "outer",
            index.queryFirstCollision("inner", listOf(span("inner", railA, 50.0, 60.0))).collidingTrainId
        )
    }

    @Test
    fun `does not collide across rails or non-overlapping intervals in same bucket`() {
        val index = SweptOccupancyIndex(
            listOf(
                span("A", railA, 0.0, 5.0),
                span("B", railA, 20.0, 25.0),
                span("C", railB, 0.0, 5.0)
            ),
            bucketSize = 32.0
        )

        val result = index.queryFirstCollision("A", listOf(span("A", railA, 0.0, 5.0)))

        assertNull(result.collidingTrainId)
        assertEquals(1, result.checkedCandidates)
    }

    @Test
    fun `uses tolerance at touching endpoints`() {
        val a = span("A", railA, 0.0, 10.0)
        val b = span("B", railA, 10.004, 20.0)
        assertTrue(a.overlaps(b, tolerance = 0.005))
        assertFalse(a.overlaps(b, tolerance = 0.001))
    }

    @Test
    fun `collision query finds tolerance contact across either bucket boundary`() {
        val a = span("A", railA, 31.0, 31.996)
        val b = span("B", railA, 32.0, 33.0)
        val occupancy = SweptOccupancyIndex(listOf(a, b), bucketSize = 32.0)

        val fromA = occupancy.queryFirstCollision("A", listOf(a))
        val fromB = occupancy.queryFirstCollision("B", listOf(b))

        assertEquals(SweptOccupancyQueryResult("B", 1, false), fromA)
        assertEquals(SweptOccupancyQueryResult("A", 1, false), fromB)
    }

    @Test
    fun `collision query rejects neighboring bucket candidate outside tolerance`() {
        val a = span("A", railA, 31.0, 31.996)
        val b = span("B", railA, 32.002, 33.0)
        val occupancy = SweptOccupancyIndex(listOf(a, b), bucketSize = 32.0)

        val fromA = occupancy.queryFirstCollision("A", listOf(a))
        val fromB = occupancy.queryFirstCollision("B", listOf(b))

        assertEquals(SweptOccupancyQueryResult(null, 1, false), fromA)
        assertEquals(SweptOccupancyQueryResult(null, 1, false), fromB)
    }

    @Test
    fun `collision bucket expansion uses requested tolerance`() {
        val a = span("A", railA, 31.0, 31.991)
        val b = span("B", railA, 32.0, 33.0)
        val occupancy = SweptOccupancyIndex(listOf(a, b), bucketSize = 32.0)

        assertNull(occupancy.queryFirstCollision("A", listOf(a)).collidingTrainId)
        assertEquals(
            SweptOccupancyQueryResult("B", 1, false),
            occupancy.queryFirstCollision("A", listOf(a), tolerance = 0.01)
        )
    }

    @Test
    fun `overlap is prioritized ahead of a capped broad-phase candidate list`() {
        val spans = buildList {
            add(span("A", railA, 15.0, 16.0))
            for (index in 0 until 5) {
                add(span("B$index", railA, index.toDouble(), index + 0.5))
            }
            add(span("Z-collision", railA, 15.5, 17.0))
        }
        val occupancy = SweptOccupancyIndex(spans, bucketSize = 32.0)

        val result = occupancy.queryFirstCollision(
            trainId = "A",
            ownSpans = listOf(span("A", railA, 15.0, 16.0)),
            maxCandidates = 2
        )

        assertEquals("Z-collision", result.collidingTrainId)
        assertFalse(result.candidateLimitReached)
    }

    @Test
    fun `candidate limit is explicit`() {
        val spans = buildList {
            add(span("A", railA, 0.0, 1.0))
            for (index in 0 until 5) {
                add(span("B$index", railA, 10.0 + index, 11.0 + index))
            }
        }
        val occupancy = SweptOccupancyIndex(spans, bucketSize = 32.0)

        val result = occupancy.queryFirstCollision(
            trainId = "A",
            ownSpans = listOf(span("A", railA, 0.0, 1.0)),
            maxCandidates = 2
        )

        assertNull(result.collidingTrainId)
        assertEquals(2, result.checkedCandidates)
        assertTrue(result.candidateLimitReached)
    }

    @Test
    fun `blocking query excludes owner and returns every distinct blocker in stable order`() {
        val occupancy = SweptOccupancyIndex(
            listOf(
                span("requesting", railA, 0.0, 40.0),
                span("z-train", railA, 20.0, 70.0),
                span("z-train", railA, 75.0, 80.0),
                span("a-train", railB, 5.0, 10.0)
            )
        )

        assertEquals(
            listOf("a-train", "z-train"),
            occupancy.blockingTrainIds(
                listOf(
                    RailInterval(railA, 30.0, 78.0),
                    RailInterval(railB, 0.0, 6.0)
                ),
                excludedTrainId = "requesting"
            )
        )
    }

    @Test
    fun `blocking query expands buckets by overlap tolerance`() {
        val occupancy = SweptOccupancyIndex(
            listOf(span("next-bucket", railA, 32.0, 33.0)),
            bucketSize = 32.0
        )

        assertEquals(
            listOf("next-bucket"),
            occupancy.blockingTrainIds(
                listOf(RailInterval(railA, 31.0, 32.0 - Curve.TOLERANCE)),
                excludedTrainId = null
            )
        )
    }

    private fun span(trainId: String, railId: UUID, min: Double, max: Double) =
        SweptRailSpan(trainId, railId, min, max)
}
