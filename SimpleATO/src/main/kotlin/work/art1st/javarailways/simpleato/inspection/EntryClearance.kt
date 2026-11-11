package work.art1st.javarailways.simpleato.inspection

import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.constraint.*
import work.art1st.javarailways.simpleato.api.inspection.InspectionEntryClearance
import work.art1st.javarailways.simpleato.api.inspection.InspectionEntryClearanceState
import kotlin.math.abs

/** Preserve ordered occurrences, including repeated rails and coupling gaps. Empty geometry is no proof. */
internal fun conservativeTrainLengthMeters(body: List<RailTraversalSegmentSnapshot>): Double? =
    body.takeIf { it.isNotEmpty() }?.sumOf { abs(it.endArcLengthInAbMeters - it.startArcLengthInAbMeters) }
        ?.takeIf { it.isFinite() && it > 0.0 }

/** Identity, epoch and geometry are read from one immutable published frame, never mixed. */
internal fun currentTrainLengthMeters(frame: TrainFrameSnapshot?, trainId: String, physicsEpoch: Long): Double? =
    frame?.takeIf { it.trainId == trainId && !it.derailed && it.physicsEpoch == physicsEpoch }
        ?.let { conservativeTrainLengthMeters(it.bodyTraversal) }

/** Route-owned conservative envelope absorbs placement tolerance, not real consist changes. */
internal class ConservativeTrainLengthEnvelope {
    private var cached: Double? = null
    private var latestEpoch = -1L
    fun observe(raw: Double?, physicsEpoch: Long): Double? {
        if (raw == null || !raw.isFinite() || raw <= 0.0) return null
        return synchronized(this) {
            val previous = cached
            if (previous == null || raw > previous ||
                physicsEpoch >= latestEpoch && previous - raw >= 3.0 * GUARD_METERS) {
                val raised = kotlin.math.ceil(raw / GUARD_METERS) * GUARD_METERS + GUARD_METERS
                if (!raised.isFinite() || raised < raw) return@synchronized null
                cached = raised
            }
            latestEpoch = maxOf(latestEpoch, physicsEpoch)
            cached
        }
    }
    companion object {
        private const val GUARD_METERS = 2.0 * Curve.TOLERANCE
    }
}

internal data class BoundEntryClearance(val sourceId: String, val requirement: InspectionEntryClearance)

/** Low-frequency compiled entrance index; the physics worker queries only its forward window. */
internal class CompiledEntryClearances(requirements: Collection<BoundEntryClearance>) {
    private val sorted = requirements.sortedBy { it.requirement.entryRouteDistanceMeters }
    val sourceIds: Set<String> = java.util.Set.copyOf(sorted.map { it.sourceId })
    fun between(head: Double, end: Double, trainLengthMeters: Double? = null, marginMeters: Double = 0.0): List<BoundEntryClearance> {
        fun bound(value: Double, inclusive: Boolean): Int {
            var low = 0
            var high = sorted.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (sorted[middle].requirement.entryRouteDistanceMeters < value ||
                    inclusive && sorted[middle].requirement.entryRouteDistanceMeters == value) low = middle + 1
                else high = middle
            }
            return low
        }
        val start = bound(head, false)
        var included = bound(end, true)
        var expandedEnd = end
        var index = start
        while (index < included) {
            val target = sorted[index++].requirement.tailClearRouteDistanceMeters + (trainLengthMeters ?: 0.0) + marginMeters
            if (target.isFinite() && target > expandedEnd) {
                expandedEnd = target
                while (included < sorted.size && sorted[included].requirement.entryRouteDistanceMeters < expandedEnd)
                    included++
            }
        }
        return sorted.subList(start, included)
    }
}

/** One local entrance-to-clearance proof against current constraints, including all authority bounds. */
internal fun entryClearanceState(
    requirement: InspectionEntryClearance,
    trainLengthMeters: Double?,
    routeProgressMeters: Double,
    routeLengthMeters: Double,
    observedDistanceMeters: Double,
    profile: AtoSafetyProfile,
    constraints: Collection<AtoConstraint>
): InspectionEntryClearanceState {
    val headClear = trainLengthMeters?.let { requirement.tailClearRouteDistanceMeters + it }
        ?.takeIf { it.isFinite() && it > requirement.tailClearRouteDistanceMeters }
    val authorityEnd = headClear?.let { it + profile.safetyDistanceMeters }?.takeIf(Double::isFinite)
    val remaining = (headClear ?: requirement.tailClearRouteDistanceMeters) - routeProgressMeters
    val entryRemaining = requirement.entryRouteDistanceMeters - routeProgressMeters
    fun stopsInside(boundary: Double): Boolean = boundary >= entryRemaining - EPSILON && boundary < remaining - EPSILON
    val ready = headClear != null && authorityEnd != null && authorityEnd <= routeLengthMeters &&
        remaining <= observedDistanceMeters - profile.stopToleranceMeters + EPSILON &&
        constraints.none { constraint ->
            val exactSelfRed = constraint is RouteSpeedLimitConstraint &&
                abs(constraint.distanceToStartMeters - entryRemaining) <= EPSILON &&
                requirement.ignoredEntryRestrictions.any {
                    it.sourceId == constraint.key.namespace && it.restrictionId == constraint.key.value
                }
            if (exactSelfRed) false else when (constraint) {
                is MovementAuthorityConstraint ->
                    constraint.remainingDistanceMeters - (constraint.stoppingMarginMeters ?: profile.safetyDistanceMeters) < remaining - EPSILON
                is StopConstraint -> {
                    // Arrival tolerance is uncertainty of the accepted position, never an aim offset.
                    // A window straddling the entry can still leave the train stopped inside it.
                    val aim = constraint.remainingDistanceMeters - (constraint.stoppingMarginMeters ?: 0.0)
                    val latest = aim + if (constraint.operatingTarget) profile.stopToleranceMeters else 0.0
                    latest >= entryRemaining - EPSILON && aim - profile.stopToleranceMeters < remaining - EPSILON
                }
                is FollowConstraint -> stopsInside(constraint.gapMeters - profile.safetyDistanceMeters)
                is RouteSpeedLimitConstraint -> constraint.maximumSpeedMetersPerSecond == 0.0 &&
                    stopsInside(constraint.distanceToStartMeters)
                is CurrentSpeedLimitConstraint -> constraint.maximumSpeedMetersPerSecond == 0.0
                is DepartureHoldConstraint -> true
            }
        }
    return InspectionEntryClearanceState(requirement.declarationId, requirement.entryRouteDistanceMeters,
        requirement.tailClearRouteDistanceMeters, headClear ?: requirement.tailClearRouteDistanceMeters,
        authorityEnd ?: headClear ?: requirement.tailClearRouteDistanceMeters, ready)
}

private const val EPSILON = 1.0e-9
