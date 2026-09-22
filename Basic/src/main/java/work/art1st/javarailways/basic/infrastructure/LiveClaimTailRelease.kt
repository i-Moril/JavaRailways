package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource

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
 * resource identities. It knows only already-committed resources; pending extension reservation and
 * preparation belong to the resource-acquisition/live-grant owner. Local queue/progress state changes
 * only after the registry reports the exact expected success status. Invalid progress and unexpected
 * registry results latch the lifecycle fail-closed; the live-grant owner turns that into a world Stall.
 */
internal class LiveClaimTailRelease(
    private val registry: InfrastructureLeaseRegistry,
    private val platformOwner: Any,
    private val claimOwner: InfrastructureOwner,
    queue: CompiledTailReleaseQueue
) {
    private val lock = Any()
    private var remainingQueue: CompiledTailReleaseQueue? = queue
    private var maximumProgress: Double? = null
    private var terminalFailure: LiveClaimTailReleaseResult.FailedClosed? = null

    val remainingEntries: List<ResolvedTailReleaseEntry>
        get() = synchronized(lock) {
            remainingQueue?.entries ?: emptyList()
        }

    val maximumAcceptedTailRouteProgressMeters: Double?
        get() = synchronized(lock) { maximumProgress }

    val resourceQueueEmpty: Boolean
        get() = synchronized(lock) { remainingQueue == null }

    fun advance(authoritativeTailRouteProgressMeters: Double): LiveClaimTailReleaseResult =
        synchronized(lock) {
            terminalFailure?.let { return@synchronized it }
            val queue = remainingQueue
                ?: return@synchronized LiveClaimTailReleaseResult.Completed(
                    releasedResources = emptyList(),
                    alreadyCompleted = true
                )

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
            val expectedStatus = if (remainingEntries.isEmpty()) {
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
