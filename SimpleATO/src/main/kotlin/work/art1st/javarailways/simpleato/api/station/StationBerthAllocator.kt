package work.art1st.javarailways.simpleato.api.station

import work.art1st.javarailways.core.api.util.annotation.MainThread

data class StationBerthOwner(
    val serviceRunId: String,
    val trainId: String,
    val callId: String
) {
    init {
        require(serviceRunId.isNotBlank() && trainId.isNotBlank() && callId.isNotBlank())
    }
}

data class StationBerthAllocationKey(
    val owner: StationBerthOwner,
    val berthRef: StationBerthRef,
    val missionRevision: Long
) {
    init {
        require(missionRevision >= 0L)
    }
}

sealed interface StationBerthAllocationResult {
    data class Confirmed(val key: StationBerthAllocationKey) : StationBerthAllocationResult
    data class Conflict(
        val berthRef: StationBerthRef,
        val heldBy: StationBerthAllocationKey
    ) : StationBerthAllocationResult
}

/** World-local, non-preemptive ownership of explicit Station berths. */
class StationBerthAllocator(private val stoppingArea: (StationBerthRef) -> StationStoppingAreaRef) {
    private val lock = Any()
    private val allocations = LinkedHashMap<StationBerthAllocationKey, StationBerthAllocationKey>()
    private val berthOwners = HashMap<StationBerthRef, StationBerthAllocationKey>()
    private val areaOwners = HashMap<StationStoppingAreaRef, StationBerthAllocationKey>()
    private val allocatedAreas = HashMap<StationBerthAllocationKey, StationStoppingAreaRef>()

    @MainThread
    fun allocate(key: StationBerthAllocationKey): StationBerthAllocationResult = synchronized(lock) {
        allocations[key]?.let { return@synchronized StationBerthAllocationResult.Confirmed(it) }
        berthOwners[key.berthRef]?.let { current ->
            return@synchronized StationBerthAllocationResult.Conflict(key.berthRef, current)
        }
        val area = stoppingArea(key.berthRef)
        require(area.stationId == key.berthRef.stationId)
        areaOwners[area]?.let { current ->
            return@synchronized StationBerthAllocationResult.Conflict(key.berthRef, current)
        }
        allocations[key] = key
        areaOwners[area] = key
        berthOwners[key.berthRef] = key
        allocatedAreas[key] = area
        StationBerthAllocationResult.Confirmed(key)
    }

    /** One atomic choice for a call owner; retries cannot reserve another alternative. */
    @MainThread
    internal fun allocateChoice(keys: List<StationBerthAllocationKey>, allowFallback: Boolean): StationBerthChoiceAllocation = synchronized(lock) {
        require(keys.isNotEmpty() && keys.map { it.berthRef }.distinct().size == keys.size)
        val first = keys.first()
        require(keys.all { it.owner == first.owner && it.missionRevision == first.missionRevision })
        val existing = allocations.keys.filter { it.owner == first.owner }
        check(existing.size <= 1) { "Station call already owns multiple logical berths" }
        existing.singleOrNull()?.let { held ->
            return@synchronized if (held in keys) StationBerthChoiceAllocation.Confirmed(held)
                else StationBerthChoiceAllocation.ExistingMismatch(held)
        }
        val conflicts = arrayListOf<StationBerthAllocationResult.Conflict>()
        for (key in if (allowFallback) keys else keys.take(1)) {
            when (val result = allocate(key)) {
                is StationBerthAllocationResult.Confirmed -> return@synchronized StationBerthChoiceAllocation.Confirmed(result.key)
                is StationBerthAllocationResult.Conflict -> conflicts += result
            }
        }
        StationBerthChoiceAllocation.Busy(java.util.List.copyOf(conflicts))
    }

    @MainThread
    fun release(key: StationBerthAllocationKey): Boolean = synchronized(lock) {
        if (allocations.remove(key) == null) return@synchronized false
        areaOwners.remove(checkNotNull(allocatedAreas.remove(key)), key)
        berthOwners.remove(key.berthRef, key)
        true
    }

    fun isAllocated(area: StationStoppingAreaRef): Boolean = synchronized(lock) { area in areaOwners }

    fun allocations(): List<StationBerthAllocationKey> = synchronized(lock) {
        allocations.keys.toList()
    }
}

internal sealed interface StationBerthChoiceAllocation {
    data class Confirmed(val key: StationBerthAllocationKey) : StationBerthChoiceAllocation
    data class Busy(val conflicts: List<StationBerthAllocationResult.Conflict>) : StationBerthChoiceAllocation
    data class ExistingMismatch(val held: StationBerthAllocationKey) : StationBerthChoiceAllocation
}
