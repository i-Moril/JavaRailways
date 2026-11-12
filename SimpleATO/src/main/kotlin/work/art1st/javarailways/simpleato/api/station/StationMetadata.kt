package work.art1st.javarailways.simpleato.api.station

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import work.art1st.javarailways.simpleato.api.metadata.JsonMetadata

/** A stale editor must reload before replacing another writer's station metadata. */
class StationMetadataConflict(val stationId: StationId) :
    IllegalStateException("Station metadata changed: $stationId")

/** Station-facing access to the shared bounded immutable JSON policy. */
object StationMetadata {
    const val MAX_DEPTH = JsonMetadata.MAX_DEPTH
    const val MAX_NODES = JsonMetadata.MAX_NODES
    const val MAX_BYTES = JsonMetadata.MAX_BYTES

    fun name(metadata: JsonObject): JsonElement? = when (val name = metadata["name"]) {
        is JsonPrimitive -> name.takeIf { it.isString && it.content.isNotBlank() }
        is JsonObject -> buildJsonObject {
            for (language in listOf("zh", "en")) {
                val translation = name[language] as? JsonPrimitive
                if (translation != null && translation.isString && translation.content.isNotBlank()) {
                    put(language, translation)
                }
            }
        }.takeIf { it.isNotEmpty() }
        else -> null
    }

    fun preferredName(name: JsonElement?): String? = when (name) {
        is JsonPrimitive -> name.content
        is JsonObject -> ((name["zh"] ?: name["en"]) as? JsonPrimitive)?.content
        else -> null
    }

    fun immutableCopy(metadata: JsonObject): JsonObject = JsonMetadata.immutableCopy(metadata)
}
