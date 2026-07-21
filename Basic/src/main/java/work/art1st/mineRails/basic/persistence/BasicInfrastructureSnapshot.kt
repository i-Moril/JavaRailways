package work.art1st.mineRails.basic.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.api.destination.DestinationRoleType
import work.art1st.mineRails.basic.api.destination.OperationalDestination
import work.art1st.mineRails.basic.api.destination.OperationalDestinationService
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.station.StationId
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
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
data class BasicInfrastructureSnapshotV1(
    val formatVersion: Int = 1,
    val infrastructureRevision: Long,
    val destinations: List<SavedOperationalDestinationV1>,
    val legacyWayPoints: List<SavedWayPointV1> = emptyList()
) {
    fun restore(world: World) {
        check(world.basicWorldData.wayPoints.all().isEmpty())
        check(world.basicWorldData.destinations.all().isEmpty())
        val missingRails = destinations.filter { saved ->
            world.getRail(UUID.fromString(saved.railId)) == null
        }
        check(missingRails.isEmpty()) {
            "Infrastructure snapshot contains destinations on missing rails: " +
                missingRails.joinToString { "${it.id}@${it.railId}" }
        }
        val missingWayPointRails = legacyWayPoints.filter { saved ->
            world.getRail(UUID.fromString(saved.railId)) == null
        }
        check(missingWayPointRails.isEmpty()) {
            "Infrastructure snapshot contains WayPoints on missing rails: " +
                missingWayPointRails.joinToString { "${it.id}@${it.railId}" }
        }
        val service = OperationalDestinationService(world)
        destinations.forEach { service.register(it.restore()) }
        legacyWayPoints.forEach { world.basicWorldData.wayPoints.add(it.restore()) }
    }

    companion object {
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
            val legacyWayPoints = data.wayPoints.all().filterNot { it.id in destinationIds }
            val missingWayPointRails = legacyWayPoints.filter { world.getRail(it.railId) == null }
            check(missingWayPointRails.isEmpty()) {
                "Cannot capture WayPoints on missing rails: " +
                    missingWayPointRails.joinToString { "${it.id}@${it.railId}" }
            }
            return BasicInfrastructureSnapshotV1(
                infrastructureRevision = infrastructureRevision,
                destinations = destinations.map(SavedOperationalDestinationV1::capture),
                legacyWayPoints = legacyWayPoints.map(SavedWayPointV1::capture)
            )
        }
    }
}

object BasicInfrastructureSnapshotCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(snapshot: BasicInfrastructureSnapshotV1): String =
        json.encodeToString(BasicInfrastructureSnapshotV1.serializer(), snapshot)

    fun decode(data: String): BasicInfrastructureSnapshotV1 =
        json.decodeFromString(BasicInfrastructureSnapshotV1.serializer(), data)
}
