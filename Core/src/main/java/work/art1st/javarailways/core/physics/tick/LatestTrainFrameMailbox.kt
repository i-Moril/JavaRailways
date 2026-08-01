package work.art1st.javarailways.core.physics.tick

import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.LinkedHashMap

/**
 * Bounded latest-wins handoff between physics publishers and a slower visual consumer.
 *
 * At most one frame is retained per train. Replacing a pending frame does not grow the mailbox, and
 * stale revisions are rejected so a delayed publisher cannot overwrite newer visual state.
 */
class LatestTrainFrameMailbox(
    val capacity: Int
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, TrainFrameSnapshot>()
    private var coalescedFrames = 0L
    private var droppedStaleFrames = 0L
    private var droppedCapacityFrames = 0L
    private var peakSize = 0

    init {
        require(capacity > 0) { "LatestTrainFrameMailbox capacity must be positive" }
    }

    fun offer(frame: TrainFrameSnapshot): OfferResult = synchronized(lock) {
        val current = pending[frame.trainId]
        if (current != null && current.revision >= frame.revision) {
            droppedStaleFrames++
            return@synchronized OfferResult.DROPPED_STALE
        }
        if (current != null) {
            pending[frame.trainId] = frame
            coalescedFrames++
            return@synchronized OfferResult.COALESCED
        }
        if (pending.size >= capacity) {
            val oldest = pending.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
                droppedCapacityFrames++
            }
        }
        pending[frame.trainId] = frame
        if (pending.size > peakSize) peakSize = pending.size
        OfferResult.ACCEPTED
    }

    fun drain(limit: Int = Int.MAX_VALUE): List<TrainFrameSnapshot> = synchronized(lock) {
        if (limit <= 0 || pending.isEmpty()) return@synchronized emptyList()
        val frames = ArrayList<TrainFrameSnapshot>(minOf(limit, pending.size))
        val iterator = pending.entries.iterator()
        while (iterator.hasNext() && frames.size < limit) {
            frames += iterator.next().value
            iterator.remove()
        }
        frames
    }

    fun remove(trainId: String): TrainFrameSnapshot? = synchronized(lock) {
        pending.remove(trainId)
    }

    fun clear() = synchronized(lock) {
        pending.clear()
    }

    fun metrics(): Metrics = synchronized(lock) {
        Metrics(
            size = pending.size,
            capacity = capacity,
            peakSize = peakSize,
            coalescedFrames = coalescedFrames,
            droppedStaleFrames = droppedStaleFrames,
            droppedCapacityFrames = droppedCapacityFrames
        )
    }

    enum class OfferResult {
        ACCEPTED,
        COALESCED,
        DROPPED_STALE
    }

    data class Metrics(
        val size: Int,
        val capacity: Int,
        val peakSize: Int,
        val coalescedFrames: Long,
        val droppedStaleFrames: Long,
        val droppedCapacityFrames: Long
    )
}
