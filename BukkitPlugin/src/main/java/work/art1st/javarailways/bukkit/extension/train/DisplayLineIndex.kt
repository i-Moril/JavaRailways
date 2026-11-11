package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.json.*
import work.art1st.javarailways.bukkit.api.model.extension.immutablePartExtensionJson
import work.art1st.javarailways.simpleato.api.line.Line

/** Called once per fresh world input sample, never by guest code or an entity-region callback. */
internal fun captureDisplayLineIndex(lines: List<Line>): JsonObject {
    require(lines.map { it.id }.distinct().size == lines.size) { "Duplicate display line identity" }
    return immutablePartExtensionJson(buildJsonObject {
        lines.forEach { line -> put(line.id.value, buildJsonObject {
            put("id", line.id.value)
            put("displayName", line.displayName)
            put("metadata", line.metadata)
            line.color?.let { put("color", it) }
        }) }
    }, 0) as JsonObject
}
