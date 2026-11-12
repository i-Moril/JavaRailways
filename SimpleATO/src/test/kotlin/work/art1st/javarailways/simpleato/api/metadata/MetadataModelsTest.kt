package work.art1st.javarailways.simpleato.api.metadata

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.api.line.LineRevisionConflict
import work.art1st.javarailways.simpleato.api.line.LineStore
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.api.station.StationMetadata
import work.art1st.javarailways.simpleato.persistence.SavedLineV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableRunSegmentV1
import work.art1st.javarailways.simpleato.timetable.TimetableRunSegment

class MetadataModelsTest {
    private val empty = JsonObject(emptyMap())
    private fun line(metadata: JsonObject = empty) = Line(
        LineId("metro"), listOf(StationId("a"), StationId("b")),
        listOf(StationId("b"), StationId("a")), metadata = metadata
    )
    private fun run(metadata: JsonObject = empty) = TimetableRunSegment(
        "run", LineId("metro"), 0, 2, true, metadata = metadata
    )

    @Test
    fun `fixed missing field inputs default to empty without changing persisted boundaries`() {
        val oldLine = """{"id":"metro","upStationIds":["a","b"],"downStationIds":["b","a"]}"""
        val oldRun = """{"runId":"run","lineId":"metro","startStepIndex":0,"endStepIndexExclusive":2,"isInService":true}"""
        assertEquals(empty, Json.decodeFromString<SavedLineV1>(oldLine).restore().metadata)
        assertEquals(empty, Json.decodeFromString<SavedTimetableRunSegmentV1>(oldRun).restore().metadata)
    }

    @Test
    fun `line and run preserve independent metadata through saved JSON and equality`() {
        val lineMetadata = Json.parseToJsonElement("""{"nameEn":"Metro","custom":{"flags":[true,null,4]}}""").jsonObject
        val runMetadata = Json.parseToJsonElement("""{"notice":"Express","custom":{"flags":[false]}}""").jsonObject
        val sourceLine = line(lineMetadata)
        val sourceRun = run(runMetadata)
        val restoredLine = Json.decodeFromString<SavedLineV1>(Json.encodeToString(SavedLineV1.capture(sourceLine))).restore()
        val restoredRun = Json.decodeFromString<SavedTimetableRunSegmentV1>(Json.encodeToString(SavedTimetableRunSegmentV1.capture(sourceRun))).restore()
        assertEquals(sourceLine, restoredLine)
        assertEquals(sourceLine.hashCode(), restoredLine.hashCode())
        assertEquals(sourceRun, restoredRun)
        assertEquals(sourceRun.hashCode(), restoredRun.hashCode())
        assertNotEquals(sourceLine, line())
        assertNotEquals(sourceRun, run())
        assertEquals(lineMetadata, restoredLine.metadata)
        assertEquals(runMetadata, restoredRun.metadata)
    }

    @Test
    fun `domain construction detaches mutable nested metadata and exposes immutable collections`() {
        val children = mutableListOf<JsonElement>(JsonPrimitive("original"))
        val nested = linkedMapOf<String, JsonElement>("children" to JsonArray(children))
        val source = linkedMapOf<String, JsonElement>("nested" to JsonObject(nested))
        val original = JsonObject(source)
        val line = line(original)
        val run = run(original)
        val station = StationMetadata.immutableCopy(original)
        children[0] = JsonPrimitive("changed")
        nested.clear()
        source.clear()
        listOf(line.metadata, run.metadata, station).forEach { metadata ->
            assertEquals("original", metadata.getValue("nested").jsonObject.getValue("children").jsonArray[0].jsonPrimitive.content)
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (metadata.values as MutableCollection<JsonElement>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (metadata.getValue("nested").jsonObject.getValue("children").jsonArray.subList(0, 1) as MutableList<JsonElement>).clear()
            }
        }
    }

    @Test
    fun `metadata edits use existing line revision guard and are retained by fresh store restoration`() {
        val store = LineStore()
        val first = store.put(line(), 0)
        val edited = line(buildJsonObject { put("notice", "Changed") })
        val second = store.put(edited, first)
        assertThrows(LineRevisionConflict::class.java) { store.put(line(), first) }
        assertEquals(edited.metadata, store[edited.id]!!.metadata)
        assertEquals(second, store.snapshot().revision)
        val detached = LineStore()
        detached.restore(store.snapshot().lines.map { SavedLineV1.capture(it).restore() }, second)
        assertEquals(store.snapshot(), detached.snapshot())
    }

    @Test
    fun `shared metadata bounds apply to both models and snapshot restore`() {
        var tooDeep: JsonElement = JsonNull
        repeat(JsonMetadata.MAX_DEPTH + 1) { tooDeep = JsonArray(listOf(tooDeep)) }
        val invalid = listOf(
            JsonObject(mapOf("bad" to JsonPrimitive(Double.NaN))),
            JsonObject(mapOf("bad" to JsonPrimitive(Double.POSITIVE_INFINITY))),
            JsonObject(mapOf("deep" to tooDeep)),
            JsonObject(mapOf("many" to JsonArray(List(JsonMetadata.MAX_NODES) { JsonNull }))),
            JsonObject(mapOf("unicode" to JsonPrimitive("界".repeat(JsonMetadata.MAX_BYTES / 2))))
        )
        invalid.forEach { metadata ->
            assertThrows(IllegalArgumentException::class.java) { line(metadata) }
            assertThrows(IllegalArgumentException::class.java) { run(metadata) }
            assertThrows(IllegalArgumentException::class.java) { SavedLineV1.capture(line()).copy(metadata = metadata).restore() }
            assertThrows(IllegalArgumentException::class.java) { SavedTimetableRunSegmentV1.capture(run()).copy(metadata = metadata).restore() }
        }
    }
}
