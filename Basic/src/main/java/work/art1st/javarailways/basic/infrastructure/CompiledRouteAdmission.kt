package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef

/** One ordered physical preparation required before a claimed route can become ready. */
internal interface InfrastructurePreparationRequirement {
    val resource: InfrastructureResource
}

/** One direction-specific turnout preparation resolved from the validated frozen route. */
internal data class ResolvedTurnoutSelection(
    override val resource: StableInfrastructureResource,
    val fromPort: RailEndpointRef,
    val toPort: RailEndpointRef,
    val routeDistanceMeters: Double
) : InfrastructurePreparationRequirement {
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
 * [resources] is the only footprint submitted to the atomic registry. [preparations] is ordered
 * operation metadata, [occupancyGeometry] is admission-time physical validation metadata, and
 * [releaseSlices] is ordered lifecycle metadata derived by the same validation pass; none is
 * another resource set. Preparation requirements use stable claim resource identities and
 * deliberately contain no revision-local topology node identity.
 */
internal class CompiledRouteAdmission private constructor(
    val resources: InfrastructureFootprint,
    val preparations: List<InfrastructurePreparationRequirement>,
    val occupancyGeometry: OccupancyQueryGeometry,
    val releaseSlices: List<ResolvedReleaseSlice>
) {
    companion object {
        fun create(
            resources: InfrastructureFootprint,
            preparations: Collection<InfrastructurePreparationRequirement>,
            occupancyGeometry: OccupancyQueryGeometry,
            releaseSlices: Collection<ResolvedReleaseSlice>
        ): CompiledRouteAdmission {
            val immutablePreparations = java.util.List.copyOf(preparations)
            val immutableReleaseSlices = java.util.List.copyOf(releaseSlices)
            val uniqueResourceCount = immutablePreparations
                .map(InfrastructurePreparationRequirement::resource)
                .distinct()
                .size
            require(uniqueResourceCount == immutablePreparations.size) {
                "validated route claim contains duplicate preparation resources"
            }
            require(immutablePreparations.all { it.resource in resources.resources }) {
                "every infrastructure preparation must be represented in the claim footprint"
            }
            require(immutableReleaseSlices.isNotEmpty()) {
                "validated route claim requires release metadata"
            }
            val releasedResources = immutableReleaseSlices.flatMap(ResolvedReleaseSlice::resources)
            require(releasedResources.size == releasedResources.distinct().size) {
                "each claimed resource must appear in release metadata exactly once"
            }
            require(releasedResources.toSet() == resources.resources.toSet()) {
                "release metadata must cover the complete claim footprint exactly once"
            }
            require(immutableReleaseSlices.zipWithNext().all { (first, second) ->
                first.releaseAfterRouteMeters <= second.releaseAfterRouteMeters
            }) {
                "release slices must be ordered by route progress"
            }
            return CompiledRouteAdmission(
                resources,
                immutablePreparations,
                occupancyGeometry,
                immutableReleaseSlices
            )
        }
    }
}
