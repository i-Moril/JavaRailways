package work.art1st.javarailways.basic.api.ato.command

import java.util.Collections

/** Immutable command envelope ordered by a monotonic producer revision. */
data class RevisionedCommand<C>(
    val revision: Long,
    val command: C
) {
    init {
        require(revision >= 0L) { "command revision must be non-negative" }
    }
}

enum class CommandOfferResult {
    ACCEPTED,
    STALE
}

/**
 * Multi-producer command queue with strict monotonic revisions and one logical consumer.
 *
 * Main-thread and plugin producers may call [offer] or [enqueue]. The physics owner drains commands
 * at a Tick boundary with [drain]. Submission and draining are serialized, so accepted commands are
 * returned in revision order. A revision not newer than [latestAcceptedRevision] is rejected as
 * stale and is never queued.
 *
 * This queue transfers immutable command values; it does not make a mutable command payload safe to
 * share between threads.
 */
class RevisionedCommandQueue<C>(initialRevision: Long = -1L) {
    init {
        require(initialRevision >= -1L) { "initialRevision must be at least -1" }
    }

    private val lock = Any()
    private val pending = ArrayDeque<RevisionedCommand<C>>()

    @Volatile
    var latestAcceptedRevision: Long = initialRevision
        private set

    @Volatile
    var latestDrainedRevision: Long = initialRevision
        private set

    /** Offer a caller-assigned revision, rejecting duplicate or out-of-order commands. */
    fun offer(revision: Long, command: C): CommandOfferResult {
        require(revision >= 0L) { "command revision must be non-negative" }
        synchronized(lock) {
            if (revision <= latestAcceptedRevision) return CommandOfferResult.STALE
            pending.addLast(RevisionedCommand(revision, command))
            latestAcceptedRevision = revision
            return CommandOfferResult.ACCEPTED
        }
    }

    /** Atomically assign the next revision and enqueue [command]. */
    fun enqueue(command: C): RevisionedCommand<C> = synchronized(lock) {
        check(latestAcceptedRevision < Long.MAX_VALUE) { "command revision overflow" }
        val envelope = RevisionedCommand(latestAcceptedRevision + 1L, command)
        pending.addLast(envelope)
        latestAcceptedRevision = envelope.revision
        envelope
    }

    /** Drain all currently accepted commands as an immutable, revision-ordered list. */
    fun drain(): List<RevisionedCommand<C>> = synchronized(lock) {
        if (pending.isEmpty()) return emptyList()
        val drained = ArrayList<RevisionedCommand<C>>(pending.size)
        while (pending.isNotEmpty()) drained += pending.removeFirst()
        latestDrainedRevision = drained.last().revision
        Collections.unmodifiableList(drained)
    }

    val pendingCount: Int
        get() = synchronized(lock) { pending.size }
}
