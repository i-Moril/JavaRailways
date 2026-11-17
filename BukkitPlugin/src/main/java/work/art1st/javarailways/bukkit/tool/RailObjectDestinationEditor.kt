package work.art1st.javarailways.bukkit.tool

import java.util.UUID
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.bukkit.api.wand.config.ConfigConstraints
import work.art1st.javarailways.bukkit.api.wand.config.ConfigGroup
import work.art1st.javarailways.bukkit.api.wand.config.WandConfigItemImpl
import work.art1st.javarailways.bukkit.api.wand.config.ConfigKey
import work.art1st.javarailways.bukkit.api.wand.config.ConfigValueType
import work.art1st.javarailways.bukkit.ui.config.ConfigUIRegistry
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.DestinationRoleType
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService

enum class WayPointApproachOption(val direction: ViewDirection?) {
    UNRESTRICTED(null),
    A_TO_B(ViewDirection.AB),
    B_TO_A(ViewDirection.BA);

    companion object {
        fun from(direction: ViewDirection?): WayPointApproachOption = when (direction) {
            ViewDirection.AB -> A_TO_B
            ViewDirection.BA -> B_TO_A
            null -> UNRESTRICTED
        }
    }
}

internal fun initialWayPointDialogName(wayPointId: String, pending: Boolean): String =
    if (pending) "destination" else wayPointId

class WayPointEditKeys {
    val name = ConfigKey.build<String>("railObject.waypoint.name") {
        label = "waypoint.id.label"
        defaultValue("destination")
        valueType = ConfigValueType.STRING
        constraints = ConfigConstraints.StringLength(1, 64)
        group = GROUP_ID
        order = 0
        dependsOn = listOf(ROLE_KEY_ID)
        visibleWhen = roleVisible(DestinationRoleType.REGULAR)
    }
    val role = ConfigKey.build<DestinationRoleType>(ROLE_KEY_ID) {
        label = "waypoint.type.label"
        description = "waypoint.type.description"
        defaultValue(DestinationRoleType.REGULAR)
        valueType = ConfigValueType.ENUM
        constraints = ConfigConstraints.EnumOptions(
            DestinationRoleType.entries,
            mapOf(
                DestinationRoleType.REGULAR to "waypoint.type.regular",
                DestinationRoleType.STATION_BERTH to "waypoint.type.station",
                DestinationRoleType.DEPOT_STABLING_POINT to "waypoint.type.depot"
            )
        )
        group = GROUP_ID
        order = 1
    }
    val approachDirection = ConfigKey.build<WayPointApproachOption>("railObject.waypoint.direction") {
        label = "waypoint.approach.label"
        description = "waypoint.approach.description"
        defaultValue(WayPointApproachOption.UNRESTRICTED)
        valueType = ConfigValueType.ENUM
        constraints = ConfigConstraints.EnumOptions(
            WayPointApproachOption.entries,
            mapOf(
                WayPointApproachOption.UNRESTRICTED to "waypoint.approach.any",
                WayPointApproachOption.A_TO_B to "waypoint.approach.ab",
                WayPointApproachOption.B_TO_A to "waypoint.approach.ba"
            )
        )
        group = GROUP_ID
        order = 2
    }
    val stationId = stringKey(
        "railObject.waypoint.stationId",
        "waypoint.station_id.label",
        3,
        DestinationRoleType.STATION_BERTH
    )
    val berthId = stringKey(
        "railObject.waypoint.berthId",
        "waypoint.berth_id.label",
        4,
        DestinationRoleType.STATION_BERTH
    )
    val doorSide = ConfigKey.build<DoorSide>("railObject.waypoint.doorSide") {
        label = "waypoint.door_side.label"
        description = "waypoint.door_side.description"
        defaultValue(DoorSide.LEFT)
        valueType = ConfigValueType.ENUM
        constraints = ConfigConstraints.EnumOptions(
            DoorSide.entries,
            mapOf(
                DoorSide.NONE to "waypoint.door.none",
                DoorSide.LEFT to "waypoint.door.left",
                DoorSide.RIGHT to "waypoint.door.right",
                DoorSide.BOTH to "waypoint.door.both"
            )
        )
        group = GROUP_ID
        order = 5
        dependsOn = listOf(ROLE_KEY_ID)
        visibleWhen = roleVisible(DestinationRoleType.STATION_BERTH)
    }
    val depotId = stringKey(
        "railObject.waypoint.depotId",
        "waypoint.depot_id.label",
        6,
        DestinationRoleType.DEPOT_STABLING_POINT
    )
    val stablingPointId = stringKey(
        "railObject.waypoint.stablingPointId",
        "waypoint.stabling_id.label",
        7,
        DestinationRoleType.DEPOT_STABLING_POINT
    )
    val stablingUsableLength = ConfigKey.build<Double>(
        "railObject.waypoint.stablingUsableLength"
    ) {
        label = "waypoint.stabling_length.label"
        description = "waypoint.stabling_length.description"
        defaultValue(0.0)
        valueType = ConfigValueType.NUMBER
        constraints = ConfigConstraints.NumberRange(0.0, 1000.0, 1.0, "m")
        group = GROUP_ID
        order = 8
        dependsOn = listOf(ROLE_KEY_ID)
        visibleWhen = roleVisible(DestinationRoleType.DEPOT_STABLING_POINT)
    }
    val stablingAllowedTags = stringKey(
        "railObject.waypoint.stablingAllowedTags",
        "waypoint.stabling_tags.label",
        9,
        DestinationRoleType.DEPOT_STABLING_POINT
    )

    companion object {
        const val GROUP_ID = "railObject.waypoint.edit"
        const val ROLE_KEY_ID = "railObject.waypoint.role"

        private fun roleVisible(role: DestinationRoleType): (work.art1st.javarailways.bukkit.api.wand.config.ConfigSnapshot) -> Boolean =
            { snapshot -> snapshot.get<DestinationRoleType>(ROLE_KEY_ID) == role }

        private fun stringKey(
            id: String,
            textKey: String,
            itemOrder: Int,
            role: DestinationRoleType
        ) = ConfigKey.build<String>(id) {
            label = textKey
            defaultValue("")
            valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(0, 64)
            group = GROUP_ID
            order = itemOrder
            dependsOn = listOf(ROLE_KEY_ID)
            visibleWhen = roleVisible(role)
        }
    }
}


/**
 * RailObjectWand's operational dialog model and destination mutation adapter.
 * The host owns player state, rendering and scheduling. Call mutations in the world's
 * simulation context while holding its exact rail-scoped edit permit.
 * This same-JAR composition helper adds no persistence or independent lifecycle.
 */
class RailObjectDestinationEditor {
    val configUI = ConfigUIRegistry()
    val keys = WayPointEditKeys()

    init {
        configUI.register(ConfigGroup(
            id = "railObject.waypoint.edit",
            label = "waypoint.dialog.title",
            description = "waypoint.dialog.description",
            items = mutableListOf(
                WandConfigItemImpl(keys.name),
                WandConfigItemImpl(keys.role),
                WandConfigItemImpl(keys.approachDirection),
                WandConfigItemImpl(keys.stationId),
                WandConfigItemImpl(keys.berthId),
                WandConfigItemImpl(keys.doorSide),
                WandConfigItemImpl(keys.depotId),
                WandConfigItemImpl(keys.stablingPointId),
                WandConfigItemImpl(keys.stablingUsableLength),
                WandConfigItemImpl(keys.stablingAllowedTags)
            )
        ))
    }

    fun seedSession(
        playerId: UUID,
        wayPoint: WayPoint,
        destination: OperationalDestination?,
        pending: Boolean,
        preferredRole: DestinationRoleType? = null
    ) {
        val role = destination?.role ?: DestinationRole.Regular
        val session = configUI.getPlayerSession(playerId)
        // Config sessions validate programmatic values immediately; use the field's valid default
        // for a new pending destination instead of trying to seed a forbidden empty string.
        session.setValue(keys.name, initialWayPointDialogName(wayPoint.id, pending))
        session.setValue(keys.role, preferredRole ?: role.type)
        session.setValue(
            keys.approachDirection,
            WayPointApproachOption.from(destination?.approachDirection)
        )
        val stationRole = role as? DestinationRole.StationBerthRole
        session.setValue(keys.stationId, stationRole?.stationId?.value.orEmpty())
        session.setValue(keys.berthId, stationRole?.berthId.orEmpty())
        session.setValue(keys.doorSide, stationRole?.doorSide ?: DoorSide.LEFT)
        val depotRole = role as? DestinationRole.DepotStablingPointRole
        session.setValue(keys.depotId, depotRole?.depotId.orEmpty())
        session.setValue(keys.stablingPointId, depotRole?.stablingPointId.orEmpty())
        session.setValue(keys.stablingUsableLength, depotRole?.usableLengthMeters ?: 0.0)
        session.setValue(keys.stablingAllowedTags, depotRole?.allowedTags?.sorted()?.joinToString(",").orEmpty())
    }

    fun readEdit(
        playerId: UUID,
        wayPoint: WayPoint,
        original: OperationalDestination?
    ): OperationalDestination {
        val session = configUI.getPlayerSession(playerId)
        return applyRailObjectDestinationEdit(
            wayPoint,
            original,
            RailObjectDestinationEdit(
                id = session.getValue(keys.name) ?: wayPoint.id,
                roleType = session.getValue(keys.role) ?: DestinationRoleType.REGULAR,
                approachDirection = (session.getValue(keys.approachDirection)
                    ?: WayPointApproachOption.UNRESTRICTED).direction,
                stationId = session.getValue(keys.stationId).orEmpty(),
                berthId = session.getValue(keys.berthId).orEmpty(),
                doorSide = session.getValue(keys.doorSide) ?: DoorSide.LEFT,
                depotId = session.getValue(keys.depotId).orEmpty(),
                stablingPointId = session.getValue(keys.stablingPointId).orEmpty(),
                stablingUsableLengthMeters = session.getValue(keys.stablingUsableLength)
                    ?.takeIf { it > 0.0 },
                stablingAllowedTags = session.getValue(keys.stablingAllowedTags)
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toSet()
            )
        )
    }

    /** Must run inside the host's rail-scoped edit permit, not before acquiring it. */
    fun save(world: World, wayPoint: WayPoint, updated: OperationalDestination, pending: Boolean) {
        val service = OperationalDestinationService(world)
        if (service[wayPoint.id] != null) {
            service.replace(wayPoint.id, updated)
        } else if (pending) {
            service.register(updated)
        } else {
            world.basicWorldData.wayPoints.remove(wayPoint.id)
            try {
                service.register(updated)
            } catch (failure: Exception) {
                world.basicWorldData.wayPoints.add(wayPoint)
                throw failure
            }
        }
    }

    /** Remove the canonical destination and projections, or an existing standalone anchor. */
    fun remove(world: World, wayPoint: WayPoint) {
        val service = OperationalDestinationService(world)
        if (service[wayPoint.id] != null) service.remove(wayPoint.id)
        else world.basicWorldData.wayPoints.remove(wayPoint.id)
    }
}
