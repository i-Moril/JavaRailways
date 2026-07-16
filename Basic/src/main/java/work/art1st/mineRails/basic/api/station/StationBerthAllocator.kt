package work.art1st.mineRails.basic.api.station

import work.art1st.mineRails.core.api.util.annotation.MainThread

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
class StationBerthAllocator {
    private val lock = Any()
    private val allocations = LinkedHashMap<StationBerthAllocationKey, StationBerthAllocationKey>()
    private val berthOwners = HashMap<StationBerthRef, StationBerthAllocationKey>()

    @MainThread
    fun allocate(key: StationBerthAllocationKey): StationBerthAllocationResult = synchronized(lock) {
        allocations[key]?.let { return@synchronized StationBerthAllocationResult.Confirmed(it) }
        berthOwners[key.berthRef]?.let { current ->
            return@synchronized StationBerthAllocationResult.Conflict(key.berthRef, current)
        }
        allocations[key] = key
        berthOwners[key.berthRef] = key
        StationBerthAllocationResult.Confirmed(key)
    }

    @MainThread
    fun release(key: StationBerthAllocationKey): Boolean = synchronized(lock) {
        if (allocations.remove(key) == null) return@synchronized false
        berthOwners.remove(key.berthRef, key)
        true
    }

    fun allocations(): List<StationBerthAllocationKey> = synchronized(lock) {
        allocations.keys.toList()
    }
}
