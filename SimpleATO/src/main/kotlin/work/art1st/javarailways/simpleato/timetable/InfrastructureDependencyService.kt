package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner

/** World-local Basic registry for non-exclusive edit dependencies. */
interface InfrastructureDependencyService {
    fun set(request: InfrastructureDependencyRequest): InfrastructureDependencyMutationResult

    fun remove(owner: InfrastructureOwner): InfrastructureDependencyMutationResult
}
