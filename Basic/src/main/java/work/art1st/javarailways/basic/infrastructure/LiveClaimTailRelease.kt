package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource

internal enum class TailReleaseFailureKind {
    INVALID_PROGRESS,
    DECREASING_PROGRESS,
    CLAIM_SHRINK_FAILED,
    CLAIM_APPEND_FAILED
}

internal sealed interface LiveClaimAppendResult {
    data class Reserved(val resourceCount: Int) : LiveClaimAppendResult

    data class Finalized(val remainingResourceCount: Int) : LiveClaimAppendResult

    data class RolledBack(val completed: Boolean) : LiveClaimAppendResult

    data class Rejected(
        val detail: String,
        val mutationStatus: InfrastructureMutationStatus? = null
    ) : LiveClaimAppendResult {
        init {
            require(detail.isNotBlank())
        }
    }

    data class FailedClosed(val failure: LiveClaimTailReleaseResult.FailedClosed) :
        LiveClaimAppendResult
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
     * Every established entry has cleared, but a reserved delta is not finalized yet.
     *
     * This is an active lifecycle state, not completion. [releasedResources] is non-empty only on
     * the advance that cleared the final established prefix; later advances report an empty list.
     */
    data class AwaitingAppend(
        val releasedResources: List<InfrastructureResource>,
        val maximumAcceptedTailRouteProgressMeters: Double,
        val pendingResourceCount: Int
    ) : LiveClaimTailReleaseResult {
        init {
            require(pendingResourceCount > 0)
        }
    }

    /** Every queued resource has been released. Further calls remain completed no-ops. */
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
 * resource identities. It also owns at most one append reservation: reserved delta resources stay
 * outside the releasable queue until registry finalization succeeds, while established prefixes may
 * continue shrinking. Local queue/progress state changes only after the registry reports the exact
 * expected success status. Invalid progress and unexpected registry results latch the lifecycle
 * fail-closed; future grant integration is responsible for turning that result into a world Stall.
 */
internal class LiveClaimTailRelease(
    private val registry: InfrastructureLeaseRegistry,
    private val platformOwner: Any,
    private val claimOwner: InfrastructureOwner,
    queue: CompiledTailReleaseQueue
) {
    private data class PendingAppend(
        val queue: CompiledTailReleaseQueue,
        val reservation: InfrastructureLeaseRegistry.ClaimAppendReservation
    )

    private val lock = Any()
    private var remainingQueue: CompiledTailReleaseQueue? = queue
    private var pendingAppend: PendingAppend? = null
    private var maximumProgress: Double? = null
    private var terminalFailure: LiveClaimTailReleaseResult.FailedClosed? = null

    val remainingEntries: List<ResolvedTailReleaseEntry>
        get() = synchronized(lock) {
            remainingQueue?.entries ?: emptyList()
        }

    val maximumAcceptedTailRouteProgressMeters: Double?
        get() = synchronized(lock) { maximumProgress }

    fun beginAppend(
        topologyRevision: Long,
        deltaEntries: Collection<ResolvedTailReleaseEntry>
    ): LiveClaimAppendResult = synchronized(lock) {
        terminalFailure?.let { return@synchronized LiveClaimAppendResult.FailedClosed(it) }
        if (pendingAppend != null) {
            return@synchronized LiveClaimAppendResult.Rejected("a claim append is already pending")
        }

        val delta = deltaEntries.toList()
        validateAppendOrder(delta)?.let { detail ->
            return@synchronized LiveClaimAppendResult.Rejected(detail)
        }
        val deltaQueue = CompiledTailReleaseQueue(delta)
        val reservation = registry.beginAppendClaim(
            platformOwner,
            claimOwner,
            topologyRevision,
            delta.map(ResolvedTailReleaseEntry::resource)
        )
        if (reservation.result.status != InfrastructureMutationStatus.REPLACED) {
            if (reservation.result.successful) {
                return@synchronized LiveClaimAppendResult.FailedClosed(
                    failClosed(
                        TailReleaseFailureKind.CLAIM_APPEND_FAILED,
                        "claim append reservation returned ${reservation.result.status}; expected REPLACED",
                        reservation.result.status,
                        claimMayHaveChanged = true
                    )
                )
            }
            return@synchronized LiveClaimAppendResult.Rejected(
                reservation.result.detail.ifBlank {
                    "claim append reservation was rejected: ${reservation.result.status}"
                },
                reservation.result.status
            )
        }
        if (reservation.token == null) {
            return@synchronized LiveClaimAppendResult.FailedClosed(
                failClosed(
                    TailReleaseFailureKind.CLAIM_APPEND_FAILED,
                    "claim append reservation succeeded without a rollback token",
                    reservation.result.status,
                    claimMayHaveChanged = true
                )
            )
        }

        pendingAppend = PendingAppend(deltaQueue, reservation)
        LiveClaimAppendResult.Reserved(delta.size)
    }

    fun finalizeAppend(): LiveClaimAppendResult = synchronized(lock) {
        terminalFailure?.let { return@synchronized LiveClaimAppendResult.FailedClosed(it) }
        val pending = pendingAppend
            ?: return@synchronized LiveClaimAppendResult.Rejected("no claim append is pending")
        val combinedEntries = remainingQueue?.entries.orEmpty() + pending.queue.entries
        val combinedQueue = CompiledTailReleaseQueue(combinedEntries)
        if (!registry.completeClaimAppend(pending.reservation)) {
            return@synchronized LiveClaimAppendResult.FailedClosed(
                failClosed(
                    TailReleaseFailureKind.CLAIM_APPEND_FAILED,
                    "pending claim append could not be finalized"
                )
            )
        }

        remainingQueue = combinedQueue
        pendingAppend = null
        LiveClaimAppendResult.Finalized(combinedEntries.size)
    }

    fun rollbackAppend(): LiveClaimAppendResult = synchronized(lock) {
        terminalFailure?.let { return@synchronized LiveClaimAppendResult.FailedClosed(it) }
        val pending = pendingAppend
            ?: return@synchronized LiveClaimAppendResult.Rejected("no claim append is pending")
        val expectedStatus = if (remainingQueue == null) {
            InfrastructureMutationStatus.RELEASED
        } else {
            InfrastructureMutationStatus.REPLACED
        }
        val rollback = registry.rollbackClaimAppend(pending.reservation)
        if (rollback.status != expectedStatus) {
            return@synchronized LiveClaimAppendResult.FailedClosed(
                failClosed(
                    TailReleaseFailureKind.CLAIM_APPEND_FAILED,
                    rollback.detail.ifBlank {
                        "claim append rollback returned ${rollback.status}; expected $expectedStatus"
                    },
                    rollback.status,
                    rollback.successful
                )
            )
        }

        pendingAppend = null
        LiveClaimAppendResult.RolledBack(completed = remainingQueue == null)
    }

    fun advance(authoritativeTailRouteProgressMeters: Double): LiveClaimTailReleaseResult =
        synchronized(lock) {
            terminalFailure?.let { return@synchronized it }
            val queue = remainingQueue
            if (queue == null && pendingAppend == null) {
                return@synchronized LiveClaimTailReleaseResult.Completed(
                    releasedResources = emptyList(),
                    alreadyCompleted = true
                )
            }

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

            if (queue == null) {
                maximumProgress = authoritativeTailRouteProgressMeters
                return@synchronized LiveClaimTailReleaseResult.AwaitingAppend(
                    releasedResources = emptyList(),
                    maximumAcceptedTailRouteProgressMeters = authoritativeTailRouteProgressMeters,
                    pendingResourceCount = checkNotNull(pendingAppend).queue.entries.size
                )
            }

            val inspection = queue.inspectEligiblePrefix(authoritativeTailRouteProgressMeters)
            if (inspection is TailReleaseInspectionResult.Rejected) {
                return@synchronized failClosed(
                    TailReleaseFailureKind.INVALID_PROGRESS,
                    inspection.detail
                )
            }
            val eligible = (inspection as TailReleaseInspectionResult.Eligible).resources
            if (eligible.isEmpty()) {
                maximumProgress = authoritativeTailRouteProgressMeters
                return@synchronized LiveClaimTailReleaseResult.Retained(
                    maximumAcceptedTailRouteProgressMeters = authoritativeTailRouteProgressMeters,
                    remainingResourceCount = queue.entries.size
                )
            }

            val remainingEntries = queue.entries.drop(eligible.size)
            val expectedStatus = if (remainingEntries.isEmpty() && pendingAppend == null) {
                InfrastructureMutationStatus.RELEASED
            } else {
                InfrastructureMutationStatus.REPLACED
            }
            val mutation = registry.shrinkClaim(platformOwner, claimOwner, eligible)
            if (mutation.status != expectedStatus) {
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
            if (remainingEntries.isEmpty()) {
                remainingQueue = null
                pendingAppend?.let { pending ->
                    return@synchronized LiveClaimTailReleaseResult.AwaitingAppend(
                        releasedResources = eligible,
                        maximumAcceptedTailRouteProgressMeters = authoritativeTailRouteProgressMeters,
                        pendingResourceCount = pending.queue.entries.size
                    )
                }
                return@synchronized LiveClaimTailReleaseResult.Completed(
                    releasedResources = eligible,
                    alreadyCompleted = false
                )
            }
            remainingQueue = CompiledTailReleaseQueue(remainingEntries)
            LiveClaimTailReleaseResult.Shrunk(
                releasedResources = eligible,
                maximumAcceptedTailRouteProgressMeters = authoritativeTailRouteProgressMeters,
                remainingResourceCount = remainingEntries.size
            )
        }

    private fun validateAppendOrder(delta: List<ResolvedTailReleaseEntry>): String? {
        if (delta.isEmpty()) return "claim append delta must not be empty"
        if (delta.map(ResolvedTailReleaseEntry::resource).distinct().size != delta.size) {
            return "claim append delta contains duplicate resources"
        }
        if (!delta.zipWithNext().all { (first, second) ->
                TAIL_RELEASE_ENTRY_ORDER.compare(first, second) <= 0
            }
        ) {
            return "claim append delta must use deterministic compiled tail-clear order"
        }
        val established = remainingQueue?.entries.orEmpty()
        val establishedResources = established.mapTo(hashSetOf(), ResolvedTailReleaseEntry::resource)
        val repeated = delta.firstOrNull { it.resource in establishedResources }
        if (repeated != null) {
            return "claim append delta repeats established resource: ${repeated.resource}"
        }
        val establishedTail = established.lastOrNull()
        if (establishedTail != null && TAIL_RELEASE_ENTRY_ORDER.compare(establishedTail, delta.first()) > 0) {
            return "claim append delta precedes the established queue tail"
        }
        val acceptedProgress = maximumProgress
        if (establishedTail == null && acceptedProgress != null &&
            delta.first().tailClearRouteMeters <= acceptedProgress
        ) {
            return "claim append delta starts at ${delta.first().tailClearRouteMeters}, " +
                "not after accepted tail progress $acceptedProgress"
        }
        return null
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
