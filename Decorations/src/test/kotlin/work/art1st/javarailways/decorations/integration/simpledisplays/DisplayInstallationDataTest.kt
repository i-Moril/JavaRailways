package work.art1st.javarailways.decorations.integration.simpledisplays

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.decorations.display.DisplayPartConfig

class DisplayInstallationDataTest {
    private fun input(side: String?, end: String?) = Json.parseToJsonElement(
        """{"operational":{"doorSide":${side?.let { "\"$it\"" } ?: "null"}},"car":{"operatingEnd":${end?.let { "\"$it\"" } ?: "null"}},"train":{"name":"pinned"}}"""
    ).jsonObject

    @Test fun `head and tail use actual operation reversal convention for both installed sides`() {
        for (installed in listOf("left", "right")) {
            for (end in listOf("head", "tail")) {
                val sameOperatingSide = if ((installed == "left") == (end == "head")) "LEFT" else "RIGHT"
                for (operating in listOf("LEFT", "RIGHT")) {
                    val source = input(operating, end)
                    val projected = withDisplayInstallation(source, DisplayPartConfig("cabin", true, 1.0, 1.0, doorSide = installed))
                    val display = projected.getValue("display").jsonObject
                    assertEquals(JsonPrimitive(installed), display["doorSide"])
                    assertEquals(JsonPrimitive(if (operating == sameOperatingSide) "this" else "opposite"), display["openingSide"])
                    assertFalse("display" in source)
                    assertSame(source["train"], projected["train"])
                    assertEquals(source["car"], projected["car"])
                    assertEquals(source["operational"], projected["operational"])
                }
            }
        }
    }

    @Test fun `known both and none need neither installation nor operating end while directional facts do`() {
        for (installed in listOf(null, "left", "right")) {
            for (end in listOf(null, "head", "tail", "unknown")) {
                val config = DisplayPartConfig("cabin", true, 1.0, 1.0, doorSide = installed)
                for ((side, expected) in listOf("BOTH" to "both", "NONE" to "none")) {
                    assertEquals(JsonPrimitive(expected), withDisplayInstallation(input(side, end), config)["display"]!!.jsonObject["openingSide"])
                }
                for (side in listOf(null, "left", "INVALID")) {
                    assertEquals(JsonNull, withDisplayInstallation(input(side, end), config)["display"]!!.jsonObject["openingSide"])
                }
                if (installed == null || end !in listOf("head", "tail")) {
                    for (side in listOf("LEFT", "RIGHT")) {
                        assertEquals(JsonNull, withDisplayInstallation(input(side, end), config)["display"]!!.jsonObject["openingSide"])
                    }
                }
            }
        }
        val empty = withDisplayInstallation(Json.parseToJsonElement("{}").jsonObject, DisplayPartConfig("cabin", true, 1.0, 1.0))
        assertEquals(JsonNull, empty["display"]!!.jsonObject["openingSide"])
        assertEquals(JsonNull, empty["display"]!!.jsonObject["doorSide"])
    }
}
