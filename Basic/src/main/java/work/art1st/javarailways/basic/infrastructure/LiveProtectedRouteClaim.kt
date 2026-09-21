package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.core.api.util.annotation.MainThread

internal enum class LiveProtectedRouteClaimFailureKind {
    WRONG_WORLD,
    STALE_TOPOLOGY,
    DECREASING_PHYSICS_EPOCH,
    TRAIN_MISSING,
    ROUTE_CURSOR_FAILED,
    TAIL_RELEASE_FAILED
}

internal sealed interface LiveProtectedRouteClaimUpdate {
    /** The same coherent physics epoch was already consumed. */
    data class Unchanged(
        val physicsEpoch: Long,
        val cursorState: RouteProgressCursorState
    ) : LiveProtectedRouteClaimUpdate

    /** A newer coherent observation advanced or retained the live claim. */
    data class Active(
        val physicsEpoch: Long,
        val cursorState: RouteProgressCursorState,
        val tailRelease: LiveClaimTailReleaseResult
    ) : LiveProtectedRouteClaimUpdate

    /** The complete claim was released. Later observations remain completed no-ops. */
    data class Completed(
        val physicsEpoch: Long,
        val alreadyCompleted: Boolean
    ) : LiveProtectedRouteClaimUpdate

    /** The lifecycle is latched fail closed and has stalled its world exactly once. */
    data class FailedClosed(
        val kind: LiveProtectedRouteClaimFailureKind,
        val detail: String
    ) : LiveProtectedRouteClaimUpdate {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Internal owner of one admitted protected-route claim after infrastructure is ready.
 *
 * The owner accepts only coherent observations for the world and topology against which the claim
 * was compiled. It projects the named train's authoritative tail through the retained route cursor
 * and supplies that monotonic coordinate to [LiveClaimTailRelease]. There is deliberately no
 * observation subscription here; the future world-local host calls [consume] after publication.
 */
internal class LiveProtectedRouteClaim(
    private val worldId: String,
    private val topologyRevision: Long,
    private val trainId: String,
    initialPhysicsEpoch: Long,
    private val routeProgressCursor: RouteProgressCursor,
    private val tailRelease: LiveClaimTailRelease,
    private val stallWorld: (String, Exception) -> Unit
) {
    private val lock = Any()
    private var lastPhysicsEpoch = initialPhysicsEpoch
    private var terminalFailure: LiveProtectedRouteClaimUpdate.FailedClosed? = null
    private var completedAtPhysicsEpoch: Long? = null

    init {
        require(worldId.isNotBlank())
        require(topologyRevision >= 0L)
        require(trainId.isNotBlank())
        require(initialPhysicsEpoch >= 0L)
    }

    @MainThread
    fun consume(observation: RailwayObservation): LiveProtectedRouteClaimUpdate = synchronized(lock) {
        completedAtPhysicsEpoch?.let { completedEpoch ->
            return@synchronized LiveProtectedRouteClaimUpdate.Completed(completedEpoch, alreadyCompleted = true)
        }
        terminalFailure?.let { return@synchronized it }

        val stamp = observation.stamp
        if (stamp.worldId != worldId) {
            return@synchronized failClosed(
                LiveProtectedRouteClaimFailureKind.WRONG_WORLD,
                "live protected-route claim belongs to world $worldId, not ${stamp.worldId}"
            )
        }
        if (stamp.topologyRevision != topologyRevision) {
            return@synchronized failClosed(
                LiveProtectedRouteClaimFailureKind.STALE_TOPOLOGY,
                "live protected-route claim topology $topologyRevision does not match ${stamp.topologyRevision}"
            )
        }
        if (stamp.physicsEpoch < lastPhysicsEpoch) {
            return@synchronized failClosed(
                LiveProtectedRouteClaimFailureKind.DECREASING_PHYSICS_EPOCH,
                "live protected-route observation epoch decreased from $lastPhysicsEpoch to ${stamp.physicsEpoch}"
            )
        }
        if (stamp.physicsEpoch == lastPhysicsEpoch) {
            return@synchronized LiveProtectedRouteClaimUpdate.Unchanged(
                stamp.physicsEpoch,
                routeProgressCursor.state
            )
        }

        val train = observation.trains.firstOrNull { it.trainId == trainId }
            ?: return@synchronized failClosed(
                LiveProtectedRouteClaimFailureKind.TRAIN_MISSING,
                "train $trainId is absent from coherent observation epoch ${stamp.physicsEpoch}"
            )
        val cursorUpdate = try {
            routeProgressCursor.project(train.tail)
        } catch (failure: Exception) {
            return@synchronized failClosed(
                LiveProtectedRouteClaimFailureKind.ROUTE_CURSOR_FAILED,
                "route cursor threw while projecting train $trainId tail: ${failure.message.orEmpty()}",
                failure
            )
        }
        if (cursorUpdate !is RouteProgressCursorUpdate.Resolved) {
            return@synchronized failClosed(
                LiveProtectedRouteClaimFailureKind.ROUTE_CURSOR_FAILED,
                "train $trainId tail could not advance on its frozen route: ${cursorUpdate::class.simpleName}"
            )
        }

        val releaseResult = try {
            tailRelease.advance(cursorUpdate.state.routeDistanceMeters)
        } catch (failure: Exception) {
            return@synchronized failClosed(
                LiveProtectedRouteClaimFailureKind.TAIL_RELEASE_FAILED,
                "tail release threw for train $trainId: ${failure.message.orEmpty()}",
                failure
            )
        }
        if (releaseResult is LiveClaimTailReleaseResult.FailedClosed) {
            return@synchronized failClosed(
                LiveProtectedRouteClaimFailureKind.TAIL_RELEASE_FAILED,
                "tail release failed closed for train $trainId: ${releaseResult.detail}"
            )
        }

        try {
            routeProgressCursor.commit(cursorUpdate)
        } catch (failure: Exception) {
            return@synchronized failClosed(
                LiveProtectedRouteClaimFailureKind.ROUTE_CURSOR_FAILED,
                "route cursor could not commit after tail release for train $trainId: " +
                    failure.message.orEmpty(),
                failure
            )
        }
        lastPhysicsEpoch = stamp.physicsEpoch
        if (releaseResult is LiveClaimTailReleaseResult.Completed) {
            completedAtPhysicsEpoch = stamp.physicsEpoch
            return@synchronized LiveProtectedRouteClaimUpdate.Completed(
                stamp.physicsEpoch,
                alreadyCompleted = false
            )
        }
        LiveProtectedRouteClaimUpdate.Active(stamp.physicsEpoch, cursorUpdate.state, releaseResult)
    }

    private fun failClosed(
        kind: LiveProtectedRouteClaimFailureKind,
        detail: String,
        failure: Exception = IllegalStateException(detail)
    ): LiveProtectedRouteClaimUpdate.FailedClosed {
        terminalFailure?.let { return it }
        val failed = LiveProtectedRouteClaimUpdate.FailedClosed(kind, detail)
        terminalFailure = failed
        stallWorld(
            "live protected-route claim for train $trainId entered fail-closed recovery: $detail",
            failure
        )
        return failed
    }
}
