package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.core.api.util.annotation.MainThread

internal enum class RouteGrantTailReleaseFailureKind {
    WRONG_WORLD,
    STALE_TOPOLOGY,
    DECREASING_PHYSICS_EPOCH,
    TRAIN_MISSING,
    ROUTE_CURSOR_FAILED,
    TAIL_RELEASE_FAILED
}

internal sealed interface RouteGrantTailReleaseUpdate {
    /** The same coherent physics epoch was already consumed. */
    data class Unchanged(
        val physicsEpoch: Long,
        val cursorState: RouteProgressCursorState
    ) : RouteGrantTailReleaseUpdate

    /** A newer coherent observation advanced or retained the live claim. */
    data class Active(
        val physicsEpoch: Long,
        val cursorState: RouteProgressCursorState,
        val releaseResult: LiveClaimTailReleaseResult
    ) : RouteGrantTailReleaseUpdate

    /** The current committed resource queue is empty; the live-grant cursor remains active. */
    data class Completed(
        val physicsEpoch: Long,
        val alreadyCompleted: Boolean
    ) : RouteGrantTailReleaseUpdate

    /** The lifecycle is latched fail closed and has stalled its world exactly once. */
    data class FailedClosed(
        val kind: RouteGrantTailReleaseFailureKind,
        val detail: String
    ) : RouteGrantTailReleaseUpdate {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Internal tail-release controller for one admitted route grant after infrastructure is ready.
 *
 * The world-local service resolves the named train once per observation dispatch and supplies the
 * coherent stamp plus that exact frame. This controller still verifies world, topology, epoch and
 * train identity before projecting the authoritative tail. It creates neither a claim nor a
 * footprint: [LiveClaimTailRelease] only shrinks the current committed registry resources. After
 * that queue empties, this controller keeps projecting and committing the same frozen-route cursor
 * without touching the registry so its live grant can later accept another committed suffix.
 */
internal class RouteGrantTailRelease(
    private val worldId: String,
    private val topologyRevision: Long,
    private val trainId: String,
    initialPhysicsEpoch: Long,
    private val routeProgressCursor: RouteProgressCursor,
    private val claimRelease: LiveClaimTailRelease,
    private val stallWorld: (String, Exception) -> Unit
) {
    private val lock = Any()
    private var lastPhysicsEpoch = initialPhysicsEpoch
    private var terminalFailure: RouteGrantTailReleaseUpdate.FailedClosed? = null

    val resourceQueueEmpty: Boolean
        get() = synchronized(lock) { claimRelease.resourceQueueEmpty }

    val remainingEntries: List<ResolvedTailReleaseEntry>
        get() = synchronized(lock) { claimRelease.remainingEntries }

    val acceptedTailProgressMeters: Double?
        get() = synchronized(lock) { claimRelease.maximumAcceptedTailRouteProgressMeters }

    @MainThread
    fun validateExtendedHolds(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        check(terminalFailure == null)
        claimRelease.validateExtendedHolds(entries)
    }

    @MainThread
    fun stageExtendedHolds(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        check(terminalFailure == null)
        claimRelease.stageExtendedHolds(entries)
    }

    @MainThread
    fun validateExtendedSuffix(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        check(terminalFailure == null)
        claimRelease.validateExtendedSuffix(entries)
    }

    @MainThread
    fun installExtendedSuffix(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        check(terminalFailure == null)
        claimRelease.installExtendedSuffix(entries)
    }

    @MainThread
    fun rollbackExtendedHolds(): LiveClaimTailReleaseResult? = synchronized(lock) {
        check(terminalFailure == null)
        claimRelease.rollbackExtendedHolds()
    }

    /** Installs an already-committed and physically-ready suffix without replacing the cursor. */
    @MainThread
    fun installCommittedSuffix(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        check(terminalFailure == null) { "cannot install a committed suffix after fail-closed recovery" }
        claimRelease.installCommittedSuffix(entries)
    }

    @MainThread
    fun validateCommittedSuffix(entries: Collection<ResolvedTailReleaseEntry>) = synchronized(lock) {
        check(terminalFailure == null) { "cannot validate a committed suffix after fail-closed recovery" }
        claimRelease.validateCommittedSuffix(entries)
    }

    init {
        require(worldId.isNotBlank())
        require(topologyRevision >= 0L)
        require(trainId.isNotBlank())
        require(initialPhysicsEpoch >= 0L)
    }

    @MainThread
    fun consume(
        stamp: RailwayStateStamp,
        train: RailwayTrainObservation?
    ): RouteGrantTailReleaseUpdate = synchronized(lock) {
        terminalFailure?.let { return@synchronized it }

        if (stamp.worldId != worldId) {
            return@synchronized failClosed(
                RouteGrantTailReleaseFailureKind.WRONG_WORLD,
                "route-grant tail release belongs to world $worldId, not ${stamp.worldId}"
            )
        }
        if (stamp.topologyRevision != topologyRevision) {
            return@synchronized failClosed(
                RouteGrantTailReleaseFailureKind.STALE_TOPOLOGY,
                "route-grant tail release topology $topologyRevision does not match ${stamp.topologyRevision}"
            )
        }
        if (stamp.physicsEpoch < lastPhysicsEpoch) {
            return@synchronized failClosed(
                RouteGrantTailReleaseFailureKind.DECREASING_PHYSICS_EPOCH,
                "live route-grant observation epoch decreased from $lastPhysicsEpoch to ${stamp.physicsEpoch}"
            )
        }
        if (stamp.physicsEpoch == lastPhysicsEpoch) {
            return@synchronized RouteGrantTailReleaseUpdate.Unchanged(
                stamp.physicsEpoch,
                routeProgressCursor.state
            )
        }

        if (train == null || train.trainId != trainId) {
            return@synchronized failClosed(
                RouteGrantTailReleaseFailureKind.TRAIN_MISSING,
                "train $trainId is absent from coherent observation epoch ${stamp.physicsEpoch}"
            )
        }
        val cursorUpdate = try {
            routeProgressCursor.project(train.tail)
        } catch (failure: Exception) {
            return@synchronized failClosed(
                RouteGrantTailReleaseFailureKind.ROUTE_CURSOR_FAILED,
                "route cursor threw while projecting train $trainId tail: ${failure.message.orEmpty()}",
                failure
            )
        }
        if (cursorUpdate !is RouteProgressCursorUpdate.Resolved) {
            return@synchronized failClosed(
                RouteGrantTailReleaseFailureKind.ROUTE_CURSOR_FAILED,
                "train $trainId tail could not advance on its frozen route: ${cursorUpdate::class.simpleName}"
            )
        }

        val releaseResult = try {
            claimRelease.advance(cursorUpdate.state.routeDistanceMeters)
        } catch (failure: Exception) {
            return@synchronized failClosed(
                RouteGrantTailReleaseFailureKind.TAIL_RELEASE_FAILED,
                "tail release threw for train $trainId: ${failure.message.orEmpty()}",
                failure
            )
        }
        if (releaseResult is LiveClaimTailReleaseResult.FailedClosed) {
            return@synchronized failClosed(
                RouteGrantTailReleaseFailureKind.TAIL_RELEASE_FAILED,
                "tail release failed closed for train $trainId: ${releaseResult.detail}"
            )
        }

        try {
            routeProgressCursor.commit(cursorUpdate)
        } catch (failure: Exception) {
            return@synchronized failClosed(
                RouteGrantTailReleaseFailureKind.ROUTE_CURSOR_FAILED,
                "route cursor could not commit after tail release for train $trainId: " +
                    failure.message.orEmpty(),
                failure
            )
        }
        lastPhysicsEpoch = stamp.physicsEpoch
        if (releaseResult is LiveClaimTailReleaseResult.Completed) {
            return@synchronized RouteGrantTailReleaseUpdate.Completed(
                stamp.physicsEpoch,
                alreadyCompleted = releaseResult.alreadyCompleted
            )
        }
        RouteGrantTailReleaseUpdate.Active(stamp.physicsEpoch, cursorUpdate.state, releaseResult)
    }

    private fun failClosed(
        kind: RouteGrantTailReleaseFailureKind,
        detail: String,
        failure: Exception = IllegalStateException(detail)
    ): RouteGrantTailReleaseUpdate.FailedClosed {
        terminalFailure?.let { return it }
        val failed = RouteGrantTailReleaseUpdate.FailedClosed(kind, detail)
        terminalFailure = failed
        stallWorld(
            "route-grant tail release for train $trainId entered fail-closed recovery: $detail",
            failure
        )
        return failed
    }
}
