package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RailRenderMetaBrightnessTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Test
    fun `current reader defaults brightness missing from existing v2 metadata`() {
        val fixture = """
            {
              "version": 2,
              "templates": [{
                "templateId": "old-template",
                "repeatLength": 0.95,
                "elements": [{
                  "type": "BLOCK",
                  "itemStack": null,
                  "blockMaterial": "RAIL",
                  "offsetX": 0.0,
                  "offsetY": 0.0,
                  "offsetZ": 0.0,
                  "rotationX": 0.0,
                  "rotationY": 0.0,
                  "rotationZ": 0.0,
                  "scaleX": 1.0,
                  "scaleY": 1.0,
                  "scaleZ": 1.0
                }]
              }],
              "rails": []
            }
        """.trimIndent()

        val decoded = json.decodeFromString<RailRenderMetaFileV2>(fixture)
        val element = decoded.templates.single().elements.single()

        assertNull(element.brightnessBlock)
        assertNull(element.brightnessSky)
    }

    @Test
    fun `current metadata round trip retains brightness override without version bump`() {
        val element = RailRenderTemplateElementV2(
            type = "BLOCK",
            blockMaterial = "RAIL",
            brightnessBlock = 15,
            brightnessSky = 8,
            offsetX = 0.0,
            offsetY = 0.0,
            offsetZ = 0.0,
            scaleX = 1.0,
            scaleY = 1.0,
            scaleZ = 1.0
        )
        val file = RailRenderMetaFileV2(
            version = 2,
            templates = listOf(RailRenderTemplateV2("lit-template", 0.95, listOf(element))),
            rails = emptyList()
        )

        val encoded = json.encodeToString(file)
        val restored = json.decodeFromString<RailRenderMetaFileV2>(encoded)
            .templates.single().elements.single()

        assertTrue(encoded.contains("\"version\":2"))
        assertEquals(15, restored.brightnessBlock)
        assertEquals(8, restored.brightnessSky)
    }
}
