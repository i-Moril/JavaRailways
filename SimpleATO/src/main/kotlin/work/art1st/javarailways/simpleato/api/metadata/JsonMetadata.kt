package work.art1st.javarailways.simpleato.api.metadata

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections

/** Bounds apply at the domain boundary, including API writes and snapshot restores. */
object JsonMetadata {
    const val MAX_DEPTH = 16
    const val MAX_NODES = 4096
    const val MAX_BYTES = 65536
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun immutableCopy(metadata: JsonObject): JsonObject {
        var nodes = 0
        var characters = 0L
        fun countText(value: String) {
            characters += value.length
            require(characters <= MAX_BYTES) { "Metadata exceeds $MAX_BYTES bytes" }
        }
        fun copy(value: JsonElement, depth: Int): JsonElement {
            require(depth <= MAX_DEPTH) { "Metadata exceeds depth $MAX_DEPTH" }
            require(++nodes <= MAX_NODES) { "Metadata exceeds $MAX_NODES values" }
            return when (value) {
                is JsonObject -> {
                    val result = LinkedHashMap<String, JsonElement>()
                    value.forEach { (key, child) ->
                        countText(key)
                        result[key] = copy(child, depth + 1)
                    }
                    JsonObject(Collections.unmodifiableMap(result))
                }
                is JsonArray -> {
                    val result = ArrayList<JsonElement>()
                    value.forEach { result += copy(it, depth + 1) }
                    JsonArray(Collections.unmodifiableList(result))
                }
                JsonNull -> JsonNull
                is JsonPrimitive -> {
                    countText(value.content)
                    require(value.isString || value.content == "true" || value.content == "false" ||
                        number.matches(value.content) && value.content.toDoubleOrNull()?.isFinite() == true) {
                        "Metadata contains an invalid or non-finite JSON value"
                    }
                    value // Primitive values own immutable text.
                }
            }
        }
        val result = copy(metadata, 0) as JsonObject
        require(result.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Metadata exceeds $MAX_BYTES bytes"
        }
        return result
    }
}
