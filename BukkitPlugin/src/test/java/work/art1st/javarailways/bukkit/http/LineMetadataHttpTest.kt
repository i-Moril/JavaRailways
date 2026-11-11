package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LineMetadataHttpTest {
    @Test fun `missing metadata defaults empty while explicit nonobjects are rejected`() {
        assertEquals(JsonObject(emptyMap()), parseLineMetadata(null))
        for (invalid in listOf(JsonNull, JsonPrimitive(false), JsonPrimitive("{}"), JsonArray(emptyList()))) {
            assertEquals(400, assertThrows(HttpFailure::class.java) { parseLineMetadata(invalid) }.status)
        }
        val source = linkedMapOf<String, JsonElement>("notice" to JsonPrimitive("custom"))
        val parsed = parseLineMetadata(JsonObject(source))
        source.clear()
        assertEquals(JsonPrimitive("custom"), parsed["notice"])
    }
}
