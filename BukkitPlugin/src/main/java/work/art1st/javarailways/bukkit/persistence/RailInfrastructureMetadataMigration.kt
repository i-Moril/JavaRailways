package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.Serializable
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
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.basic.persistence.SavedRailInfrastructureMetadataV1
import work.art1st.javarailways.basic.persistence.SavedWayPointV1
import work.art1st.javarailways.basic.rail.CurveSafetyMetadata
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.simpleato.persistence.SavedLineV1
import work.art1st.javarailways.basic.persistence.SavedOperatingDirectionV1
import work.art1st.javarailways.simpleato.persistence.SavedOperationalDestinationV1
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotCodec
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import java.util.UUID

/**
 * Pure one-way conversion of the last real pre-extraction infrastructure document.
 *
 * The source is outer format 1 with a Basic format 1 payload and no SimpleATO payload. The
 * result is the current outer format 2 pair. Normal world loading remains current-only.
 */
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

    fun migrate(source: String): Result {
        val outer = json.decodeFromString<LegacyInfrastructureFileV1>(source)
        require(outer.formatVersion == LegacyInfrastructureFileV1.FORMAT_VERSION) {
            "Expected outer infrastructure format 1, got ${outer.formatVersion}"
        }
        val basic = json.decodeFromString<LegacyBasicInfrastructureSnapshotV1>(outer.basicSnapshotJson)
        require(basic.formatVersion == LegacyBasicInfrastructureSnapshotV1.FORMAT_VERSION) {
            "Outer infrastructure format 1 must contain Basic infrastructure format 1"
        }
        require(basic.infrastructureRevision == outer.infrastructureRevision) {
            "Basic infrastructure revision does not match outer infrastructure revision"
        }

        val split = splitRailMetadata(outer.coreSnapshotJson, outer.infrastructureRevision, basic)
        val currentBasic = BasicInfrastructureSnapshotV2(
            formatVersion = BasicInfrastructureSnapshotV2.CURRENT_FORMAT_VERSION,
            infrastructureRevision = outer.infrastructureRevision,
            wayPoints = basic.wayPoints,
            railMetadata = split.metadata,
            operatingDirections = split.directions
        )
        val currentSimpleAto = SimpleATOInfrastructureSnapshotV1(
            formatVersion = SimpleATOInfrastructureSnapshotV1.CURRENT_FORMAT_VERSION,
            infrastructureRevision = outer.infrastructureRevision,
            destinations = basic.destinations,
            lines = basic.lines,
            lineRevision = basic.lineRevision
        )
        val target = InfrastructureFileV2(
            formatVersion = SplitWorldSnapshotCodec.CURRENT_INFRASTRUCTURE_FORMAT_VERSION,
            infrastructureRevision = outer.infrastructureRevision,
            coreSnapshotJson = split.coreSnapshotJson,
            basicSnapshotJson = BasicInfrastructureSnapshotCodec.encode(currentBasic),
            simpleAtoSnapshotJson = SimpleATOInfrastructureSnapshotCodec.encode(currentSimpleAto)
        )

        // Strictly decode the complete current document pair before returning it.
        target.core()
        target.basic()
        target.simpleAto()
        return Result(target, split.metadata.size)
    }

    private fun splitRailMetadata(
        coreSnapshotJson: String,
        infrastructureRevision: Long,
        basic: LegacyBasicInfrastructureSnapshotV1
    ): SplitRailMetadata {
        val coreRoot = json.parseToJsonElement(coreSnapshotJson).jsonObject
        require(coreRoot.requiredLong("infrastructureRevision") == infrastructureRevision) {
            "Core infrastructure revision does not match outer infrastructure revision"
        }
        return when (val coreVersion = coreRoot.requiredInt("formatVersion")) {
            CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION -> {
                WorldSnapshotCodec.decodeInfrastructure(coreSnapshotJson)
                val converted = basic.railMetadata.map(LegacySavedRailInfrastructureMetadataV1::toCurrent)
                SplitRailMetadata(
                    coreSnapshotJson,
                    converted.map { it.metadata },
                    converted.mapNotNull { it.direction }
                )
            }
            1 -> {
                require(basic.railMetadata.isEmpty()) {
                    "Core infrastructure format 1 and Basic rail metadata cannot both own Rail metadata"
                }
                splitLegacyCoreMetadata(coreRoot)
            }
            else -> error("Expected Core infrastructure format 1 or current, got $coreVersion")
        }.also { split ->
            val duplicateMetadata = split.metadata.groupingBy(SavedRailInfrastructureMetadataV1::railId)
                .eachCount().filterValues { it > 1 }.keys
            require(duplicateMetadata.isEmpty()) {
                "Legacy infrastructure contains duplicate Rail metadata: ${duplicateMetadata.joinToString()}"
            }
            val duplicateDirections = split.directions.groupingBy(SavedOperatingDirectionV1::railId)
                .eachCount().filterValues { it > 1 }.keys
            require(duplicateDirections.isEmpty()) {
                "Legacy infrastructure contains duplicate operating directions: ${duplicateDirections.joinToString()}"
            }
        }
    }

    private fun splitLegacyCoreMetadata(coreRoot: JsonObject): SplitRailMetadata {
        val metadata = ArrayList<SavedRailInfrastructureMetadataV1>()
        val directions = ArrayList<SavedOperatingDirectionV1>()
        val rails = coreRoot.getValue("rails").jsonArray.map { railElement ->
            val rail = railElement.jsonObject
            val railId = UUID.fromString(rail.getValue("id").jsonPrimitive.content)
            val legacyElement = requireNotNull(rail["metadata"]) {
                "Legacy Core Rail $railId has no metadata field"
            }
            val legacy = json.decodeFromJsonElement<LegacyRailMetadataV1>(legacyElement)
            metadata += SavedRailInfrastructureMetadataV1.capture(railId, legacy.toCurrent())
            legacy.allowedDirection?.let { direction ->
                directions += SavedOperatingDirectionV1.capture(railId, direction)
            }
            JsonObject(rail - "metadata")
        }
        val migratedCore = JsonObject(
            coreRoot + mapOf(
                "formatVersion" to JsonPrimitive(CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION),
                "rails" to JsonArray(rails)
            )
        )
        val encoded = json.encodeToString(JsonElement.serializer(), migratedCore)
        WorldSnapshotCodec.decodeInfrastructure(encoded)
        return SplitRailMetadata(encoded, metadata, directions)
    }

    private fun JsonObject.requiredInt(name: String): Int =
        getValue(name).jsonPrimitive.content.toInt()

    private fun JsonObject.requiredLong(name: String): Long =
        getValue(name).jsonPrimitive.content.toLong()

    private data class SplitRailMetadata(
        val coreSnapshotJson: String,
        val metadata: List<SavedRailInfrastructureMetadataV1>,
        val directions: List<SavedOperatingDirectionV1>
    )
}

@Serializable
private data class LegacyInfrastructureFileV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val coreSnapshotJson: String,
    val basicSnapshotJson: String
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
private data class LegacyBasicInfrastructureSnapshotV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val destinations: List<SavedOperationalDestinationV1>,
    val wayPoints: List<SavedWayPointV1>,
    val lines: List<SavedLineV1>,
    val lineRevision: Long,
    val railMetadata: List<LegacySavedRailInfrastructureMetadataV1> = emptyList()
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
private data class LegacySavedRailInfrastructureMetadataV1(
    val railId: String,
    val metadata: LegacyBasicRailInfrastructureMetadataV1
) {
    fun toCurrent(): SplitLegacyBasicMetadata {
        val id = UUID.fromString(railId)
        val direction = metadata.operating.allowedDirection?.let {
            SavedOperatingDirectionV1.capture(id, it)
        }
        return SplitLegacyBasicMetadata(
            SavedRailInfrastructureMetadataV1.capture(id, metadata.toCurrent()),
            direction
        )
    }
}

private data class SplitLegacyBasicMetadata(
    val metadata: SavedRailInfrastructureMetadataV1,
    val direction: SavedOperatingDirectionV1?
)

@Serializable
private data class LegacyBasicRailInfrastructureMetadataV1(
    val authoring: RailAuthoringMetadata? = null,
    val operating: LegacyBasicRailOperatingMetadataV1 = LegacyBasicRailOperatingMetadataV1(),
    val speedLimits: SpeedLimitMetadata = SpeedLimitMetadata(),
    val curveSafety: CurveSafetyMetadata = CurveSafetyMetadata()
) {
    fun toCurrent(): RailInfrastructureMetadata = RailInfrastructureMetadata(
        authoring = authoring,
        speedLimits = speedLimits,
        curveSafety = curveSafety
    )
}

@Serializable
private data class LegacyBasicRailOperatingMetadataV1(
    val allowedDirection: ViewDirection? = null,
    val turnoutAtA: LegacyTurnoutEndpointMetadata? = null,
    val turnoutAtB: LegacyTurnoutEndpointMetadata? = null
)
