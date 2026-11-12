package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.persistence.SavedTimetableV1
import work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec

class DefaultOperationalTripTest {
    private fun segment(id: String, start: Int, from: String, to: String) =
        TimetableRunSegment(id, LineId("line"), start, start + 1, true, calls = listOf(
            TimetableRunCall("$id-origin", from, "$from@one", TimetableServiceStopStatus.ORIGIN),
            TimetableRunCall("$id-end", to, "$to@one", TimetableServiceStopStatus.TERMINAL)))
    private val first = segment("first", 0, "A", "C")
    private val second = segment("second", 1, "C", "E")
    private val service = TimetableService("duty", steps = listOf(TimetableStep.Check("A@one"), TimetableStep.Check("C@one")),
        runSegments = listOf(first, second))
    private fun values(text: String) = JsonObject(mapOf("notice" to JsonPrimitive(text)))
    private fun Timetable.default(runId: String) = operationalTrips.single { trip ->
        trip.segments.singleOrNull()?.let { it.unassignedUnits && it.serviceId == service.id && it.runId == runId } == true
    }

    @Test fun `construction copy store and strict JSON restore keep exactly one generated trip per RUN`() {
        val table = Timetable("table", services = listOf(service))
        assertEquals(2, table.operationalTrips.size)
        assertNotEquals(table.default("first").id, table.default("second").id)
        assertTrue(table.operationalTrips.all { it.defaults.isEmpty() && it.segments.single().unitSlots.isEmpty() })
        assertEquals(table, table.copy())
        assertEquals(table.hashCode(), table.copy().hashCode())
        val store = TimetableStore()
        store.add(table.copy())
        store.replaceAll(store.all())
        assertEquals(table, store["table"])
        val encoded = TimetableSnapshotCodec.encode(SavedTimetableV1.capture(table))
        val restored = TimetableSnapshotCodec.decode(encoded).restore()
        assertEquals(table, restored)
        assertEquals(encoded, TimetableSnapshotCodec.encode(SavedTimetableV1.capture(restored.copy())))
        val reduced = restored.copy(services = listOf(service.copy(runSegments = listOf(first))))
        assertEquals(listOf("first"), reduced.operationalTrips.map { it.segments.single().runId })
        assertEquals(table.default("first").id, reduced.default("first").id)
        assertTrue(Timetable("navigation", services = listOf(service.copy(runSegments = emptyList()))).operationalTrips.isEmpty())
    }

    @Test fun `authored IDs including generated-looking names retain values and win collisions`() {
        val plain = Timetable("table", services = listOf(service))
        val reserved = plain.default("first").id
        val authored = TimetableOperationalTrip(reserved, values("authored"), listOf(
            TimetableOperationalTripSegment("duty", "first", listOf(1))))
        val table = Timetable("table", services = listOf(service), operationalTrips = listOf(authored))
        assertSame(authored, table.operationalTrips.first())
        assertEquals("$reserved#1", table.default("first").id)
        assertEquals(table.operationalTrips, table.copy().operationalTrips)
        val noRuns = table.copy(services = listOf(service.copy(runSegments = emptyList())))
        assertEquals(listOf(authored), noRuns.operationalTrips, "Only generated items may be dropped during normalization")
    }

    @Test fun `default selection follows actual one or many complete units instead of slot one`() {
        val table = Timetable("table", services = listOf(service))
        for (units in listOf(listOf("U1"), listOf("U1", "U2", "U3"))) {
            val runtime = TimetableDisplayRuntime()
            runtime.enter(table, 0, service, first, units)
            val displays = runtime.snapshots(table, 0, units)
            assertEquals(units, displays.map { it.unitId })
            assertTrue(displays.all { it.operationalTripId == table.default("first").id && it.active })
            assertTrue(displays.all { it.plannedStops.map { stop -> stop.call.stationId } == listOf("A", "C") })
        }
    }

    @Test fun `new default RUN resets values and station sequence without merging consecutive RUNs`() {
        val table = Timetable("table", services = listOf(service))
        val runtime = TimetableDisplayRuntime()
        runtime.enter(table, 0, service, first, listOf("U1", "U2"))
        runtime.set("table", 0, listOf("U1", "U2"), values("first only"))
        runtime.enter(table, 0, service, second, listOf("U2", "U1"))
        val displays = runtime.snapshots(table, 0, listOf("U1", "U2"))
        assertTrue(displays.all { it.operationalTripId == table.default("second").id && it.values.isEmpty() })
        assertTrue(displays.all { it.plannedStops.map { stop -> stop.call.stationId } == listOf("C", "E") })
    }

    @Test fun `explicit cross-RUN unit ownership takes priority while peers receive fresh defaults`() {
        val authored = TimetableOperationalTrip("through", values("authored"), listOf(
            TimetableOperationalTripSegment("duty", "first", listOf(1)),
            TimetableOperationalTripSegment("duty", "second", listOf(2))))
        val table = Timetable("table", services = listOf(service), operationalTrips = listOf(authored))
        val runtime = TimetableDisplayRuntime()
        runtime.enter(table, 0, service, first, listOf("U1", "U2"))
        runtime.set("table", 0, listOf("U1", "U2"), values("changed"))
        runtime.enter(table, 0, service, second, listOf("U2", "U1"))
        val displays = runtime.snapshots(table, 0, listOf("U1", "U2")).associateBy { it.unitId }
        assertEquals("through", displays.getValue("U1").operationalTripId)
        assertEquals(values("changed"), displays.getValue("U1").values)
        assertEquals(table.default("second").id, displays.getValue("U2").operationalTripId)
        assertTrue(displays.getValue("U2").values.isEmpty())
        runtime.set("table", 0, listOf("U1", "U2"), null)
        assertEquals(values("authored"), runtime.snapshots(table, 0, listOf("U1")).single().values)
        assertTrue(runtime.snapshots(table, 0, listOf("U2")).single().values.isEmpty())
    }

    @Test fun `default BEGIN prepares all unclaimed units and RUN entry preserves pre-run SET`() {
        val authored = TimetableOperationalTrip("authored", values("peer"), listOf(
            TimetableOperationalTripSegment("duty", "first", listOf(2))))
        val table = Timetable("table", services = listOf(service), operationalTrips = listOf(authored))
        val runtime = TimetableDisplayRuntime()
        val units = listOf("U1", "U2", "U3")
        runtime.begin(table, 0, service, TimetableAction.BeginOperationalTrip("authored", "first", listOf(2)), units)
        runtime.begin(table, 0, service, TimetableAction.BeginOperationalTrip(table.default("first").id, "first", emptyList()), units)
        runtime.set("table", 0, units, values("station program"))
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN), runtime.captureProgress(table, 0, "U1", null, false))
        runtime.enter(table, 0, service, first, units)
        val displays = runtime.snapshots(table, 0, units)
        assertTrue(displays.all { it.values == values("station program") })
        assertEquals(listOf(table.default("first").id, "authored", table.default("first").id), displays.map { it.operationalTripId })
    }

    @Test fun `empty explicit BEGIN and explicit default slots are rejected without replacing active values`() {
        val authored = TimetableOperationalTrip("authored", values("peer"), listOf(
            TimetableOperationalTripSegment("duty", "first", listOf(1))))
        val table = Timetable("table", services = listOf(service), operationalTrips = listOf(authored))
        val runtime = TimetableDisplayRuntime()
        runtime.enter(table, 0, service, first, listOf("U1", "U2"))
        val before = runtime.snapshots(table, 0, listOf("U1", "U2"))
        assertThrows(IllegalArgumentException::class.java) {
            runtime.begin(table, 0, service, TimetableAction.BeginOperationalTrip("authored", "first", emptyList()), listOf("U1", "U2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.begin(table, 0, service, TimetableAction.BeginOperationalTrip(table.default("first").id, "first", listOf(2)), listOf("U1", "U2"))
        }
        assertEquals(before, runtime.snapshots(table, 0, listOf("U1", "U2")))
    }

    @Test fun `marked generated definitions reject authored defaults mixed legs and explicit slot lists`() {
        val marked = TimetableOperationalTripSegment("duty", "first", emptyList(), unassignedUnits = true)
        assertThrows(IllegalArgumentException::class.java) { TimetableOperationalTrip("bad", values("must not be lost"), listOf(marked)) }
        assertThrows(IllegalArgumentException::class.java) {
            TimetableOperationalTrip("bad", segments = listOf(marked, TimetableOperationalTripSegment("duty", "second", listOf(1))))
        }
        assertThrows(IllegalArgumentException::class.java) { TimetableOperationalTripSegment("duty", "first", listOf(1), unassignedUnits = true) }
        assertThrows(IllegalArgumentException::class.java) { TimetableOperationalTripSegment("duty", "first", emptyList()) }
    }
}
