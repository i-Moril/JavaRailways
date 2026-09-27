package work.art1st.javarailways.basic.api.ato

import work.art1st.javarailways.basic.api.ato.constraint.ConstraintManager

data class AtoPlanningContext(
    val currentSpeedMetersPerSecond: Double,
    val maximumSpeedMetersPerSecond: Double,
    val profile: AtoSafetyProfile,
    val constraints: ConstraintManager,
    val brakingEnvelope: GradeAwareBrakingEnvelope? = null
)

fun interface AtoPlanner {
    fun plan(context: AtoPlanningContext): ControlDemand
}

/** Selects the minimum speed permitted by all current constraints. */
object MinimumConstraintPlanner : AtoPlanner {
    override fun plan(context: AtoPlanningContext): ControlDemand = ControlDemand(
        context.constraints.permittedSpeedMetersPerSecond(
            currentSpeedMetersPerSecond = context.currentSpeedMetersPerSecond,
            maximumSpeedMetersPerSecond = context.maximumSpeedMetersPerSecond,
            profile = context.profile,
            brakingEnvelope = context.brakingEnvelope
        )
    )
}
