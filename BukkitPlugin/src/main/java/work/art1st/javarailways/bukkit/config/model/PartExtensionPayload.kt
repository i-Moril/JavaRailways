package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import work.art1st.javarailways.bukkit.api.model.extension.requirePartExtensionTypeId
import java.util.Collections
import java.util.TreeMap
import java.util.IdentityHashMap

/** Opaque addon-owned configuration; only the decode boundary converts YAML to immutable JSON. */
@Serializable(with = PartExtensionPayload.Serializer::class)
class PartExtensionPayload(val typeId: String, val schemaVersion: Int = 1, config: JsonObject = JsonObject(emptyMap())) {
    val config: JsonObject = work.art1st.javarailways.bukkit.api.model.extension.immutablePartExtensionJson(config, 0) as JsonObject
    init { requirePartExtensionTypeId(typeId); require(schemaVersion > 0) { "Part extension schemaVersion must be positive" } }
    override fun equals(other: Any?): Boolean = other is PartExtensionPayload && typeId == other.typeId && schemaVersion == other.schemaVersion && config == other.config
    override fun hashCode(): Int = 31 * (31 * typeId.hashCode() + schemaVersion) + config.hashCode()
    @Serializable private data class Payload(val typeId: String, val schemaVersion: Int = 1, val config: JsonObject = JsonObject(emptyMap()))
    object Serializer : KSerializer<PartExtensionPayload> {
        override val descriptor: SerialDescriptor = Payload.serializer().descriptor
        override fun serialize(encoder: Encoder, value: PartExtensionPayload) = encoder.encodeSerializableValue(Payload.serializer(), Payload(value.typeId, value.schemaVersion, value.config))
        override fun deserialize(decoder: Decoder): PartExtensionPayload = decoder.decodeSerializableValue(Payload.serializer()).let { PartExtensionPayload(it.typeId, it.schemaVersion, it.config) }
    }
}

internal fun extensionYamlObject(value: Map<*, *>): JsonObject {
    val active = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    fun convert(item: Any?, depth: Int): JsonElement {
        require(depth <= 64) { "Extension configuration exceeds maximum nesting depth" }
        return when (item) {
            null -> JsonNull
            is String -> JsonPrimitive(item)
            is Boolean -> JsonPrimitive(item)
            is Number -> { require(item.toDouble().isFinite()) { "Extension config numbers must be finite" }; JsonPrimitive(item) }
            is Map<*, *> -> {
                require(active.add(item)) { "Recursive YAML alias in extension config" }
                try {
                    JsonObject(item.entries.associate { (key, child) ->
                        require(key is String) { "Extension config object keys must be strings" }
                        key to convert(child, depth + 1)
                    })
                } finally { active.remove(item) }
            }
            is List<*> -> {
                require(active.add(item)) { "Recursive YAML alias in extension config" }
                try { JsonArray(item.map { convert(it, depth + 1) }) } finally { active.remove(item) }
            }
            else -> throw IllegalArgumentException("Unsupported extension config value ${item.javaClass.simpleName}")
        }
    }
    return convert(value, 0) as JsonObject
}
