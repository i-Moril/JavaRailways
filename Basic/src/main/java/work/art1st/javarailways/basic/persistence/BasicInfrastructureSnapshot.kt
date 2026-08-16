package work.art1st.javarailways.basic.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.destination.DestinationRoleType
import work.art1st.javarailways.basic.api.destination.OperationalDestination
import work.art1st.javarailways.basic.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.api.line.Line
import work.art1st.javarailways.basic.api.line.LineId
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.basic.api.station.StationId
import work.art1st.javarailways.basic.pathFinding.basicWorldData
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
data class SavedOperationalDestinationV1(
    val id: String,
    val displayName: String,
    val railId: String,
    val arcLengthInAbMeters: Double,
    val approachDirection: ViewDirection? = null,
    val roleType: DestinationRoleType = DestinationRoleType.REGULAR,
    val stationId: String? = null,
    val stationDisplayName: String? = null,
    val berthId: String? = null,
    val doorSide: DoorSide? = null,
    val stoppingOffsetMetersBefore: Double = 0.0,
    val positionToleranceMeters: Double = DestinationRole.DEFAULT_POSITION_TOLERANCE_METERS,
    val usableLengthMeters: Double? = null,
    val defaultDwellTicks: Long = 0L,
    val releaseDistanceMetersAfter: Double = 0.0,
    val depotId: String? = null,
    val stablingPointId: String? = null,
    val depotDisplayName: String? = null,
    val allowedTags: Set<String> = emptySet()
) {
    fun restore(): OperationalDestination = OperationalDestination(
        id = id,
        displayName = displayName,
        railId = UUID.fromString(railId),
        arcLengthInAbMeters = arcLengthInAbMeters,
        approachDirection = approachDirection,
        role = restoreRole()
    )

    private fun restoreRole(): DestinationRole = when (roleType) {
        DestinationRoleType.REGULAR -> DestinationRole.Regular
        DestinationRoleType.STATION_BERTH -> DestinationRole.StationBerthRole(
            stationId = StationId(requireNotNull(stationId)),
            stationDisplayName = requireNotNull(stationDisplayName),
            berthId = requireNotNull(berthId),
            doorSide = requireNotNull(doorSide),
            stoppingOffsetMetersBefore = stoppingOffsetMetersBefore,
            positionToleranceMeters = positionToleranceMeters,
            usableLengthMeters = usableLengthMeters,
            defaultDwellTicks = defaultDwellTicks,
            releaseDistanceMetersAfter = releaseDistanceMetersAfter
        )
        DestinationRoleType.DEPOT_STABLING_POINT -> DestinationRole.DepotStablingPointRole(
            depotId = requireNotNull(depotId),
            stablingPointId = requireNotNull(stablingPointId),
            depotDisplayName = requireNotNull(depotDisplayName),
            usableLengthMeters = usableLengthMeters,
            allowedTags = allowedTags
        )
    }

    companion object {
        fun capture(value: OperationalDestination): SavedOperationalDestinationV1 {
            val station = value.role as? DestinationRole.StationBerthRole
            val depot = value.role as? DestinationRole.DepotStablingPointRole
            return SavedOperationalDestinationV1(
                id = value.id,
                displayName = value.displayName,
                railId = value.railId.toString(),
                arcLengthInAbMeters = value.arcLengthInAbMeters,
                approachDirection = value.approachDirection,
                roleType = value.role.type,
                stationId = station?.stationId?.value,
                stationDisplayName = station?.stationDisplayName,
                berthId = station?.berthId,
                doorSide = station?.doorSide,
                stoppingOffsetMetersBefore = station?.stoppingOffsetMetersBefore ?: 0.0,
                positionToleranceMeters = station?.positionToleranceMeters
                    ?: DestinationRole.DEFAULT_POSITION_TOLERANCE_METERS,
                usableLengthMeters = station?.usableLengthMeters ?: depot?.usableLengthMeters,
                defaultDwellTicks = station?.defaultDwellTicks ?: 0L,
                releaseDistanceMetersAfter = station?.releaseDistanceMetersAfter ?: 0.0,
                depotId = depot?.depotId,
                stablingPointId = depot?.stablingPointId,
                depotDisplayName = depot?.depotDisplayName,
                allowedTags = depot?.allowedTags.orEmpty()
            )
        }
    }
}

@Serializable
data class SavedLineV1(
    val id: String,
    val displayName: String,
    val upStationIds: List<String>,
    val downStationIds: List<String>,
    val color: String? = null
) {
    fun restore(): Line = Line(
        LineId(id),
        displayName,
        upStationIds.map(::StationId),
        downStationIds.map(::StationId),
        color
    )

    companion object {
        fun capture(value: Line): SavedLineV1 = SavedLineV1(
            id = value.id.value,
            displayName = value.displayName,
            upStationIds = value.upStationIds.map { it.value },
            downStationIds = value.downStationIds.map { it.value },
            color = value.color
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
data class BasicInfrastructureSnapshotV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val destinations: List<SavedOperationalDestinationV1>,
    val wayPoints: List<SavedWayPointV1>,
    val lines: List<SavedLineV1>,
    val lineRevision: Long,
    val railMetadata: List<SavedRailInfrastructureMetadataV1> = emptyList()
) {
    fun restore(world: World) {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported Basic infrastructure snapshot version: $formatVersion"
        }
        check(world.basicWorldData.wayPoints.all().isEmpty())
        check(world.basicWorldData.destinations.all().isEmpty())
        val missingRails = destinations.filter { saved ->
            world.getRail(UUID.fromString(saved.railId)) == null
        }
        check(missingRails.isEmpty()) {
            "Infrastructure snapshot contains destinations on missing rails: " +
                missingRails.joinToString { "${it.id}@${it.railId}" }
        }
        val missingWayPointRails = wayPoints.filter { saved ->
            world.getRail(UUID.fromString(saved.railId)) == null
        }
        check(missingWayPointRails.isEmpty()) {
            "Infrastructure snapshot contains WayPoints on missing rails: " +
                missingWayPointRails.joinToString { "${it.id}@${it.railId}" }
        }
        val duplicateMetadataIds = railMetadata.groupingBy { it.railId }.eachCount().filterValues { it > 1 }.keys
        check(duplicateMetadataIds.isEmpty()) {
            "Infrastructure snapshot contains duplicate Rail metadata: ${duplicateMetadataIds.joinToString()}"
        }
        val missingMetadataRails = railMetadata.filter { world.getRail(it.parsedRailId()) == null }
        check(missingMetadataRails.isEmpty()) {
            "Infrastructure snapshot contains metadata for missing rails: " +
                missingMetadataRails.joinToString { it.railId }
        }
        val service = OperationalDestinationService(world)
        destinations.forEach { service.register(it.restore()) }
        wayPoints.forEach { world.basicWorldData.wayPoints.add(it.restore()) }
        val restoredLines = lines.map(SavedLineV1::restore)
        val stationIds = world.basicWorldData.stations.all().mapTo(HashSet()) { it.id }
        val missingStations = restoredLines.flatMap { it.upStationIds + it.downStationIds }.filterNot { it in stationIds }.distinct()
        check(missingStations.isEmpty()) {
            "Infrastructure snapshot contains lines with missing stations: ${missingStations.joinToString()}"
        }
        world.basicWorldData.lines.restore(restoredLines, lineRevision)
        world.basicWorldData.railMetadata.restore(
            railMetadata.associate { it.parsedRailId() to it.metadata }
        )
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1

        fun capture(world: World, infrastructureRevision: Long): BasicInfrastructureSnapshotV1 {
            require(infrastructureRevision >= 0L)
            val data = world.basicWorldData
            val destinations = data.destinations.all()
            val missingRails = destinations.filter { world.getRail(it.railId) == null }
            check(missingRails.isEmpty()) {
                "Cannot capture destinations on missing rails: " +
                    missingRails.joinToString { "${it.id}@${it.railId}" }
            }
            val destinationIds = destinations.mapTo(HashSet()) { it.id }
            val standaloneWayPoints = data.wayPoints.all().filterNot { it.id in destinationIds }
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
            val lineSnapshot = data.lines.snapshot()
            return BasicInfrastructureSnapshotV1(
                formatVersion = CURRENT_FORMAT_VERSION,
                infrastructureRevision = infrastructureRevision,
                destinations = destinations.map(SavedOperationalDestinationV1::capture),
                wayPoints = standaloneWayPoints.map(SavedWayPointV1::capture),
                lines = lineSnapshot.lines.map(SavedLineV1::capture),
                lineRevision = lineSnapshot.revision,
                railMetadata = railMetadata.map { (railId, metadata) ->
                    SavedRailInfrastructureMetadataV1.capture(railId, metadata)
                }
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

    fun encode(snapshot: BasicInfrastructureSnapshotV1): String =
        json.encodeToString(BasicInfrastructureSnapshotV1.serializer(), snapshot)

    fun decode(data: String): BasicInfrastructureSnapshotV1 =
        json.decodeFromString(BasicInfrastructureSnapshotV1.serializer(), data).also {
            require(it.formatVersion == BasicInfrastructureSnapshotV1.CURRENT_FORMAT_VERSION) {
                "Unsupported Basic infrastructure snapshot version: ${it.formatVersion}"
            }
        }
}
