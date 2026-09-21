package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.core.api.util.annotation.MainThread

internal enum class ProtectedRouteTailReleaseFailureKind {
    WRONG_WORLD,
    STALE_TOPOLOGY,
    DECREASING_PHYSICS_EPOCH,
    TRAIN_MISSING,
    ROUTE_CURSOR_FAILED,
    TAIL_RELEASE_FAILED
}

internal sealed interface ProtectedRouteTailReleaseUpdate {
    /** The same coherent physics epoch was already consumed. */
    data class Unchanged(
        val physicsEpoch: Long,
        val cursorState: RouteProgressCursorState
    ) : ProtectedRouteTailReleaseUpdate

    /** A newer coherent observation advanced or retained the live claim. */
    data class Active(
        val physicsEpoch: Long,
        val cursorState: RouteProgressCursorState,
        val releaseResult: LiveClaimTailReleaseResult
    ) : ProtectedRouteTailReleaseUpdate

    /** The complete claim was released. Later observations remain completed no-ops. */
    data class Completed(
        val physicsEpoch: Long,
        val alreadyCompleted: Boolean
    ) : ProtectedRouteTailReleaseUpdate

    /** The lifecycle is latched fail closed and has stalled its world exactly once. */
    data class FailedClosed(
        val kind: ProtectedRouteTailReleaseFailureKind,
        val detail: String
    ) : ProtectedRouteTailReleaseUpdate {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Internal tail-release controller for one admitted protected route after infrastructure is ready.
 *
 * The controller accepts only coherent observations for the world and topology against which the
 * existing registry claim was compiled. It creates neither a claim nor a footprint: it projects
 * the named train's authoritative tail through the retained route cursor and supplies that
 * monotonic coordinate to [LiveClaimTailRelease], which shrinks that existing claim. There is
 * deliberately no observation subscription here; the future world-local host calls [consume]
 * after publication.
 */
internal class ProtectedRouteTailRelease(
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
    private var terminalFailure: ProtectedRouteTailReleaseUpdate.FailedClosed? = null
    private var completedAtPhysicsEpoch: Long? = null

    init {
        require(worldId.isNotBlank())
        require(topologyRevision >= 0L)
        require(trainId.isNotBlank())
        require(initialPhysicsEpoch >= 0L)
    }

    @MainThread
    fun consume(observation: RailwayObservation): ProtectedRouteTailReleaseUpdate = synchronized(lock) {
        completedAtPhysicsEpoch?.let { completedEpoch ->
            return@synchronized ProtectedRouteTailReleaseUpdate.Completed(completedEpoch, alreadyCompleted = true)
        }
        terminalFailure?.let { return@synchronized it }

        val stamp = observation.stamp
        if (stamp.worldId != worldId) {
            return@synchronized failClosed(
                ProtectedRouteTailReleaseFailureKind.WRONG_WORLD,
                "protected-route tail release belongs to world $worldId, not ${stamp.worldId}"
            )
        }
        if (stamp.topologyRevision != topologyRevision) {
            return@synchronized failClosed(
                ProtectedRouteTailReleaseFailureKind.STALE_TOPOLOGY,
                "protected-route tail release topology $topologyRevision does not match ${stamp.topologyRevision}"
            )
        }
        if (stamp.physicsEpoch < lastPhysicsEpoch) {
            return@synchronized failClosed(
                ProtectedRouteTailReleaseFailureKind.DECREASING_PHYSICS_EPOCH,
                "live protected-route observation epoch decreased from $lastPhysicsEpoch to ${stamp.physicsEpoch}"
            )
        }
        if (stamp.physicsEpoch == lastPhysicsEpoch) {
            return@synchronized ProtectedRouteTailReleaseUpdate.Unchanged(
                stamp.physicsEpoch,
                routeProgressCursor.state
            )
        }

        val train = observation.trains.firstOrNull { it.trainId == trainId }
            ?: return@synchronized failClosed(
                ProtectedRouteTailReleaseFailureKind.TRAIN_MISSING,
                "train $trainId is absent from coherent observation epoch ${stamp.physicsEpoch}"
            )
        val cursorUpdate = try {
            routeProgressCursor.project(train.tail)
        } catch (failure: Exception) {
            return@synchronized failClosed(
                ProtectedRouteTailReleaseFailureKind.ROUTE_CURSOR_FAILED,
                "route cursor threw while projecting train $trainId tail: ${failure.message.orEmpty()}",
                failure
            )
        }
        if (cursorUpdate !is RouteProgressCursorUpdate.Resolved) {
            return@synchronized failClosed(
                ProtectedRouteTailReleaseFailureKind.ROUTE_CURSOR_FAILED,
                "train $trainId tail could not advance on its frozen route: ${cursorUpdate::class.simpleName}"
            )
        }

        val releaseResult = try {
            claimRelease.advance(cursorUpdate.state.routeDistanceMeters)
        } catch (failure: Exception) {
            return@synchronized failClosed(
                ProtectedRouteTailReleaseFailureKind.TAIL_RELEASE_FAILED,
                "tail release threw for train $trainId: ${failure.message.orEmpty()}",
                failure
            )
        }
        if (releaseResult is LiveClaimTailReleaseResult.FailedClosed) {
            return@synchronized failClosed(
                ProtectedRouteTailReleaseFailureKind.TAIL_RELEASE_FAILED,
                "tail release failed closed for train $trainId: ${releaseResult.detail}"
            )
        }

        try {
            routeProgressCursor.commit(cursorUpdate)
        } catch (failure: Exception) {
            return@synchronized failClosed(
                ProtectedRouteTailReleaseFailureKind.ROUTE_CURSOR_FAILED,
                "route cursor could not commit after tail release for train $trainId: " +
                    failure.message.orEmpty(),
                failure
            )
        }
        lastPhysicsEpoch = stamp.physicsEpoch
        if (releaseResult is LiveClaimTailReleaseResult.Completed) {
            completedAtPhysicsEpoch = stamp.physicsEpoch
            return@synchronized ProtectedRouteTailReleaseUpdate.Completed(
                stamp.physicsEpoch,
                alreadyCompleted = false
            )
        }
        ProtectedRouteTailReleaseUpdate.Active(stamp.physicsEpoch, cursorUpdate.state, releaseResult)
    }

    private fun failClosed(
        kind: ProtectedRouteTailReleaseFailureKind,
        detail: String,
        failure: Exception = IllegalStateException(detail)
    ): ProtectedRouteTailReleaseUpdate.FailedClosed {
        terminalFailure?.let { return it }
        val failed = ProtectedRouteTailReleaseUpdate.FailedClosed(kind, detail)
        terminalFailure = failed
        stallWorld(
            "protected-route tail release for train $trainId entered fail-closed recovery: $detail",
            failure
        )
        return failed
    }
}
