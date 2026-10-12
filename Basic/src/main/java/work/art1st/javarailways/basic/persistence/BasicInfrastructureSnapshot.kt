package work.art1st.javarailways.basic.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

@Serializable
data class SavedWayPointV1(
    val id: String,
    val displayName: String,
    val railId: String,
    val arcLengthInAbMeters: Double
) {
    fun restore(): WayPoint = WayPoint(
        id,
        displayName,
        UUID.fromString(railId),
        arcLengthInAbMeters
    )

    companion object {
        fun capture(value: WayPoint): SavedWayPointV1 = SavedWayPointV1(
            value.id,
            value.displayName,
            value.railId.toString(),
            value.arcLength
        )
    }
}

@Serializable
data class SavedRailInfrastructureMetadataV1(
    val railId: String,
    val metadata: RailInfrastructureMetadata
) {
    fun parsedRailId(): UUID = UUID.fromString(railId)

    companion object {
        fun capture(railId: UUID, metadata: RailInfrastructureMetadata) =
            SavedRailInfrastructureMetadataV1(railId.toString(), metadata)
    }
}

@Serializable
data class SavedOperatingDirectionV1(
    val railId: String,
    val permittedDirection: ViewDirection
) {
    fun parsedRailId(): UUID = UUID.fromString(railId)

    companion object {
        fun capture(railId: UUID, direction: ViewDirection): SavedOperatingDirectionV1 =
            SavedOperatingDirectionV1(railId.toString(), direction)
    }
}

@Serializable
data class BasicInfrastructureSnapshotV2(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val wayPoints: List<SavedWayPointV1>,
    val railMetadata: List<SavedRailInfrastructureMetadataV1> = emptyList(),
    val operatingDirections: List<SavedOperatingDirectionV1> = emptyList()
) {
    fun restore(world: World) {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported Basic infrastructure snapshot version: $formatVersion"
        }
        check(world.basicWorldData.wayPoints.all().isEmpty())
        check(world.basicWorldData.railMetadata.snapshot().isEmpty()) { "Basic RailInfrastructureMetadataStore is not empty" }
        check(world.basicWorldData.operatingDirections.entries().isEmpty()) { "Basic OperatingDirectionStore is not empty" }
        val parsedDirections = operatingDirections.map { it.parsedRailId() to it.permittedDirection }
        val duplicateDirectionRails = parsedDirections.groupingBy { it.first }.eachCount()
            .filterValues { it > 1 }.keys
        check(duplicateDirectionRails.isEmpty()) {
            "Basic infrastructure snapshot contains duplicate operating directions: ${duplicateDirectionRails.joinToString()}"
        }
        val missingDirectionRails = parsedDirections.map { it.first }.filter { world.getRail(it) == null }
        check(missingDirectionRails.isEmpty()) {
            "Basic infrastructure snapshot contains operating directions for missing rails: ${missingDirectionRails.joinToString()}"
        }
        val duplicateWayPointIds = wayPoints.groupingBy(SavedWayPointV1::id).eachCount()
            .filterValues { it > 1 }
            .keys
        check(duplicateWayPointIds.isEmpty()) {
            "Infrastructure snapshot contains duplicate WayPoints: ${duplicateWayPointIds.joinToString()}"
        }
        val restoredWayPoints = wayPoints.map { saved ->
            saved.restore().also { require(it.id.isNotBlank()) { "WayPoint id must not be blank" } }
        }
        val missingWayPointRails = restoredWayPoints.filter { world.getRail(it.railId) == null }
        check(missingWayPointRails.isEmpty()) {
            "Infrastructure snapshot contains WayPoints on missing rails: " +
                missingWayPointRails.joinToString { "${it.id}@${it.railId}" }
        }
        val parsedMetadata = railMetadata.map { it.parsedRailId() to it.metadata }
        val duplicateMetadataIds = parsedMetadata.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
        check(duplicateMetadataIds.isEmpty()) {
            "Infrastructure snapshot contains duplicate Rail metadata: ${duplicateMetadataIds.joinToString()}"
        }
        val missingMetadataRails = parsedMetadata.map { it.first }.filter { world.getRail(it) == null }
        check(missingMetadataRails.isEmpty()) {
            "Infrastructure snapshot contains metadata for missing rails: " +
                missingMetadataRails.joinToString()
        }
        restoredWayPoints.forEach { world.basicWorldData.wayPoints.add(it) }
        world.basicWorldData.railMetadata.restore(parsedMetadata.toMap())
        world.basicWorldData.operatingDirections.restore(parsedDirections.toMap())
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 2

        fun capture(
            world: World,
            infrastructureRevision: Long,
            projectedWayPointIds: Set<String>
        ): BasicInfrastructureSnapshotV2 {
            require(infrastructureRevision >= 0L)
            val data = world.basicWorldData
            val standaloneWayPoints = data.wayPoints.all().filterNot { it.id in projectedWayPointIds }
            val missingWayPointRails = standaloneWayPoints.filter { world.getRail(it.railId) == null }
            check(missingWayPointRails.isEmpty()) {
                "Cannot capture WayPoints on missing rails: " +
                    missingWayPointRails.joinToString { "${it.id}@${it.railId}" }
            }
            val railMetadata = data.railMetadata.snapshot()
            val orphanMetadata = railMetadata.keys.filter { world.getRail(it) == null }
            check(orphanMetadata.isEmpty()) {
                "Cannot capture metadata for missing rails: ${orphanMetadata.joinToString()}"
            }
            val operatingDirections = data.operatingDirections.entries()
            val orphanDirections = operatingDirections.keys.filter { world.getRail(it) == null }
            check(orphanDirections.isEmpty()) {
                "Cannot capture operating directions for missing rails: ${orphanDirections.joinToString()}"
            }
            return BasicInfrastructureSnapshotV2(
                formatVersion = CURRENT_FORMAT_VERSION,
                infrastructureRevision = infrastructureRevision,
                wayPoints = standaloneWayPoints.map(SavedWayPointV1::capture),
                railMetadata = railMetadata.map { (railId, metadata) ->
                    SavedRailInfrastructureMetadataV1.capture(railId, metadata)
                },
                operatingDirections = operatingDirections.entries.sortedBy { it.key }
                    .map { (railId, direction) -> SavedOperatingDirectionV1.capture(railId, direction) }
            )
        }
    }
}

object BasicInfrastructureSnapshotCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun encode(snapshot: BasicInfrastructureSnapshotV2): String =
        json.encodeToString(BasicInfrastructureSnapshotV2.serializer(), snapshot)

    fun decode(data: String): BasicInfrastructureSnapshotV2 =
        json.decodeFromString(BasicInfrastructureSnapshotV2.serializer(), data).also {
            require(it.formatVersion == BasicInfrastructureSnapshotV2.CURRENT_FORMAT_VERSION) {
                "Unsupported Basic infrastructure snapshot version: ${it.formatVersion}"
            }
        }
}
