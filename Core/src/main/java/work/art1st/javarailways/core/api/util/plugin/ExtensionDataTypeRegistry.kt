package work.art1st.javarailways.core.api.util.plugin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.reflect.KClass

/**
 * Explicit startup registrations for persisted extension payloads; never discovers JVM classes.
 * Register before restoring worlds. Reads are nonblocking against one immutable registration snapshot;
 * registrations are synchronized and an identical repeat is a no-op. Conflicting identities fail.
 * This registry is process-local, not a persisted object or runtime extension-slot registry.
 */
class ExtensionDataTypeRegistry {
    private data class Entry(
        val typeId: String,
        val schemaVersion: Int,
        val payloadClass: KClass<out SerializableExtensionData>,
        val serializer: KSerializer<SerializableExtensionData>
    )

    private data class Registrations(
        val byId: Map<String, Entry> = emptyMap(),
        val byClass: Map<KClass<out SerializableExtensionData>, Entry> = emptyMap()
    )

    @Volatile
    private var registrations = Registrations()

    /** Binds a stable namespaced ID to an exact class and its explicitly supplied current serializer. */
    @Synchronized
    fun <T : SerializableExtensionData> register(
        typeId: String,
        schemaVersion: Int,
        payloadClass: KClass<T>,
        serializer: KSerializer<T>
    ) {
        require(typeId.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) { "Invalid extension type ID: $typeId" }
        require(schemaVersion > 0) { "Extension schema version must be positive" }
        val current = registrations
        val existing = current.byId[typeId] ?: current.byClass[payloadClass]
        if (existing != null) {
            require(existing.typeId == typeId && existing.schemaVersion == schemaVersion &&
                existing.payloadClass == payloadClass && existing.serializer == serializer) {
                "Conflicting extension registration: $typeId ($payloadClass, version $schemaVersion)"
            }
            return
        }
        @Suppress("UNCHECKED_CAST")
        val entry = Entry(typeId, schemaVersion, payloadClass, serializer as KSerializer<SerializableExtensionData>)
        registrations = Registrations(current.byId + (typeId to entry), current.byClass + (payloadClass to entry))
    }

    /** Strict JSON envelope codec; unknown IDs, classes and versions fail before payload decoding. */
    val serializer: KSerializer<SerializableExtensionData> = object : KSerializer<SerializableExtensionData> {
        override val descriptor = buildClassSerialDescriptor("ExtensionDataEnvelope") {
            element<String>("typeId")
            element<Int>("schemaVersion")
            element("payload", JsonElement.serializer().descriptor)
        }

        override fun serialize(encoder: Encoder, value: SerializableExtensionData) {
            val output = encoder as? JsonEncoder ?: throw SerializationException("Extension payloads require JSON")
            val entry = registrations.byClass[value::class]
                ?: throw SerializationException("Unregistered extension payload class: ${value::class}")
            output.encodeJsonElement(buildJsonObject {
                put("typeId", entry.typeId)
                put("schemaVersion", entry.schemaVersion)
                put("payload", output.json.encodeToJsonElement(entry.serializer, value))
            })
        }

        override fun deserialize(decoder: Decoder): SerializableExtensionData {
            val input = decoder as? JsonDecoder ?: throw SerializationException("Extension payloads require JSON")
            val envelope = input.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("Extension envelope must be an object")
            if (envelope.keys != setOf("typeId", "schemaVersion", "payload")) {
                throw SerializationException("Extension envelope requires exactly typeId, schemaVersion and payload")
            }
            val id = envelope["typeId"] as? JsonPrimitive
            if (id == null || !id.isString) throw SerializationException("Extension typeId must be a string")
            val entry = registrations.byId[id.content]
                ?: throw SerializationException("Unknown extension type ID: ${id.content}")
            val version = envelope["schemaVersion"] as? JsonPrimitive
            if (version == null || version.isString || version.intOrNull != entry.schemaVersion) {
                throw SerializationException("Unsupported extension schema version for ${entry.typeId}: $version")
            }
            return input.json.decodeFromJsonElement(entry.serializer, envelope.getValue("payload"))
        }
    }

    companion object {
        /** Shared startup registry used by Core's persisted train, car and bogie extension maps. */
        val global = ExtensionDataTypeRegistry()
    }
}

/** Map-bound codec, independent of polymorphic curve/config discriminator conventions. */
object ExtensionDataMapSerializer : KSerializer<Map<String, SerializableExtensionData>> by
    MapSerializer(String.serializer(), ExtensionDataTypeRegistry.global.serializer)
