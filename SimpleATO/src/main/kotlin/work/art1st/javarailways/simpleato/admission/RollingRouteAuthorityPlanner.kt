package work.art1st.javarailways.simpleato.admission

import work.art1st.javarailways.basic.api.route.RouteBoundary

/**
 * Chooses the next bounded authority extension on an already frozen route.
 *
 * This is deliberately stateless. The admission client remains the sole owner of committed and
 * pending Basic state; the Dispatcher remains the owner of retry timing. The planner only applies
 * a high/low-water rule: request up to one perception horizon ahead after the remaining committed
 * authority falls to the allocation trigger distance.
 */
internal object RollingRouteAuthorityPlanner {
    fun nextBoundary(
        routeLengthMeters: Double,
        headProgressMeters: Double,
        perceptionHorizonMeters: Double,
        extensionTriggerMeters: Double,
        admissionState: RouteAuthorityAdmissionState
    ): RouteBoundary? {
        require(routeLengthMeters.isFinite() && routeLengthMeters >= 0.0)
        require(headProgressMeters.isFinite() && headProgressMeters >= 0.0)
        require(perceptionHorizonMeters.isFinite() && perceptionHorizonMeters >= 0.0)
        require(extensionTriggerMeters.isFinite() && extensionTriggerMeters >= 0.0)
        require(headProgressMeters <= routeLengthMeters + POSITION_TOLERANCE_METERS) {
            "head progress exceeds the frozen route"
        }
        if (admissionState.pending) return null

        val committed = admissionState.committedAuthority?.distanceFromRouteStartMeters
        if (committed != null) {
            require(committed <= routeLengthMeters + POSITION_TOLERANCE_METERS) {
                "committed authority exceeds the frozen route"
            }
            if (committed - headProgressMeters > extensionTriggerMeters) return null
        }

        val desired = (headProgressMeters + perceptionHorizonMeters)
            .coerceAtMost(routeLengthMeters)
        if (desired <= (committed ?: 0.0) + EPSILON_METERS) return null
        return RouteBoundary(desired)
    }

    private const val EPSILON_METERS = 1.0e-6
    private const val POSITION_TOLERANCE_METERS = 0.5
}
