package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.DestinationRoleType
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import java.util.UUID

@Serializable
data class SavedOperationalDestinationV1(
    val id: String,
    val railId: String,
    val arcLengthInAbMeters: Double,
    val approachDirection: ViewDirection? = null,
    val roleType: DestinationRoleType = DestinationRoleType.REGULAR,
    val stationId: String? = null,
    val stationDisplayName: String? = null,
    val berthId: String? = null,
    val doorSide: DoorSide? = null,
    val stoppingOffsetMetersBefore: Double = 0.0,
    val usableLengthMeters: Double? = null,
    val defaultDwellTicks: Long = 0L,
    val releaseDistanceMetersAfter: Double = 0.0,
    val depotId: String? = null,
    val stablingPointId: String? = null,
    val depotDisplayName: String? = null,
    val allowedTags: Set<String> = emptySet(),
) {
    fun restore(): OperationalDestination = OperationalDestination(
        id = id,
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
                railId = value.railId.toString(),
                arcLengthInAbMeters = value.arcLengthInAbMeters,
                approachDirection = value.approachDirection,
                roleType = value.role.type,
                stationId = station?.stationId?.value,
                stationDisplayName = station?.stationDisplayName,
                berthId = station?.berthId,
                doorSide = station?.doorSide,
                stoppingOffsetMetersBefore = station?.stoppingOffsetMetersBefore ?: 0.0,

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
    val color: String? = null,
    val maximumSpeedMetersPerSecond: Double? = null
) {
    fun restore(): Line = Line(
        LineId(id),
        displayName,
        upStationIds.map(::StationId),
        downStationIds.map(::StationId),
        color,
        maximumSpeedMetersPerSecond
    )

    companion object {
        fun capture(value: Line): SavedLineV1 = SavedLineV1(
            id = value.id.value,
            displayName = value.displayName,
            upStationIds = value.upStationIds.map { it.value },
            downStationIds = value.downStationIds.map { it.value },
            color = value.color,
            maximumSpeedMetersPerSecond = value.maximumSpeedMetersPerSecond
        )
    }
}

/** SimpleATO-owned operational destinations and lines restored after Basic infrastructure. */
@Serializable
data class SimpleATOInfrastructureSnapshotV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val destinations: List<SavedOperationalDestinationV1>,
    val lines: List<SavedLineV1>,
    val lineRevision: Long
) {
    fun restore(world: World) {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported SimpleATO infrastructure snapshot version: $formatVersion"
        }
        val restoredDestinations = destinations.map(SavedOperationalDestinationV1::restore)
        val restoredLines = lines.map(SavedLineV1::restore)
        val duplicateDestinationIds = restoredDestinations
            .groupingBy(OperationalDestination::id).eachCount()
            .filterValues { it > 1 }
            .keys
        check(duplicateDestinationIds.isEmpty()) {
            "SimpleATO infrastructure snapshot contains duplicate destinations: " +
                duplicateDestinationIds.joinToString()
        }
        val duplicateLineIds = restoredLines.groupingBy(Line::id).eachCount()
            .filterValues { it > 1 }
            .keys
        check(duplicateLineIds.isEmpty()) {
            "SimpleATO infrastructure snapshot contains duplicate lines: ${duplicateLineIds.joinToString()}"
        }
        val data = world.simpleAtoWorldData
        val basicData = world.basicWorldData
        check(data.destinations.all().isEmpty()) { "SimpleATO DestinationStore is not empty" }
        check(data.stations.all().isEmpty()) { "SimpleATO StationStore is not empty" }
        check(data.depots.allDepots().isEmpty() && data.depots.allRollingStock().isEmpty()) {
            "SimpleATO DepotStore is not empty"
        }
        check(data.lines.snapshot().let { it.lines.isEmpty() && it.revision == 0L }) {
            "SimpleATO LineStore is not empty"
        }
        require(lineRevision >= 0L) { "SimpleATO line revision must be non-negative" }

        val missingRails = restoredDestinations.filter { world.getRail(it.railId) == null }
        check(missingRails.isEmpty()) {
            "SimpleATO infrastructure snapshot contains destinations on missing rails: " +
                missingRails.joinToString { "${it.id}@${it.railId}" }
        }
        val outOfRange = restoredDestinations.filter { destination ->
            val railLength = requireNotNull(world.getRail(destination.railId))
                .getView(ViewDirection.AB).curve.totalArcLength
            destination.arcLengthInAbMeters > railLength
        }
        check(outOfRange.isEmpty()) {
            "SimpleATO infrastructure snapshot contains destinations beyond their rails: " +
                outOfRange.joinToString { "${it.id}@${it.arcLengthInAbMeters}" }
        }
        val projectedIdCollisions = restoredDestinations.map(OperationalDestination::id)
            .filter { basicData.wayPoints[it] != null }
        check(projectedIdCollisions.isEmpty()) {
            "SimpleATO destination IDs collide with standalone Basic WayPoints: " +
                projectedIdCollisions.joinToString()
        }
        val stationRoles = restoredDestinations.mapNotNull {
            it.role as? DestinationRole.StationBerthRole
        }
        val stationRailObjectCollisions = stationRoles.filter {
            basicData.railObjects.get(it.berthRef.railObjectRef) != null
        }
        check(stationRailObjectCollisions.isEmpty()) {
            "SimpleATO StationBerths collide with existing RailObjects: " +
                stationRailObjectCollisions.joinToString { it.berthRef.toString() }
        }
        val depotRoles = restoredDestinations.mapNotNull {
            it.role as? DestinationRole.DepotStablingPointRole
        }
        val depotPointCollisions = depotRoles.filter {
            data.depots.stablingPoint(it.pointRef) != null
        }
        check(depotPointCollisions.isEmpty()) {
            "SimpleATO depot destinations collide with existing StablingPoints: " +
                depotPointCollisions.joinToString { it.pointRef.toString() }
        }
        val stationIds = stationRoles.mapTo(HashSet()) { it.stationId }
        val missingStations = restoredLines
            .flatMap { it.upStationIds + it.downStationIds }
            .filterNot { it in stationIds }
            .distinct()
        check(missingStations.isEmpty()) {
            "SimpleATO infrastructure snapshot contains lines with missing stations: ${missingStations.joinToString()}"
        }
        val service = OperationalDestinationService(world)
        val materialized = ArrayList<OperationalDestination>(restoredDestinations.size)
        try {
            restoredDestinations.forEach { destination ->
                service.register(destination)
                materialized += destination
            }
        } catch (failure: Exception) {
            materialized.asReversed().forEach { destination ->
                runCatching { service.remove(destination.id) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
        data.lines.restore(restoredLines, lineRevision)
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1

        fun capture(world: World, infrastructureRevision: Long): SimpleATOInfrastructureSnapshotV1 {
            require(infrastructureRevision >= 0L)
            val data = world.simpleAtoWorldData
            val destinations = data.destinations.all()
            val missingRails = destinations.filter { world.getRail(it.railId) == null }
            check(missingRails.isEmpty()) {
                "Cannot capture destinations on missing rails: " +
                    missingRails.joinToString { "${it.id}@${it.railId}" }
            }
            val lineSnapshot = data.lines.snapshot()
            return SimpleATOInfrastructureSnapshotV1(
                formatVersion = CURRENT_FORMAT_VERSION,
                infrastructureRevision = infrastructureRevision,
                destinations = destinations.map(SavedOperationalDestinationV1::capture),
                lines = lineSnapshot.lines.map(SavedLineV1::capture),
                lineRevision = lineSnapshot.revision
            )
        }
    }
}

object SimpleATOInfrastructureSnapshotCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun encode(snapshot: SimpleATOInfrastructureSnapshotV1): String =
        json.encodeToString(SimpleATOInfrastructureSnapshotV1.serializer(), snapshot)

    fun decode(data: String): SimpleATOInfrastructureSnapshotV1 =
        json.decodeFromString(SimpleATOInfrastructureSnapshotV1.serializer(), data).also {
            require(it.formatVersion == SimpleATOInfrastructureSnapshotV1.CURRENT_FORMAT_VERSION) {
                "Unsupported SimpleATO infrastructure snapshot version: ${it.formatVersion}"
            }
        }
}
