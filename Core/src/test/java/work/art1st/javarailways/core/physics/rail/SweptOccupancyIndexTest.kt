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
    fun `endpoint contact uses exact acceptance but positive body penetration cannot be ignored`() {
        val own = span("own", railA, 10.0, 20.0)
        val contact = SweptOccupancyIndex(listOf(own, span("other", railA, 20.0, 30.0)))
        var checks = 0
        assertNull(contact.queryFirstCollision("own", listOf(own), acceptConnector = {
            checks++
            false
        }).collidingTrainId)
        assertEquals(1, checks)
        assertEquals("other", contact.queryFirstCollision("own", listOf(own), acceptConnector = { true }).collidingTrainId)
        assertEquals(listOf("other", "own"), contact.blockingTrainIds(listOf(RailInterval(railA, 20.0, 20.0)), null))

        val penetration = SweptOccupancyIndex(listOf(own, span("other", railA, 19.999, 30.0)))
        assertEquals("other", penetration.queryFirstCollision("own", listOf(own), acceptConnector = {
            error("real body penetration must not depend on connector acceptance")
        }).collidingTrainId)
    }

    @Test
    fun `collision only false candidates continue and do not occupy ordinary intervals`() {
        val own = SweptRailSpan("own", railA, 10.0, 15.0, collisionOnly = true)
        val index = SweptOccupancyIndex(listOf(own,
            SweptRailSpan("false", railA, 11.0, 12.0, collisionOnly = true),
            SweptRailSpan("real", railA, 11.0, 12.0, collisionOnly = true)))
        val checked = mutableListOf<String>()
        val result = index.queryFirstCollision("own", listOf(own), acceptConnector = {
            checked += it
            it == "real"
        })
        assertEquals("real", result.collidingTrainId)
        assertEquals(listOf("false", "real"), checked)
        assertTrue(index.blockingTrainIds(listOf(RailInterval(railA, 10.0, 15.0)), null).isEmpty())
    }

    @Test
    fun `real connector gap priority orders likely contact before farther candidates`() {
        val own = SweptRailSpan("own", railA, 10.0, 15.0, collisionOnly = true)
        val falseCandidates = List(40) { SweptRailSpan("far-$it", railA, 11.0, 12.0, collisionOnly = true) }
        val index = SweptOccupancyIndex(listOf(own, SweptRailSpan("real", railA, 11.0, 12.0, collisionOnly = true)) + falseCandidates)
        val result = index.queryFirstCollision("own", listOf(own), acceptConnector = { it == "real" },
            connectorPriority = { if (it == "real") 0.0 else 10.0 })
        assertEquals("real", result.collidingTrainId)
        assertEquals(1, result.checkedCandidates)
    }

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

        assertEquals(SweptOccupancyQueryResult("B", 1), fromA)
        assertEquals(SweptOccupancyQueryResult("A", 1), fromB)
    }

    @Test
    fun `collision query rejects neighboring bucket candidate outside tolerance`() {
        val a = span("A", railA, 31.0, 31.996)
        val b = span("B", railA, 32.002, 33.0)
        val occupancy = SweptOccupancyIndex(listOf(a, b), bucketSize = 32.0)

        val fromA = occupancy.queryFirstCollision("A", listOf(a))
        val fromB = occupancy.queryFirstCollision("B", listOf(b))

        assertEquals(SweptOccupancyQueryResult(null, 1), fromA)
        assertEquals(SweptOccupancyQueryResult(null, 1), fromB)
    }

    @Test
    fun `collision bucket expansion uses requested tolerance`() {
        val a = span("A", railA, 31.0, 31.991)
        val b = span("B", railA, 32.0, 33.0)
        val occupancy = SweptOccupancyIndex(listOf(a, b), bucketSize = 32.0)

        assertNull(occupancy.queryFirstCollision("A", listOf(a)).collidingTrainId)
        assertEquals(
            SweptOccupancyQueryResult("B", 1),
            occupancy.queryFirstCollision("A", listOf(a), tolerance = 0.01)
        )
    }

    @Test
    fun `body overlap is prioritized ahead of connector-only candidates`() {
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
            ownSpans = listOf(span("A", railA, 15.0, 16.0))
        )

        assertEquals("Z-collision", result.collidingTrainId)
    }

    @Test
    fun `checks every candidate until a valid connector collision is found`() {
        val own = SweptRailSpan("own", railA, 10.0, 15.0, collisionOnly = true)
        val falseCandidates = List(40) { index ->
            SweptRailSpan("false-%02d".format(index), railA, 11.0, 12.0, collisionOnly = true)
        }
        val real = SweptRailSpan("zz-real", railA, 11.0, 12.0, collisionOnly = true)
        val occupancy = SweptOccupancyIndex(listOf(own, real) + falseCandidates)
        val acceptedCandidates = mutableListOf<String>()

        val result = occupancy.queryFirstCollision("own", listOf(own), acceptConnector = {
            acceptedCandidates += it
            it == real.trainId
        })

        assertEquals(real.trainId, result.collidingTrainId)
        assertEquals(41, result.checkedCandidates)
        assertEquals(41, acceptedCandidates.size)
        assertEquals(real.trainId, acceptedCandidates.last())
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
