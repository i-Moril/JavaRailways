package work.art1st.javarailways.simpleato.control

import work.art1st.javarailways.simpleato.api.atp.AtpTarget
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.constraint.*

/** Both lists use signed distance from the current head, but have distinct purposes. */
data class TrainControlFrame(
    val referenceTargets: List<MotionReferenceConstraint>,
    val protectionTargets: List<AtpTarget>,
    val mostAdverseGrade: Double,
    val unavailableReason: String? = null
)

internal fun controlFrame(constraints: Map<ConstraintOwner, List<AtoConstraint>>,
    profile: AtoSafetyProfile, mostAdverseGrade: Double): TrainControlFrame {
    val reference = mutableListOf<MotionReferenceConstraint>()
    val protection = mutableListOf<AtpTarget>()
    for ((owner, values) in constraints) for (constraint in values) {
        // Owner-scoped keys can contain arbitrary separators. Preserve injective identity.
        val id = listOf(owner.type.name, owner.id, constraint.key.namespace, constraint.key.value)
            .joinToString("") { "${it.length}:$it" }
        val target = when (constraint) {
            is CurrentSpeedLimitConstraint -> MotionReferenceConstraint(id, 0.0, constraint.maximumSpeedMetersPerSecond)
            is RouteSpeedLimitConstraint -> MotionReferenceConstraint(id, constraint.distanceToStartMeters, constraint.maximumSpeedMetersPerSecond)
            is StopConstraint -> MotionReferenceConstraint(id,
                constraint.remainingDistanceMeters - (constraint.stoppingMarginMeters ?: 0.0), 0.0)
            is MovementAuthorityConstraint -> MotionReferenceConstraint(id,
                constraint.remainingDistanceMeters - (constraint.stoppingMarginMeters ?: profile.safetyDistanceMeters), 0.0)
            is FollowConstraint -> MotionReferenceConstraint(id,
                constraint.gapMeters - profile.safetyDistanceMeters, constraint.leadingSpeedMetersPerSecond)
            is DepartureHoldConstraint -> MotionReferenceConstraint(id, 0.0, 0.0)
        }
        reference += target
        when (constraint) {
            is StopConstraint -> if (!constraint.operatingTarget)
                protection += AtpTarget(id, constraint.remainingDistanceMeters, 0.0)
            is MovementAuthorityConstraint -> protection += AtpTarget(id, constraint.remainingDistanceMeters, 0.0)
            is FollowConstraint -> protection += AtpTarget(id, target.distanceMeters, 0.0)
            else -> protection += AtpTarget(id, target.distanceMeters, target.targetSpeedMetersPerSecond)
        }
    }
    return TrainControlFrame(java.util.List.copyOf(reference), java.util.List.copyOf(protection), mostAdverseGrade)
}
