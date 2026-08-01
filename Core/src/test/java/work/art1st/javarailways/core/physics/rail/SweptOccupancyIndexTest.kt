package work.art1st.javarailways.core.physics.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    private fun span(trainId: String, railId: UUID, min: Double, max: Double) =
        SweptRailSpan(trainId, railId, min, max)
}
