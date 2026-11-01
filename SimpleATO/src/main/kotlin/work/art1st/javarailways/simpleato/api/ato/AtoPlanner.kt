package work.art1st.javarailways.simpleato.api.ato

import work.art1st.javarailways.basic.api.ato.BrakingConstraints
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import work.art1st.javarailways.simpleato.api.constraint.ConstraintManager

data class AtoPlanningContext(
    val currentSpeedMetersPerSecond: Double,
    val maximumSpeedMetersPerSecond: Double,
    val profile: AtoSafetyProfile,
    val constraints: ConstraintManager,
    val brakingEnvelope: GradeAwareBrakingEnvelope? = null,
    val brakingGradeSegments: List<BrakingGradeSegment> = emptyList(),
    val maximumControlDeltaSeconds: Double? = null
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
        ),
        brakingConstraints = context.maximumControlDeltaSeconds?.let { maximumDelta ->
            if (context.brakingGradeSegments.isEmpty()) null else BrakingConstraints(
                context.brakingGradeSegments,
                context.constraints.snapshot().values.flatten().mapNotNull { it.brakingLimit(context.profile) },
                maximumDelta
            )
        }
    )
}
