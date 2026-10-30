package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.FormationRouteContext
import work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature
import work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget
import work.art1st.javarailways.simpleato.api.simulation.SimulationResourceProvider
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_RESOURCE_TYPE
import work.art1st.javarailways.simplesignals.block.FixedBlockDomainResourcePolicy
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.signalpack.SignalPackEpochRuntime
import work.art1st.javarailways.simplesignals.signalpack.SignalRuleCatalog
import work.art1st.javarailways.simplesignals.signalpack.SignalTypeBinding
import java.util.concurrent.atomic.AtomicBoolean

/** Detached authored values; no live publication, services, model assets or script runtime are retained. */
internal class SimpleSignalsSimulationFeature(
    override val id: String,
    private val definitions: SimpleSignalsWorldDefinitions,
    private val rules: SignalRuleCatalog,
    private val protectionRegionMaxDistanceMeters: Double
) : PreparedSimulationFeature {
    override fun install(target: SimulationDomainTarget): AutoCloseable {
        val runtime = SimpleSignalsWorldRuntime(target.worldId, protectionRegionMaxDistanceMeters)
        val registrations = arrayListOf<AutoCloseable>()
        try {
            val copied = SimpleSignalsWorldDefinitions(target.worldId, definitions.definitionRevision,
                definitions.regions, definitions.routeTables, definitions.fixedBlocks, definitions.fixedBlockSignals,
                definitions.protectionSignals, definitions.routeIndicators, definitions.attachedSignals,
                definitions.pendingProtectionBoundaries, definitions.boundaryNames)
            val initialized = runtime.initializeDefinitions(target.topology, copied)
            check(initialized.status == SimpleSignalsDefinitionPublicationStatus.PUBLISHED) {
                "SimpleSignals simulation ${target.worldId} definitions rejected: ${initialized.diagnostics.joinToString()}"
            }
            val publication = runtime.authoringPublication()
            val bindings = (copied.fixedBlockSignals.map { SignalTypeBinding(it.signalId, it.signalType) } +
                copied.protectionSignals.map { SignalTypeBinding(it.signalId, it.signalType) }).sortedBy { it.deviceId }
            val epoch = SignalPackEpochRuntime.compile(publication, rules, runtime.signalLogic(rules), bindings,
                reportFailure = { context, error -> java.util.logging.Logger.getLogger("work.art1st.javarailways.simplesignals.simulation")
                    .log(java.util.logging.Level.SEVERE, "simulation ${target.worldId}: $context", error) }) { topology, rails ->
                check(topology === target.topology) { "simulation static speed belongs to another topology" }
                target.railSpeedLimits(rails)
            }
            val occupancy = SectionOccupancyRuntime.from(publication, epoch)
            check(runtime.replaceSectionOccupancy(publication, null, occupancy))
            val policy = FixedBlockDomainResourcePolicy(target.worldId,
                { publication.unavailableTerritory.requireUsable(it.route, it.desiredAuthority.distanceFromRouteStartMeters) },
                runtime::fixedBlockSnapshot)
            registrations += target.registerResourceProvider(FIXED_BLOCK_RESOURCE_TYPE, object : SimulationResourceProvider {
                override fun exists(resource: StableInfrastructureResource): Boolean =
                    resource.typeId == FIXED_BLOCK_RESOURCE_TYPE && policy.exists(resource.resourceId)

                override fun validate(contributorId: String, contribution: ContributedRouteResource): RouteAdmissionFailure? {
                    val code = when {
                        contribution.preparation != null -> RouteAdmissionFailureCode.INVALID_PREPARATION
                        contributorId != id || !exists(contribution.resource) -> RouteAdmissionFailureCode.INVALID_CONTRIBUTION
                        else -> return null
                    }
                    return RouteAdmissionFailure(code, "invalid SimpleSignals simulation contribution in ${target.worldId}")
                }

                override fun authorizeFormationResource(resource: StableInfrastructureResource, context: FormationRouteContext,
                    intent: RouteResourceIntent): FormationRoutePolicyResult =
                    if (resource.typeId == FIXED_BLOCK_RESOURCE_TYPE) policy.authorizeFormationResource(resource.resourceId, context, intent)
                    else FormationRoutePolicyResult.Rejected(RouteAdmissionFailure(
                        RouteAdmissionFailureCode.INVALID_CONTRIBUTION, "not a SimpleSignals resource"))

                override fun prepare(resource: StableInfrastructureResource, descriptor: ResourcePreparationDescriptor):
                    (() -> ResourcePreparationTicket)? = null
            })
            registrations += target.registerFormationRouteContributor(id, policy::contribute, policy::authorizeFormationRoute)
            registrations += target.installRoutePlanningSources(listOf(runtime.routePlanningBinding))
            registrations += target.installInspectionSources(listOf(runtime.fixedBlockInspectionSource, runtime.protectionRegionInspectionSource))
            registrations += target.installSignalSources(occupancy.sources)
        } catch (failure: Throwable) {
            closeInstallation(runtime, registrations, failure)
            throw failure
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) closeInstallation(runtime, registrations)?.let { throw it }
        }
    }

    private fun closeInstallation(runtime: SimpleSignalsWorldRuntime, registrations: List<AutoCloseable>,
        initialFailure: Throwable? = null): Throwable? {
        var failure = initialFailure
        for (registration in registrations.asReversed() + AutoCloseable { try { runtime.retire() } finally { runtime.closeLogic() } }) {
            try { registration.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (error !== failure) failure.addSuppressed(error)
            }
        }
        return failure
    }
}
