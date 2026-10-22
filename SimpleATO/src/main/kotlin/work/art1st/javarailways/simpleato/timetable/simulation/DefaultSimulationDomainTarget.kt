package work.art1st.javarailways.simpleato.timetable.simulation

import work.art1st.javarailways.basic.api.route.FormationRouteContext
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget
import work.art1st.javarailways.simpleato.api.simulation.SimulationResourceProvider
import work.art1st.javarailways.simpleato.runtime.OperationalWorldRuntime
import java.util.UUID

/** Accessed only during target-owner installation and after target worker termination for cleanup. */
internal class DefaultSimulationDomainTarget(
    private val world: CoreWorld,
    private val domain: OperationalWorldRuntime,
    private val admission: SimulationRouteAdmissionHost
) : SimulationDomainTarget {
    override val worldId = UUID.fromString(world.runtimeId)
    override val topology = world.topologyState
    private val signals = arrayListOf<List<SignalEpochSource>>()
    private val inspections = arrayListOf<List<InspectionSource>>()
    private val planning = arrayListOf<List<RoutePlanningRestrictionBinding>>()

    override fun operatingDirections(railIds: Collection<UUID>) =
        domain.basicData.operatingDirections.snapshot(topology, railIds)

    override fun railSpeedLimits(railIds: Collection<UUID>) =
        domain.basicData.railMetadata.speedLimits(topology, railIds)

    override fun installSignalSources(sources: Collection<SignalEpochSource>): AutoCloseable =
        install(signals, sources, domain.signalEpochRuntime::replaceSources)

    override fun installInspectionSources(sources: Collection<InspectionSource>): AutoCloseable =
        install(inspections, sources, domain.inspectionRuntime::replaceSources) { removed, _ ->
            removed.forEach { domain.inspectionRuntime.removeSource(it.sourceId) }
        }

    override fun installRoutePlanningSources(sources: Collection<RoutePlanningRestrictionBinding>): AutoCloseable =
        install(planning, sources, domain.data.routePlanningRestrictions::replaceSources)

    override fun registerResourceProvider(typeId: String, provider: SimulationResourceProvider) =
        admission.registerProvider(typeId, provider)

    override fun registerRouteContributor(id: String, collect: (RouteResourceIntent) -> List<ContributedRouteResource>) =
        admission.registerContributor(id, collect)

    override fun registerFormationRouteContributor(id: String, collect: (RouteResourceIntent) -> List<ContributedRouteResource>,
        authorize: (FormationRouteContext, RouteResourceIntent) -> RouteAdmissionFailure?) =
        admission.registerFormationContributor(id, collect, authorize)

    private fun <T> install(
        registrations: MutableList<List<T>>,
        sources: Collection<T>,
        replace: (Collection<T>) -> Unit,
        retire: (List<T>, List<T>) -> Unit = { _, remaining -> replace(remaining) }
    ): AutoCloseable {
        val installed = java.util.List.copyOf(sources)
        replace(registrations.flatten() + installed)
        registrations += installed
        var closed = false
        return AutoCloseable {
            if (!closed) {
                val remaining = registrations.filterNot { it === installed }
                retire(installed, remaining.flatten())
                registrations.removeAll { it === installed }
                closed = true
            }
        }
    }
}
