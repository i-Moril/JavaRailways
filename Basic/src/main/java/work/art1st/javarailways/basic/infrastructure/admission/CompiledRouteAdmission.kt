package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.infrastructure.TurnoutResourceIdentity

/** One ordered physical preparation required before a claimed route can become ready. */
internal sealed interface InfrastructurePreparationRequirement {
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

/** Provider-owned preparation data, retained only after the contribution has been validated. */
internal data class ResolvedContributedResourcePreparation(
    override val resource: StableInfrastructureResource,
    val descriptor: ResourcePreparationDescriptor
) : InfrastructurePreparationRequirement

internal sealed interface RouteContributionsCompilation {
    data class Compiled(val admission: CompiledRouteAdmission) : RouteContributionsCompilation
    data class Rejected(val failure: RouteAdmissionFailure) : RouteContributionsCompilation
}

/**
 * Immutable output of one successful frozen-route validation.
 *
 * [resources] is the only footprint submitted to the atomic registry. [preparations] is ordered
 * operation metadata, [occupancyGeometry] is admission-time physical validation metadata, and
 * [tailReleaseEntries] plus [routeProgressPath] are lifecycle metadata derived by the same
 * validation pass; none is another resource set. Preparation requirements use stable claim
 * resource identities and deliberately contain no revision-local topology node identity.
 */
internal class CompiledRouteAdmission private constructor(
    val resources: InfrastructureFootprint,
    val preparations: List<InfrastructurePreparationRequirement>,
    val occupancyGeometry: OccupancyQueryGeometry,
    val tailReleaseEntries: List<ResolvedTailReleaseEntry>,
    val routeProgressPath: CompiledRouteProgressPath
) {
    /**
     * Adds already validated, unique contributor resources to this compiled physical floor before
     * any claim. The world-local resource owner registry must have checked the authenticated
     * contributor, exact identity, uniqueness, range, and optional preparation descriptor without
     * mutation. A duplicate physical identity may delay release, but cannot replace physical
     * preparation.
     */
    fun withContributions(
        contributions: Collection<ContributedRouteResource>
    ): RouteContributionsCompilation {
        if (contributions.isEmpty()) return RouteContributionsCompilation.Compiled(this)
        val originalResources = resources.resources.toSet()
        val added = ArrayList<StableInfrastructureResource>()
        val addedPreparations = ArrayList<InfrastructurePreparationRequirement>()
        val releaseByResource = tailReleaseEntries.associateByTo(LinkedHashMap()) { it.resource }
        for (contribution in contributions) {
            if (contribution.resource in originalResources && contribution.preparation != null) {
                return RouteContributionsCompilation.Rejected(RouteAdmissionFailure(
                    RouteAdmissionFailureCode.INVALID_PREPARATION,
                    "contributed preparation cannot replace physical preparation for ${contribution.resource.resourceId}"
                ))
            }
            val previous = releaseByResource[contribution.resource]
            releaseByResource[contribution.resource] = ResolvedTailReleaseEntry(
                contribution.resource,
                maxOf(previous?.tailClearRouteMeters ?: 0.0,
                    contribution.releaseAfterTailPasses.distanceFromRouteStartMeters)
            )
            if (previous == null) added += contribution.resource
            contribution.preparation?.let {
                addedPreparations += ResolvedContributedResourcePreparation(contribution.resource, it)
            }
        }
        return RouteContributionsCompilation.Compiled(create(
            InfrastructureFootprint(resources.resources + added.sortedWith(compareBy(
                StableInfrastructureResource::typeId,
                StableInfrastructureResource::resourceId
            ))),
            preparations + addedPreparations,
            occupancyGeometry,
            releaseByResource.values.sortedWith(TAIL_RELEASE_ENTRY_ORDER),
            routeProgressPath
        ))
    }

    companion object {
        fun create(
            resources: InfrastructureFootprint,
            preparations: Collection<InfrastructurePreparationRequirement>,
            occupancyGeometry: OccupancyQueryGeometry,
            tailReleaseEntries: Collection<ResolvedTailReleaseEntry>,
            routeProgressPath: CompiledRouteProgressPath
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
            val claimedResources = resources.resources.toHashSet()
            require(immutablePreparations.all { it.resource in claimedResources }) {
                "every infrastructure preparation must be represented in the claim footprint"
            }
            require(immutableTailReleaseEntries.isNotEmpty()) {
                "validated route claim requires release metadata"
            }
            val releasedResources = immutableTailReleaseEntries.map(ResolvedTailReleaseEntry::resource)
            require(releasedResources.size == releasedResources.distinct().size) {
                "each claimed resource must appear in release metadata exactly once"
            }
            require(releasedResources.toSet() == claimedResources) {
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
                routeProgressPath
            )
        }
    }
}
