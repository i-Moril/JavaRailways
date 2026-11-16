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
 * Actual bounded transient before pneumatic service force is guaranteed. No device buffers escape.
 * @property distanceMeters Upper distance travelled during the response, including discrete stop reservation.
 * @property speedGainMetersPerSecond Upper speed increase above the captured initial speed, in m/s.
 * @property durationSeconds Response interval in seconds, including the actual first control step.
 * @property proven Whether the bounded model established the response; false must not authorize traction.
 */
data class BrakingResponse internal constructor(val distanceMeters: Double, val speedGainMetersPerSecond: Double,
    val durationSeconds: Double, val proven: Boolean) {
    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        require(speedGainMetersPerSecond.isFinite() && speedGainMetersPerSecond >= 0.0)
        require(durationSeconds.isFinite() && durationSeconds >= 0.0)
    }
}

/**
 * Bounded grade - aware service - braking work accumulated once over the current forward perception.
 * Queries are O(log n) and do not rescan the route or simulate handle candidates.
 */
class GradeAwareBrakingEnvelope private constructor(
    private val entries: List<Entry>,
    val totalDistanceMeters: Double,
    private val firstUnbrakeableDistanceMeters: Double?,
    private val accelerationReleaseSeconds: Double,
    private val accelerationReleaseSpeedGain: Double,
    private val accelerationReleaseExtraDistance: Double,
    private val response: BrakingResponse?
) {
    private data class Entry(
        val endDistanceMeters: Double,
        val startWork: Double,
        val decelerationMetersPerSecondSquared: Double
    )

    /**
     * Includes nominal brake - response time and unwinding this frame's positive acceleration.
     * Geometry - only consumers retain the old steady - state query when construction also omits
     * current acceleration
     a captured positive acceleration still tightens queries with defaults.
     * Distances are metres, speeds m / s, response time seconds.
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
        if (response?.proven == false) return 0.0
        val prefix = response
        val speedGain = prefix?.speedGainMetersPerSecond ?: accelerationReleaseSpeedGain
        // The bounded prefix already contains command, cutout, air build and jerk. Explicit
        // additional configured delay only reserves the portion not covered by that prefix.
        val extraDelay = if (prefix == null)reactionTimeSeconds else (reactionTimeSeconds - prefix.durationSeconds).coerceAtLeast(0.0)
        val reactionDistance = if (prefix != null)prefix.distanceMeters + (currentSpeedMetersPerSecond + speedGain) * extraDelay
            else currentSpeedMetersPerSecond * (reactionTimeSeconds + accelerationReleaseSeconds)+
                accelerationReleaseExtraDistance + accelerationReleaseSpeedGain * reactionTimeSeconds
        val start = reactionDistance.coerceIn(0.0, totalDistanceMeters)
        val end = distanceMeters.coerceIn(0.0, totalDistanceMeters)
        if (end <= start || entries.isEmpty()) return (targetSpeedMetersPerSecond - speedGain).coerceAtLeast(0.0)
        // Braking begins AFTER the response prefix. Subtract cumulative work at that physical
        // position instead of sliding uphill work from an already traversed slice to the future.
        val work = (workAt(end) - workAt(start)).coerceAtLeast(0.0)
        return (sqrt((targetSpeedMetersPerSecond * targetSpeedMetersPerSecond + work).coerceAtLeast(0.0)) - speedGain).coerceAtLeast(0.0)
    }

    /** Checks the captured actual speed, including targets inside the response interval. */
    fun admitsSpeed(
        currentSpeedMetersPerSecond: Double,
        limit: BrakingLimit
    ): Boolean {
        val prefix = response
        if (prefix != null) {
            if (!prefix.proven) return false
            val peak = currentSpeedMetersPerSecond + prefix.speedGainMetersPerSecond
            val extraDelay = (limit.reactionTimeSeconds - prefix.durationSeconds).coerceAtLeast(0.0)
            val responseDistance = prefix.distanceMeters + peak * extraDelay
            // A clamped permitted - speed result of zero is not permission for a zero - speed train
            // to begin a power pulse that crosses a nearby stopping target.
            if (limit.distanceMeters < responseDistance && peak > limit.targetSpeedMetersPerSecond) return false
        }
        return currentSpeedMetersPerSecond <= permittedSpeedMetersPerSecond(
            limit.targetSpeedMetersPerSecond,
            limit.distanceMeters,
            currentSpeedMetersPerSecond,
            limit.reactionTimeSeconds
        )
    }

    private fun workAt(distance: Double): Double {
        val index = entries.binarySearch { if (it.endDistanceMeters<distance)-1 else 1 }
            .let { if (it >= 0) it else (-it-1).coerceAtMost(entries.lastIndex) }
        val entry = entries[index]
        val start = if (index == 0) 0.0 else entries[index-1].endDistanceMeters
        return entry.startWork + 2.0 * entry.decelerationMetersPerSecondSquared * (distance - start).coerceAtLeast(0.0)
    }

    companion object {
        /**
         * Deterministic constant - deceleration envelope for actuators that model braking without
         * per - car dynamics (for example the kinematic test actuator).
         */
        fun constant(
            decelerationMetersPerSecondSquared: Double,
            segments: List<BrakingGradeSegment>
        ): GradeAwareBrakingEnvelope {
            require(decelerationMetersPerSecondSquared.isFinite() && decelerationMetersPerSecondSquared > 0.0)
            require(segments.isNotEmpty())
            val entries = ArrayList<Entry>(segments.size)
            var distance = 0.0
            var work = 0.0
            segments.forEach { segment -> entries += Entry(distance + segment.lengthMeters, work, decelerationMetersPerSecondSquared)
                work += 2.0 * decelerationMetersPerSecondSquared * segment.lengthMeters
                distance += segment.lengthMeters
            }
            return GradeAwareBrakingEnvelope(entries, distance, null, 0.0, 0.0, 0.0, null)
        }

        /**
         * Builds a grade-work envelope from the guaranteed pneumatic service-force floor.
         * Runtime actuators supply a captured equipment [response]; geometry-only callers can
         * instead provide current acceleration and reserve their own explicit reaction time.
         */
        fun fromDynamics(
            trainProfile: TrainDynamicsProfile,
            carProfiles: List<CarDynamicsProfile>,
            gravityMetersPerSecondSquared: Double,
            segments: List<BrakingGradeSegment>,
            currentAccelerationMetersPerSecondSquared: Double = 0.0,
            response: BrakingResponse? = null
        ): GradeAwareBrakingEnvelope {
            require(carProfiles.isNotEmpty())
            require(segments.isNotEmpty())
            require(currentAccelerationMetersPerSecondSquared.isFinite())
            val brakeForce = TrainDynamics.steadyServiceBrakeForce(
                trainProfile,
                carProfiles,
                gravityMetersPerSecondSquared
            )
            val effectiveMass = TrainDynamics.totalEffectiveMass(carProfiles)
            val physicalMass = carProfiles.sumOf(CarDynamicsProfile::mass)
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
            return GradeAwareBrakingEnvelope(entries, distance, firstUnbrakeable, releaseSeconds,
                acceleration * releaseSeconds / 2.0, acceleration * releaseSeconds * releaseSeconds / 3.0, response)
        }
    }
}
