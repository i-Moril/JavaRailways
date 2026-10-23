package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.depot.*

class FormationOutputMetadataTest {
    private val source = RollingStockRecord("source", DepotId("A"), "metro/model", setOf("metro"),
        4, 46.0, 22.0, setOf("doors"))
    private val cars = listOf("a", "b", "c", "d")
    private val lengths = mapOf("a" to 10.0, "b" to 11.0, "c" to 12.0, "d" to 13.0)

    @Test fun `split classification inherits declared metadata and uses exact member lengths without inventing an identity`() {
        val metadata = FormationOutputMetadata.split(source, cars, listOf("c", "d"), lengths)
        assertEquals(source.copy(trainId = "actual-output", carCount = 2, lengthMeters = 25.0), metadata.toRecord("actual-output"))
        assertThrows(UnsupportedOperationException::class.java) { (metadata.tags as MutableSet<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (metadata.capabilities as MutableSet<String>).clear() }
        assertEquals(4, source.carCount)
        assertEquals(46.0, source.lengthMeters)
    }

    @Test fun `split classification rejects stale source geometry noncontiguous members and invalid lengths`() {
        fun derive(record: RollingStockRecord = source, selected: List<String> = listOf("a", "b"),
            sizes: Map<String, Double> = lengths) = FormationOutputMetadata.split(record, cars, selected, sizes)
        assertThrows(IllegalArgumentException::class.java) { derive(source.copy(carCount = 3)) }
        assertThrows(IllegalArgumentException::class.java) { derive(source.copy(lengthMeters = 45.0)) }
        assertThrows(IllegalArgumentException::class.java) { derive(selected = listOf("a", "c")) }
        assertThrows(IllegalArgumentException::class.java) { derive(selected = cars) }
        assertThrows(IllegalArgumentException::class.java) { derive(selected = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { derive(sizes = lengths - "b") }
        assertThrows(IllegalArgumentException::class.java) { derive(sizes = lengths + ("b" to Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { derive(sizes = lengths + ("b" to -1.0)) }
    }

    @Test fun `storage compatibility uses exact capacity and declared tag intersection`() {
        val metadata = FormationOutputMetadata.split(source, cars, listOf("a", "b"), lengths)
        val point = DepotStablingPoint(StablingPointRef(DepotId("B"), "one"), "target", 21.0, setOf("metro"))
        assertTrue(metadata.accepts(point))
        assertTrue(metadata.accepts(point.copy(allowedTags = emptySet())))
        assertFalse(metadata.accepts(point.copy(usableLengthMeters = 20.999)))
        assertFalse(metadata.accepts(point.copy(allowedTags = setOf("freight"))))
    }
    @Test fun `mixed coupling retains only shared declared classification and conserves actual cars`() {
        val left = source.copy(trainId = "left", carCount = 2, lengthMeters = 21.0,
            tags = setOf("metro", "left-only"), capabilities = setOf("doors", "left-only"))
        val right = source.copy(trainId = "right", homeDepotId = DepotId("B"), modelPackageId = "different/model",
            carCount = 2, lengthMeters = 25.0, maximumSpeedMetersPerSecond = 17.0)
        val memberships = mapOf("left" to listOf("a", "b"), "right" to listOf("c", "d"))
        val output = FormationOutputMetadata.couple(listOf(left, right), memberships, listOf("d", "c", "a", "b"), lengths)
            .toRecord("survivor")
        assertEquals(null, output.modelPackageId)
        assertEquals(setOf("metro"), output.tags)
        assertEquals(setOf("doors"), output.capabilities)
        assertEquals(17.0, output.maximumSpeedMetersPerSecond)
        assertEquals(46.0, output.lengthMeters)
        assertEquals(4, output.carCount)
        assertEquals(DepotId("A"), output.homeDepotId)
        assertEquals(DepotId("B"), right.homeDepotId)
        val matching = FormationOutputMetadata.couple(listOf(left, right.copy(modelPackageId = left.modelPackageId)),
            memberships, cars, lengths).toRecord("matching")
        assertEquals(left.modelPackageId, matching.modelPackageId)
        val child = FormationOutputMetadata.split(output, listOf("d", "c", "a", "b"), listOf("d", "c"), lengths)
            .toRecord("child")
        assertEquals(null, child.modelPackageId, "split must not invent lost per-unit classification")
        assertEquals(setOf("metro"), child.tags)
        assertThrows(IllegalArgumentException::class.java) {
            FormationOutputMetadata.couple(listOf(left, right), memberships, listOf("a", "b", "c", "c"), lengths)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FormationOutputMetadata.couple(listOf(left, right.copy(lengthMeters = 26.0)), memberships, cars, lengths)
        }
    }

}
