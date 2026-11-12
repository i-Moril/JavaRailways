package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.timetable.*

class OperationalPartRenderProjectionTest {
    private fun stop(id: String, station: String, status: TimetableServiceStopStatus,
        run: String = "run", completed: Boolean = false, arrival: Long? = null, departure: Long? = null) =
        TimetableUnitStopSnapshot("duty", run, TimetableRunCall(id, station, "$station@one", status,
            arrivalTick = arrival, departureTick = departure), completed)
    private fun snapshot(
        values: JsonObject = buildJsonObject { put("destination", "Authored destination"); put("runType", "Express") },
        planned: List<TimetableUnitStopSnapshot> = emptyList(),
        unresolved: List<String> = emptyList(),
        active: Boolean = true,
        cycle: Long = 3L,
    ) = TimetableUnitDisplaySnapshot("unit", "trip", cycle, values, planned, planned.filter { it.completed },
        planned.filterNot { it.completed }, unresolved, active)
    private fun context(display: TimetableUnitDisplaySnapshot? = snapshot(), berth: StationBerthRef? = null,
        progress: TimetableDisplayProgress = TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN)) =
        TimetableUnitDisplayContext("train", "unit", listOf("car-a", "car-b"), if (display == null) null else "table", display, berth,
            progress = progress)
    private val names = mapOf("A" to "Alpha", "B" to "Beta", "C" to "Central")
    private fun stationMetadata(id: String) = buildJsonObject {
        names[id]?.let { put("name", it) }
    }

    private fun projection(display: TimetableUnitDisplaySnapshot, berth: StationBerthRef? = null,
        progress: TimetableDisplayProgress = TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN)) =
        projectOperationalPartRender(context(display, berth, progress), stationMetadata = ::stationMetadata)

    @Test fun `default unit trip supplies the same ordered run facts and real identities as authored trips`() {
        val stops = listOf(stop("past", "A", TimetableServiceStopStatus.STOP, "actual-run", completed = true),
            stop("pass", "B", TimetableServiceStopStatus.PASS, "actual-run"),
            stop("terminal", "C", TimetableServiceStopStatus.TERMINAL, "actual-run"))
        val actualRun = TimetableRunDisplayContext("duty", "actual-run", "line", true,
            buildJsonObject { put("notice", "actual") }, "C")
        fun capture(tripId: String) = TimetableUnitDisplayContext(
            "actual-train", "unit", listOf("car-a", "car-b"), "table",
            TimetableUnitDisplaySnapshot("unit", tripId, 2L, JsonObject(emptyMap()), stops,
                stops.filter { it.completed }, stops.filterNot { it.completed }, emptyList(), true),
            currentBerth = null, currentRun = actualRun, progress = TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT, 1))
        fun project(tripId: String) = projectOperationalPartRender(capture(tripId), stationMetadata = { id ->
            if (id == "C") buildJsonObject { put("name", buildJsonObject { put("zh", "中央"); put("en", "Central") }) }
            else JsonObject(emptyMap())
        })
        val result = project("default-run/4:duty/10:actual-run")
        assertEquals(JsonPrimitive("unit"), result.unit.jsonObject["id"])
        val op = result.operational.jsonObject
        assertEquals(JsonPrimitive("default-run/4:duty/10:actual-run"), op["operationalTripId"])
        assertEquals(JsonPrimitive("table"), op["timetableId"])
        assertEquals(listOf("past", "pass", "terminal"), op.getValue("stops").jsonArray.map { it.jsonObject.getValue("callId").jsonPrimitive.content })
        assertEquals(JsonPrimitive(1), op["stationIndex"])
        assertEquals(JsonPrimitive("in_transit"), op["phase"])
        assertEquals(JsonPrimitive(3), op["plannedStopCount"])
        assertEquals(JsonPrimitive(1), op["completedStopCount"])
        assertEquals(JsonArray(emptyList()), op["unresolvedRuns"])
        assertEquals(JsonPrimitive("C"), op.getValue("nextStation").jsonObject["stationId"])
        assertEquals(buildJsonObject { put("zh", "中央"); put("en", "Central") }, op.getValue("values").jsonObject["destination"])
        assertFalse(op.getValue("values").jsonObject.containsKey("notice"))
        assertEquals(op - "operationalTripId", project("authored-trip").operational.jsonObject - "operationalTripId")
    }

    @Test fun `withdrawing actual run changes context key and never borrows another train projection`() {
        val display = snapshot(values = JsonObject(emptyMap()))
        fun current(terminal: String) = TimetableUnitDisplayContext("train", "unit", listOf("car-a", "car-b"),
            "table", display, currentBerth = null, currentRun = TimetableRunDisplayContext("duty", "run", "line", true, JsonObject(emptyMap()), terminal))
        val old = projectOperationalPartRender(current("B"), stationMetadata = ::stationMetadata)
        val updated = projectOperationalPartRender(current("C"), stationMetadata = ::stationMetadata)
        val outsideRun = projectOperationalPartRender(context(display), stationMetadata = ::stationMetadata)
        val inactive = projection(snapshot(active = false))
        assertNotEquals(old.contextKey, updated.contextKey)
        assertEquals(JsonPrimitive("Central"), updated.operational.jsonObject.getValue("values").jsonObject["destination"])
        assertEquals(JsonNull, outsideRun.operational.jsonObject["currentRun"])
        assertFalse(outsideRun.operational.jsonObject.getValue("values").jsonObject.containsKey("destination"))
        assertNotEquals(updated.contextKey, outsideRun.contextKey)
        assertEquals(JsonNull, inactive.operational)
        assertEquals("", inactive.aliases.getValue("destination").jsonPrimitive.content)
        assertEquals(outsideRun.unit, inactive.unit)
    }

    @Test fun `actual current run metadata is separate from authored values and missing scope stays null`() {
        val captured = context(snapshot())
        val actual = TimetableUnitDisplayContext(captured.trainId,captured.unitId,captured.carIds,captured.timetableId,
            captured.display,null,TimetableRunDisplayContext("service","run","line", true,buildJsonObject { put("notice","run-specific") }))
        val result = projectOperationalPartRender(actual, stationMetadata = ::stationMetadata)
        val run = result.operational.jsonObject.getValue("currentRun").jsonObject
        assertEquals(JsonPrimitive("service"),run["serviceId"])
        assertEquals(JsonPrimitive("line"),run["lineId"])
        assertEquals(JsonPrimitive("run-specific"),run.getValue("metadata").jsonObject["notice"])
        assertEquals(JsonNull,projectOperationalPartRender(captured,stationMetadata=::stationMetadata).operational.jsonObject["currentRun"])
        assertFalse(result.operational.jsonObject.getValue("values").jsonObject.containsKey("notice"))
    }

    @Test fun `current run carries boolean service status and changing it invalidates the rendering context`() {
        val captured = context(snapshot())
        fun project(isInService: Boolean) = projectOperationalPartRender(TimetableUnitDisplayContext(
            captured.trainId, captured.unitId, captured.carIds, captured.timetableId, captured.display, null,
            TimetableRunDisplayContext("service", "run", "line", isInService,
                buildJsonObject { put("isInService", "authored metadata") })))
        val passenger = project(true)
        val deadhead = project(false)
        for ((isInService, result) in listOf(true to passenger, false to deadhead)) {
            assertEquals(JsonPrimitive(isInService), result.operational.jsonObject.getValue("currentRun").jsonObject["isInService"])
            assertEquals(JsonPrimitive("Express"), result.aliases["runType"], "authored display strings retain their meaning")
        }
        assertNotEquals(passenger.contextKey, deadhead.contextKey)
        assertEquals(passenger.operational.jsonObject - "currentRun", deadhead.operational.jsonObject - "currentRun")
    }

    @Test fun `missing destination defaults to actual run terminal with localized metadata without mutating author values`() {
        val authored = buildJsonObject { put("notice", "retain") }
        val display = snapshot(values = authored, planned = listOf(
            stop("current-end", "B", TimetableServiceStopStatus.TERMINAL),
            stop("future-end", "C", TimetableServiceStopStatus.TERMINAL, run = "future")),
            unresolved = listOf("later/unknown"))
        val actual = TimetableUnitDisplayContext("train", "unit", listOf("car"), "table", display, null,
            TimetableRunDisplayContext("service", "run", "line", true, JsonObject(emptyMap()), "B"))
        val names = linkedMapOf<String, JsonElement>("zh" to JsonPrimitive("乙站"), "en" to JsonPrimitive("Beta Station"))
        fun project() = projectOperationalPartRender(actual,
            stationMetadata = { id -> if (id == "B") buildJsonObject { put("name", JsonObject(names)) } else JsonObject(emptyMap()) })
        val result = project()
        assertEquals(JsonObject(names), result.operational.jsonObject.getValue("values").jsonObject["destination"])
        assertEquals("乙站", result.aliases.getValue("destination").jsonPrimitive.content)
        assertEquals(JsonPrimitive("B"), result.operational.jsonObject.getValue("currentRun").jsonObject["terminalStationId"])
        assertFalse(authored.containsKey("destination"))
        assertFalse(actual.display!!.values.containsKey("destination"))
        names["en"] = JsonPrimitive("New Beta")
        assertEquals("Beta Station", result.operational.jsonObject.getValue("values").jsonObject.getValue("destination").jsonObject.getValue("en").jsonPrimitive.content)
        assertNotEquals(result.contextKey, project().contextKey, "terminal metadata changes invalidate the old destination")
    }

    @Test fun `default destination handles missing terminal and malformed names while retaining explicit overrides`() {
        fun project(values: JsonObject = JsonObject(emptyMap()), terminal: String? = "B", name: JsonElement? = null,
            currentRun: Boolean = true, active: Boolean = true): OperationalPartRenderProjection =
            projectOperationalPartRender(TimetableUnitDisplayContext("train", "unit", listOf("car"), "table",
                snapshot(values = values, active = active), null,
                if (currentRun) TimetableRunDisplayContext("service", "run", "line", true, JsonObject(emptyMap()), terminal) else null),
                stationMetadata = { buildJsonObject { if (name != null) put("name", name) } })
        for (name in listOf<JsonElement?>(null, JsonNull, JsonPrimitive(42), JsonPrimitive(""), JsonArray(emptyList()),
            buildJsonObject { put("zh", false); put("en", ""); put("foreign", "ignored") })) {
            val result = project(name = name)
            assertFalse(result.operational.jsonObject.getValue("values").jsonObject.containsKey("destination"))
            assertEquals("", result.aliases.getValue("destination").jsonPrimitive.content)
        }
        val partial = project(name = buildJsonObject { put("zh", "乙站"); put("en", false) })
        assertEquals(buildJsonObject { put("zh", "乙站") }, partial.operational.jsonObject.getValue("values").jsonObject["destination"])
        assertEquals("乙站", partial.aliases.getValue("destination").jsonPrimitive.content)
        assertEquals("Metadata Beta", project(name = JsonPrimitive("Metadata Beta")).aliases.getValue("destination").jsonPrimitive.content)
        for (override in listOf(JsonPrimitive("Branch"), JsonPrimitive(""), JsonNull, JsonPrimitive(false),
            JsonPrimitive(17), JsonArray(emptyList()), buildJsonObject { put("zh", "自定义") })) {
            val result = project(buildJsonObject { put("destination", override) })
            assertEquals(override, result.operational.jsonObject.getValue("values").jsonObject["destination"])
            assertEquals(if (override is JsonPrimitive && override.isString) override.content else "",
                result.aliases.getValue("destination").jsonPrimitive.content)
        }
        for (result in listOf(project(terminal = null), project(currentRun = false))) {
            assertFalse(result.operational.jsonObject.getValue("values").jsonObject.containsKey("destination"))
            assertEquals("", result.aliases.getValue("destination").jsonPrimitive.content)
        }
        val inactive = project(active = false)
        assertEquals(JsonNull, inactive.operational)
        assertEquals("", inactive.aliases.getValue("destination").jsonPrimitive.content)
        assertNotEquals(project().contextKey, project(currentRun = false).contextKey)
    }

    @Test fun `station metadata reaches history current next and repeated sequence occurrences as detached data`() {
        val mutable = linkedMapOf<String, JsonElement>("nameEn" to JsonPrimitive("Central Station"))
        val metadata = JsonObject(mutable)
        val stops = listOf(stop("one", "C", TimetableServiceStopStatus.STOP, completed = true),
            stop("two", "C", TimetableServiceStopStatus.STOP), stop("unknown", "foreign", TimetableServiceStopStatus.TERMINAL))
        val result = projectOperationalPartRender(context(snapshot(planned = stops), StationBerthRef(StationId("A"), "one"),
                TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT, 1)),
            stationMetadata = { if (it == "C" || it == "A") metadata else JsonObject(emptyMap()) })
        val operational = result.operational.jsonObject
        assertEquals(metadata, operational.getValue("currentStation").jsonObject.getValue("metadata"))
        assertEquals(metadata, operational.getValue("nextStation").jsonObject.getValue("metadata"))
        val remaining = operational.getValue("stops").jsonArray
        assertEquals(metadata, remaining[0].jsonObject.getValue("metadata"))
        assertEquals(metadata, remaining[1].jsonObject.getValue("metadata"))
        assertEquals(JsonObject(emptyMap()), remaining[2].jsonObject.getValue("metadata"))
        mutable["nameEn"] = JsonPrimitive("mutated")
        assertEquals("Central Station", operational.getValue("currentStation").jsonObject.getValue("metadata").jsonObject.getValue("nameEn").jsonPrimitive.content)
        val changed = projectOperationalPartRender(context(snapshot(planned = stops), StationBerthRef(StationId("A"), "one"),
                TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT, 1)),
            stationMetadata = { metadata })
        assertNotEquals(result.contextKey, changed.contextKey, "metadata changes invalidate stale completed screen output")
    }

    @Test fun `effective operating door side is detached nullable and invalidates old-side output`() {
        val display = snapshot(planned = listOf(stop("arrived", "C", TimetableServiceStopStatus.STOP, completed = true)))
        fun withSide(side: work.art1st.javarailways.basic.api.operation.DoorSide?) = projectOperationalPartRender(
            TimetableUnitDisplayContext("train", "unit", listOf("car-a"), "table", display,
                StationBerthRef(StationId("C"), "one"),
                TimetableRunDisplayContext("service", "run", "line", true, JsonObject(emptyMap())),
                TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION, 0), doorSide = side), stationMetadata = ::stationMetadata)
        val unknown = withSide(null)
        assertEquals(JsonNull, unknown.operational.jsonObject["doorSide"])
        for (side in work.art1st.javarailways.basic.api.operation.DoorSide.entries) {
            val captured = withSide(side)
            assertEquals(JsonPrimitive(side.name), captured.operational.jsonObject["doorSide"])
            assertNotEquals(unknown.contextKey, captured.contextKey)
        }
        assertNotEquals(withSide(work.art1st.javarailways.basic.api.operation.DoorSide.LEFT).contextKey,
            withSide(work.art1st.javarailways.basic.api.operation.DoorSide.RIGHT).contextKey)
        assertEquals(JsonNull, projection(snapshot()).operational.jsonObject["doorSide"], "no current stop cannot invent a side")
    }

    @Test fun `authored values retain types and aliases only use exact active strings`() {
        val values = buildJsonObject {
            put("destination", "Branch via Central"); put("runType", "Limited express")
            put("color", 123); put("flags", JsonArray(listOf(JsonPrimitive(true), JsonNull)))
            put("nested", buildJsonObject { put("label", "custom") })
        }
        val result = projection(snapshot(values))
        assertEquals(values, result.operational.jsonObject["values"])
        assertEquals("Branch via Central", result.aliases["destination"]?.jsonPrimitive?.content)
        assertEquals("Limited express", result.aliases["runType"]?.jsonPrimitive?.content)
        for (notString in listOf(JsonPrimitive(123), JsonPrimitive(true), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))) {
            val noAliases = projection(snapshot(buildJsonObject { put("destination", notString); put("runType", notString) }))
            assertEquals("", noAliases.aliases["destination"]?.jsonPrimitive?.content)
            assertEquals("", noAliases.aliases["runType"]?.jsonPrimitive?.content)
            assertEquals(notString, noAliases.operational.jsonObject["values"]?.jsonObject?.get("destination"))
        }
    }

    @Test fun `full sequence preserves reached history PASS repeated stations metadata and exact longs`() {
        val stops = listOf(
            stop("origin", "A", TimetableServiceStopStatus.ORIGIN, completed = true),
            stop("pass", "B", TimetableServiceStopStatus.PASS, arrival = Long.MAX_VALUE - 1),
            stop("visit-one", "C", TimetableServiceStopStatus.STOP, arrival = Long.MAX_VALUE, departure = Long.MAX_VALUE),
            stop("visit-two", "C", TimetableServiceStopStatus.STOP, run = "next-run"),
            stop("terminal", "foreign", TimetableServiceStopStatus.TERMINAL),
        )
        val result = projection(snapshot(planned = stops, cycle = Long.MAX_VALUE), progress =
            TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT, 1)).operational.jsonObject
        assertEquals("table", result["timetableId"]?.jsonPrimitive?.content)
        assertEquals("trip", result["operationalTripId"]?.jsonPrimitive?.content)
        assertEquals(Long.MAX_VALUE.toString(), result["cycleIndex"]?.jsonPrimitive?.content)
        assertTrue(result["cycleIndex"]!!.jsonPrimitive.isString)
        assertEquals(5, result["plannedStopCount"]?.jsonPrimitive?.int)
        assertEquals(1, result["completedStopCount"]?.jsonPrimitive?.int)
        assertEquals("in_transit", result["phase"]?.jsonPrimitive?.content)
        assertEquals(1, result["stationIndex"]?.jsonPrimitive?.int)
        val sequence = result.getValue("stops").jsonArray.map { it.jsonObject }
        assertEquals(listOf("origin", "pass", "visit-one", "visit-two", "terminal"), sequence.map { it["callId"]?.jsonPrimitive?.content })
        assertEquals(listOf("A", "B", "C", "C", "foreign"), sequence.map { it["stationId"]?.jsonPrimitive?.content })
        assertEquals("PASS", sequence[1]["status"]?.jsonPrimitive?.content)
        assertEquals("next-run", sequence[3]["runId"]?.jsonPrimitive?.content)
        assertEquals(Long.MAX_VALUE.toString(), sequence[2]["arrivalTick"]?.jsonPrimitive?.content)
        assertTrue(sequence[2]["arrivalTick"]!!.jsonPrimitive.isString)
        assertEquals(JsonNull, sequence[3]["departureTick"])
        assertEquals(JsonNull, sequence[4]["stationName"], "Unknown IDs must not be presented as station names")
        assertTrue(sequence[0].getValue("completed").jsonPrimitive.boolean)
        assertFalse(result.containsKey("plannedStops")); assertFalse(result.containsKey("completedStops"))
        assertFalse(result.containsKey("remainingStops"), "Only one full sequence crosses the template boundary")
        assertEquals("visit-one", result.getValue("nextStation").jsonObject["callId"]?.jsonPrimitive?.content)
    }

    @Test fun `arrival stays on indexed occurrence while next STOP advances past current and PASS`() {
        val stops = listOf(stop("origin", "A", TimetableServiceStopStatus.ORIGIN, completed = true),
            stop("first-c", "C", TimetableServiceStopStatus.STOP, completed = true),
            stop("pass", "B", TimetableServiceStopStatus.PASS),
            stop("second-c", "C", TimetableServiceStopStatus.STOP), stop("end", "foreign", TimetableServiceStopStatus.TERMINAL))
        val arrived = projection(snapshot(planned = stops), StationBerthRef(StationId("C"), "one"),
            TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION, 1))
        val data = arrived.operational.jsonObject
        assertEquals("at_station", data["phase"]?.jsonPrimitive?.content)
        assertEquals(1, data["stationIndex"]?.jsonPrimitive?.int)
        assertEquals("second-c", data.getValue("nextStation").jsonObject["callId"]?.jsonPrimitive?.content,
            "Repeated station IDs cannot identify the current/next occurrence")
        assertEquals("Central", arrived.aliases["currentStationName"]?.jsonPrimitive?.content)
        assertFalse(data.getValue("currentStation").jsonObject.containsKey("callId"))
        val travelling = projection(snapshot(planned = stops), progress = TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT, 2))
        assertEquals(2, travelling.operational.jsonObject["stationIndex"]?.jsonPrimitive?.int, "Physical cursor includes PASS")
        assertEquals("second-c", travelling.operational.jsonObject.getValue("nextStation").jsonObject["callId"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, travelling.operational.jsonObject["currentStation"])
        assertNotEquals(arrived.contextKey, travelling.contextKey, "Departure invalidates old arrival output")
        val terminal = projection(snapshot(planned = stops), progress = TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION, 4))
        assertEquals(JsonNull, terminal.operational.jsonObject["nextStation"])
    }

    @Test fun `unknown and finished progress never infer next station from completion or current berth`() {
        val stops = listOf(stop("origin", "A", TimetableServiceStopStatus.ORIGIN, completed = true),
            stop("end", "C", TimetableServiceStopStatus.TERMINAL))
        for (phase in listOf(TimetableDisplayPhase.UNKNOWN, TimetableDisplayPhase.FINISHED)) {
            val phaseStops = if (phase == TimetableDisplayPhase.FINISHED) stops.map { it.copy(completed = true) } else stops
            val result = projection(snapshot(planned = phaseStops), StationBerthRef(StationId("A"), "one"), TimetableDisplayProgress(phase))
            assertEquals(JsonNull, result.operational.jsonObject["stationIndex"])
            assertEquals(JsonNull, result.operational.jsonObject["nextStation"])
            assertEquals("", result.aliases["nextStationName"]?.jsonPrimitive?.content)
            assertEquals(2, result.operational.jsonObject.getValue("stops").jsonArray.size)
        }
    }

    @Test fun `unresolved legs make next unknown and no final stop is synthesized into destination`() {
        val stops = listOf(stop("end", "C", TimetableServiceStopStatus.TERMINAL))
        val result = projection(snapshot(values = JsonObject(emptyMap()), planned = stops, unresolved = listOf("child/unknown")),
            progress = TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT, 0))
        assertEquals(JsonArray(listOf(JsonPrimitive("child/unknown"))), result.operational.jsonObject["unresolvedRuns"])
        assertEquals(JsonNull, result.operational.jsonObject["nextStation"])
        assertEquals("", result.aliases["destination"]?.jsonPrimitive?.content)
        assertEquals("", result.aliases["runType"]?.jsonPrimitive?.content)
        assertEquals("", result.aliases["nextStationName"]?.jsonPrimitive?.content)
    }

    @Test fun `absent inactive and contextless registered units clear aliases while keeping valid membership`() {
        val absent = projectOperationalPartRender(null, stationMetadata = ::stationMetadata)
        assertSame(OperationalPartRenderProjection.EMPTY, absent)
        assertEquals(JsonNull, absent.unit); assertEquals(JsonNull, absent.operational)
        listOf(context(null), context(snapshot(active = false))).forEach { context ->
            val result = projectOperationalPartRender(context, stationMetadata = ::stationMetadata)
            assertEquals("unit", result.unit.jsonObject["id"]?.jsonPrimitive?.content)
            assertEquals(JsonArray(listOf(JsonPrimitive("car-a"), JsonPrimitive("car-b"))), result.unit.jsonObject["carIds"])
            assertEquals(JsonNull, result.operational)
            assertTrue(result.aliases.values.all { it.jsonPrimitive.isString && it.jsonPrimitive.content.isEmpty() })
        }
    }

    @Test fun `unknown current berth name remains null and never falls back to station ID`() {
        val result = projection(snapshot(), StationBerthRef(StationId("foreign"), "one"))
        assertEquals("foreign", result.operational.jsonObject["currentStation"]?.jsonObject?.get("stationId")?.jsonPrimitive?.content)
        assertEquals(JsonNull, result.operational.jsonObject["currentStation"]?.jsonObject?.get("stationName"))
        assertEquals("", result.aliases["currentStationName"]?.jsonPrimitive?.content)
    }

    @Test fun `projection and semantic keys own immutable values and differ on reset or trip facts`() {
        val values = linkedMapOf<String, JsonElement>("destination" to JsonPrimitive("captured"))
        val display = snapshot(values = JsonObject(values))
        val before = projection(display)
        values["destination"] = JsonPrimitive("changed")
        assertEquals("captured", before.operational.jsonObject["values"]?.jsonObject?.get("destination")?.jsonPrimitive?.content)
        assertThrows(UnsupportedOperationException::class.java) { (before.contextKey as java.util.Map<*, *>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (before.operational.jsonObject as java.util.Map<*, *>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (before.unit.jsonObject.getValue("carIds").jsonArray as java.util.List<*>).clear() }
        assertEquals(before.contextKey, projection(snapshot(buildJsonObject { put("destination", "captured") })).contextKey)
        assertNotEquals(before.contextKey, projection(snapshot(active = false)).contextKey)
        assertNotEquals(before.contextKey, projection(snapshot(values = JsonObject(emptyMap()))).contextKey)
        assertNotEquals(before.contextKey, projection(snapshot(cycle = 4L)).contextKey)
    }
}
