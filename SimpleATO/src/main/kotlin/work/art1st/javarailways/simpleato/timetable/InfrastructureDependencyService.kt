package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner

/** World-local Basic registry for non-exclusive edit dependencies. */
interface InfrastructureDependencyService {
    fun set(request: InfrastructureDependencyRequest): InfrastructureDependencyMutationResult

    fun remove(owner: InfrastructureOwner): InfrastructureDependencyMutationResult
}
