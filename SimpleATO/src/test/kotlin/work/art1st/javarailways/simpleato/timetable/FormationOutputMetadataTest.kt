package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.depot.*

class FormationOutputMetadataTest {
    private val facts = TrainCandidateFacts("metro/model", setOf("metro"), 22.0, setOf("doors"))
    private val cars = listOf("a", "b", "c", "d")
    private val lengths = mapOf("a" to 10.0, "b" to 11.0, "c" to 12.0, "d" to 13.0)
    private val source get() = FormationOutputMetadata.source(facts, cars, lengths)

    @Test fun `fresh captured classification compares by value rather than callback instance`() {
        val first = source
        val refreshed = FormationOutputMetadata.source(TrainCandidateFacts("metro/model", setOf("metro"), 22.0, setOf("doors")), cars, lengths)
        assertEquals(first, refreshed)
        assertEquals(first.hashCode(), refreshed.hashCode())
        assertNotEquals(first, FormationOutputMetadata.source(TrainCandidateFacts("metro/model", setOf("metro"), 21.0,
            setOf("doors")), cars, lengths))
    }

    @Test fun `split classification inherits captured facts and exact physical lengths without a stored identity`() {
        val metadata = FormationOutputMetadata.split(source, cars, listOf("c", "d"), lengths)
        assertEquals(facts.modelPackageId, metadata.candidateFacts.modelPackageId)
        assertEquals(2, metadata.carCount)
        assertEquals(25.0, metadata.lengthMeters)
        assertThrows(UnsupportedOperationException::class.java) { (metadata.tags as MutableSet<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (metadata.capabilities as MutableSet<String>).clear() }
        assertEquals(mapOf("c" to facts, "d" to facts), metadata.candidateFacts.carFacts)
        assertEquals(4, source.carCount)
        assertEquals(46.0, source.lengthMeters)
    }

    @Test fun `split rejects stale captured geometry noncontiguous members and invalid physical lengths`() {
        fun derive(selected: List<String> = listOf("a", "b"), sizes: Map<String, Double> = lengths) =
            FormationOutputMetadata.split(source, cars, selected, sizes)
        assertThrows(IllegalArgumentException::class.java) { derive(selected = listOf("a", "c")) }
        assertThrows(IllegalArgumentException::class.java) { derive(selected = cars) }
        assertThrows(IllegalArgumentException::class.java) { derive(selected = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { derive(sizes = lengths - "b") }
        assertThrows(IllegalArgumentException::class.java) { derive(sizes = lengths + ("b" to Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { derive(sizes = lengths + ("b" to -1.0)) }
        assertThrows(IllegalArgumentException::class.java) { derive(sizes = lengths + ("b" to 12.0)) }
    }

    @Test fun `storage compatibility uses captured physical capacity and declared tag intersection`() {
        val metadata = FormationOutputMetadata.split(source, cars, listOf("a", "b"), lengths)
        val point = DepotStablingPoint(StablingPointRef(DepotId("B"), "one"), "target", 21.0, setOf("metro"))
        assertTrue(metadata.accepts(point))
        assertTrue(metadata.accepts(point.copy(allowedTags = emptySet())))
        assertFalse(metadata.accepts(point.copy(usableLengthMeters = 20.999)))
        assertFalse(metadata.accepts(point.copy(allowedTags = setOf("freight"))))
    }

    @Test fun `source and split reject stale exact classification membership even at equal geometry`() {
        val captured = TrainCandidateFacts.fromCars(cars.associateWith { facts })
        assertEquals(source, FormationOutputMetadata.source(captured, cars, lengths))
        val wrong = TrainCandidateFacts.fromCars((cars - "a" + "other").associateWith { facts })
        assertThrows(IllegalArgumentException::class.java) { FormationOutputMetadata.source(wrong, cars, lengths) }
        val changedCars = listOf("other", "b", "c", "d")
        assertThrows(IllegalArgumentException::class.java) {
            FormationOutputMetadata.split(source, changedCars, listOf("c", "d"), (lengths - "a") + ("other" to 10.0))
        }
    }

    @Test fun `mixed coupling retains shared candidate facts conservatively and conserves actual cars`() {
        val left = FormationOutputMetadata.source(TrainCandidateFacts("metro/model", setOf("metro", "left-only"), 22.0,
            setOf("doors", "left-only")), cars.take(2), lengths.filterKeys(cars.take(2)::contains))
        val right = FormationOutputMetadata.source(TrainCandidateFacts("different/model", setOf("metro"), 17.0,
            setOf("doors")), cars.drop(2), lengths.filterKeys(cars.drop(2)::contains))
        val membership = listOf(cars.take(2), cars.drop(2))
        val output = FormationOutputMetadata.combine(listOf(left, right), membership, listOf("d", "c", "a", "b"), lengths)
        assertNull(output.modelPackageId)
        assertEquals(setOf("metro"), output.tags)
        assertEquals(setOf("doors"), output.capabilities)
        assertEquals(17.0, output.maximumSpeedMetersPerSecond)
        assertEquals(46.0, output.lengthMeters)
        assertEquals(4, output.carCount)
        val child = FormationOutputMetadata.split(output, listOf("d", "c", "a", "b"), listOf("d", "c"), lengths)
        assertEquals(right, child, "split restores the exact right-hand classification, including reversed physical order")
        val leftChild = FormationOutputMetadata.split(output, listOf("d", "c", "a", "b"), listOf("a", "b"), lengths)
        assertEquals(left, leftChild)
        assertEquals(setOf("metro", "left-only"), leftChild.tags)
        assertEquals(setOf("doors", "left-only"), leftChild.capabilities)
        assertEquals(22.0, leftChild.maximumSpeedMetersPerSecond)
        assertEquals(output, FormationOutputMetadata.source(TrainCandidateFacts.fromCars(
            left.candidateFacts.carFacts + right.candidateFacts.carFacts), listOf("d", "c", "a", "b"), lengths))
        assertThrows(UnsupportedOperationException::class.java) {
            (output.candidateFacts.carFacts as MutableMap<String, TrainCandidateFacts>).clear()
        }
        assertEquals(4, output.candidateFacts.carFacts.size)
        assertThrows(IllegalArgumentException::class.java) {
            FormationOutputMetadata.combine(listOf(left, right), membership, listOf("a", "b", "c", "c"), lengths)
        }
        val unknown = FormationOutputMetadata.source(TrainCandidateFacts(null, emptySet(), null, emptySet()),
            cars.drop(2), lengths.filterKeys(cars.drop(2)::contains))
        assertNull(FormationOutputMetadata.combine(listOf(left, unknown), membership, cars, lengths).maximumSpeedMetersPerSecond)
    }
}
