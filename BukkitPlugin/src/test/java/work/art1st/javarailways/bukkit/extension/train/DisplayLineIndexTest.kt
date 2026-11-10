package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.api.station.StationId

class DisplayLineIndexTest {
    private fun line(id: String, metadata: JsonObject = JsonObject(emptyMap())) =
        Line(LineId(id), "Line $id", listOf(StationId("a"), StationId("b")), listOf(StationId("b"), StationId("a")), metadata = metadata)

    @Test fun `whole world catalog allows arbitrary transfer lookup and preserves opaque data`() {
        val source = linkedMapOf<String, JsonElement>("label" to JsonPrimitive("airport"))
        val index = captureDisplayLineIndex(listOf(line("current"), line("airport", JsonObject(source))))
        source.clear()
        assertEquals(setOf("current", "airport"), index.keys)
        assertEquals("airport", index.getValue("airport").jsonObject.getValue("metadata").jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), index.getValue("current").jsonObject.getValue("metadata"))
        assertNull(index["missing"])
        assertEquals(JsonObject(emptyMap()), captureDisplayLineIndex(emptyList()), "a different empty world cannot inherit another catalog")
        assertThrows(IllegalArgumentException::class.java) { captureDisplayLineIndex(listOf(line("same"), line("same"))) }
    }
}
