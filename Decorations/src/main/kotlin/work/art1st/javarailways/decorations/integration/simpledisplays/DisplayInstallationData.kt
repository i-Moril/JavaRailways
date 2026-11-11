package work.art1st.javarailways.decorations.integration.simpledisplays

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import work.art1st.javarailways.decorations.display.DisplayPartConfig

/** Maps detached operating-side facts to this installation's fixed car HEAD basis. */
internal fun withDisplayInstallation(data: JsonObject, config: DisplayPartConfig): JsonObject {
    fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    val side = (data["operational"] as? JsonObject)?.get("doorSide").stringOrNull()
    val end = (data["car"] as? JsonObject)?.get("operatingEnd").stringOrNull()
    val openingSide = when (side) {
        "BOTH" -> "both"
        "NONE" -> "none"
        "LEFT", "RIGHT" -> if (config.doorSide == null || end !in setOf("head", "tail")) null else {
            val headSide = if ((side == "LEFT") == (end == "head")) "left" else "right"
            if (headSide == config.doorSide) "this" else "opposite"
        }
        else -> null
    }
    val display = buildJsonObject {
        put("doorSide", config.doorSide?.let(::JsonPrimitive) ?: JsonNull)
        put("openingSide", openingSide?.let(::JsonPrimitive) ?: JsonNull)
    }
    return JsonObject(data + ("display" to display))
}
