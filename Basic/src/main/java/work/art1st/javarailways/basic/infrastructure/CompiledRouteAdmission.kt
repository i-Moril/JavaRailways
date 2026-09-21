package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef

/** One direction-specific turnout operation resolved from the validated frozen route. */
internal data class ResolvedTurnoutSelection(
    val resource: StableInfrastructureResource,
    val fromPort: RailEndpointRef,
    val toPort: RailEndpointRef,
    val routeDistanceMeters: Double
) {
    init {
        require(resource.typeId == TurnoutResourceIdentity.TYPE_ID) {
            "turnout selection must reference a turnout resource"
        }
        require(fromPort != toPort) { "turnout selection must connect two different ports" }
        require(routeDistanceMeters.isFinite() && routeDistanceMeters >= 0.0) {
            "turnout selection route distance must be finite and non-negative"
        }
    }
}

/**
 * Immutable output of one successful frozen-route validation.
 *
 * [resources] is the only footprint submitted to the atomic registry. Turnout selections are
 * ordered operation metadata and [occupancyGeometry] is physical validation metadata derived by
 * the same validation pass; neither is another resource set. They deliberately contain no
 * revision-local topology node identity.
 */
internal class CompiledRouteAdmission private constructor(
    val resources: InfrastructureFootprint,
    val turnoutSelections: List<ResolvedTurnoutSelection>,
    val occupancyGeometry: OccupancyQueryGeometry
) {
    companion object {
        fun create(
            resources: InfrastructureFootprint,
            turnoutSelections: Collection<ResolvedTurnoutSelection>,
            occupancyGeometry: OccupancyQueryGeometry
        ): CompiledRouteAdmission {
            val immutableSelections = java.util.List.copyOf(turnoutSelections)
            val uniqueResourceCount = immutableSelections
                .map(ResolvedTurnoutSelection::resource)
                .distinct()
                .size
            require(uniqueResourceCount == immutableSelections.size) {
                "validated route claim contains duplicate turnout selections"
            }
            require(immutableSelections.all { it.resource in resources.resources }) {
                "every turnout selection must be represented in the claim footprint"
            }
            return CompiledRouteAdmission(resources, immutableSelections, occupancyGeometry)
        }
    }
}
