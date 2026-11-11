package work.art1st.javarailways.simpleato.admission

import work.art1st.javarailways.basic.api.route.RouteBoundary

/**
 * Chooses the next bounded authority extension on an already frozen route.
 *
 * This is deliberately stateless. The admission client remains the sole owner of committed and
 * pending Basic state; the Dispatcher remains the owner of retry timing. The planner only applies
 * a high/low-water rule: request up to the current-motion window ahead after the remaining
 * committed authority falls to the stable allocation trigger distance. Perception can look much
 * farther without reserving those resources. A minimum advance avoids tiny requests while the
 * current-motion window grows; an unchanged stopped train cannot grow its grant repeatedly.
 */
internal object RollingRouteAuthorityPlanner {
    fun nextBoundary(
        routeLengthMeters: Double,
        headProgressMeters: Double,
        requestHorizonMeters: Double,
        extensionTriggerMeters: Double,
        admissionState: RouteAuthorityAdmissionState
    ): RouteBoundary? {
        require(routeLengthMeters.isFinite() && routeLengthMeters >= 0.0)
        require(headProgressMeters.isFinite() && headProgressMeters >= 0.0)
        require(requestHorizonMeters.isFinite() && requestHorizonMeters >= 0.0)
        require(extensionTriggerMeters.isFinite() && extensionTriggerMeters >= 0.0)
        require(headProgressMeters <= routeLengthMeters + POSITION_TOLERANCE_METERS) {
            "head progress exceeds the frozen route"
        }
        if (admissionState.pending || admissionState.reversalPending) return null

        val committed = admissionState.committedAuthority?.distanceFromRouteStartMeters
        if (committed != null) {
            require(committed <= routeLengthMeters + POSITION_TOLERANCE_METERS) {
                "committed authority exceeds the frozen route"
            }
            if (committed - headProgressMeters > extensionTriggerMeters) return null
        }

        val desired = (headProgressMeters + requestHorizonMeters)
            .coerceAtMost(routeLengthMeters)
        if (desired <= (committed ?: 0.0) + EPSILON_METERS) return null
        // Do not strand the final short suffix (including a terminal stopping margin) behind
        // the batching threshold. First admission and a reversal's body-only grant also need
        // no special retry path: the ordinary startup window advances beyond the current head.
        if (committed != null && desired < routeLengthMeters - EPSILON_METERS &&
            desired - committed < MINIMUM_EXTENSION_METERS - EPSILON_METERS
        ) return null
        return RouteBoundary(desired)
    }

    private const val MINIMUM_EXTENSION_METERS = 5.0
    private const val EPSILON_METERS = 1.0e-6
    private const val POSITION_TOLERANCE_METERS = work.art1st.javarailways.basic.api.ato.AtoSpatialPolicy.POSITION_TOLERANCE_METERS
}
