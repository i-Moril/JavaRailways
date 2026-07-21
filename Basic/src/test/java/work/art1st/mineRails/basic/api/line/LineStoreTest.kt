package work.art1st.mineRails.basic.api.line

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.api.station.StationId

class LineStoreTest {
    private fun line(id: String = "L1") = Line(
        LineId(id),
        "Line $id",
        listOf(StationId("A"), StationId("B")),
        "#56c5ff"
    )

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
    fun `line requires unique ordered stations`() {
        assertThrows(IllegalArgumentException::class.java) {
            Line(LineId("L1"), "Line", listOf(StationId("A"), StationId("A")))
        }
    }
}
