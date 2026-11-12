package work.art1st.javarailways.simpleato.api.line

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.station.StationId

class LineStoreTest {
    private fun line(id: String = "L1", maximumSpeed: Double? = null) = Line(
        LineId(id),
        listOf(StationId("A"), StationId("B")),
        listOf(StationId("B"), StationId("A")),
        "#56c5ff",
        maximumSpeed
    )

    @Test
    fun `line maximum speed is optional and otherwise finite positive`() {
        assertEquals(null, line().maximumSpeedMetersPerSecond)
        assertEquals(20.0, line(maximumSpeed = 20.0).maximumSpeedMetersPerSecond)
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { speed ->
            assertThrows(IllegalArgumentException::class.java) { line(maximumSpeed = speed) }
        }
    }

    @Test
    fun `mutations require current revision`() {
        val store = LineStore()
        assertEquals(1L, store.put(line(), expectedRevision = 0L))
        assertThrows(LineRevisionConflict::class.java) {
            store.put(line("L2"), expectedRevision = 0L)
        }
        assertEquals(listOf("L1"), store.snapshot().lines.map { it.id.value })
        assertEquals(2L, store.remove(LineId("L1"), expectedRevision = 1L).second)
    }

    @Test
    fun `line keeps independent directional station patterns`() {
        val line = Line(
            LineId("L1"),
            listOf(StationId("A"), StationId("B"), StationId("C")),
            listOf(StationId("C"), StationId("X"), StationId("A")),
            "#56c5ff"
        )
        assertEquals(listOf("A", "B", "C"), line.stations(LineDirection.UP).map { it.value })
        assertEquals(listOf("C", "X", "A"), line.stations(LineDirection.DOWN).map { it.value })
    }

    @Test
    fun `line requires unique ordered stations`() {
        assertThrows(IllegalArgumentException::class.java) {
            Line(
                LineId("L1"),
                listOf(StationId("A"), StationId("A")),
                listOf(StationId("A"), StationId("A"))
            )
        }
    }
}
