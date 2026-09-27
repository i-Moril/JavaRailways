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
    private val firstUnbrakeableDistanceMeters: Double?
) {
    private data class Entry(
        val endDistanceMeters: Double,
        val startWork: Double,
        val decelerationMetersPerSecondSquared: Double
    )

    fun permittedSpeedMetersPerSecond(
        targetSpeedMetersPerSecond: Double,
        distanceMeters: Double
    ): Double {
        require(targetSpeedMetersPerSecond.isFinite() && targetSpeedMetersPerSecond >= 0.0)
        require(distanceMeters.isFinite())
        val distance = distanceMeters.coerceIn(0.0, totalDistanceMeters)
        if (firstUnbrakeableDistanceMeters != null && distance > firstUnbrakeableDistanceMeters) {
            return 0.0
        }
        if (distance == 0.0 || entries.isEmpty()) return targetSpeedMetersPerSecond
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
        return sqrt((targetSpeedMetersPerSecond * targetSpeedMetersPerSecond + work)
            .coerceAtLeast(0.0))
    }

    companion object {
        fun fromDynamics(
            trainProfile: TrainDynamics.TrainProfile,
            carProfiles: List<TrainDynamics.CarProfile>,
            gravityMetersPerSecondSquared: Double,
            segments: List<BrakingGradeSegment>
        ): GradeAwareBrakingEnvelope {
            require(carProfiles.isNotEmpty())
            require(segments.isNotEmpty())
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
            return GradeAwareBrakingEnvelope(entries, distance, firstUnbrakeable)
        }
    }
}
