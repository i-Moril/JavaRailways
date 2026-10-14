package work.art1st.javarailways.basic.api.ato

import work.art1st.javarailways.basic.control.TrainDynamics
import kotlin.math.sqrt

/** One forward route slice. [grade] is sin(theta), positive uphill in the operating direction. */
data class BrakingGradeSegment(
    val lengthMeters: Double,
    val grade: Double
) {
    init {
        require(lengthMeters.isFinite() && lengthMeters > 0.0)
        require(grade.isFinite())
    }
}

/**
 * Bounded grade-aware service-braking work accumulated once over the current forward perception.
 * Queries are O(log n) and do not rescan the route or simulate handle candidates.
 */
class GradeAwareBrakingEnvelope private constructor(
    private val entries: List<Entry>,
    val totalDistanceMeters: Double,
    private val firstUnbrakeableDistanceMeters: Double?,
    private val accelerationReleaseSeconds: Double,
    private val accelerationReleaseSpeedGain: Double,
    private val accelerationReleaseExtraDistance: Double
) {
    private data class Entry(
        val endDistanceMeters: Double,
        val startWork: Double,
        val decelerationMetersPerSecondSquared: Double
    )

    /**
     * Includes nominal brake-response time and unwinding this frame's positive acceleration.
     * Geometry-only consumers retain the old steady-state query when construction also omits
     * current acceleration; a captured positive acceleration still tightens queries with defaults.
     * Distances are metres, speeds m/s, response time seconds.
     */
    fun permittedSpeedMetersPerSecond(
        targetSpeedMetersPerSecond: Double,
        distanceMeters: Double,
        currentSpeedMetersPerSecond: Double = 0.0,
        reactionTimeSeconds: Double = 0.0
    ): Double {
        require(targetSpeedMetersPerSecond.isFinite() && targetSpeedMetersPerSecond >= 0.0)
        require(distanceMeters.isFinite())
        require(currentSpeedMetersPerSecond.isFinite() && currentSpeedMetersPerSecond >= 0.0)
        require(reactionTimeSeconds.isFinite() && reactionTimeSeconds >= 0.0)
        // Additional unwind reservation must not hide an unbrakeable slice from the target range.
        val steadyDistance = (distanceMeters - currentSpeedMetersPerSecond * reactionTimeSeconds)
            .coerceIn(0.0, totalDistanceMeters)
        if (firstUnbrakeableDistanceMeters != null && steadyDistance > firstUnbrakeableDistanceMeters) {
            return 0.0
        }
        // First unwind existing positive acceleration under the same jerk as real dynamics.
        // The resulting extra speed also persists through the nominal brake-response delay.
        val reactionDistance = currentSpeedMetersPerSecond *
            (reactionTimeSeconds + accelerationReleaseSeconds) + accelerationReleaseExtraDistance +
            accelerationReleaseSpeedGain * reactionTimeSeconds
        val distance = (distanceMeters - reactionDistance).coerceIn(0.0, totalDistanceMeters)
        if (distance == 0.0 || entries.isEmpty()) {
            return (targetSpeedMetersPerSecond - accelerationReleaseSpeedGain).coerceAtLeast(0.0)
        }
        val index = entries.binarySearch { entry ->
            when {
                entry.endDistanceMeters < distance -> -1
                else -> 1
            }
        }.let { if (it >= 0) it else (-it - 1).coerceAtMost(entries.lastIndex) }
        val entry = entries[index]
        val startDistance = if (index == 0) 0.0 else entries[index - 1].endDistanceMeters
        val work = entry.startWork + 2.0 * entry.decelerationMetersPerSecondSquared *
            (distance - startDistance).coerceAtLeast(0.0)
        return (sqrt((targetSpeedMetersPerSecond * targetSpeedMetersPerSecond + work)
            .coerceAtLeast(0.0)) - accelerationReleaseSpeedGain).coerceAtLeast(0.0)
    }

    companion object {
        fun fromDynamics(
            trainProfile: TrainDynamics.TrainProfile,
            carProfiles: List<TrainDynamics.CarProfile>,
            gravityMetersPerSecondSquared: Double,
            segments: List<BrakingGradeSegment>,
            currentAccelerationMetersPerSecondSquared: Double = 0.0
        ): GradeAwareBrakingEnvelope {
            require(carProfiles.isNotEmpty())
            require(segments.isNotEmpty())
            require(currentAccelerationMetersPerSecondSquared.isFinite())
            val brakeForce = TrainDynamics.steadyServiceBrakeForce(
                trainProfile,
                carProfiles,
                gravityMetersPerSecondSquared
            )
            val effectiveMass = carProfiles.sumOf(TrainDynamics.CarProfile::effectiveMass)
                .coerceAtLeast(1.0)
            val physicalMass = carProfiles.sumOf(TrainDynamics.CarProfile::mass)
            val entries = ArrayList<Entry>(segments.size)
            var distance = 0.0
            var work = 0.0
            var firstUnbrakeable: Double? = null
            segments.forEach { segment ->
                val gradeForce = physicalMass * gravityMetersPerSecondSquared *
                    segment.grade.coerceIn(-1.0, 1.0)
                val deceleration = (brakeForce + gradeForce) / effectiveMass
                if (deceleration <= 0.0 && firstUnbrakeable == null) {
                    firstUnbrakeable = distance
                }
                entries += Entry(distance + segment.lengthMeters, work, deceleration.coerceAtLeast(0.0))
                if (deceleration > 0.0) work += 2.0 * deceleration * segment.lengthMeters
                distance += segment.lengthMeters
            }
            val acceleration = currentAccelerationMetersPerSecondSquared.coerceAtLeast(0.0)
            val releaseSeconds = if (trainProfile.brakeJerk > 0.0) acceleration / trainProfile.brakeJerk else 0.0
            return GradeAwareBrakingEnvelope(entries, distance, firstUnbrakeable,
                releaseSeconds, acceleration * releaseSeconds / 2.0,
                acceleration * releaseSeconds * releaseSeconds / 3.0)
        }
    }
}
