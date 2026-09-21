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
 * [tailReleaseEntries] plus [routeProgressIndex] are lifecycle metadata derived by the same
 * validation pass; none is another resource set. Preparation requirements use stable claim
 * resource identities and deliberately contain no revision-local topology node identity.
 */
internal class CompiledRouteAdmission private constructor(
    val resources: InfrastructureFootprint,
    val preparations: List<InfrastructurePreparationRequirement>,
    val occupancyGeometry: OccupancyQueryGeometry,
    val tailReleaseEntries: List<ResolvedTailReleaseEntry>,
    val routeProgressIndex: CompiledRouteProgressIndex
) {
    companion object {
        fun create(
            resources: InfrastructureFootprint,
            preparations: Collection<InfrastructurePreparationRequirement>,
            occupancyGeometry: OccupancyQueryGeometry,
            tailReleaseEntries: Collection<ResolvedTailReleaseEntry>,
            routeProgressIndex: CompiledRouteProgressIndex
        ): CompiledRouteAdmission {
            val immutablePreparations = java.util.List.copyOf(preparations)
            val immutableTailReleaseEntries = java.util.List.copyOf(tailReleaseEntries)
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
            require(immutableTailReleaseEntries.isNotEmpty()) {
                "validated route claim requires release metadata"
            }
            val releasedResources = immutableTailReleaseEntries.map(ResolvedTailReleaseEntry::resource)
            require(releasedResources.size == releasedResources.distinct().size) {
                "each claimed resource must appear in release metadata exactly once"
            }
            require(releasedResources.toSet() == resources.resources.toSet()) {
                "release metadata must cover the complete claim footprint exactly once"
            }
            require(immutableTailReleaseEntries.zipWithNext().all { (first, second) ->
                TAIL_RELEASE_ENTRY_ORDER.compare(first, second) <= 0
            }) {
                "tail release entries must use deterministic compiled tail-clear order"
            }
            return CompiledRouteAdmission(
                resources,
                immutablePreparations,
                occupancyGeometry,
                immutableTailReleaseEntries,
                routeProgressIndex
            )
        }
    }
}
