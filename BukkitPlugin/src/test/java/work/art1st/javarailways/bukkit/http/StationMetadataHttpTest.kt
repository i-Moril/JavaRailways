package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.rail.RailObjectStore
import work.art1st.javarailways.simpleato.api.station.*

class StationMetadataHttpTest {
    @Test fun `request preserves nested user data and rejects nonobjects missing baseline and unknown fields`() {
        val request = parseStationMetadataUpdate("""{"metadata":{"transfers":[{"name":"2号线"}],"flags":[true,null,3.5]},"expectedMetadata":{}}""")
        assertEquals("2号线", request.metadata.getValue("transfers").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), request.expectedMetadata)
        for (body in listOf("[]", "null", "{}", """{"metadata":{},"expectedMetadata":null}""",
            """{"metadata":[],"expectedMetadata":{}}""", """{"metadata":{},"expectedMetadata":{},"extra":1}""")) {
            assertEquals(400, assertThrows(HttpFailure::class.java) { parseStationMetadataUpdate(body) }.status)
        }
    }

    @Test fun `station response uses canonical identity and metadata without execution or rewriting`() {
        val id = StationId("中央")
        val metadata = buildJsonObject { put("script", "throw Error('plain data')"); put("custom", JsonNull) }
        val station = Station(id, listOf(StationBerthRef(id, "one")), metadata = metadata)
        val response = stationMetadataJson(station)
        assertEquals(setOf("id", "metadata"), response.keys)
        assertEquals("中央", response.getValue("id").jsonPrimitive.content)
        assertEquals(metadata, response.getValue("metadata"))
    }

    @Test fun `missing station returns 404 without creating an orphan metadata record`() {
        val store = StationStore(RailObjectStore())
        assertEquals(404, assertThrows(HttpFailure::class.java) {
            updateStationMetadataJson(store, "missing", StationMetadataUpdate(JsonObject(emptyMap()), JsonObject(emptyMap())))
        }.status)
        assertTrue(store.all().isEmpty())
    }
}
