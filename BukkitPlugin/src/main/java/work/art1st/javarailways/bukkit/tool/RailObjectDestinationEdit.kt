package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.DestinationRoleType
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationIds
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.core.api.physics.rail.ViewDirection

/** Values exposed by the RailObjectWand Destination dialog. */
internal data class RailObjectDestinationEdit(
    val id: String,
    val roleType: DestinationRoleType,
    val approachDirection: ViewDirection?,
    val stationId: String = "",
    val berthId: String = "",
    val doorSide: DoorSide = DoorSide.LEFT,
    val depotId: String = "",
    val stablingPointId: String = "",
    val stablingUsableLengthMeters: Double? = null,
    val stablingAllowedTags: Set<String> = emptySet(),
    val stoppingAreaId: String? = null,
    val minimumTrainLengthMeters: Double? = null,
    val maximumTrainLengthMeters: Double? = null
)

/**
 * Apply dialog-owned values while retaining role fields that the dialog does not expose.
 * Converting to a different role uses that role's documented defaults.
 */
internal fun applyRailObjectDestinationEdit(
    wayPoint: WayPoint,
    original: OperationalDestination?,
    edit: RailObjectDestinationEdit
): OperationalDestination {
    val role = when (edit.roleType) {
        DestinationRoleType.REGULAR -> DestinationRole.Regular
        DestinationRoleType.STATION_BERTH -> {
            val stationId = StationId(edit.stationId.trim())
            val previous = original?.role as? DestinationRole.StationBerthRole
            DestinationRole.StationBerthRole(
                stationId = stationId,
                stationDisplayName = previous
                    ?.takeIf { it.stationId == stationId }
                    ?.stationDisplayName
                    ?: stationId.value,
                berthId = edit.berthId.trim(),
                doorSide = edit.doorSide,
                stoppingOffsetMetersBefore = previous?.stoppingOffsetMetersBefore ?: 0.0,
                positionToleranceMeters = previous?.positionToleranceMeters
                    ?: DestinationRole.DEFAULT_POSITION_TOLERANCE_METERS,
                usableLengthMeters = previous?.usableLengthMeters,
                defaultDwellTicks = previous?.defaultDwellTicks ?: 0L,
                releaseDistanceMetersAfter = previous?.releaseDistanceMetersAfter ?: 0.0,
                stoppingAreaId = edit.stoppingAreaId?.trim()?.takeIf(String::isNotEmpty) ?: edit.berthId.trim(),
                minimumTrainLengthMeters = edit.minimumTrainLengthMeters,
                maximumTrainLengthMeters = edit.maximumTrainLengthMeters
            )
        }
        DestinationRoleType.DEPOT_STABLING_POINT -> {
            val depotId = edit.depotId.trim()
            val previous = original?.role as? DestinationRole.DepotStablingPointRole
            DestinationRole.DepotStablingPointRole(
                depotId = depotId,
                stablingPointId = edit.stablingPointId.trim(),
                depotDisplayName = previous
                    ?.takeIf { it.depotId == depotId }
                    ?.depotDisplayName
                    ?: depotId,
                usableLengthMeters = edit.stablingUsableLengthMeters,
                allowedTags = edit.stablingAllowedTags
            )
        }
    }
    val id = when (role) {
        DestinationRole.Regular -> edit.id.trim().also {
            require(it.isNotBlank()) { "Destination ID must not be blank" }
        }
        is DestinationRole.StationBerthRole -> OperationalDestinationIds.stationBerth(
            role.stationId,
            role.berthId
        )
        is DestinationRole.DepotStablingPointRole -> OperationalDestinationIds.depotStablingPoint(
            role.depotId,
            role.stablingPointId
        )
    }
    val displayName = original?.displayName ?: wayPoint.displayName.ifBlank { id }
    return OperationalDestination(
        id = id,
        displayName = displayName,
        railId = wayPoint.railId,
        arcLengthInAbMeters = wayPoint.arcLength,
        approachDirection = edit.approachDirection,
        role = role
    )
}
