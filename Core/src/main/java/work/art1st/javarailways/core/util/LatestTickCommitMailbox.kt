package work.art1st.javarailways.core.util

import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickCommitMailboxMetrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Bounded identity-keyed handoff for generic tick commit requests. */
class LatestTickCommitMailbox(
    val capacity: Int
) {
    private val pending = ConcurrentHashMap<IdentityKey, Synchronizable>()
    private val size = AtomicInteger()
    private val peakSize = AtomicInteger()
    private val coalescedRequests = AtomicLong()
    private val droppedCapacityRequests = AtomicLong()

    init {
        require(capacity > 0) { "LatestTickCommitMailbox capacity must be positive" }
    }

    fun offer(synchronizable: Synchronizable) {
        val key = IdentityKey(synchronizable)
        while (true) {
            val current = pending[key]
            if (current != null) {
                if (pending.replace(key, current, synchronizable)) {
                    coalescedRequests.incrementAndGet()
                    return
                }
                continue
            }

            val reservedSize = size.incrementAndGet()
            if (reservedSize > capacity) {
                size.decrementAndGet()
                droppedCapacityRequests.incrementAndGet()
                return
            }
            val previous = pending.putIfAbsent(key, synchronizable)
            if (previous == null) {
                peakSize.accumulateAndGet(reservedSize, ::maxOf)
                return
            }
            size.decrementAndGet()
        }
    }

    fun remove(synchronizable: Synchronizable) {
        if (pending.remove(IdentityKey(synchronizable)) != null) size.decrementAndGet()
    }

    fun drain(limit: Int = Int.MAX_VALUE): List<Synchronizable> {
        if (limit <= 0 || pending.isEmpty()) return emptyList()
        val requests = ArrayList<Synchronizable>(minOf(limit, size.get()))
        for ((key, synchronizable) in pending.entries) {
            if (requests.size >= limit) break
            if (pending.remove(key, synchronizable)) {
                size.decrementAndGet()
                requests += synchronizable
            }
        }
        return requests
    }

    fun clear() {
        pending.clear()
        size.set(0)
    }

    fun metrics(): TickCommitMailboxMetrics = TickCommitMailboxMetrics(
        size = size.get(),
        capacity = capacity,
        peakSize = peakSize.get(),
        coalescedRequests = coalescedRequests.get(),
        droppedCapacityRequests = droppedCapacityRequests.get()
    )

    private class IdentityKey(
        private val value: Synchronizable
    ) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && value === other.value
        override fun hashCode(): Int = System.identityHashCode(value)
    }
}
