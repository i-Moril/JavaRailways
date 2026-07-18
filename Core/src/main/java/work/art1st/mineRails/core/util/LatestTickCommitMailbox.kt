package work.art1st.mineRails.core.util

import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickCommitMailboxMetrics
import java.util.LinkedHashMap

/**
 * Bounded identity-keyed handoff for generic tick commit requests.
 *
 * A ticking object can publish every simulation tick while a slower platform consumer retains only
 * one pending request for that exact object instance.
 */
class LatestTickCommitMailbox(
    val capacity: Int
) {
    private val lock = Any()
    private val pending = LinkedHashMap<IdentityKey, Synchronizable>()
    private var coalescedRequests = 0L
    private var droppedCapacityRequests = 0L
    private var peakSize = 0

    init {
        require(capacity > 0) { "LatestTickCommitMailbox capacity must be positive" }
    }

    fun offer(synchronizable: Synchronizable) = synchronized(lock) {
        val key = IdentityKey(synchronizable)
        if (pending.containsKey(key)) {
            pending[key] = synchronizable
            coalescedRequests++
            return@synchronized
        }
        if (pending.size >= capacity) {
            val oldest = pending.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
                droppedCapacityRequests++
            }
        }
        pending[key] = synchronizable
        if (pending.size > peakSize) peakSize = pending.size
    }

    fun remove(synchronizable: Synchronizable) = synchronized(lock) {
        pending.remove(IdentityKey(synchronizable))
    }

    fun drain(limit: Int = Int.MAX_VALUE): List<Synchronizable> = synchronized(lock) {
        if (limit <= 0 || pending.isEmpty()) return@synchronized emptyList()
        val requests = ArrayList<Synchronizable>(minOf(limit, pending.size))
        val iterator = pending.entries.iterator()
        while (iterator.hasNext() && requests.size < limit) {
            requests += iterator.next().value
            iterator.remove()
        }
        requests
    }

    fun clear() = synchronized(lock) {
        pending.clear()
    }

    fun metrics(): TickCommitMailboxMetrics = synchronized(lock) {
        TickCommitMailboxMetrics(
            size = pending.size,
            capacity = capacity,
            peakSize = peakSize,
            coalescedRequests = coalescedRequests,
            droppedCapacityRequests = droppedCapacityRequests
        )
    }

    private class IdentityKey(
        private val value: Synchronizable
    ) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && value === other.value
        override fun hashCode(): Int = System.identityHashCode(value)
    }

}
