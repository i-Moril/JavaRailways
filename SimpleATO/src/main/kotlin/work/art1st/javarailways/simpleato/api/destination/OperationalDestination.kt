package work.art1st.javarailways.simpleato.api.destination

import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotStablingPoint
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.StationBerth
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stable role chosen when an operational Destination is created. */
enum class DestinationRoleType(val displayName: String) {
    REGULAR("普通目的地"),
    STATION_BERTH("车站停车位"),
    DEPOT_STABLING_POINT("车辆段停车点")
}

/** Canonical WayPoint IDs for operational destinations whose identity is owned by a role. */
object OperationalDestinationIds {
    const val COMPONENT_SEPARATOR = '@'

    fun stationBerth(stationId: StationId, berthId: String): String =
        join(stationId.value, berthId, "stationId", "berthId")

    fun depotStablingPoint(depotId: String, stablingPointId: String): String =
        join(depotId, stablingPointId, "depotId", "stablingPointId")

    fun parseStationBerth(value: String): StationBerthRef {
        val (stationId, berthId) = split(value, "station berth")
        return StationBerthRef(StationId(stationId), berthId)
    }

    private fun join(first: String, second: String, firstLabel: String, secondLabel: String): String {
        requireComponent(firstLabel, first)
        requireComponent(secondLabel, second)
        return "$first$COMPONENT_SEPARATOR$second"
    }

    private fun split(value: String, label: String): Pair<String, String> {
        require(value == value.trim()) { "$label destination ID must be trimmed" }
        val separator = value.indexOf(COMPONENT_SEPARATOR)
        require(separator > 0 && separator == value.lastIndexOf(COMPONENT_SEPARATOR) &&
            separator < value.lastIndex
        ) { "$label destination ID must use '<owner>@<point>'" }
        return value.substring(0, separator) to value.substring(separator + 1)
    }

    private fun requireComponent(label: String, value: String) {
        require(value.isNotBlank() && value == value.trim() &&
            COMPONENT_SEPARATOR !in value && value.none(Char::isISOControl)
        ) { "$label must be stable text without '@' or control characters" }
    }
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
        val usableLengthMeters: Double? = null,
        val defaultDwellTicks: Long = 0L,
        val releaseDistanceMetersAfter: Double = 0.0,
    ) : DestinationRole {
        val positionToleranceMeters: Double get() = work.art1st.javarailways.basic.api.ato.AtoSpatialPolicy.POSITION_TOLERANCE_METERS
        override val type: DestinationRoleType = DestinationRoleType.STATION_BERTH

        init {
            require(stationDisplayName.isNotBlank() && stationDisplayName == stationDisplayName.trim())
            StationBerthRef(stationId, berthId)
            require(stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
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
        const val DEFAULT_POSITION_TOLERANCE_METERS = work.art1st.javarailways.basic.api.ato.AtoSpatialPolicy.POSITION_TOLERANCE_METERS
    }
}

/**
 * Stable, directed route anchor. Role objects are passive metadata; behavior remains in Dispatcher.
 * Arc length is always measured on the owning Rail's AB view.
 */
data class OperationalDestination(
    val id: String,
    val railId: UUID,
    val arcLengthInAbMeters: Double,
    /** Null means no destination-level restriction; rail direction rules still apply. */
    val approachDirection: ViewDirection? = null,
    val role: DestinationRole = DestinationRole.Regular
) {
    init {
        requireStableId("destinationId", id)
        require(arcLengthInAbMeters.isFinite() && arcLengthInAbMeters >= 0.0)
        val canonicalRoleId = when (role) {
            DestinationRole.Regular -> id
            is DestinationRole.StationBerthRole -> OperationalDestinationIds.stationBerth(
                role.stationId,
                role.berthId
            )
            is DestinationRole.DepotStablingPointRole -> OperationalDestinationIds.depotStablingPoint(
                role.depotId,
                role.stablingPointId
            )
        }
        require(id == canonicalRoleId) {
            "${role.type} destination ID must be canonical: $canonicalRoleId"
        }
    }

    fun toWayPoint(): WayPoint = WayPoint(id, railId, arcLengthInAbMeters)
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

/** Main-thread atomic adapter keeping Destination-owned operational stores in sync. */
class OperationalDestinationService(private val world: World) {
    private val basicData = world.basicWorldData
    private val data = world.simpleAtoWorldData

    /**
     * Return the current definition from this service's world, or `null` when [id] is unknown.
     *
     * This is a non-blocking read of the live world-local destination store. The returned value is
     * immutable, the call performs no mutation, and it is not a persisted or revision-pinned snapshot.
     */
    operator fun get(id: String): OperationalDestination? = data.destinations[id]

    @MainThread
    fun register(destination: OperationalDestination) {
        check(data.destinations[destination.id] == null) {
            "Destination already registered: ${destination.id}"
        }
        check(basicData.wayPoints[destination.id] == null) {
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
        requireUnallocatedStationBerth(destination)
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
            basicData.wayPoints.add(destination.toWayPoint())
            data.destinations.add(destination)
        } catch (failure: Exception) {
            basicData.wayPoints.remove(destination.id)
            if (stationRole != null) data.stations.removeBerth(stationRole.berthRef)
            if (depotRole != null) data.depots.removeStablingPoint(depotRole.pointRef)
            throw failure
        }
    }

    /** Remove every destination owned by a rail before that rail is permanently deleted. */
    @MainThread
    fun removeAllOnRail(railId: UUID): List<OperationalDestination> {
        val attached = data.destinations.all().filter { it.railId == railId }
        attached.forEach(::requireUnallocatedStationBerth)
        attached.forEach { destination ->
            check(remove(destination.id) == destination) {
                "Destination changed while deleting rail $railId: ${destination.id}"
            }
        }
        basicData.wayPoints.all().filter { it.railId == railId }.forEach { wayPoint ->
            basicData.wayPoints.remove(wayPoint.id)
        }
        return attached
    }

    /**
     * Move every destination from an old rail onto its geometric position on a replacement rail.
     * Directed destinations retain their physical approach direction even if the replacement's
     * AB orientation is reversed. All role-owned station/depot records are rebuilt by [replace].
     */
    @MainThread
    @OptIn(Heavy::class)
    fun migrateAllOnRail(oldRailId: UUID, replacementRailId: UUID): List<OperationalDestination> =
        migrateAllOnRails(mapOf(oldRailId to replacementRailId))

    /** Atomically migrate destination references for a multi-rail rebuild. */
    @MainThread
    @OptIn(Heavy::class)
    fun migrateAllOnRails(replacements: Map<UUID, UUID>): List<OperationalDestination> {
        if (replacements.isEmpty()) return emptyList()
        replacements.forEach { (oldRailId, replacementRailId) ->
            require(oldRailId != replacementRailId) { "replacement rail must have a new ID" }
            requireNotNull(world.getRail(oldRailId)) {
                "Destination source rail not found: $oldRailId"
            }
            requireNotNull(world.getRail(replacementRailId)) {
                "Destination replacement rail not found: $replacementRailId"
            }
        }
        val originals = data.destinations.all().filter { it.railId in replacements }
        val destinationIds = data.destinations.all().mapTo(HashSet()) { it.id }
        val standaloneWayPoints = basicData.wayPoints.all().filter {
            it.id !in destinationIds && it.railId in replacements
        }
        val plans = originals.map { original ->
            val replacementRailId = requireNotNull(replacements[original.railId])
            val oldRail = requireNotNull(world.getRail(original.railId))
            val replacement = requireNotNull(world.getRail(replacementRailId))
            val oldPoint = oldRail.curve.getPointAt(original.arcLengthInAbMeters)
            val replacementPoint = replacement.curve.getNearestPoint(oldPoint.position)
            val direction = original.approachDirection?.let { oldDirection ->
                val sameAbOrientation = oldPoint.tangent.dot(replacementPoint.tangent) >= 0.0
                if (sameAbOrientation) oldDirection else when (oldDirection) {
                    ViewDirection.AB -> ViewDirection.BA
                    ViewDirection.BA -> ViewDirection.AB
                }
            }
            original to original.copy(
                railId = replacementRailId,
                arcLengthInAbMeters = replacementPoint.arcLength,
                approachDirection = direction
            )
        }
        val wayPointPlans = standaloneWayPoints.map { original ->
            val replacementRailId = requireNotNull(replacements[original.railId])
            val oldRail = requireNotNull(world.getRail(original.railId))
            val replacement = requireNotNull(world.getRail(replacementRailId))
            val oldPoint = oldRail.curve.getPointAt(original.arcLength)
            val replacementPoint = replacement.curve.getNearestPoint(oldPoint.position)
            original.copy(railId = replacementRailId, arcLength = replacementPoint.arcLength)
        }
        val migrated = ArrayList<OperationalDestination>(plans.size)
        try {
            plans.forEach { (original, next) ->
                replace(original.id, next)
                migrated += next
            }
            wayPointPlans.forEach(basicData.wayPoints::add)
        } catch (failure: Exception) {
            plans.asReversed().forEach { (original, next) ->
                if (data.destinations[original.id] == next) {
                    runCatching { replace(original.id, original) }
                        .onFailure(failure::addSuppressed)
                }
            }
            throw failure
        }
        return migrated
    }

    /** Rebind destinations after Core atomically replaced one rail with two same-direction pieces. */
    @MainThread
    fun migrateAfterRailSplit(
        oldRailId: UUID,
        beforeRailId: UUID,
        afterRailId: UUID,
        splitArcLengthInAbMeters: Double
    ): List<OperationalDestination> {
        require(splitArcLengthInAbMeters > 0.0 && splitArcLengthInAbMeters.isFinite())
        requireNotNull(world.getRail(beforeRailId)) { "Split before rail not found: $beforeRailId" }
        requireNotNull(world.getRail(afterRailId)) { "Split after rail not found: $afterRailId" }
        val plans = data.destinations.all().filter { it.railId == oldRailId }.map { original ->
            if (original.arcLengthInAbMeters <= splitArcLengthInAbMeters) {
                original.copy(railId = beforeRailId)
            } else {
                original.copy(
                    railId = afterRailId,
                    arcLengthInAbMeters = original.arcLengthInAbMeters - splitArcLengthInAbMeters
                )
            }
        }
        plans.forEach { replace(it.id, it) }
        basicData.wayPoints.all().filter {
            it.railId == oldRailId && data.destinations[it.id] == null
        }.forEach { original ->
            basicData.wayPoints.add(
                if (original.arcLength <= splitArcLengthInAbMeters) {
                    original.copy(railId = beforeRailId)
                } else {
                    original.copy(
                        railId = afterRailId,
                        arcLength = original.arcLength - splitArcLengthInAbMeters
                    )
                }
            )
        }
        return plans
    }

    /** Rebind destinations after Core atomically concatenated two same-direction rail pieces. */
    @MainThread
    fun migrateAfterRailMerge(
        beforeRailId: UUID,
        afterRailId: UUID,
        mergedRailId: UUID,
        beforeLengthMeters: Double
    ): List<OperationalDestination> {
        require(beforeLengthMeters > 0.0 && beforeLengthMeters.isFinite())
        requireNotNull(world.getRail(mergedRailId)) { "Merged rail not found: $mergedRailId" }
        val plans = data.destinations.all()
            .filter { it.railId == beforeRailId || it.railId == afterRailId }
            .map { original ->
                original.copy(
                    railId = mergedRailId,
                    arcLengthInAbMeters = if (original.railId == beforeRailId) {
                        original.arcLengthInAbMeters
                    } else {
                        beforeLengthMeters + original.arcLengthInAbMeters
                    }
                )
            }
        plans.forEach { replace(it.id, it) }
        basicData.wayPoints.all().filter {
            (it.railId == beforeRailId || it.railId == afterRailId) && data.destinations[it.id] == null
        }.forEach { original ->
            basicData.wayPoints.add(
                original.copy(
                    railId = mergedRailId,
                    arcLength = if (original.railId == beforeRailId) {
                        original.arcLength
                    } else {
                        beforeLengthMeters + original.arcLength
                    }
                )
            )
        }
        return plans
    }

    @MainThread
    fun replace(oldId: String, destination: OperationalDestination) {
        val old = data.destinations[oldId]
            ?: throw NoSuchElementException("Destination not found: $oldId")
        if (oldId != destination.id) {
            check(data.destinations[destination.id] == null) {
                "Destination already registered: ${destination.id}"
            }
            check(basicData.wayPoints[destination.id] == null) {
                "WayPoint ID already registered: ${destination.id}"
            }
        }
        requireUnallocatedStationBerth(destination)
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
        requireUnallocatedStationBerth(destination)
        (destination.role as? DestinationRole.DepotStablingPointRole)?.let { role ->
            data.depots.removeStablingPoint(role.pointRef)
        }
        (destination.role as? DestinationRole.StationBerthRole)?.let { role ->
            data.stations.removeBerth(role.berthRef)
        }
        check(data.destinations.remove(id) == destination) {
            "Destination changed during main-thread removal: $id"
        }
        basicData.wayPoints.remove(id)
        return destination
    }

    private fun requireUnallocatedStationBerth(destination: OperationalDestination) {
        val role = destination.role as? DestinationRole.StationBerthRole ?: return
        val berthRef = work.art1st.javarailways.simpleato.api.station.StationBerthRef(role.stationId, role.berthId)
        check(!data.dispatcher.hasStationBerthAllocation(berthRef)) {
            "cannot edit an allocated station berth: $berthRef"
        }
    }

    private fun registerStationBerth(
        destination: OperationalDestination,
        role: DestinationRole.StationBerthRole,
        railLength: Double
    ) {
        val rail = requireNotNull(world.getRail(destination.railId))
        val placementDirection = destination.approachDirection
            ?: basicData.operatingDirections[rail.id]
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
