package work.art1st.javarailways.basic.api.ato.constraint

import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
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

    fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile
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
        profile: AtoSafetyProfile
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
        profile: AtoSafetyProfile
    ): Double = speedForTarget(
        targetSpeed = maximumSpeedMetersPerSecond,
        distance = distanceToStartMeters - currentSpeedMetersPerSecond * profile.reactionTimeSeconds,
        deceleration = profile.serviceDecelerationMetersPerSecondSquared
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
        profile: AtoSafetyProfile
    ): Double = speedForTarget(
        targetSpeed = 0.0,
        distance = remainingDistanceMeters -
            currentSpeedMetersPerSecond * profile.reactionTimeSeconds -
            (stoppingMarginMeters ?: profile.stopToleranceMeters),
        deceleration = profile.serviceDecelerationMetersPerSecondSquared
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
        profile: AtoSafetyProfile
    ): Double = speedForTarget(
        targetSpeed = leadingSpeedMetersPerSecond,
        distance = gapMeters - profile.safetyDistanceMeters -
            currentSpeedMetersPerSecond * profile.reactionTimeSeconds,
        deceleration = profile.serviceDecelerationMetersPerSecondSquared
    )
}

data class MovementAuthorityConstraint(
    override val key: ConstraintKey,
    val remainingDistanceMeters: Double
) : AtoConstraint {
    init {
        require(remainingDistanceMeters >= 0.0 && remainingDistanceMeters.isFinite())
    }

    override fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile
    ): Double = speedForTarget(
        targetSpeed = 0.0,
        distance = remainingDistanceMeters - profile.safetyDistanceMeters -
            currentSpeedMetersPerSecond * profile.reactionTimeSeconds,
        deceleration = profile.serviceDecelerationMetersPerSecondSquared
    )
}

data class DepartureHoldConstraint(
    override val key: ConstraintKey
) : AtoConstraint {
    override fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile
    ): Double = 0.0
}

private fun speedForTarget(targetSpeed: Double, distance: Double, deceleration: Double): Double =
    sqrt(targetSpeed * targetSpeed + 2.0 * deceleration * distance.coerceAtLeast(0.0))
