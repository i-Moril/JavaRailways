package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry

internal enum class TailReleaseFailureKind {
    INVALID_PROGRESS,
    DECREASING_PROGRESS,
    CLAIM_SHRINK_FAILED
}

internal sealed interface LiveClaimTailReleaseResult {
    /** Valid progress was accepted, but the queue head is not clear yet. */
    data class Retained(
        val maximumAcceptedTailRouteProgressMeters: Double,
        val remainingResourceCount: Int
    ) : LiveClaimTailReleaseResult

    /** A non-empty prefix was removed while later resources remain claimed. */
    data class Shrunk(
        val releasedResources: List<InfrastructureResource>,
        val maximumAcceptedTailRouteProgressMeters: Double,
        val remainingResourceCount: Int
    ) : LiveClaimTailReleaseResult

    /**
     * Every queued resource has been released. Further calls still validate and retain monotonic
     * accepted progress for safe suffix installation, but do not access the registry.
     */
    data class Completed(
        val releasedResources: List<InfrastructureResource>,
        val alreadyCompleted: Boolean
    ) : LiveClaimTailReleaseResult

    /** The lifecycle is latched fail-closed; callers must not treat this as a routine retry. */
    data class FailedClosed(
        val kind: TailReleaseFailureKind,
        val detail: String,
        val mutationStatus: InfrastructureMutationStatus? = null,
        /** True when the unexpected registry result reports a successful mutation. */
        val claimMayHaveChanged: Boolean = false
    ) : LiveClaimTailReleaseResult {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Stateful owner of tail-driven release for one established live claim.
 *
 * The caller supplies already-authoritative tail progress on the claim's frozen route. This owner
 * first inspects only the queue's eligible head prefix, then asks the registry to remove those exact
 * resource identities. It knows only already-committed resources; pending extension reservation and
 * preparation belong to the resource-acquisition/live-grant owner. Local queue/progress state changes
 * only after the registry reports the exact expected success status. The compiled queue is retained
 * with a moving head index, so normal releases never copy or revalidate its remaining suffix.
 * Invalid progress and unexpected
 * registry results latch the lifecycle fail-closed; the live-grant owner turns that into a world Stall.
 */
internal class LiveClaimTailRelease(
    private val registry: InfrastructureCoordinationRegistry,
    private val platformOwner: Any,
    private val claimOwner: InfrastructureOwner,
    queue: CompiledTailReleaseQueue
) {
    private val lock = Any()
    private var remainingQueue: CompiledTailReleaseQueue? = queue
    private var headIndex = 0
    private var maximumProgress: Double? = null
    private var releasedPrefixMeters = 0.0
    private var terminalFailure: LiveClaimTailReleaseResult.FailedClosed? = null
    // Only one same-route extension may be pending. Its already-held resources remain in this
    // queue, but are placed after ordinary entries until READY or a proven safe abort.
    private var pendingExtendedHolds: Map<InfrastructureResource, Pair<Double, Double>>? = null

    val remainingEntries: List<ResolvedTailReleaseEntry>
        get() = synchronized(lock) {
            remainingQueue?.entries?.let { entries ->
                java.util.List.copyOf(entries.subList(headIndex, entries.size))
            } ?: emptyList()
        }

    val maximumAcceptedTailRouteProgressMeters: Double?
        get() = synchronized(lock) { maximumProgress }

    /** Last successful release boundary: a proven tail lower bound, not complete range clearance. */
    val releasedThroughRouteMeters: Double
        get() = synchronized(lock) { releasedPrefixMeters }

    val resourceQueueEmpty: Boolean
        get() = synchronized(lock) { remainingQueue == null }

    /** Validates without mutation before the registry reserves the extension's new resources. */
    fun validateExtendedHolds(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        check(terminalFailure == null && pendingExtendedHolds == null)
        val held = remainingEntries.associateBy(ResolvedTailReleaseEntry::resource)
        require(entries.map(ResolvedTailReleaseEntry::resource).distinct().size == entries.size)
        entries.forEach { entry ->
            val old = requireNotNull(held[entry.resource]) { "extended hold is not currently claimed" }
            require(entry.tailClearRouteMeters >= old.tailClearRouteMeters) {
                "extension cannot shorten an existing resource hold"
            }
        }
    }

    /** Defers only the named held identities; all other eligible queue entries still release. */
    fun stageExtendedHolds(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        validateExtendedHolds(entries)
        val original = remainingEntries.associateBy(ResolvedTailReleaseEntry::resource)
        val changed = entries.filter { entry ->
            original.getValue(entry.resource).tailClearRouteMeters < entry.tailClearRouteMeters
        }
        pendingExtendedHolds = changed.associate { entry ->
            entry.resource to (original.getValue(entry.resource).tailClearRouteMeters to entry.tailClearRouteMeters)
        }
        if (changed.isNotEmpty()) {
            val deferred = pendingExtendedHolds!!.keys
            replaceRemainingQueue(remainingEntries.map { entry ->
                if (entry.resource in deferred) entry.copy(tailClearRouteMeters = Double.MAX_VALUE) else entry
            })
        }
    }

    /** Checks the whole one-queue result before finalizing the registry delta. */
    fun validateExtendedSuffix(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        check(terminalFailure == null && pendingExtendedHolds != null)
        val current = remainingEntries
        val held = current.mapTo(HashSet()) { it.resource }
        require(entries.isNotEmpty())
        require(entries.none { it.resource in held }) { "extension suffix repeats a held resource" }
        require(entries.map(ResolvedTailReleaseEntry::resource).distinct().size == entries.size)
        val accepted = maximumProgress
        require(entries.all { accepted == null || it.tailClearRouteMeters > accepted }) {
            "extension suffix release boundary has already been passed"
        }
        require(pendingExtendedHolds!!.values.all { accepted == null || it.second > accepted }) {
            "extended hold release boundary has already been passed"
        }
        CompiledTailReleaseQueue(current.map { entry ->
            pendingExtendedHolds!![entry.resource]?.let { entry.copy(tailClearRouteMeters = it.second) } ?: entry
        }.plus(entries).sortedWith(TAIL_RELEASE_ENTRY_ORDER))
    }

    /** Publishes release metadata after the reserved delta is READY and finalized. */
    fun installExtendedSuffix(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        validateExtendedSuffix(entries)
        replaceRemainingQueue(remainingEntries.map { entry ->
            pendingExtendedHolds!![entry.resource]?.let { entry.copy(tailClearRouteMeters = it.second) } ?: entry
        } + entries)
        pendingExtendedHolds = null
    }

    /** Restores old boundaries after the registry has safely removed only the extension delta. */
    fun rollbackExtendedHolds(): LiveClaimTailReleaseResult? = synchronized(lock) {
        check(terminalFailure == null && pendingExtendedHolds != null)
        val original = pendingExtendedHolds!!
        if (original.isNotEmpty()) {
            replaceRemainingQueue(remainingEntries.map { entry ->
                original[entry.resource]?.let { entry.copy(tailClearRouteMeters = it.first) } ?: entry
            })
        }
        pendingExtendedHolds = null
        maximumProgress?.let(::advance)
    }

    private fun replaceRemainingQueue(entries: Collection<ResolvedTailReleaseEntry>) {
        remainingQueue = CompiledTailReleaseQueue(entries.sortedWith(TAIL_RELEASE_ENTRY_ORDER))
        headIndex = 0
    }

    fun advance(authoritativeTailRouteProgressMeters: Double): LiveClaimTailReleaseResult =
        synchronized(lock) {
            terminalFailure?.let { return@synchronized it }
            if (!authoritativeTailRouteProgressMeters.isFinite() ||
                authoritativeTailRouteProgressMeters < 0.0
            ) {
                return@synchronized failClosed(
                    TailReleaseFailureKind.INVALID_PROGRESS,
                    "tail route progress must be finite and non-negative: " +
                        authoritativeTailRouteProgressMeters
                )
            }
            maximumProgress?.let { accepted ->
                if (authoritativeTailRouteProgressMeters < accepted) {
                    return@synchronized failClosed(
                        TailReleaseFailureKind.DECREASING_PROGRESS,
                        "tail route progress decreased from $accepted to " +
                            authoritativeTailRouteProgressMeters
                    )
                }
            }

            val queue = remainingQueue
            if (queue == null) {
                maximumProgress = authoritativeTailRouteProgressMeters
                return@synchronized LiveClaimTailReleaseResult.Completed(
                    releasedResources = emptyList(),
                    alreadyCompleted = true
                )
            }

            val entries = queue.entries
            val eligible = ArrayList<InfrastructureResource>()
            var nextHead = headIndex
            while (nextHead < entries.size &&
                authoritativeTailRouteProgressMeters >= entries[nextHead].tailClearRouteMeters
            ) {
                eligible += entries[nextHead].resource
                nextHead++
            }
            if (eligible.isEmpty()) {
                maximumProgress = authoritativeTailRouteProgressMeters
                return@synchronized LiveClaimTailReleaseResult.Retained(
                    maximumAcceptedTailRouteProgressMeters = authoritativeTailRouteProgressMeters,
                    remainingResourceCount = entries.size - headIndex
                )
            }

            val queueCompleted = nextHead == entries.size
            val expectedStatus = if (queueCompleted) {
                InfrastructureMutationStatus.RELEASED
            } else {
                InfrastructureMutationStatus.REPLACED
            }
            val shrink = registry.shrinkClaimWithEvidence(platformOwner, claimOwner, eligible)
            val mutation = shrink.mutation
            val pendingDeltaKeptClaimAlive = queueCompleted &&
                mutation.status == InfrastructureMutationStatus.REPLACED && shrink.pendingAppendOnly
            if (mutation.status != expectedStatus && !pendingDeltaKeptClaimAlive) {
                return@synchronized failClosed(
                    TailReleaseFailureKind.CLAIM_SHRINK_FAILED,
                    mutation.detail.ifBlank {
                        "claim shrink returned ${mutation.status}; expected $expectedStatus"
                    },
                    mutation.status,
                    mutation.successful
                )
            }

            maximumProgress = authoritativeTailRouteProgressMeters
            releasedPrefixMeters = maxOf(releasedPrefixMeters, entries[nextHead - 1].tailClearRouteMeters)
            if (queueCompleted) {
                remainingQueue = null
                headIndex = 0
                return@synchronized LiveClaimTailReleaseResult.Completed(
                    releasedResources = java.util.List.copyOf(eligible),
                    alreadyCompleted = false
                )
            }
            headIndex = nextHead
            LiveClaimTailReleaseResult.Shrunk(
                releasedResources = java.util.List.copyOf(eligible),
                maximumAcceptedTailRouteProgressMeters = authoritativeTailRouteProgressMeters,
                remainingResourceCount = entries.size - headIndex
            )
        }

    private fun failClosed(
        kind: TailReleaseFailureKind,
        detail: String,
        mutationStatus: InfrastructureMutationStatus? = null,
        claimMayHaveChanged: Boolean = false
    ): LiveClaimTailReleaseResult.FailedClosed =
        LiveClaimTailReleaseResult.FailedClosed(
            kind,
            detail,
            mutationStatus,
            claimMayHaveChanged
        ).also {
            terminalFailure = it
        }
}
