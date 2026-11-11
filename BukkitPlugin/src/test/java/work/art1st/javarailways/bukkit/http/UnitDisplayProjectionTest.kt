package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.timetable.*

class UnitDisplayProjectionTest {
    @Test fun `projection retains per-unit values and ordered repeated stop occurrences`() {
        val first = TimetableUnitStopSnapshot("d", "out", TimetableRunCall("c-out", "C", "C@1", TimetableServiceStopStatus.STOP, 10, 20), true)
        val returning = TimetableUnitStopSnapshot("d", "back", TimetableRunCall("c-back", "C", "C@2", TimetableServiceStopStatus.TERMINAL, 100), false)
        val projected = unitDisplayJson(TimetableUnitDisplaySnapshot("unit-2", "trip-E", 3,
            buildJsonObject { put("destination", "E"); put("custom", JsonArray(listOf(JsonPrimitive("x")))) },
            listOf(first, returning), listOf(first), listOf(returning), listOf("child/unresolved")))
        assertEquals("unit-2", projected["unitId"]!!.jsonPrimitive.content)
        assertEquals("E", projected["values"]!!.jsonObject["destination"]!!.jsonPrimitive.content)
        assertEquals(listOf("c-out", "c-back"), projected["plannedStops"]!!.jsonArray.map { it.jsonObject["call"]!!.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("child/unresolved"), projected["unresolvedRuns"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, projected["completedStops"]!!.jsonArray.size)
        assertEquals("back", projected["remainingStops"]!!.jsonArray.single().jsonObject["runId"]!!.jsonPrimitive.content)
    }
}
