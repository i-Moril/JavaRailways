package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

/** Immutable diagnostic state for one route-progress cursor. */
internal data class RouteProgressCursorState(
    val occurrenceIndex: Int,
    val routeDistanceMeters: Double
) {
    init {
        require(occurrenceIndex >= 0)
        require(routeDistanceMeters.isFinite() && routeDistanceMeters >= 0.0)
    }
}

/** Result of binding one authoritative physical tail to the frozen-route start. */
internal sealed interface RouteProgressCursorInitialization {
    data class Initialized(val cursor: RouteProgressCursor) : RouteProgressCursorInitialization

    data object PositionDoesNotMatchRouteStart : RouteProgressCursorInitialization
}

/** Result of moving an initialized cursor with one authoritative physical position. */
internal sealed interface RouteProgressCursorUpdate {
    data class Resolved(val state: RouteProgressCursorState) : RouteProgressCursorUpdate

    /** A matching position would move the accepted route coordinate backward. */
    data class NonMonotonicProgress(
        val currentState: RouteProgressCursorState,
        val attemptedRouteDistanceMeters: Double
    ) : RouteProgressCursorUpdate

    /** The position matched neither the current occurrence nor any later route occurrence. */
    data class PositionNotOnCurrentOrForwardPath(
        val currentState: RouteProgressCursorState
    ) : RouteProgressCursorUpdate
}

/**
 * Immutable ordered physical path compiled once from a validated frozen route.
 *
 * A future grant owner initializes a cursor only when the authoritative physical tail matches the
 * first occurrence's route start. The cursor therefore begins conservatively at route distance zero
 * and never trusts a caller-owned occurrence index or progress coordinate.
 */
internal class CompiledRouteProgressPath private constructor(
    internal val occurrences: List<RouteProgressOccurrence>
) {
    val occurrenceCount: Int
        get() = occurrences.size

    fun initializeCursor(position: RailPositionSnapshot): RouteProgressCursorInitialization {
        val projectedDistance = occurrences.first().project(position)
        if (projectedDistance == null || projectedDistance > Curve.TOLERANCE) {
            return RouteProgressCursorInitialization.PositionDoesNotMatchRouteStart
        }
        return RouteProgressCursorInitialization.Initialized(
            RouteProgressCursor(occurrences, occurrenceIndex = 0, routeDistanceMeters = 0.0)
        )
    }

    companion object {
        fun compile(route: ProjectedTopologyRoute): CompiledRouteProgressPath =
            CompiledRouteProgressPath(
                java.util.List.copyOf(
                    route.occurrences.map { occurrence ->
                        RouteProgressOccurrence(
                            railId = occurrence.segment.railId,
                            facing = occurrence.segment.facing,
                            canonicalStartAbMeters = occurrence.canonicalStartAbMeters,
                            canonicalEndAbMeters = occurrence.canonicalEndAbMeters,
                            routeStartMeters = occurrence.routeStartMeters,
                            lengthMeters = occurrence.segment.lengthMeters
                        )
                    }
                )
            )
    }
}

/** Stateful, grant-owned cursor that can only move forward through one compiled path. */
internal class RouteProgressCursor internal constructor(
    occurrences: List<RouteProgressOccurrence>,
    occurrenceIndex: Int,
    routeDistanceMeters: Double
) {
    private var occurrences: List<RouteProgressOccurrence> = occurrences
    private var currentOccurrenceIndex = occurrenceIndex
    private var currentRouteDistanceMeters = routeDistanceMeters
    private var latchedFailure: RouteProgressCursorUpdate? = null

    val state: RouteProgressCursorState
        get() = RouteProgressCursorState(currentOccurrenceIndex, currentRouteDistanceMeters)

    /** Installs an append while preserving accepted cursor progress. */
    fun installExtension(path: CompiledRouteProgressPath) {
        require(path.occurrences.size > occurrences.size) {
            "route progress extension must append at least one occurrence"
        }
        require(path.occurrences.subList(0, occurrences.size) == occurrences) {
            "route progress extension changed the committed prefix"
        }
        occurrences = path.occurrences
    }

    fun update(position: RailPositionSnapshot): RouteProgressCursorUpdate {
        val projected = project(position)
        if (projected is RouteProgressCursorUpdate.Resolved) {
            commit(projected)
        }
        return projected
    }

    /**
     * Resolves [position] without advancing accepted cursor state.
     *
     * This split lets a live claim shrink its registry footprint before committing the matching
     * local route progress. Projection failures still latch immediately because they are terminal
     * frozen-route invariant failures rather than retryable registry outcomes.
     */
    fun project(position: RailPositionSnapshot): RouteProgressCursorUpdate {
        latchedFailure?.let { return it }
        for (candidateIndex in currentOccurrenceIndex until occurrences.size) {
            val routeDistanceMeters = occurrences[candidateIndex].project(position) ?: continue
            if (routeDistanceMeters < currentRouteDistanceMeters) {
                return RouteProgressCursorUpdate.NonMonotonicProgress(
                    currentState = state,
                    attemptedRouteDistanceMeters = routeDistanceMeters
                ).also { latchedFailure = it }
            }
            return RouteProgressCursorUpdate.Resolved(
                RouteProgressCursorState(candidateIndex, routeDistanceMeters)
            )
        }
        return RouteProgressCursorUpdate.PositionNotOnCurrentOrForwardPath(state)
            .also { latchedFailure = it }
    }

    /** Commits a successful [project] result after the caller's authoritative mutation succeeds. */
    fun commit(projected: RouteProgressCursorUpdate.Resolved) {
        check(latchedFailure == null) { "cannot commit a route cursor after a latched failure" }
        check(projected.state.occurrenceIndex >= currentOccurrenceIndex) {
            "route cursor occurrence cannot move backward"
        }
        check(projected.state.routeDistanceMeters >= currentRouteDistanceMeters) {
            "route cursor distance cannot move backward"
        }
        currentOccurrenceIndex = projected.state.occurrenceIndex
        currentRouteDistanceMeters = projected.state.routeDistanceMeters
    }
}

internal data class RouteProgressOccurrence(
    val railId: UUID,
    val facing: ViewDirection,
    val canonicalStartAbMeters: Double,
    val canonicalEndAbMeters: Double,
    val routeStartMeters: Double,
    val lengthMeters: Double
) {
    fun project(position: RailPositionSnapshot): Double? {
        if (position.railId != railId || position.facing != facing) return null
        val minimum = minOf(canonicalStartAbMeters, canonicalEndAbMeters)
        val maximum = maxOf(canonicalStartAbMeters, canonicalEndAbMeters)
        if (position.arcLengthInAbMeters < minimum - Curve.TOLERANCE ||
            position.arcLengthInAbMeters > maximum + Curve.TOLERANCE
        ) {
            return null
        }
        val localOffset = when (facing) {
            ViewDirection.AB -> position.arcLengthInAbMeters - canonicalStartAbMeters
            ViewDirection.BA -> canonicalStartAbMeters - position.arcLengthInAbMeters
        }.coerceIn(0.0, lengthMeters)
        return routeStartMeters + localOffset
    }
}
