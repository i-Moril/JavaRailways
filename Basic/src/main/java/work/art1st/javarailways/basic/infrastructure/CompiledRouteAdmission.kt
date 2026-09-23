package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.AdditionalResourceRequirement
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
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

/** Provider-owned preparation data, retained only after the addition has been validated. */
internal data class ResolvedAddedResourcePreparation(
    override val resource: StableInfrastructureResource,
    val descriptor: ResourcePreparationDescriptor
) : InfrastructurePreparationRequirement

internal sealed interface RouteAdditionsCompilation {
    data class Compiled(val admission: CompiledRouteAdmission) : RouteAdditionsCompilation
    data class Rejected(val failure: RouteAdmissionFailure) : RouteAdditionsCompilation
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
     * Adds validated caller resources to this already compiled physical floor before any claim.
     * [validate] is supplied by the world-local resource owner registry and must check the
     * authenticated caller, exact identity, and optional preparation descriptor without mutation.
     * A duplicate physical identity may delay release, but cannot replace physical preparation.
     */
    fun withAdditions(
        additions: Collection<AdditionalResourceRequirement>,
        validate: (AdditionalResourceRequirement) -> RouteAdmissionFailure?
    ): RouteAdditionsCompilation {
        if (additions.isEmpty()) return RouteAdditionsCompilation.Compiled(this)
        val originalResources = resources.resources.toSet()
        val seen = HashSet<StableInfrastructureResource>()
        val added = ArrayList<StableInfrastructureResource>()
        val addedPreparations = ArrayList<InfrastructurePreparationRequirement>()
        val releaseByResource = tailReleaseEntries.associateByTo(LinkedHashMap()) { it.resource }
        for (addition in additions) {
            if (!seen.add(addition.resource)) {
                return RouteAdditionsCompilation.Rejected(RouteAdmissionFailure(
                    RouteAdmissionFailureCode.INVALID_ADDITION,
                    "duplicate added resource ${addition.resource.typeId}/${addition.resource.resourceId}"
                ))
            }
            validate(addition)?.let { return RouteAdditionsCompilation.Rejected(it) }
            if (addition.resource in originalResources && addition.preparation != null) {
                return RouteAdditionsCompilation.Rejected(RouteAdmissionFailure(
                    RouteAdmissionFailureCode.INVALID_PREPARATION,
                    "added preparation cannot replace physical preparation for ${addition.resource.resourceId}"
                ))
            }
            val previous = releaseByResource[addition.resource]
            releaseByResource[addition.resource] = ResolvedTailReleaseEntry(
                addition.resource,
                maxOf(previous?.tailClearRouteMeters ?: 0.0,
                    addition.releaseAfterTailPasses.distanceFromRouteStartMeters)
            )
            if (previous == null) added += addition.resource
            addition.preparation?.let { addedPreparations += ResolvedAddedResourcePreparation(addition.resource, it) }
        }
        return RouteAdditionsCompilation.Compiled(create(
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
                routeProgressPath
            )
        }
    }
}
