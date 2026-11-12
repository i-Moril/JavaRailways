package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.FixedEmuUnit

class TimetableUnitDisplayContextTest {
    private fun unit(id: String, vararg cars: String) = FixedEmuUnit(id, cars.toList(), "family")

    @Test fun `current run capture requires exact entered range and freezes metadata`() {
        val metadata = linkedMapOf<String, JsonElement>("notice" to JsonPrimitive("limited"))
        val segment = TimetableRunSegment("run", work.art1st.javarailways.simpleato.api.line.LineId("line"), 2, 5, true,
            metadata = JsonObject(metadata))
        val current = captureTimetableRunDisplayContext("service", segment, 2)!!
        assertEquals("service", current.serviceId)
        assertEquals("run", current.runId)
        assertEquals("line", current.lineId)
        assertTrue(current.isInService)
        metadata.clear()
        assertEquals(JsonPrimitive("limited"), current.metadata["notice"])
        assertNotNull(captureTimetableRunDisplayContext("service", segment, 4))
        assertNull(captureTimetableRunDisplayContext("service", segment, 1))
        assertNull(captureTimetableRunDisplayContext("service", segment, 5))
        assertNull(captureTimetableRunDisplayContext("service", null, 2))
    }

    @Test fun `entered run service status is captured from its segment independent of display metadata`() {
        for (isInService in listOf(true, false)) {
            val segment = TimetableRunSegment("run", work.art1st.javarailways.simpleato.api.line.LineId("line"),
                2, 5, isInService, metadata = buildJsonObject { put("isInService", !isInService) })
            assertEquals(isInService, captureTimetableRunDisplayContext("service", segment, 2)!!.isInService)
            assertEquals(isInService, captureTimetableRunDisplayContext("service", segment, 4)!!.isInService)
            assertNull(captureTimetableRunDisplayContext("service", segment, 1))
            assertNull(captureTimetableRunDisplayContext("service", segment, 5))
        }
    }

    @Test fun `terminal capture uses only last TERMINAL of the entered run and changes with actual scope`() {
        fun segment(run: String, station: String, status: TimetableServiceStopStatus = TimetableServiceStopStatus.TERMINAL,
            start: Int = 2) = TimetableRunSegment(run, work.art1st.javarailways.simpleato.api.line.LineId("line"), start, start + 3, true,
                calls = listOf(TimetableRunCall("end", station, "$station@one", status)))
        val current = segment("current", "B")
        val future = segment("future", "C", start = 5)
        assertEquals("B", captureTimetableRunDisplayContext("service", current, 4)!!.terminalStationId)
        assertNull(captureTimetableRunDisplayContext("service", future, 4))
        assertNull(captureTimetableRunDisplayContext("service", current, 5))
        assertEquals("C", captureTimetableRunDisplayContext("service", future, 5)!!.terminalStationId)
        assertNull(captureTimetableRunDisplayContext("service", segment("missing", "B", TimetableServiceStopStatus.STOP), 2)!!.terminalStationId)
        assertNull(captureTimetableRunDisplayContext("service", TimetableRunSegment("empty",
            work.art1st.javarailways.simpleato.api.line.LineId("line"), 2, 5, true), 2)!!.terminalStationId)
    }

    @Test fun `default trip progress preserves PASS repeated occurrence identity and refuses invented arrival`() {
        fun stop(id: String, station: String, status: TimetableServiceStopStatus, completed: Boolean) =
            TimetableUnitStopSnapshot("service", "run", TimetableRunCall(id, station, "$station@one", status), completed)
        val stops = listOf(stop("origin", "A", TimetableServiceStopStatus.ORIGIN, true),
            stop("pass", "B", TimetableServiceStopStatus.PASS, true),
            stop("first", "C", TimetableServiceStopStatus.STOP, true),
            stop("second", "C", TimetableServiceStopStatus.TERMINAL, false))
        val segment = TimetableRunSegment("run", work.art1st.javarailways.simpleato.api.line.LineId("line"),
            0, 1, true, calls = stops.map { it.call })
        val service = TimetableService("service", steps = listOf(TimetableStep.Check("A@one")), runSegments = listOf(segment))
        val table = Timetable("table", services = listOf(service))
        fun reachedRuntime(reached: List<TimetableUnitStopSnapshot>) = TimetableDisplayRuntime().also { runtime ->
            runtime.enter(table, 0, service, segment, listOf("unit"))
            reached.filter { it.completed }.forEach { stop ->
                runtime.reached("table", 0, "service", "run", stop.call.id, listOf("unit"))
            }
        }
        val runtime = reachedRuntime(stops)
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN),
            runtime.captureProgress(table, 0, "unit", null, false), "Completed calls alone are not arrival evidence")
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION, 2),
            runtime.captureProgress(table, 0, "unit", Triple("service", "run", "first"), false))
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT, 3),
            runtime.captureProgress(table, 0, "unit", null, true))
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN),
            runtime.captureProgress(table, 0, "unit", Triple("service", "run", "pass"), false))
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN),
            runtime.captureProgress(table, 0, "unit", Triple("service", "future", "first"), false))
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN),
            reachedRuntime(stops.mapIndexed { index, value -> if (index == 1) value.copy(completed = false) else value })
                .captureProgress(table, 0, "unit", null, true))
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.FINISHED),
            reachedRuntime(stops.map { it.copy(completed = true) }).captureProgress(table, 0, "unit", null, true))
    }

    @Test fun `only complete actual contiguous units map to cars including reversal and ordinary neighbours`() {
        val first = unit("first", "a", "b")
        val second = unit("second", "c", "d")
        assertEquals(listOf(second, first), completeDisplayUnits(listOf("d", "c", "ordinary", "b", "a"), listOf(first, second)))
        assertEquals(emptyList<FixedEmuUnit>(), completeDisplayUnits(listOf("a"), listOf(first)))
        assertEquals(emptyList<FixedEmuUnit>(), completeDisplayUnits(listOf("a", "ordinary", "b"), listOf(first)))
        assertEquals(emptyList<FixedEmuUnit>(), completeDisplayUnits(listOf("a", "b", "a"), listOf(first)))
        val triple = unit("triple", "a", "b", "c")
        assertTrue(completeDisplayUnits(listOf("a", "c", "b"), listOf(triple)).isEmpty())
        assertTrue(completeDisplayUnits(listOf("ordinary"), listOf(first)).isEmpty())
    }

    @Test fun `capture DTO owns nested values stop lists and call via IDs and rejects mutable collection access`() {
        val cars = mutableListOf("a")
        val nested = linkedMapOf<String, JsonElement>("label" to JsonPrimitive("before"))
        val values = linkedMapOf<String, JsonElement>("nested" to JsonObject(nested))
        val vias = mutableListOf("via")
        val stops = mutableListOf(TimetableUnitStopSnapshot("duty", "run",
            TimetableRunCall("call", "station", "station@berth", TimetableServiceStopStatus.STOP, viaIds = vias), false))
        val unresolved = mutableListOf("later/run")
        val display = TimetableUnitDisplaySnapshot("unit", "trip", 5, JsonObject(values), stops, emptyList(), stops, unresolved)
        val captured = TimetableUnitDisplayContext("train", "unit", cars, "table", display, null)
        cars.clear(); nested["label"] = JsonPrimitive("after"); values.clear(); vias.clear(); stops.clear(); unresolved.clear()
        assertEquals(listOf("a"), captured.carIds)
        assertEquals("before", captured.display!!.values.getValue("nested").jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals(listOf("via"), captured.display.plannedStops.single().call.viaIds)
        assertEquals(listOf("later/run"), captured.display.unresolvedRuns)
        assertThrows(UnsupportedOperationException::class.java) { (captured.carIds as java.util.List<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (captured.display.values.getValue("nested").jsonObject as java.util.Map<*, *>).clear() }
        assertThrows(IllegalArgumentException::class.java) { TimetableUnitDisplayContext("train", "unit", listOf("a"), null, display, null) }
    }
    @Test fun `door side requires current entered run and actual stopping occurrence while NONE is known`() {
        val stop = TimetableUnitStopSnapshot("service","run",TimetableRunCall("origin","A","A@one",TimetableServiceStopStatus.ORIGIN),true)
        val display = TimetableUnitDisplaySnapshot("unit","trip",0,JsonObject(emptyMap()),listOf(stop),listOf(stop),emptyList())
        val run = TimetableRunDisplayContext("service","run","line", true,JsonObject(emptyMap()))
        val arrived = TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,0)
        val context = TimetableUnitDisplayContext("train","unit",listOf("car"),"table",display,null,run,arrived,DoorSide.NONE)
        assertEquals(DoorSide.NONE,context.doorSide)
        assertThrows(IllegalArgumentException::class.java) {
            TimetableUnitDisplayContext("train","unit",listOf("car"),"table",display,null,null,arrived,DoorSide.LEFT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimetableUnitDisplayContext("train","unit",listOf("car"),"table",display,null,run,
                TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT,0),DoorSide.LEFT)
        }
        assertNull(TimetableUnitDisplayContext("train","unit",listOf("car"),null,null,null).doorSide)
    }

}
