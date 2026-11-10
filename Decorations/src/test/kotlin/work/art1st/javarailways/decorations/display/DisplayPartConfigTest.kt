package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DisplayPartConfigTest {
    @Test fun `optional template defaults to absent and accepts only valid string identifiers`() {
        val base = Json.parseToJsonElement("""{"category":"cabin","interior":true,"width":0.8,"height":0.45}""").jsonObject
        assertNull(DisplayPartConfig.parse(base, "part cabin").template)
        val explicit = kotlinx.serialization.json.JsonObject(base + ("template" to JsonPrimitive("cabin-main")))
        assertEquals("cabin-main", DisplayPartConfig.parse(explicit, "part cabin").template)
        listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive(""), JsonPrimitive("bad id"),
            Json.parseToJsonElement("[]"), Json.parseToJsonElement("{}")).forEach { invalid ->
            assertThrows<IllegalArgumentException> {
                DisplayPartConfig.parse(kotlinx.serialization.json.JsonObject(base + ("template" to invalid)), "part cabin")
            }
        }
        assertThrows<IllegalArgumentException> { DisplayPartConfig("cabin", true, 1.0, 1.0, "bad id") }
    }

    @Test fun `optional installation door side is strict lowercase and defaults absent`() {
        val base = Json.parseToJsonElement("""{"category":"cabin","interior":true,"width":0.8,"height":0.45}""").jsonObject
        assertNull(DisplayPartConfig.parse(base, "part cabin").doorSide)
        for (side in listOf("left", "right")) {
            assertEquals(side, DisplayPartConfig.parse(kotlinx.serialization.json.JsonObject(base + ("door-side" to JsonPrimitive(side))), "part cabin").doorSide)
        }
        for (invalid in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive(""), JsonPrimitive("LEFT"), JsonPrimitive("both"), JsonPrimitive(" left "))) {
            assertThrows<IllegalArgumentException> {
                DisplayPartConfig.parse(kotlinx.serialization.json.JsonObject(base + ("door-side" to invalid)), "part cabin")
            }
        }
        assertThrows<IllegalArgumentException> { DisplayPartConfig("cabin", true, 1.0, 1.0, doorSide = "RIGHT") }
    }

    @Test fun `four display fields retain installation semantics`() {
        assertEquals(DisplayPartConfig("custom-category", true, 0.8, 0.45),
            DisplayPartConfig.parse(Json.parseToJsonElement("""{"category":"custom-category","interior":true,"width":0.8,"height":0.45}""").jsonObject, "part cabin"))
    }
    @Test fun `config rejects missing unknown wrong types invalid IDs and invalid dimensions`() {
        val valid = """{"category":"cabin","interior":true,"width":0.8,"height":0.45}"""
        val invalid = listOf(
            "{}", valid.replace("\"category\":\"cabin\",", ""), valid.replace("\"interior\":true,", ""),
            valid.replace("\"width\":0.8,", ""), valid.replace(",\"height\":0.45", ""),
            valid.replace("\"cabin\"", "\"\""), valid.replace("\"cabin\"", "\"bad category\""),
            valid.replace("\"cabin\"", "1"), valid.replace("true", "\"true\""), valid.replace("true", "1"),
            valid.replace("0.8", "\"0.8\""), valid.replace("0.8", "true"), valid.replace("0.8", "0"),
            valid.replace("0.8", "-1"), valid.replace("0.8", "1e999"), valid.replace("0.45", "0"),
            valid.replace("0.45", "null"), valid.replace("}", ",\"unknown\":true}"),
        )
        invalid.forEach { text -> assertThrows<IllegalArgumentException>(text) {
            DisplayPartConfig.parse(Json.parseToJsonElement(text).jsonObject, "part cabin")
        } }
    }
    @Test fun `direct constructors reject nonfinite dimensions and invalid identifiers`() {
        assertThrows<IllegalArgumentException> { DisplayPartConfig("", false, 1.0, 1.0) }
        assertThrows<IllegalArgumentException> { DisplayPartConfig("bad id", false, 1.0, 1.0) }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0).forEach {
            assertThrows<IllegalArgumentException> { DisplayPartConfig("cabin", false, it, 1.0) }
            assertThrows<IllegalArgumentException> { DisplayPartConfig("cabin", false, 1.0, it) }
        }
    }
}
