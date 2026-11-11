package work.art1st.javarailways.simpleato.api.depot

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrainCandidateFactsTest {
    private val left = TrainCandidateFacts("A", setOf("passenger", "express"), 30.0, setOf("doors", "tilt"))
    private val right = TrainCandidateFacts("B", setOf("passenger"), 20.0, setOf("doors"))

    @Test fun `car reduction conservatively aggregates mixed models and unknown members`() {
        val combined = TrainCandidateFacts.fromCars(mapOf("left" to left, "right" to right))
        assertNull(combined.modelPackageId)
        assertEquals(setOf("passenger"), combined.tags)
        assertEquals(setOf("doors"), combined.capabilities)
        assertEquals(20.0, combined.maximumSpeedMetersPerSecond)
        assertEquals(mapOf("left" to left, "right" to right), combined.carFacts)
        assertEquals("A", TrainCandidateFacts.fromCars(mapOf("a" to left, "b" to left)).modelPackageId)
        val unknown = TrainCandidateFacts(null, emptySet(), null, emptySet())
        val incomplete = TrainCandidateFacts.fromCars(mapOf("known" to left, "unknown" to unknown))
        assertNull(incomplete.modelPackageId)
        assertNull(incomplete.maximumSpeedMetersPerSecond)
        assertTrue(incomplete.tags.isEmpty())
        assertTrue(incomplete.capabilities.isEmpty())
    }

    @Test fun `captured leaves and collections are immutable and equality includes exact car origins`() {
        val tags = mutableSetOf("passenger")
        val capabilities = mutableSetOf("doors")
        val leaf = TrainCandidateFacts("A", tags, 30.0, capabilities)
        val input = linkedMapOf("a" to leaf, "b" to right)
        val captured = TrainCandidateFacts.fromCars(input)
        input.clear(); tags.clear(); capabilities.clear()
        val fresh = TrainCandidateFacts.fromCars(mapOf("b" to right,
            "a" to TrainCandidateFacts("A", setOf("passenger"), 30.0, setOf("doors"))))
        assertEquals(fresh, captured)
        assertEquals(fresh.hashCode(), captured.hashCode())
        assertNotEquals(captured, TrainCandidateFacts.fromCars(mapOf("different-car" to leaf, "b" to right)))
        assertThrows(UnsupportedOperationException::class.java) {
            (captured.carFacts as MutableMap<String, TrainCandidateFacts>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) { (leaf.tags as MutableSet<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (leaf.capabilities as MutableSet<String>).clear() }
        assertEquals(2, captured.carFacts.size)
        assertTrue(leaf.carFacts.isEmpty())
    }

    @Test fun `car factory rejects absent invalid or nested membership`() {
        assertThrows(IllegalArgumentException::class.java) { TrainCandidateFacts.fromCars(emptyMap()) }
        listOf("", " leading", "trailing ", "bad\ncar").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { TrainCandidateFacts.fromCars(mapOf(id to left)) }
        }
        val aggregate = TrainCandidateFacts.fromCars(mapOf("a" to left))
        assertThrows(IllegalArgumentException::class.java) { TrainCandidateFacts.fromCars(mapOf("a" to aggregate)) }
    }
}
