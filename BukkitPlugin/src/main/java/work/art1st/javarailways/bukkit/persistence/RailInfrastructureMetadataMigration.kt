package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotCodec
import work.art1st.javarailways.basic.persistence.SavedRailInfrastructureMetadataV1
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.UUID

/** Pure one-way migration from Core infrastructure format 1 to the current split ownership model. */
object RailInfrastructureMetadataMigration {
    data class Result(
        val file: InfrastructureFileV2,
        val migratedRailCount: Int
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun migrate(file: InfrastructureFileV2): Result {
        val coreRoot = json.parseToJsonElement(file.coreSnapshotJson).jsonObject
        val version = coreRoot.requiredInt("formatVersion")
        require(version in setOf(1, CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION)) {
            "Expected Core infrastructure format 1 or current, got $version"
        }
        require(coreRoot.requiredLong("infrastructureRevision") == file.infrastructureRevision)
        if (version == CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION) {
            file.core()
            file.basic()
            file.simpleAto()
            return Result(file, migratedRailCount = 0)
        }

        val basic = BasicInfrastructureSnapshotCodec.decode(file.basicSnapshotJson)
        require(basic.infrastructureRevision == file.infrastructureRevision)
        file.simpleAto()
        require(basic.railMetadata.isEmpty()) {
            "Basic infrastructure already contains Rail metadata; refusing ambiguous merge"
        }

        val migratedMetadata = ArrayList<SavedRailInfrastructureMetadataV1>()
        val migratedRails = coreRoot.getValue("rails").jsonArray.map { railElement ->
            migrateRail(railElement.jsonObject, migratedMetadata)
        }
        val migratedCoreRoot = JsonObject(
            coreRoot + mapOf(
                "formatVersion" to JsonPrimitive(CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION),
                "rails" to JsonArray(migratedRails)
            )
        )
        val coreJson = json.encodeToString(JsonElement.serializer(), migratedCoreRoot)
        val decodedCore = WorldSnapshotCodec.decodeInfrastructure(coreJson)
        check(decodedCore.rails.size == migratedMetadata.size)

        val migratedBasic = basic.copy(railMetadata = migratedMetadata)
        val basicJson = BasicInfrastructureSnapshotCodec.encode(migratedBasic)
        return Result(
            file.copy(coreSnapshotJson = coreJson, basicSnapshotJson = basicJson),
            migratedMetadata.size
        )
    }

    private fun migrateRail(
        rail: JsonObject,
        output: MutableList<SavedRailInfrastructureMetadataV1>
    ): JsonObject {
        val railId = UUID.fromString(rail.getValue("id").jsonPrimitive.content)
        val legacyElement = requireNotNull(rail["metadata"]) {
            "Legacy Core Rail $railId has no metadata field"
        }
        val legacy = json.decodeFromJsonElement<LegacyRailMetadataV1>(legacyElement)
        output += SavedRailInfrastructureMetadataV1.capture(railId, legacy.toCurrent())
        return JsonObject(rail - "metadata")
    }

    private fun JsonObject.requiredInt(name: String): Int =
        getValue(name).jsonPrimitive.content.toInt()

    private fun JsonObject.requiredLong(name: String): Long =
        getValue(name).jsonPrimitive.content.toLong()
}
