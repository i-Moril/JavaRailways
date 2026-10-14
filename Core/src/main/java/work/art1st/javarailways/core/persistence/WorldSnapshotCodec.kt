package work.art1st.javarailways.core.persistence

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import work.art1st.javarailways.core.physics.CoreWorld

/**
 * Strict current-world JSON codec; root versions are checked before nested payload decoding.
 */
object WorldSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    fun encodeInfrastructure(snapshot: CoreWorld.SavedInfrastructureV2): String =
        json.encodeToString(CoreWorld.SavedInfrastructureV2.serializer(), snapshot)

    fun decodeInfrastructure(data: String): CoreWorld.SavedInfrastructureV2 =
        json.decodeFromJsonElement(CoreWorld.SavedInfrastructureV2.serializer(),
            currentDocument(data, CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION, "infrastructure"))

    fun encodeRuntime(snapshot: CoreWorld.SavedRuntimeV1): String =
        json.encodeToString(CoreWorld.SavedRuntimeV1.serializer(), snapshot)

    fun decodeRuntime(data: String): CoreWorld.SavedRuntimeV1 =
        json.decodeFromJsonElement(CoreWorld.SavedRuntimeV1.serializer(),
            currentDocument(data, CoreWorld.RUNTIME_SNAPSHOT_FORMAT_VERSION, "runtime"))

    private fun currentDocument(data: String, expectedVersion: Int, kind: String): JsonObject {
        val document = json.parseToJsonElement(data) as? JsonObject
            ?: throw SerializationException("Core $kind snapshot must be an object")
        val version = document["formatVersion"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == expectedVersion) {
            "Unsupported core $kind snapshot version: $version"
        }
        return document
    }
}
