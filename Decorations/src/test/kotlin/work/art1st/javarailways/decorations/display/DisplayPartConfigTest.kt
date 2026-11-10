package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DisplayPartConfigTest {
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
