package work.art1st.javarailways.simpleato.api.simulation

import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.FormationRouteContext
import work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.RailOperatingDirections
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import java.util.UUID

/** Detached definitions captured from a source world; each installation must create fresh state. */
interface PreparedSimulationFeature {
    /** The host's plugin owner identity, using the same lowercase name as its domain registrations. */
    val id: String

    /** Installs synchronously before physics starts; the result is closed after all workers exit. */
    fun install(target: SimulationDomainTarget): AutoCloseable
}

/**
 * Concrete domain endpoints for one simulation, without platform or live-world access.
 * Installations run synchronously under the target owner before physics starts. Returned tokens
 * release only their registration after all target physics/generic workers have terminated (also
 * on partial installation failure); they are not an online source-unload API. No method dispatches
 * Bukkit effects. Every feature must release partially installed registrations if its own install fails.
 * Source IDs, provider type IDs and contributor IDs must be unique within the target; conflicting or
 * invalid registrations fail without replacing an existing registration. Installation may compile
 * target definitions on the simulation worker; runtime callbacks must obey their existing bounded,
 * synchronous domain contracts and must not wait for a source-world/platform task.
 */
interface SimulationDomainTarget {
    /** Target Core runtime UUID, distinct from the captured live world and all other simulations. */
    val worldId: UUID
    /** Exact immutable topology root against which target definitions must be compiled. */
    val topology: TopologyState

    /** Captures one Basic-owned direction subset; null direction permits both facings. */
    fun operatingDirections(railIds: Collection<UUID>): RailOperatingDirections

    /** Static effective limits in m/s; a present null means no CURVE or SUBGRADE limit. */
    fun railSpeedLimits(railIds: Collection<UUID>): Map<UUID, Double?>

    /** Adds one complete source group to this target's shared real signal-epoch runtime. */
    fun installSignalSources(sources: Collection<SignalEpochSource>): AutoCloseable
    /** Adds target-local inspection callbacks; retirement discards their active route bindings. */
    fun installInspectionSources(sources: Collection<InspectionSource>): AutoCloseable
    /** Adds target-local planning restrictions without replacing other features' groups. */
    fun installRoutePlanningSources(sources: Collection<RoutePlanningRestrictionBinding>): AutoCloseable
    /** Registers the target-local policy for one stable resource type. */
    fun registerResourceProvider(typeId: String, provider: SimulationResourceProvider): AutoCloseable
    /** Atomically registers contribution and explicit formation opt-in under one identity. */
    fun registerFormationRouteContributor(
        id: String, collect: (RouteResourceIntent) -> List<ContributedRouteResource>,
        authorize: (FormationRouteContext, RouteResourceIntent) -> RouteAdmissionFailure?
    ): AutoCloseable
    /** Adds bounded, deterministic policy contributions to real Basic route admission. */
    fun registerRouteContributor(
        id: String,
        collect: (RouteResourceIntent) -> List<ContributedRouteResource>
    ): AutoCloseable
}

/** A target-local stable-resource policy using the same Basic admission/preparation contracts. */
interface SimulationResourceProvider {
    /** Whether this policy contains the exact stable identity. */
    fun exists(resource: StableInfrastructureResource): Boolean
    /** Validates the proposed policy resource before any claim mutation; null accepts it. */
    fun validate(contributorId: String, contribution: ContributedRouteResource): RouteAdmissionFailure?
    /** Exact provider-owned clearance, not a peer-occupancy or signal exception. */
    fun authorizeFormationResource(resource: StableInfrastructureResource, context: FormationRouteContext,
        intent: RouteResourceIntent): FormationRoutePolicyResult = FormationRoutePolicyResult.Rejected(
            RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "simulation provider does not authorize formation routes"))
    /** Resolves an optional target-local preparation factory using the normal Basic ticket lifecycle. */
    fun prepare(
        resource: StableInfrastructureResource,
        descriptor: ResourcePreparationDescriptor
    ): (() -> ResourcePreparationTicket)?
}
