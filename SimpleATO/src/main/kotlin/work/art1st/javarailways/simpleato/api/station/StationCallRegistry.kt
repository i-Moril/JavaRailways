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

data class StationBerthRegistrationKey(
    val owner: StationBerthOwner,
    val berthRef: StationBerthRef,
    val missionRevision: Long
) {
    init {
        require(missionRevision >= 0L)
    }
}

/**
 * World-local station-call references, not exclusive movement resources.
 * Several trains may approach the same berth; Basic's rolling grants protect physical entry.
 * References keep edit protection and exact call cleanup alive until their own tail release.
 */
class StationCallRegistry {
    private val lock = Any()
    private val registrations = LinkedHashSet<StationBerthRegistrationKey>()
    private val berthReferences = HashMap<StationBerthRef, Int>()

    @MainThread
    fun register(key: StationBerthRegistrationKey): StationBerthRegistrationKey = synchronized(lock) {
        if (registrations.add(key)) {
            berthReferences[key.berthRef] = (berthReferences[key.berthRef] ?: 0) + 1
        }
        key
    }

    /** One atomic choice per call; other calls' references do not veto this choice. */
    @MainThread
    internal fun registerChoice(keys: List<StationBerthRegistrationKey>): StationBerthChoiceRegistration = synchronized(lock) {
        require(keys.isNotEmpty() && keys.map { it.berthRef }.distinct().size == keys.size)
        val first = keys.first()
        require(keys.all { it.owner == first.owner && it.missionRevision == first.missionRevision })
        val existing = registrations.filter { it.owner == first.owner }
        check(existing.size <= 1) { "Station call already owns multiple logical berths" }
        existing.singleOrNull()?.let { held ->
            return@synchronized if (held in keys) StationBerthChoiceRegistration.Confirmed(held)
                else StationBerthChoiceRegistration.ExistingMismatch(held)
        }
        StationBerthChoiceRegistration.Confirmed(register(first))
    }

    @MainThread
    fun release(key: StationBerthRegistrationKey): Boolean = synchronized(lock) {
        if (!registrations.remove(key)) return@synchronized false
        val remaining = checkNotNull(berthReferences[key.berthRef]) - 1
        if (remaining == 0) berthReferences.remove(key.berthRef)
        else berthReferences[key.berthRef] = remaining
        true
    }

    fun isRegistered(berthRef: StationBerthRef): Boolean = synchronized(lock) { berthRef in berthReferences }

    fun registrations(): List<StationBerthRegistrationKey> = synchronized(lock) {
        registrations.toList()
    }
}

internal sealed interface StationBerthChoiceRegistration {
    data class Confirmed(val key: StationBerthRegistrationKey) : StationBerthChoiceRegistration
    data class ExistingMismatch(val held: StationBerthRegistrationKey) : StationBerthChoiceRegistration
}
