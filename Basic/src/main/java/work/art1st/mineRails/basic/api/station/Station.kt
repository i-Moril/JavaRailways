package work.art1st.mineRails.basic.api.station

import work.art1st.mineRails.basic.api.ato.provider.RailObjectStore
import work.art1st.mineRails.core.api.physics.rail.RailLocation
import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.mineRails.core.api.physics.rail.RailObjectRef
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.api.util.annotation.MainThread
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Stable world-local Station identity. */
data class StationId(val value: String) {
    init {
        requireValidId("stationId", value)
    }

    override fun toString(): String = value
}

/** Stable reference to one berth owned by a Station in the same World. */
data class StationBerthRef(
    val stationId: StationId,
    val berthId: String
) {
    init {
        requireValidId("berthId", berthId)
    }

    val railObjectRef: RailObjectRef
        get() = RailObjectRef(NAMESPACE, "${stationId.value}/$berthId")

    override fun toString(): String = "${stationId.value}/$berthId"

    companion object {
        const val NAMESPACE = "station-berth"
    }
}

enum class DoorSide {
    NONE,
    LEFT,
    RIGHT,
    BOTH
}

/**
 * Passive rail marker for one head-aligned stopping position.
 *
 * It contains no train, constraint, turnout, or platform-operation behavior. All offsets are
 * interpreted against the train head by StationCall resolution.
 */
class StationBerth(
    val berthRef: StationBerthRef,
    override val location: RailLocation,
    val approachDirection: ViewDirection? = null,
    val stoppingOffsetMetersBefore: Double = 0.0,
    val positionToleranceMeters: Double = 0.25,
    val usableLengthMeters: Double? = null,
    val defaultDwellTicks: Long? = null,
    val releaseDistanceMetersAfter: Double = 0.0,
    val doorSide: DoorSide = DoorSide.NONE
) : RailObject {
    init {
        require(stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
        require(positionToleranceMeters.isFinite() && positionToleranceMeters > 0.0)
        require(usableLengthMeters == null ||
            usableLengthMeters.isFinite() && usableLengthMeters > 0.0)
        require(defaultDwellTicks == null || defaultDwellTicks >= 0L)
        require(releaseDistanceMetersAfter.isFinite() && releaseDistanceMetersAfter >= 0.0)
    }

    override val ref: RailObjectRef = berthRef.railObjectRef

    @Volatile
    override var lifecycleState: RailObjectLifecycleState = RailObjectLifecycleState.ACTIVE
        private set

    internal fun markRemoved() {
        lifecycleState = RailObjectLifecycleState.REMOVED
    }
}

/** Station aggregate. It stores only immutable berth references, not live Train state. */
@ConsistentCopyVisibility
data class Station private constructor(
    val id: StationId,
    val displayName: String,
    val berthRefs: List<StationBerthRef>,
    val defaultDwellTicks: Long = 0L
) {
    constructor(
        id: StationId,
        displayName: String,
        berthRefs: Collection<StationBerthRef>,
        defaultDwellTicks: Long = 0L
    ) : this(id, displayName, immutableBerthRefs(berthRefs), defaultDwellTicks)

    init {
        require(displayName.isNotBlank() && displayName == displayName.trim())
        require(defaultDwellTicks >= 0L)
        require(berthRefs.isNotEmpty()) { "Station must contain at least one berth" }
        require(berthRefs.all { it.stationId == id }) {
            "all Station berth refs must belong to $id"
        }
        require(berthRefs.distinct().size == berthRefs.size) {
            "Station berth refs must be unique"
        }
    }

    private companion object {
        fun immutableBerthRefs(source: Collection<StationBerthRef>): List<StationBerthRef> =
            Collections.unmodifiableList(ArrayList(source))
    }
}

/** Immutable resolved dwell policy. */
data class DwellPlan(
    val minimumDwellTicks: Long,
    val plannedDepartureTick: Long? = null
) {
    init {
        require(minimumDwellTicks >= 0L)
        require(plannedDepartureTick == null || plannedDepartureTick >= 0L)
    }

    fun earliestDepartureTick(berthedTick: Long): Long {
        require(berthedTick >= 0L)
        val minimumDeparture = try {
            Math.addExact(berthedTick, minimumDwellTicks)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        return max(minimumDeparture, plannedDepartureTick ?: 0L)
    }
}

object DwellPlanResolver {
    fun resolve(
        station: Station,
        berth: StationBerth,
        minimumDwellTicksOverride: Long? = null,
        plannedDepartureTick: Long? = null
    ): DwellPlan {
        require(berth.berthRef.stationId == station.id)
        require(minimumDwellTicksOverride == null || minimumDwellTicksOverride >= 0L)
        return DwellPlan(
            minimumDwellTicks = minimumDwellTicksOverride
                ?: berth.defaultDwellTicks
                ?: station.defaultDwellTicks,
            plannedDepartureTick = plannedDepartureTick
        )
    }
}

/** Immutable timetable-owned request for one explicit Station berth call. */
data class StationCall(
    val callId: String,
    val missionRevision: Long,
    val stationId: StationId,
    val berthRef: StationBerthRef,
    val plannedArrivalTick: Long? = null,
    val dwellPlan: DwellPlan,
    val stoppingOffsetMetersBeforeOverride: Double? = null,
    val doorSideOverride: DoorSide? = null
) {
    init {
        require(callId.isNotBlank() && callId == callId.trim() &&
            callId.none(Char::isISOControl)
        ) { "callId must be stable non-blank text" }
        require(missionRevision >= 0L)
        require(berthRef.stationId == stationId)
        require(plannedArrivalTick == null || plannedArrivalTick >= 0L)
        require(plannedArrivalTick == null || dwellPlan.plannedDepartureTick == null ||
            dwellPlan.plannedDepartureTick >= plannedArrivalTick)
        require(stoppingOffsetMetersBeforeOverride == null ||
            stoppingOffsetMetersBeforeOverride.isFinite() &&
            stoppingOffsetMetersBeforeOverride >= 0.0)
    }
}

/** World-local owner of Station aggregates and their registered passive berth RailObjects. */
class StationStore(private val railObjects: RailObjectStore) {
    internal fun usesRailObjectStore(store: RailObjectStore): Boolean = railObjects === store

    private val stations = ConcurrentHashMap<StationId, Station>()
    private val berths = ConcurrentHashMap<StationBerthRef, StationBerth>()
    private val lock = Any()

    @MainThread
    fun register(station: Station, stationBerths: Collection<StationBerth>) = synchronized(lock) {
        check(stations[station.id] == null) { "Station already registered: ${station.id}" }
        val immutableBerths = stationBerths.toList()
        val refs = immutableBerths.map(StationBerth::berthRef)
        require(refs.toSet() == station.berthRefs.toSet() && refs.size == station.berthRefs.size) {
            "registered berths must exactly match Station ${station.id} berth refs"
        }
        require(immutableBerths.all { it.lifecycleState == RailObjectLifecycleState.ACTIVE })
        refs.forEach { ref ->
            check(berths[ref] == null) { "Station berth already registered: $ref" }
            check(railObjects.get(ref.railObjectRef) == null) {
                "RailObjectRef already registered: ${ref.railObjectRef}"
            }
        }

        val registered = ArrayList<StationBerth>()
        try {
            immutableBerths.forEach { berth ->
                railObjects.register(berth)
                registered += berth
            }
        } catch (failure: Exception) {
            registered.forEach { berth ->
                berth.markRemoved()
                railObjects.unregister(berth.ref)
            }
            throw failure
        }
        immutableBerths.forEach { berth -> berths[berth.berthRef] = berth }
        stations[station.id] = station
    }

    /** Atomically append one passive berth, creating the Station aggregate when necessary. */
    @MainThread
    fun addBerth(
        stationId: StationId,
        stationDisplayName: String,
        berth: StationBerth,
        stationDefaultDwellTicks: Long = 0L
    ) = synchronized(lock) {
        require(berth.berthRef.stationId == stationId)
        check(berths[berth.berthRef] == null) {
            "Station berth already registered: ${berth.berthRef}"
        }
        check(railObjects.get(berth.ref) == null) {
            "RailObjectRef already registered: ${berth.ref}"
        }
        val current = stations[stationId]
        val updated = if (current == null) {
            Station(
                stationId,
                stationDisplayName,
                listOf(berth.berthRef),
                stationDefaultDwellTicks
            )
        } else {
            Station(
                current.id,
                current.displayName,
                current.berthRefs + berth.berthRef,
                current.defaultDwellTicks
            )
        }
        railObjects.register(berth)
        berths[berth.berthRef] = berth
        stations[stationId] = updated
    }

    /** Atomically remove one berth and remove an empty Station aggregate. */
    @MainThread
    fun removeBerth(ref: StationBerthRef): StationBerth? = synchronized(lock) {
        val berth = berths.remove(ref) ?: return@synchronized null
        berth.markRemoved()
        railObjects.unregister(berth.ref)
        val current = stations[ref.stationId]
        if (current != null) {
            val remaining = current.berthRefs.filterNot { it == ref }
            if (remaining.isEmpty()) {
                stations.remove(ref.stationId)
            } else {
                stations[ref.stationId] = Station(
                    current.id,
                    current.displayName,
                    remaining,
                    current.defaultDwellTicks
                )
            }
        }
        berth
    }

    operator fun get(id: StationId): Station? = stations[id]

    fun berth(ref: StationBerthRef): StationBerth? = berths[ref]

    fun all(): List<Station> = stations.values.sortedBy { it.id.value }

    @MainThread
    fun remove(id: StationId): Station? = synchronized(lock) {
        val station = stations.remove(id) ?: return@synchronized null
        station.berthRefs.forEach { ref ->
            berths.remove(ref)?.let { berth ->
                berth.markRemoved()
                railObjects.unregister(berth.ref)
            }
        }
        station
    }
}

private fun requireValidId(label: String, value: String) {
    require(value.isNotBlank() && value == value.trim()) { "$label must not be blank" }
    require('/' !in value && value.none(Char::isISOControl)) {
        "$label must not contain '/' or control characters"
    }
}
