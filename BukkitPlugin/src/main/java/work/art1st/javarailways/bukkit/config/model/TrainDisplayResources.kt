package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Collections
import java.util.TreeMap

/** Raw package-local XML. Compilation belongs to the optional display provider, not package loading. */
@Serializable
data class TrainDisplayTemplate(val file: String, val source: String) {
    init { requireDisplayXmlPath(file, "Display template file") }
}

/** Detached immutable display definitions, shared by loaded packages and content-addressed snapshots. */
@Serializable(with = TrainDisplayResources.Serializer::class)
class TrainDisplayResources(
    templates: Map<String, TrainDisplayTemplate> = emptyMap(),
    defaults: Map<String, String> = emptyMap(),
) {
    val templates: Map<String, TrainDisplayTemplate> = Collections.unmodifiableMap(TreeMap(templates))
    val defaults: Map<String, String> = Collections.unmodifiableMap(TreeMap(defaults))

    init {
        this.templates.keys.forEach { requireDisplayIdentifier(it, "Display template ID") }
        this.defaults.forEach { (category, template) ->
            requireDisplayIdentifier(category, "Display defaults category")
            requireDisplayIdentifier(template, "Display defaults '$category' template")
            require(template in this.templates) { "Display defaults '$category' references unknown template '$template'" }
        }
    }

    override fun equals(other: Any?): Boolean = other is TrainDisplayResources && templates == other.templates && defaults == other.defaults
    override fun hashCode(): Int = 31 * templates.hashCode() + defaults.hashCode()
    override fun toString(): String = "TrainDisplayResources(templates=$templates, defaults=$defaults)"

    @Serializable
    private data class Payload(
        val templates: Map<String, TrainDisplayTemplate> = emptyMap(),
        val defaults: Map<String, String> = emptyMap(),
    )

    object Serializer : KSerializer<TrainDisplayResources> {
        override val descriptor: SerialDescriptor = Payload.serializer().descriptor
        override fun serialize(encoder: Encoder, value: TrainDisplayResources) =
            encoder.encodeSerializableValue(Payload.serializer(), Payload(value.templates, value.defaults))
        override fun deserialize(decoder: Decoder): TrainDisplayResources {
            val value = decoder.decodeSerializableValue(Payload.serializer())
            return TrainDisplayResources(value.templates, value.defaults)
        }
    }
}

internal fun requireDisplayIdentifier(value: String, subject: String) {
    require(value.isNotEmpty() && value.none(Char::isWhitespace)) { "$subject must be non-empty and contain no whitespace" }
}

internal fun requireDisplayXmlPath(value: String, subject: String) {
    require(value.isNotBlank() && value.endsWith(".xml") && '\\' !in value && !Regex("^[A-Za-z]:").containsMatchIn(value) &&
        value.split('/').all { it.isNotEmpty() && it != "." && it != ".." } && '\u0000' !in value) {
        "$subject must be a package-relative XML path with ordinary forward-slash segments: '$value'"
    }
}
