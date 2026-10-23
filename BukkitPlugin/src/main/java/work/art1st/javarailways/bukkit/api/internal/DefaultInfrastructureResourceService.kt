package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencySnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureClaimSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionResult
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionStatus
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditDecision
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditRequest
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditStatus
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.basic.infrastructure.ManualTurnoutSelectionRuntime
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureEditPermit
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureEditPermitResult
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProvider
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceProviderRegistration
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourcePreparationHandler
import work.art1st.javarailways.bukkit.api.infrastructure.RouteResourceContributor
import work.art1st.javarailways.bukkit.api.infrastructure.RouteResourceContributorRegistration
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.LiveWorldManagement
import work.art1st.javarailways.core.physics.WorldExecutionOwner
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChangeListener
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultInfrastructureResourceService(
    private val signalSources: DefaultSignalEpochSourceService? = null
) : InfrastructureResourceService, AutoCloseable {
    private val lock = Any()
    private data class WorldRuntime(
        val currentTopology: () -> TopologyState,
        val registry: InfrastructureCoordinationRegistry,
        val resourceValidation: AutoCloseable,
        val operatingDirections: ((TopologyState, Collection<UUID>) -> work.art1st.javarailways.basic.api.route.RailOperatingDirections)?,
        val railSpeedLimits: ((TopologyState, Collection<UUID>) -> Map<UUID, Double?>)?,
        val observeTopologyChanges: ((RailTopologyChangeListener) -> AutoCloseable)?,
        var routeAdmission: RouteResourceAdmissionRuntime? = null,
        var manualTurnoutSelection: ManualTurnoutSelectionRuntime? = null,
        var physicalWorld: CoreWorld? = null,
        var stallWorld: ((String, Exception) -> Unit)? = null,
        val detachedOwner: WorldExecutionOwner = WorldExecutionOwner()
    ) {
        val owner: WorldExecutionOwner get() = physicalWorld?.executionOwner ?: detachedOwner
    }

    private val worlds = linkedMapOf<UUID, WorldRuntime>()
    private class TopologyRegistration(val owner: Plugin, val world: WorldRuntime) : AutoCloseable {
        private var active = true
        var delegate: AutoCloseable? = null

        @Synchronized
        fun deliver(action: () -> Unit) {
            if (active && owner.isEnabled) action()
        }

        @Synchronized
        override fun close() {
            if (!active) return
            active = false
            delegate?.close()
        }
    }
    private val topologyRegistrations = linkedSetOf<TopologyRegistration>()
    private data class ProviderRegistration(
        val pluginOwner: Plugin,
        val provider: InfrastructureResourceProvider,
        val preparationHandlers: Map<String, InfrastructureResourcePreparationHandler>
    )
    private val resourceProviders = linkedMapOf<String, ProviderRegistration>()
    private class ContributorRegistration(
        val pluginOwner: Plugin,
        val contributor: RouteResourceContributor
    )
    private val routeContributors = linkedSetOf<ContributorRegistration>()
    private var closed = false

    fun registerWorld(
        worldId: UUID,
        currentTopology: () -> TopologyState,
        operatingDirections: ((TopologyState, Collection<UUID>) -> work.art1st.javarailways.basic.api.route.RailOperatingDirections)? = null,
        observeTopologyChanges: ((RailTopologyChangeListener) -> AutoCloseable)? = null,
        railSpeedLimits: ((TopologyState, Collection<UUID>) -> Map<UUID, Double?>)? = null,
        registry: InfrastructureCoordinationRegistry,
        resourceExists: (InfrastructureResource) -> Boolean
    ) = LiveWorldManagement.run { synchronized(lock) {
        check(!closed) { "InfrastructureResourceService is closed" }
        check(worldId !in worlds) { "Infrastructure coordination already exists for world $worldId" }
        worlds[worldId] = WorldRuntime(
            currentTopology = currentTopology,
            operatingDirections = operatingDirections,
            railSpeedLimits = railSpeedLimits,
            observeTopologyChanges = observeTopologyChanges,
            registry = registry,
            resourceValidation = registry.registerResourceValidator(resourceExists)
        )
    } }

    override fun currentTopology(worldId: UUID): TopologyState = synchronized(lock) {
        check(!closed) { "InfrastructureResourceService is closed" }
        checkNotNull(worlds[worldId]) { "Railway world is not registered: $worldId" }.currentTopology()
    }

    override fun observeTopologyChanges(
        pluginOwner: Plugin, worldId: UUID, listener: RailTopologyChangeListener
    ): AutoCloseable {
        val registration = synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            TopologyRegistration(requireEnabled(pluginOwner), checkNotNull(worlds[worldId]) {
                "Railway world is not registered: $worldId"
            })
        }
        val subscribe = checkNotNull(registration.world.observeTopologyChanges) {
            "Topology transaction observation is unavailable for world $worldId"
        }
        registration.delegate = subscribe(RailTopologyChangeListener { change ->
            registration.deliver { listener.onTopologyChange(change) }
        })
        val installed = synchronized(lock) {
            if (closed || worlds[worldId] !== registration.world || !pluginOwner.isEnabled) false
            else topologyRegistrations.add(registration)
        }
        if (!installed) {
            registration.close()
            error("Railway world or plugin changed while registering topology observation")
        }
        return AutoCloseable {
            synchronized(lock) { topologyRegistrations.remove(registration) }
            registration.close()
        }
    }

    private fun closeTopologyRegistrations(predicate: (TopologyRegistration) -> Boolean) {
        val removed = synchronized(lock) {
            topologyRegistrations.filter(predicate).also { topologyRegistrations.removeAll(it.toSet()) }
        }
        removed.forEach(TopologyRegistration::close)
    }

    override fun operatingDirections(worldId: UUID, topology: TopologyState, railIds: Collection<UUID>):
        work.art1st.javarailways.basic.api.route.RailOperatingDirections {
        val world = synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            checkNotNull(worlds[worldId]) { "Railway world is not registered: $worldId" }
        }
        require(railIds.all(topology.railProjection::containsKey)) { "operating direction subset references a missing rail" }
        check(world.currentTopology().preservesRails(topology, railIds)) { "operating direction rails changed" }
        val snapshot = checkNotNull(world.operatingDirections) { "operating directions are unavailable" }(topology, railIds)
        check(snapshot.topology === topology && snapshot.directions.keys == railIds.toSet()) {
            "operating direction subset does not match the request"
        }
        synchronized(lock) {
            check(!closed && worlds[worldId] === world && world.currentTopology().preservesRails(topology, railIds)) {
                "operating direction world or topology changed during the read"
            }
        }
        return snapshot
    }

    override fun railSpeedLimits(worldId: UUID, topology: TopologyState, railIds: Collection<UUID>): Map<UUID, Double?> {
        val world = synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            checkNotNull(worlds[worldId]) { "Railway world is not registered: $worldId" }
        }
        val requested = railIds.toSet()
        require(requested.all(topology.railProjection::containsKey)) { "speed limit subset references a missing rail" }
        check(world.currentTopology().preservesRails(topology, railIds)) { "speed limit rails changed" }
        val values = checkNotNull(world.railSpeedLimits) { "rail speed limits are unavailable" }(topology, requested)
        check(values.keys == requested && values.values.all { it == null || it.isFinite() && it > 0.0 }) {
            "speed limit subset does not match the request or contains an invalid speed"
        }
        val captured = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
        synchronized(lock) {
            check(!closed && worlds[worldId] === world && world.currentTopology().preservesRails(topology, railIds)) {
                "speed limit world or topology changed during the read"
            }
        }
        return captured
    }

    /** Completes world composition after Basic data is attached and before the first physics wake. */
    fun attachInfrastructureRuntime(
        worldId: UUID,
        world: World,
        latestObservation: () -> RailwayObservation?,
        publishAdmissionUpdate: (ActiveRouteAdmissionUpdate) -> Unit = {},
        stallWorld: (String, Exception) -> Unit
    ) = withWorld(worldId) {
        val runtime = synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            checkNotNull(worlds[worldId]) { "Infrastructure coordination is not registered for world $worldId" }
        }
        check(runtime.routeAdmission == null && runtime.manualTurnoutSelection == null) {
            "Infrastructure runtime already exists for world $worldId"
        }
        val admission = RouteResourceAdmissionRuntime.create(
            worldId = worldId.toString(),
            world = world,
            registry = runtime.registry,
            hostClaimOwner = runtime,
            latestObservation = latestObservation,
            collectContributions = { intent -> collectRouteContributions(worldId, intent) },
            validateContribution = { contributor, contribution ->
                val plugin = contributor as? Plugin ?: return@create contributionFailure(
                    RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                    "route contributions require an authenticated Bukkit plugin owner"
                )
                validateRouteContribution(plugin, worldId, contribution)
            },
            resolveContributedPreparation = { resource, descriptor ->
                resolveContributedPreparation(worldId, resource, descriptor)
            },
            stallWorld = stallWorld,
            publishAdmissionUpdate = publishAdmissionUpdate
        )
        val manualTurnoutSelection = ManualTurnoutSelectionRuntime(
            world,
            runtime.registry,
            ensureBetweenFrames = {
                (world as CoreWorld).physicsCoordinator.ensureBetweenFrames("ManualTurnoutSelectionRuntime.select")
            },
            publishSelection = { (world as CoreWorld).publishOwnerChanges(emptyList()) }
        )
        synchronized(lock) {
            check(!closed && worlds[worldId] === runtime && runtime.routeAdmission == null &&
                runtime.manualTurnoutSelection == null
            ) {
                "railway world changed while attaching infrastructure runtime for $worldId"
            }
            runtime.routeAdmission = admission
            runtime.manualTurnoutSelection = manualTurnoutSelection
            runtime.physicalWorld = world as? CoreWorld
            runtime.stallWorld = stallWorld
        }
    }

    override fun registerResourceProvider(
        pluginOwner: Plugin,
        typeId: String,
        provider: InfrastructureResourceProvider,
        preparationHandlers: Collection<InfrastructureResourcePreparationHandler>
    ): InfrastructureResourceProviderRegistration {
        val owner = requireEnabled(pluginOwner)
        // Reuse the public value object's validation instead of maintaining a second typeId grammar.
        StableInfrastructureResource(typeId, "validation")
        val handlersByKind = LinkedHashMap<String, InfrastructureResourcePreparationHandler>()
        preparationHandlers.forEach { handler ->
            val kindId = handler.kindId()
            ResourcePreparationDescriptor(kindId, emptyMap())
            check(handlersByKind.putIfAbsent(kindId, handler) == null) {
                "Duplicate infrastructure preparation handler kind $kindId for $typeId"
            }
        }
        val registration = ProviderRegistration(
            owner,
            provider,
            java.util.Collections.unmodifiableMap(LinkedHashMap(handlersByKind))
        )
        LiveWorldManagement.run { synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            check(typeId !in resourceProviders) { "Infrastructure resource provider already exists for $typeId" }
            resourceProviders[typeId] = registration
        } }
        val registrationClosed = AtomicBoolean()
        return InfrastructureResourceProviderRegistration {
            if (!registrationClosed.get()) {
                unregisterResourceProvider(typeId, registration)
                registrationClosed.set(true)
            }
        }
    }

    override fun registerRouteResourceContributor(
        pluginOwner: Plugin,
        contributor: RouteResourceContributor
    ): RouteResourceContributorRegistration = withManagement runInSimulationContext@ {
        val registration = ContributorRegistration(requireEnabled(pluginOwner), contributor)
        synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            check(worlds.values.none { it.routeAdmission?.hasRouteState() == true }) {
                "Cannot change route-resource contributors while route states remain active"
            }
            check(routeContributors.none {
                it.pluginOwner === registration.pluginOwner && it.contributor === contributor
            }) { "Route-resource contributor is already registered" }
            routeContributors += registration
        }
        val registrationClosed = AtomicBoolean()
        return@runInSimulationContext RouteResourceContributorRegistration {
            if (!registrationClosed.get()) {
                unregisterRouteResourceContributor(registration)
                registrationClosed.set(true)
            }
        }
    }

    private fun unregisterRouteResourceContributor(
        registration: ContributorRegistration
    ) = withManagement runInSimulationContext@ {
        synchronized(lock) {
            if (closed || registration !in routeContributors) return@runInSimulationContext
            check(worlds.values.none { it.routeAdmission?.hasRouteState() == true }) {
                "Cannot unregister route-resource contributor ${registration.pluginOwner.name} " +
                    "while route states remain active"
            }
            routeContributors.remove(registration)
        }
    }

    private fun collectRouteContributions(
        worldId: UUID,
        intent: RouteResourceIntent
    ): List<Pair<Any, ContributedRouteResource>> {
        val snapshot = synchronized(lock) {
            check(!closed && worldId in worlds) { "railway world is unavailable" }
            routeContributors.toList()
        }
        val collected = ArrayList<Pair<Any, ContributedRouteResource>>()
        for (registration in snapshot) {
            check(registration.pluginOwner.isEnabled) {
                "route-resource contributor ${registration.pluginOwner.name} is disabled"
            }
            val contributions = registration.contributor.contribute(worldId, intent)
            contributions.forEach { contribution ->
                collected += registration.pluginOwner to contribution
            }
        }
        synchronized(lock) {
            check(!closed && worldId in worlds && routeContributors.toList() == snapshot &&
                snapshot.all { it.pluginOwner.isEnabled }
            ) { "route-resource contributor registration changed during collection" }
        }
        return collected
    }

    private fun unregisterResourceProvider(
        typeId: String,
        registration: ProviderRegistration
    ) = withManagement runInSimulationContext@ {
        val snapshot = synchronized(lock) {
            if (closed) return@runInSimulationContext
            if (resourceProviders[typeId] !== registration) {
                return@runInSimulationContext
            }
            worlds.entries.map { it.key to it.value }
        }
        val activity = snapshot.flatMap { (worldId, runtime) ->
            runtime.routeAdmission?.providerActivity(setOf(typeId)).orEmpty().map { worldId to it }
        }
        val infrastructureDependencies = snapshot.flatMap { (worldId, runtime) ->
            runtime.registry.dependenciesUsingResourceTypes(setOf(typeId)).map { worldId to it }
        }
        check(activity.isEmpty() && infrastructureDependencies.isEmpty()) {
            val routeDetails = activity.joinToString { (worldId, active) ->
                "world=$worldId route=${active.owner.ownerId} train=${active.trainId}"
            }
            val dependencyDetails = infrastructureDependencies.joinToString { (worldId, dependency) ->
                "world=$worldId dependency=${dependency.owner.ownerId}@${dependency.owner.revision}"
            }
            "Cannot unregister infrastructure resource provider $typeId while it remains referenced: " +
                listOf(routeDetails, dependencyDetails).filter(String::isNotEmpty).joinToString()
        }
        synchronized(lock) {
            if (resourceProviders[typeId] === registration) resourceProviders.remove(typeId)
        }
    }

    override fun routeAdmission(
        pluginOwner: Plugin,
        worldId: UUID
    ): RouteResourceAdmissionService {
        val requester = requireEnabled(pluginOwner)
        val worldRuntime = synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            val runtime = checkNotNull(worlds[worldId]) {
                "Railway world is not registered: $worldId"
            }
            checkNotNull(runtime.routeAdmission) {
                "Route admission is not attached for world $worldId"
            }
            runtime
        }
        return BoundRouteAdmissionService(requester, worldId, worldRuntime)
    }

    override fun selectTurnout(
        pluginOwner: Plugin,
        worldId: UUID,
        request: ManualTurnoutSelectionRequest
    ): CompletableFuture<ManualTurnoutSelectionResult> {
        val requester = requireEnabled(pluginOwner)
        fun unavailable() = ManualTurnoutSelectionResult(
            ManualTurnoutSelectionStatus.WORLD_UNAVAILABLE,
            detail = "manual turnout runtime is unavailable for world $worldId"
        )
        val bound = synchronized(lock) { if (closed) null else worlds[worldId] }
            ?: return CompletableFuture.completedFuture(unavailable())
        val physicalWorld = bound.physicalWorld
            ?: return CompletableFuture.completedFuture(unavailable())
        val selection = bound.manualTurnoutSelection
            ?: return CompletableFuture.completedFuture(unavailable())
        return physicalWorld.physicsCoordinator.submitBetweenFrames {
            val available = synchronized(lock) {
                !closed && requester.isEnabled && worlds[worldId] === bound &&
                    bound.physicalWorld === physicalWorld && bound.manualTurnoutSelection === selection
            }
            if (!available) unavailable() else selection.select(requester, request)
        }.thenApply { it }
    }

    private inner class BoundRouteAdmissionService(
        private val requester: Plugin,
        private val worldId: UUID,
        private val boundWorld: WorldRuntime
    ) : RouteResourceAdmissionService {
        override fun request(intent: RouteResourceIntent): RouteAdmissionResult =
            withAdmission(::routeAdmissionUnavailable) { it.request(requester, intent) }

        override fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult =
            withAdmission(::routeAdmissionUnavailable) { it.progress(requester, handle) }

        override fun suspendReleaseForReversal(grantId: RouteGrantId): RouteGrantReversalResult =
            withAdmission(::routeReversalUnavailable) { it.suspendReleaseForReversal(requester, grantId) }

        override fun rebaseAfterReversal(intent: RouteResourceIntent): RouteGrantReversalResult =
            withAdmission(::routeReversalUnavailable) { it.rebaseAfterReversal(requester, intent) }

        override fun endGrant(grantId: RouteGrantId): Boolean =
            withAdmission({ false }) { it.endGrant(requester, grantId) }

        private fun <T> withAdmission(unavailable: () -> T, action: (RouteResourceAdmissionRuntime) -> T): T =
            withWorld(worldId) runInSimulationContext@ {
                val expected = admission() ?: return@runInSimulationContext unavailable()
                val apply = {
                    if (admission() !== expected) unavailable() else action(expected)
                }
                // A value-only non-Core World has no physics owner to coordinate with.
                val physicalWorld = boundWorld.physicalWorld
                if (physicalWorld == null) apply() else physicalWorld.physicsCoordinator.withStructuralMutation {
                    try {
                        apply()
                    } finally {
                        physicalWorld.publishOwnerSelectionChanges()
                    }
                }
            }

        private fun admission(): RouteResourceAdmissionRuntime? = synchronized(lock) {
            if (closed || !requester.isEnabled || worlds[worldId] !== boundWorld
            ) null
            else boundWorld.routeAdmission
        }
    }

    fun stableResourceExists(worldId: UUID, resource: StableInfrastructureResource): Boolean {
        val (admission, provider) = synchronized(lock) {
            if (closed) null to null
            else worlds[worldId]?.routeAdmission to resourceProviders[resource.typeId]?.provider
        }
        if (admission?.physicalResourceExists(resource) == true) return true
        provider ?: return false
        return provider.exists(worldId, resource.resourceId)
    }

    /** Pre-claim route-use validation against the same provider registration used by dependencies. */
    fun validateRouteContribution(
        contributor: Plugin,
        worldId: UUID,
        contribution: ContributedRouteResource
    ): RouteAdmissionFailure? = withWorld(worldId) runInSimulationContext@ {
        if (!contributor.isEnabled) {
            return@runInSimulationContext contributionFailure(
                RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "contributing plugin is disabled"
            )
        }
        if (synchronized(lock) { closed || worldId !in worlds }) {
            return@runInSimulationContext contributionFailure(
                RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "railway world is not registered"
            )
        }
        val registered = synchronized(lock) {
            resourceProviders[contribution.resource.typeId]
        } ?: return@runInSimulationContext contributionFailure(
            RouteAdmissionFailureCode.INVALID_CONTRIBUTION, "resource type is not registered"
        )
        if (!registered.pluginOwner.isEnabled) {
            return@runInSimulationContext contributionFailure(
                RouteAdmissionFailureCode.INVALID_CONTRIBUTION, "resource owner is disabled"
            )
        }
        val validation = try {
            if (!registered.provider.exists(worldId, contribution.resource.resourceId)) {
                RouteAdmissionFailureCode.INVALID_CONTRIBUTION
            } else {
                registered.provider.validateRouteContribution(
                    contributor, worldId, contribution.resource.resourceId, contribution.preparation
                )
            }
        } catch (failure: Exception) {
            return@runInSimulationContext contributionFailure(
                RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                "resource owner validation failed: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
        if (synchronized(lock) { closed || worldId !in worlds }) {
            return@runInSimulationContext contributionFailure(
                RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "railway world became unavailable during validation"
            )
        }
        if (synchronized(lock) { resourceProviders[contribution.resource.typeId] !== registered } ||
            !registered.pluginOwner.isEnabled
        ) {
            return@runInSimulationContext contributionFailure(
                RouteAdmissionFailureCode.INVALID_CONTRIBUTION, "resource registration changed during validation"
            )
        }
        val preparation = contribution.preparation
        if (validation == null && preparation != null &&
            preparation.kindId !in registered.preparationHandlers
        ) {
            return@runInSimulationContext contributionFailure(
                RouteAdmissionFailureCode.INVALID_PREPARATION,
                "preparation handler ${preparation.kindId} is not registered for " +
                    contribution.resource.typeId
            )
        }
        when (validation) {
            null -> null
            RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            RouteAdmissionFailureCode.INVALID_PREPARATION -> contributionFailure(
                validation, "resource owner rejected route contribution"
            )
            else -> contributionFailure(
                RouteAdmissionFailureCode.INVALID_CONTRIBUTION, "resource owner returned an invalid validation code"
            )
        }
    }

    /** Resolves one exact provider handler before the preparation batch starts any side effect. */
    private fun resolveContributedPreparation(
        worldId: UUID,
        resource: StableInfrastructureResource,
        descriptor: ResourcePreparationDescriptor
    ): (() -> ResourcePreparationTicket)? {
        val registered = synchronized(lock) {
            if (closed || worldId !in worlds) null else resourceProviders[resource.typeId]
        } ?: return null
        if (!registered.pluginOwner.isEnabled) return null
        val handler = registered.preparationHandlers[descriptor.kindId] ?: return null
        val attemptId = UUID.randomUUID()
        return {
            requireNotNull(handler.start(attemptId, worldId, resource, descriptor)) {
                "preparation handler ${descriptor.kindId} returned no ticket"
            }
        }
    }

    private fun contributionFailure(code: RouteAdmissionFailureCode, detail: String) =
        RouteAdmissionFailure(code, detail)

    fun removeWorld(worldId: UUID) {
        withWorld(worldId) runInSimulationContext@ {
            synchronized(lock) { worlds.remove(worldId) }?.let { runtime ->
                closeTopologyRegistrations { it.world === runtime }
                runtime.manualTurnoutSelection?.close()
                runtime.routeAdmission?.close()
                runtime.resourceValidation.close()
            }
        }
    }

    /** Runs tail release before Dispatcher observes the same completed physics epoch. */
    fun consumeObservation(
        worldId: UUID,
        observation: RailwayObservation
    ) = withWorld(worldId) runInSimulationContext@ {
        val admission = synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            checkNotNull(worlds[worldId]?.routeAdmission) {
                "Route admission runtime is not attached for world $worldId"
            }
        }
        admission.consumeObservation(observation)
    }

    override fun setInfrastructureDependencies(
        pluginOwner: Plugin,
        worldId: UUID,
        request: InfrastructureDependencyRequest
    ) =
        withWorld(worldId) runInSimulationContext@ {
            registry(worldId)?.setInfrastructureDependencies(requireEnabled(pluginOwner), request)
                ?: unavailableDependencies(worldId)
        }

    override fun removeInfrastructureDependencies(pluginOwner: Plugin, worldId: UUID, owner: InfrastructureOwner) =
        withWorld(worldId) runInSimulationContext@ {
            registry(worldId)?.removeInfrastructureDependencies(pluginOwner, owner)
                ?: unavailableDependencies(worldId)
        }

    /** Internal world-runtime seam; deliberately absent from the public add-on API. */
    fun acquireLiveClaim(pluginOwner: Plugin, worldId: UUID, request: InfrastructureClaimRequest) =
        withWorld(worldId) runInSimulationContext@ {
            registry(worldId)?.acquireClaim(requireEnabled(pluginOwner), request) ?: unavailable(worldId)
        }

    fun releaseLiveClaim(pluginOwner: Plugin, worldId: UUID, owner: InfrastructureOwner) =
        withWorld(worldId) runInSimulationContext@ {
            registry(worldId)?.releaseClaim(pluginOwner, owner) ?: unavailable(worldId)
        }

    override fun acquireEditPermit(
        pluginOwner: Plugin,
        worldId: UUID,
        request: TopologyEditRequest
    ): InfrastructureEditPermitResult {
        val registry = registry(worldId)
            ?: return InfrastructureEditPermitResult.denied(unavailableEdit(worldId))
        val owner = requireEnabled(pluginOwner)
        val acquired = registry.acquireEditPermit(owner, request)
        if (!acquired.decision.successful) {
            return InfrastructureEditPermitResult.denied(acquired.decision)
        }
        val permit = object : InfrastructureEditPermit {
            private val permitClosed = AtomicBoolean()
            override fun owner(): InfrastructureOwner = request.owner
            override fun <T> execute(action: java.util.function.Supplier<T>): T {
                check(!permitClosed.get()) { "Infrastructure edit permit is closed" }
                return registry.withEditPermit(owner, acquired.id) { action.get() }
            }
            override fun close() = synchronized(permitClosed) {
                if (!permitClosed.get()) {
                    registry.releaseEditPermit(owner, acquired.id)
                    permitClosed.set(true)
                }
            }
        }
        return InfrastructureEditPermitResult.granted(acquired.decision, permit)
    }

    override fun infrastructureDependencies(worldId: UUID): List<InfrastructureDependencySnapshot> =
        registry(worldId)?.dependencySnapshots().orEmpty()

    override fun liveClaims(worldId: UUID): List<InfrastructureClaimSnapshot> =
        registry(worldId)?.claimSnapshots().orEmpty()

    override fun activeRouteAdmissions(worldId: UUID): List<ActiveRouteAdmissionSnapshot> =
        withWorld(worldId) runInSimulationContext@ {
            val admission = synchronized(lock) {
                check(!closed) { "InfrastructureResourceService is closed" }
                checkNotNull(worlds[worldId]?.routeAdmission) {
                    "Route admission is not attached for world $worldId"
                }
            }
            admission.activeAdmissions()
        }

    override fun unregisterOwner(pluginOwner: Plugin) {
        withManagement runInSimulationContext@ {
            check(pluginOwner.isEnabled) {
                "Cannot unregister infrastructure owner for disabled plugin ${pluginOwner.name}"
            }
            val (snapshot, providedTypeIds, ownsContributor) = synchronized(lock) {
                check(!closed) { "InfrastructureResourceService is closed" }
                Triple(
                    worlds.entries.map { it.key to it.value },
                    resourceProviders.filterValues { it.pluginOwner === pluginOwner }.keys.toSet(),
                    routeContributors.any { it.pluginOwner === pluginOwner }
                )
            }
            val requesterActivity = snapshot.flatMap { (worldId, runtime) ->
                runtime.routeAdmission?.requesterActivity(pluginOwner).orEmpty().map { worldId to it }
            }
            val providerActivity = snapshot.flatMap { (worldId, runtime) ->
                runtime.routeAdmission?.providerActivity(providedTypeIds).orEmpty().map { worldId to it }
            }
            val infrastructureDependencies = snapshot.flatMap { (worldId, runtime) ->
                runtime.registry.dependenciesOwnedBy(pluginOwner).map { worldId to it }
            }
            val dependencyProviderActivity = snapshot.flatMap { (worldId, runtime) ->
                runtime.registry.dependenciesUsingResourceTypes(providedTypeIds).map { worldId to it }
            }
            check(requesterActivity.isEmpty() && providerActivity.isEmpty() && dependencyProviderActivity.isEmpty()) {
                "Cannot unregister infrastructure owner ${pluginOwner.name} while route resources remain active: " +
                    (requesterActivity + providerActivity).distinct().joinToString { (worldId, active) ->
                        "world=$worldId route=${active.owner.ownerId} train=${active.trainId} " +
                            "pending=${active.pendingRequest} heldResources=${active.heldResourceCount}" +
                            active.resourceTypeIds.takeIf(Set<String>::isNotEmpty)
                                ?.joinToString(prefix = " types=", separator = ",").orEmpty()
                    } + dependencyProviderActivity.joinToString(prefix = " dependencies=", separator = ",") {
                        (worldId, dependency) ->
                        "$worldId/${dependency.owner.ownerId}@${dependency.owner.revision}"
                    }
            }
            check(infrastructureDependencies.isEmpty()) {
                "Cannot unregister infrastructure owner ${pluginOwner.name} while dependencies remain active: " +
                    infrastructureDependencies.joinToString { (worldId, dependency) ->
                        "world=$worldId dependency=${dependency.owner.ownerId} revision=${dependency.owner.revision}"
                    }
            }
            check(!ownsContributor || snapshot.none { (_, runtime) ->
                runtime.routeAdmission?.hasRouteState() == true
            }) {
                "Cannot unregister route-resource contributor ${pluginOwner.name} while route states remain active"
            }
            // Signal and infrastructure checks must both succeed before either registration set is changed.
            signalSources?.requireOwnerUnused(pluginOwner)
            snapshot.forEach { (_, runtime) ->
                check(runtime.routeAdmission?.discardInactiveRequester(pluginOwner) != false) {
                    "Route resources changed while unregistering ${pluginOwner.name}"
                }
            }
            signalSources?.removeUnusedOwner(pluginOwner)
            closeTopologyRegistrations { it.owner === pluginOwner }
            synchronized(lock) {
                resourceProviders.values.removeIf { it.pluginOwner === pluginOwner }
                routeContributors.removeIf { it.pluginOwner === pluginOwner }
            }
            snapshot.forEach { (_, runtime) -> runtime.registry.releasePlatformOwner(pluginOwner) }
        }
    }

    /**
     * Handles an already-fired, non-cancellable Bukkit disable event. Active route resources remain
     * host-owned and stall their world; only non-route platform ownership receives ordinary cleanup.
     */
    fun forcedDisableOwner(pluginOwner: Plugin) {
        withManagement(freezePhysics = false) runInSimulationContext@ {
            closeTopologyRegistrations { it.owner === pluginOwner }
            val (snapshot, providedTypeIds, ownedContributor) = synchronized(lock) {
                if (closed) return@runInSimulationContext
                val ownedTypes = resourceProviders
                    .filterValues { it.pluginOwner === pluginOwner }
                    .keys.toSet()
                val ownsContributor = routeContributors.any { it.pluginOwner === pluginOwner }
                resourceProviders.values.removeIf { it.pluginOwner === pluginOwner }
                routeContributors.removeIf { it.pluginOwner === pluginOwner }
                Triple(worlds.entries.map { it.key to it.value }, ownedTypes, ownsContributor)
            }
            snapshot.forEach { (worldId, runtime) ->
                val infrastructureDependencies = (
                    runtime.registry.dependenciesOwnedBy(pluginOwner) +
                        runtime.registry.dependenciesUsingResourceTypes(providedTypeIds)
                    ).distinctBy { it.owner }
                if (infrastructureDependencies.isNotEmpty()) {
                    val reason = "Bukkit plugin ${pluginOwner.name} in world $worldId was disabled with active " +
                        "infrastructure dependencies: " + infrastructureDependencies.joinToString { dependency ->
                            "${dependency.owner.ownerId}@${dependency.owner.revision}"
                        }
                    runtime.routeAdmission?.failClosedInfrastructureDependencyLoss(reason)
                        ?: runtime.stallWorld?.invoke(reason, IllegalStateException(reason))
                }
                runtime.routeAdmission?.failClosedRequesterLoss(
                    pluginOwner,
                    "Bukkit plugin ${pluginOwner.name} in world $worldId"
                )
                runtime.routeAdmission?.failClosedProviderLoss(
                    providedTypeIds,
                    "Bukkit plugin ${pluginOwner.name} in world $worldId"
                )
                if (ownedContributor) {
                    runtime.routeAdmission?.failClosedContributorLoss(
                        "Bukkit plugin ${pluginOwner.name} in world $worldId"
                    )
                }
                runtime.registry.releasePlatformOwner(pluginOwner)
            }
            signalSources?.forcedDisableOwner(pluginOwner)
        }
    }

    override fun close() {
        withManagement(freezePhysics = false) runInSimulationContext@ {
            val snapshot = synchronized(lock) {
                if (closed) return@runInSimulationContext
                closed = true
                resourceProviders.clear()
                routeContributors.clear()
                worlds.values.toList().also { worlds.clear() }
            }
            snapshot.forEach { runtime ->
                closeTopologyRegistrations { it.world === runtime }
                runtime.manualTurnoutSelection?.close()
                runtime.routeAdmission?.close()
                runtime.resourceValidation.close()
            }
        }
    }

    fun requiredDomainFeatures(worldId: UUID): Set<String> = synchronized(lock) {
        if (worldId !in worlds) emptySet() else buildSet {
            resourceProviders.values.forEach { add(it.pluginOwner.name.lowercase()) }
            routeContributors.forEach { add(it.pluginOwner.name.lowercase()) }
        }
    }

    private fun <T> withWorld(worldId: UUID, action: () -> T): T =
        BukkitThreadGuardBridge.runInSimulationContext {
            val owner = synchronized(lock) { worlds[worldId]?.owner }
            if (owner == null) action() else owner.run(action)
        }

    private fun <T> withManagement(freezePhysics: Boolean = true, action: () -> T): T = LiveWorldManagement.run {
        val snapshot = synchronized(lock) { worlds.values.toList() }
        val owners = snapshot.map { it.owner } + signalSources?.executionOwners().orEmpty()
        val physicalWorlds = (snapshot.mapNotNull { it.physicalWorld } +
            signalSources?.executionWorlds().orEmpty()).distinct()
        BukkitThreadGuardBridge.runInSimulationContext {
            WorldExecutionOwner.runTogether(owners) {
                fun freeze(index: Int): T {
                    if (!freezePhysics || index == physicalWorlds.size) return action()
                    val coordinator = physicalWorlds[index].physicsCoordinator
                    if (coordinator.status != work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus.RUNNING) {
                        return freeze(index + 1)
                    }
                    return coordinator.withStructuralMutation { freeze(index + 1) }
                }
                freeze(0)
            }
        }
    }

    private fun registry(worldId: UUID): InfrastructureCoordinationRegistry? = synchronized(lock) {
        if (closed) null else worlds[worldId]?.registry
    }

    private fun requireEnabled(pluginOwner: Plugin): Plugin {
        check(pluginOwner.isEnabled) { "Cannot acquire infrastructure ownership for disabled plugin ${pluginOwner.name}" }
        return pluginOwner
    }

    private fun unavailable(worldId: UUID) = InfrastructureMutationResult(
        InfrastructureMutationStatus.WORLD_UNAVAILABLE,
        detail = "railway world is not registered: $worldId"
    )

    private fun unavailableDependencies(worldId: UUID) = InfrastructureDependencyMutationResult(
        InfrastructureDependencyMutationStatus.WORLD_UNAVAILABLE,
        "railway world is not registered: $worldId"
    )

    private fun unavailableEdit(worldId: UUID) = TopologyEditDecision(
        TopologyEditStatus.WORLD_UNAVAILABLE,
        detail = "railway world is not registered: $worldId"
    )

    private fun routeAdmissionUnavailable() = RouteAdmissionResult.Rejected(
        RouteAdmissionFailure(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            "route admission binding is unavailable"
        )
    )

    private fun routeReversalUnavailable() = RouteGrantReversalResult.Rejected(
        RouteAdmissionFailure(
            RouteAdmissionFailureCode.WORLD_UNAVAILABLE,
            "route admission binding is unavailable"
        )
    )
}
