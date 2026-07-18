package work.art1st.mineRails.basic.api.destination

import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.depot.DepotId
import work.art1st.mineRails.basic.api.depot.DepotStablingPoint
import work.art1st.mineRails.basic.api.depot.StablingPointRef
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.station.StationBerth
import work.art1st.mineRails.basic.api.station.StationBerthRef
import work.art1st.mineRails.basic.api.station.StationId
import work.art1st.mineRails.basic.pathFinding.RailDestination
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.api.util.annotation.MainThread
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stable role chosen when an operational Destination is created. */
enum class DestinationRoleType(val displayName: String) {
    REGULAR("普通目的地"),
    STATION_BERTH("车站停车位"),
    DEPOT_STABLING_POINT("车辆段停车点")
}

sealed interface DestinationRole {
    val type: DestinationRoleType

    data object Regular : DestinationRole {
        override val type: DestinationRoleType = DestinationRoleType.REGULAR
    }

    data class StationBerthRole(
        val stationId: StationId,
        val stationDisplayName: String = stationId.value,
        val berthId: String,
        val doorSide: DoorSide,
        val stoppingOffsetMetersBefore: Double = 0.0,
        val positionToleranceMeters: Double = DEFAULT_POSITION_TOLERANCE_METERS,
        val usableLengthMeters: Double? = null,
        val defaultDwellTicks: Long = 0L,
        val releaseDistanceMetersAfter: Double = 0.0
    ) : DestinationRole {
        override val type: DestinationRoleType = DestinationRoleType.STATION_BERTH

        init {
            require(stationDisplayName.isNotBlank() && stationDisplayName == stationDisplayName.trim())
            StationBerthRef(stationId, berthId)
            require(doorSide != DoorSide.NONE) { "Station Destination doorSide must not be NONE" }
            require(stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
            require(positionToleranceMeters.isFinite() && positionToleranceMeters > 0.0)
            require(usableLengthMeters == null || usableLengthMeters.isFinite() && usableLengthMeters > 0.0)
            require(defaultDwellTicks >= 0L)
            require(releaseDistanceMetersAfter.isFinite() && releaseDistanceMetersAfter >= 0.0)
        }

        val berthRef: StationBerthRef
            get() = StationBerthRef(stationId, berthId)
    }

    data class DepotStablingPointRole(
        val depotId: String,
        val stablingPointId: String,
        val depotDisplayName: String = depotId,
        val usableLengthMeters: Double? = null,
        val allowedTags: Set<String> = emptySet()
    ) : DestinationRole {
        override val type: DestinationRoleType = DestinationRoleType.DEPOT_STABLING_POINT

        init {
            requireStableId("depotId", depotId)
            requireStableId("stablingPointId", stablingPointId)
            require(depotDisplayName.isNotBlank() && depotDisplayName == depotDisplayName.trim())
            require(usableLengthMeters == null || usableLengthMeters.isFinite() && usableLengthMeters > 0.0)
            allowedTags.forEach { requireStableId("allowedTag", it) }
        }

        val pointRef: StablingPointRef
            get() = StablingPointRef(DepotId(depotId), stablingPointId)
    }

    companion object {
        const val DEFAULT_POSITION_TOLERANCE_METERS = 0.25
    }
}

/**
 * Stable, directed route anchor. Role objects are passive metadata; behavior remains in Dispatcher.
 * Arc length is always measured on the owning Rail's AB view.
 */
data class OperationalDestination(
    val id: String,
    val displayName: String,
    val railId: UUID,
    val arcLengthInAbMeters: Double,
    /** Null means no destination-level restriction; rail direction rules still apply. */
    val approachDirection: ViewDirection? = null,
    val role: DestinationRole = DestinationRole.Regular
) {
    init {
        requireStableId("destinationId", id)
        require(displayName.isNotBlank() && displayName == displayName.trim())
        require(arcLengthInAbMeters.isFinite() && arcLengthInAbMeters >= 0.0)
    }

    fun toWayPoint(): WayPoint = WayPoint(id, displayName, railId, arcLengthInAbMeters)
}

/** World-local stable Destination records. Live StationBerths are owned by StationStore. */
class OperationalDestinationStore {
    private val destinations = ConcurrentHashMap<String, OperationalDestination>()

    fun add(destination: OperationalDestination) {
        check(destinations.putIfAbsent(destination.id, destination) == null) {
            "Destination already registered: ${destination.id}"
        }
    }

    operator fun get(id: String): OperationalDestination? = destinations[id]

    fun all(): List<OperationalDestination> = destinations.values.sortedBy { it.id }

    fun remove(id: String): OperationalDestination? = destinations.remove(id)
}

/** Main-thread atomic adapter between new Destinations and legacy WayPoint/Station stores. */
class OperationalDestinationService(private val world: World) {
    private val data = world.basicWorldData

    /** Migrate the legacy RailView-start Destination into one directed operational record. */
    @MainThread
    fun registerLegacy(
        legacy: RailDestination,
        destinationId: String = legacy.label
    ): OperationalDestination {
        require(legacy.railView.rail.world === world) { "legacy Destination belongs to another World" }
        val railLength = legacy.railView.rail.getView(ViewDirection.AB).curve.totalArcLength
        val arcInAb = when (legacy.railView.viewDirection) {
            ViewDirection.AB -> 0.0
            ViewDirection.BA -> railLength
        }
        val destination = OperationalDestination(
            id = destinationId,
            displayName = legacy.label,
            railId = legacy.railView.rail.id,
            arcLengthInAbMeters = arcInAb,
            approachDirection = legacy.railView.viewDirection,
            role = DestinationRole.Regular
        )
        register(destination)
        return destination
    }

    @MainThread
    fun register(destination: OperationalDestination) {
        check(data.destinations[destination.id] == null) {
            "Destination already registered: ${destination.id}"
        }
        check(data.wayPoints[destination.id] == null) {
            "WayPoint ID already registered: ${destination.id}"
        }
        val rail = world.getRail(destination.railId)
            ?: throw IllegalArgumentException("Destination rail not found: ${destination.railId}")
        val railLength = rail.getView(ViewDirection.AB).curve.totalArcLength
        require(destination.arcLengthInAbMeters <= railLength) {
            "Destination arc exceeds rail length: ${destination.arcLengthInAbMeters} > $railLength"
        }
        val stationRole = destination.role as? DestinationRole.StationBerthRole
        val depotRole = destination.role as? DestinationRole.DepotStablingPointRole
        if (stationRole != null) registerStationBerth(destination, stationRole, railLength)
        if (depotRole != null) {
            data.depots.addStablingPoint(
                depotId = depotRole.pointRef.depotId,
                depotDisplayName = depotRole.depotDisplayName,
                point = DepotStablingPoint(
                    ref = depotRole.pointRef,
                    destinationId = destination.id,
                    usableLengthMeters = depotRole.usableLengthMeters,
                    allowedTags = depotRole.allowedTags
                )
            )
        }
        try {
            data.wayPoints.add(destination.toWayPoint())
            data.destinations.add(destination)
        } catch (failure: Exception) {
            data.wayPoints.remove(destination.id)
            if (stationRole != null) data.stations.removeBerth(stationRole.berthRef)
            if (depotRole != null) data.depots.removeStablingPoint(depotRole.pointRef)
            throw failure
        }
    }

    @MainThread
    fun replace(oldId: String, destination: OperationalDestination) {
        val old = data.destinations[oldId]
            ?: throw NoSuchElementException("Destination not found: $oldId")
        if (oldId != destination.id) {
            check(data.destinations[destination.id] == null) {
                "Destination already registered: ${destination.id}"
            }
            check(data.wayPoints[destination.id] == null) {
                "WayPoint ID already registered: ${destination.id}"
            }
        }
        remove(oldId)
        try {
            register(destination)
        } catch (failure: Exception) {
            runCatching { register(old) }.onFailure { rollback -> failure.addSuppressed(rollback) }
            throw failure
        }
    }

    @MainThread
    fun remove(id: String): OperationalDestination? {
        val destination = data.destinations[id] ?: return null
        (destination.role as? DestinationRole.DepotStablingPointRole)?.let { role ->
            data.depots.removeStablingPoint(role.pointRef)
        }
        (destination.role as? DestinationRole.StationBerthRole)?.let { role ->
            data.stations.removeBerth(role.berthRef)
        }
        check(data.destinations.remove(id) == destination) {
            "Destination changed during main-thread removal: $id"
        }
        data.wayPoints.remove(id)
        return destination
    }

    private fun registerStationBerth(
        destination: OperationalDestination,
        role: DestinationRole.StationBerthRole,
        railLength: Double
    ) {
        val rail = requireNotNull(world.getRail(destination.railId))
        val placementDirection = destination.approachDirection
            ?: rail.metadata.allowedDirection
            ?: ViewDirection.AB
        val view = rail.getView(placementDirection)
        val arcInView = when (placementDirection) {
            ViewDirection.AB -> destination.arcLengthInAbMeters
            ViewDirection.BA -> railLength - destination.arcLengthInAbMeters
        }
        val berth = StationBerth(
            berthRef = role.berthRef,
            location = view.getRailLocationAt(arcInView),
            approachDirection = destination.approachDirection,
            stoppingOffsetMetersBefore = role.stoppingOffsetMetersBefore,
            positionToleranceMeters = role.positionToleranceMeters,
            usableLengthMeters = role.usableLengthMeters,
            defaultDwellTicks = role.defaultDwellTicks,
            releaseDistanceMetersAfter = role.releaseDistanceMetersAfter,
            doorSide = role.doorSide
        )
        data.stations.addBerth(
            stationId = role.stationId,
            stationDisplayName = role.stationDisplayName,
            berth = berth
        )
    }
}

private fun requireStableId(label: String, value: String) {
    require(value.isNotBlank() && value == value.trim() &&
        '/' !in value && value.none(Char::isISOControl)
    ) { "$label must be stable non-blank text without '/' or control characters" }
}
