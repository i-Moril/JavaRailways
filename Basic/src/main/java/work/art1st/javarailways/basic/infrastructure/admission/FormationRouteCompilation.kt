package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.infrastructure.ProjectedTopologyRoute
import work.art1st.javarailways.core.api.physics.rail.RailInterval

/** One shared physical/compiler/provider validation, with movement occupancy kept explicit. */
internal sealed interface FormationRouteCompilation {
    data class Rejected(val failure: RouteAdmissionFailure) : FormationRouteCompilation
    class Compiled(
        val intent: RouteResourceIntent,
        val observation: RailwayObservation,
        val train: RailwayTrainObservation,
        val projection: ProjectedTopologyRoute,
        val admission: CompiledRouteAdmission,
        val observedHeadMeters: Double,
        movementOccupancy: Collection<RailInterval>
    ) : FormationRouteCompilation {
        val movementOccupancy: List<RailInterval> = java.util.List.copyOf(movementOccupancy)
    }
}
