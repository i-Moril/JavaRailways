package work.art1st.javarailways.simpleato.api.constraint

import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import work.art1st.javarailways.basic.api.ato.BrakingLimit
import kotlin.math.sqrt

data class ConstraintKey(val namespace: String, val value: String) {
    init {
        require(namespace.isNotBlank() && value.isNotBlank())
    }
}

enum class ConstraintOwnerType {
    SYSTEM_SAFETY,
    SIGNALLING,
    OPERATIONS,
    ADVISORY
}

data class ConstraintOwner(val type: ConstraintOwnerType, val id: String) {
    init {
        require(id.isNotBlank())
    }
}

sealed interface AtoConstraint {
    val key: ConstraintKey

    /** The same physical target supplies planning and actual-candidate admission. */
    fun brakingLimit(profile: AtoSafetyProfile): BrakingLimit? = null

    fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile,
        brakingEnvelope: GradeAwareBrakingEnvelope? = null
    ): Double
}

data class CurrentSpeedLimitConstraint(
    override val key: ConstraintKey,
    val maximumSpeedMetersPerSecond: Double
) : AtoConstraint {
    init {
        require(maximumSpeedMetersPerSecond >= 0.0 && maximumSpeedMetersPerSecond.isFinite())
    }

    override fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile,
        brakingEnvelope: GradeAwareBrakingEnvelope?
    ): Double = maximumSpeedMetersPerSecond
}

data class RouteSpeedLimitConstraint(
    override val key: ConstraintKey,
    val distanceToStartMeters: Double,
    val maximumSpeedMetersPerSecond: Double
) : AtoConstraint {
    init {
        require(distanceToStartMeters >= 0.0 && distanceToStartMeters.isFinite())
        require(maximumSpeedMetersPerSecond >= 0.0 && maximumSpeedMetersPerSecond.isFinite())
    }

    override fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile,
        brakingEnvelope: GradeAwareBrakingEnvelope?
    ): Double = brakingLimit(profile).permittedSpeed(currentSpeedMetersPerSecond, profile, brakingEnvelope)

    override fun brakingLimit(profile: AtoSafetyProfile): BrakingLimit = BrakingLimit(
        distanceMeters = (distanceToStartMeters).coerceAtLeast(0.0),
        targetSpeedMetersPerSecond = maximumSpeedMetersPerSecond,
        reactionTimeSeconds = profile.reactionTimeSeconds
    )
}

data class StopConstraint(
    override val key: ConstraintKey,
    val remainingDistanceMeters: Double,
    val stoppingMarginMeters: Double? = null
) : AtoConstraint {
    init {
        require(remainingDistanceMeters >= 0.0 && remainingDistanceMeters.isFinite())
        require(stoppingMarginMeters == null ||
            stoppingMarginMeters >= 0.0 && stoppingMarginMeters.isFinite())
    }

    override fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile,
        brakingEnvelope: GradeAwareBrakingEnvelope?
    ): Double = brakingLimit(profile).permittedSpeed(currentSpeedMetersPerSecond, profile, brakingEnvelope)

    override fun brakingLimit(profile: AtoSafetyProfile): BrakingLimit = BrakingLimit(
        distanceMeters = (remainingDistanceMeters - (stoppingMarginMeters ?: profile.stopToleranceMeters)).coerceAtLeast(0.0),
        targetSpeedMetersPerSecond = 0.0,
        reactionTimeSeconds = profile.reactionTimeSeconds
    )
}

data class FollowConstraint(
    override val key: ConstraintKey,
    val gapMeters: Double,
    val leadingSpeedMetersPerSecond: Double
) : AtoConstraint {
    init {
        require(gapMeters >= 0.0 && gapMeters.isFinite())
        require(leadingSpeedMetersPerSecond >= 0.0 && leadingSpeedMetersPerSecond.isFinite())
    }

    override fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile,
        brakingEnvelope: GradeAwareBrakingEnvelope?
    ): Double = brakingLimit(profile).permittedSpeed(currentSpeedMetersPerSecond, profile, brakingEnvelope)

    override fun brakingLimit(profile: AtoSafetyProfile): BrakingLimit = BrakingLimit(
        distanceMeters = (gapMeters - profile.safetyDistanceMeters).coerceAtLeast(0.0),
        targetSpeedMetersPerSecond = leadingSpeedMetersPerSecond,
        reactionTimeSeconds = profile.reactionTimeSeconds
    )
}

data class MovementAuthorityConstraint(
    override val key: ConstraintKey,
    val remainingDistanceMeters: Double,
    val stoppingMarginMeters: Double? = null
) : AtoConstraint {
    init {
        require(stoppingMarginMeters == null || stoppingMarginMeters.isFinite() && stoppingMarginMeters >= 0.0)
        require(remainingDistanceMeters >= 0.0 && remainingDistanceMeters.isFinite())
    }

    override fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile,
        brakingEnvelope: GradeAwareBrakingEnvelope?
    ): Double = brakingLimit(profile).permittedSpeed(currentSpeedMetersPerSecond, profile, brakingEnvelope)

    override fun brakingLimit(profile: AtoSafetyProfile): BrakingLimit = BrakingLimit(
        distanceMeters = (remainingDistanceMeters - (stoppingMarginMeters ?: profile.safetyDistanceMeters)).coerceAtLeast(0.0),
        targetSpeedMetersPerSecond = 0.0,
        reactionTimeSeconds = profile.reactionTimeSeconds
    )
}

data class DepartureHoldConstraint(
    override val key: ConstraintKey
) : AtoConstraint {
    override fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile,
        brakingEnvelope: GradeAwareBrakingEnvelope?
    ): Double = 0.0
}

private fun BrakingLimit.permittedSpeed(
    currentSpeed: Double,
    profile: AtoSafetyProfile,
    brakingEnvelope: GradeAwareBrakingEnvelope?
): Double = brakingEnvelope?.permittedSpeedMetersPerSecond(targetSpeedMetersPerSecond, distanceMeters, currentSpeed, reactionTimeSeconds)
    ?: sqrt(targetSpeedMetersPerSecond * targetSpeedMetersPerSecond + 2.0 * profile.serviceDecelerationMetersPerSecondSquared *
        (distanceMeters - currentSpeed * reactionTimeSeconds).coerceAtLeast(0.0))
