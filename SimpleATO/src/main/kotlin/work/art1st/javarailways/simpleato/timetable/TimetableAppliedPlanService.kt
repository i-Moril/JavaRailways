package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationResult
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner

/** World-local Basic dependency registry used by the timetable application lifecycle. */
interface TimetableAppliedPlanService {
    fun set(request: AppliedPlanDependencyRequest): AppliedPlanDependencyMutationResult

    fun remove(owner: InfrastructureOwner): AppliedPlanDependencyMutationResult
}
