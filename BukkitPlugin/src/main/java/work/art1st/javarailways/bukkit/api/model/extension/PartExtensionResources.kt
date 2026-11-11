package work.art1st.javarailways.bukkit.api.model.extension

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Collections
import java.util.TreeMap

/** Captured strict UTF-8 files, relative to the extension's declared directory. Never a filesystem view. */
@Serializable(with = PartExtensionResources.Serializer::class)
class PartExtensionResources(val schemaVersion: Int = 1, files: Map<String, String> = emptyMap()) {
    val files: Map<String, String> = Collections.unmodifiableMap(TreeMap(files))
    init {
        require(schemaVersion > 0) { "Extension resource schemaVersion must be positive" }
        this.files.keys.forEach(::requireExtensionResourcePath)
    }
    override fun equals(other: Any?): Boolean = other is PartExtensionResources && schemaVersion == other.schemaVersion && files == other.files
    override fun hashCode(): Int = 31 * schemaVersion + files.hashCode()
    @Serializable private data class Payload(val schemaVersion: Int = 1, val files: Map<String, String> = emptyMap())
    object Serializer : KSerializer<PartExtensionResources> {
        override val descriptor: SerialDescriptor = Payload.serializer().descriptor
        override fun serialize(encoder: Encoder, value: PartExtensionResources) = encoder.encodeSerializableValue(Payload.serializer(), Payload(value.schemaVersion, value.files))
        override fun deserialize(decoder: Decoder): PartExtensionResources = decoder.decodeSerializableValue(Payload.serializer()).let { PartExtensionResources(it.schemaVersion, it.files) }
    }
}

/** Stable registry keys. Short unknown native part names are never interpreted as extensions. */
fun requirePartExtensionTypeId(value: String) {
    require(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+").matches(value)) { "Invalid namespaced part extension type '$value'" }
}

fun requireExtensionResourcePath(value: String) {
    require(value.isNotBlank() && '\\' !in value && '\u0000' !in value &&
        !Regex("^[A-Za-z]:").containsMatchIn(value) && value.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
        "Extension resource path must contain ordinary relative forward-slash segments: '$value'"
    }
}

object PartExtensionResourcesMapSerializer : KSerializer<Map<String, PartExtensionResources>> {
    private val delegate = MapSerializer(String.serializer(), PartExtensionResources.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Map<String, PartExtensionResources>) = encoder.encodeSerializableValue(delegate, immutableExtensionResources(value))
    override fun deserialize(decoder: Decoder): Map<String, PartExtensionResources> = immutableExtensionResources(decoder.decodeSerializableValue(delegate))
}

internal fun immutableExtensionResources(value: Map<String, PartExtensionResources>): Map<String, PartExtensionResources> {
    value.keys.forEach(::requirePartExtensionTypeId)
    return Collections.unmodifiableMap(TreeMap(value))
}
