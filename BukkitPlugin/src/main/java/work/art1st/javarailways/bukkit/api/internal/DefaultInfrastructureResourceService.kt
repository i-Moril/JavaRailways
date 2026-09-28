package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationResult
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencySnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureClaimSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionResult
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionStatus
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditDecision
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditStatus
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
import work.art1st.javarailways.basic.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.basic.infrastructure.ManualTurnoutSelectionRuntime
import work.art1st.javarailways.basic.infrastructure.RouteResourceAdmissionRuntime
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
import work.art1st.javarailways.core.api.physics.World
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultInfrastructureResourceService(
    private val signalSources: DefaultSignalEpochSourceService? = null
) : InfrastructureResourceService, AutoCloseable {
    private val lock = Any()
    private data class WorldRuntime(
        val registry: InfrastructureCoordinationRegistry,
        var routeAdmission: RouteResourceAdmissionRuntime? = null,
        var manualTurnoutSelection: ManualTurnoutSelectionRuntime? = null,
        var stallWorld: ((String, Exception) -> Unit)? = null
    )

    private val worlds = linkedMapOf<UUID, WorldRuntime>()
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
        currentTopologyRevision: () -> Long,
        railExists: (UUID) -> Boolean = { false },
        topologyNodeExists: (StableTopologyNodeKey) -> Boolean = { false },
        resourceExists: (InfrastructureResource) -> Boolean
    ) = synchronized(lock) {
        check(!closed) { "InfrastructureResourceService is closed" }
        check(worldId !in worlds) { "Infrastructure coordination already exists for world $worldId" }
        worlds[worldId] = WorldRuntime(
            InfrastructureCoordinationRegistry(currentTopologyRevision, resourceExists, railExists, topologyNodeExists)
        )
    }

    /** Completes world composition after Basic data is attached and before the first physics wake. */
    fun attachInfrastructureRuntime(
        worldId: UUID,
        world: World,
        latestObservation: () -> RailwayObservation?,
        awaitCurrentPhysics: () -> Unit,
        turnoutLockingDistanceMeters: Double,
        stallWorld: (String, Exception) -> Unit
    ) = BukkitThreadGuardBridge.runInSimulationContext {
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
            stallWorld = stallWorld
        )
        val manualTurnoutSelection = ManualTurnoutSelectionRuntime(
            world,
            runtime.registry,
            awaitCurrentPhysics,
            turnoutLockingDistanceMeters
        )
        synchronized(lock) {
            check(!closed && worlds[worldId] === runtime && runtime.routeAdmission == null &&
                runtime.manualTurnoutSelection == null
            ) {
                "railway world changed while attaching infrastructure runtime for $worldId"
            }
            runtime.routeAdmission = admission
            runtime.manualTurnoutSelection = manualTurnoutSelection
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
        synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            check(typeId !in resourceProviders) { "Infrastructure resource provider already exists for $typeId" }
            resourceProviders[typeId] = ProviderRegistration(
                owner,
                provider,
                java.util.Collections.unmodifiableMap(LinkedHashMap(handlersByKind))
            )
        }
        val registrationClosed = AtomicBoolean()
        return InfrastructureResourceProviderRegistration {
            if (!registrationClosed.get()) {
                unregisterResourceProvider(owner, typeId, provider)
                registrationClosed.set(true)
            }
        }
    }

    override fun registerRouteResourceContributor(
        pluginOwner: Plugin,
        contributor: RouteResourceContributor
    ): RouteResourceContributorRegistration = BukkitThreadGuardBridge.runInSimulationContext {
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
    ) = BukkitThreadGuardBridge.runInSimulationContext {
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
        pluginOwner: Plugin,
        typeId: String,
        provider: InfrastructureResourceProvider
    ) = BukkitThreadGuardBridge.runInSimulationContext {
        val snapshot = synchronized(lock) {
            if (closed) return@runInSimulationContext
            val current = resourceProviders[typeId]
            if (current?.pluginOwner !== pluginOwner || current.provider !== provider) {
                return@runInSimulationContext
            }
            worlds.entries.map { it.key to it.value }
        }
        val activity = snapshot.flatMap { (worldId, runtime) ->
            runtime.routeAdmission?.providerActivity(setOf(typeId)).orEmpty().map { worldId to it }
        }
        val appliedDependencies = snapshot.flatMap { (worldId, runtime) ->
            runtime.registry.appliedPlanDependenciesUsingResourceTypes(setOf(typeId)).map { worldId to it }
        }
        check(activity.isEmpty() && appliedDependencies.isEmpty()) {
            val routeDetails = activity.joinToString { (worldId, active) ->
                "world=$worldId route=${active.owner.ownerId} train=${active.trainId}"
            }
            val planDetails = appliedDependencies.joinToString { (worldId, dependency) ->
                "world=$worldId plan=${dependency.owner.ownerId}@${dependency.owner.revision}"
            }
            "Cannot unregister infrastructure resource provider $typeId while it remains referenced: " +
                listOf(routeDetails, planDetails).filter(String::isNotEmpty).joinToString()
        }
        synchronized(lock) {
            resourceProviders[typeId]?.takeIf {
                it.pluginOwner === pluginOwner && it.provider === provider
            }?.let { resourceProviders.remove(typeId) }
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
    ): ManualTurnoutSelectionResult = BukkitThreadGuardBridge.runInSimulationContext {
        val requester = requireEnabled(pluginOwner)
        val runtime = synchronized(lock) {
            if (closed) null else worlds[worldId]?.manualTurnoutSelection
        } ?: return@runInSimulationContext ManualTurnoutSelectionResult(
            ManualTurnoutSelectionStatus.WORLD_UNAVAILABLE,
            detail = "manual turnout runtime is unavailable for world $worldId"
        )
        runtime.select(requester, request)
    }

    private inner class BoundRouteAdmissionService(
        private val requester: Plugin,
        private val worldId: UUID,
        private val boundWorld: WorldRuntime
    ) : RouteResourceAdmissionService {
        override fun request(intent: RouteResourceIntent): RouteAdmissionResult =
            BukkitThreadGuardBridge.runInSimulationContext {
                admission()?.request(requester, intent) ?: routeAdmissionUnavailable()
            }

        override fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult =
            BukkitThreadGuardBridge.runInSimulationContext {
                admission()?.progress(requester, handle) ?: routeAdmissionUnavailable()
            }

        override fun suspendReleaseForReversal(grantId: RouteGrantId): RouteGrantReversalResult =
            BukkitThreadGuardBridge.runInSimulationContext {
                admission()?.suspendReleaseForReversal(requester, grantId)
                    ?: routeReversalUnavailable()
            }

        override fun rebaseAfterReversal(intent: RouteResourceIntent): RouteGrantReversalResult =
            BukkitThreadGuardBridge.runInSimulationContext {
                admission()?.rebaseAfterReversal(requester, intent)
                    ?: routeReversalUnavailable()
            }

        override fun endGrant(grantId: RouteGrantId): Boolean =
            BukkitThreadGuardBridge.runInSimulationContext {
                admission()?.endGrant(requester, grantId) ?: false
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
    ): RouteAdmissionFailure? = BukkitThreadGuardBridge.runInSimulationContext {
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
        BukkitThreadGuardBridge.runInSimulationContext {
            synchronized(lock) { worlds.remove(worldId) }?.let { runtime ->
                runtime.manualTurnoutSelection?.close()
                runtime.routeAdmission?.close()
                runtime.registry.close()
            }
        }
    }

    /** Runs tail release before Dispatcher observes the same completed physics epoch. */
    fun consumeObservation(
        worldId: UUID,
        observation: RailwayObservation
    ) = BukkitThreadGuardBridge.runInSimulationContext {
        val admission = synchronized(lock) {
            check(!closed) { "InfrastructureResourceService is closed" }
            checkNotNull(worlds[worldId]?.routeAdmission) {
                "Route admission runtime is not attached for world $worldId"
            }
        }
        admission.consumeObservation(observation)
    }

    override fun setAppliedPlanDependencies(
        pluginOwner: Plugin,
        worldId: UUID,
        request: AppliedPlanDependencyRequest
    ) =
        BukkitThreadGuardBridge.runInSimulationContext {
            registry(worldId)?.setAppliedPlanDependencies(requireEnabled(pluginOwner), request)
                ?: unavailableDependencies(worldId)
        }

    override fun removeAppliedPlanDependencies(pluginOwner: Plugin, worldId: UUID, owner: InfrastructureOwner) =
        BukkitThreadGuardBridge.runInSimulationContext {
            registry(worldId)?.removeAppliedPlanDependencies(pluginOwner, owner)
                ?: unavailableDependencies(worldId)
        }

    /** Internal world-runtime seam; deliberately absent from the public add-on API. */
    fun acquireLiveClaim(pluginOwner: Plugin, worldId: UUID, request: InfrastructureClaimRequest) =
        BukkitThreadGuardBridge.runInSimulationContext {
            registry(worldId)?.acquireClaim(requireEnabled(pluginOwner), request) ?: unavailable(worldId)
        }

    fun releaseLiveClaim(pluginOwner: Plugin, worldId: UUID, owner: InfrastructureOwner) =
        BukkitThreadGuardBridge.runInSimulationContext {
            registry(worldId)?.releaseClaim(pluginOwner, owner) ?: unavailable(worldId)
        }

    override fun acquireEditPermit(
        pluginOwner: Plugin,
        worldId: UUID,
        request: TopologyEditRequest
    ): InfrastructureEditPermitResult = BukkitThreadGuardBridge.runInSimulationContext {
        val registry = registry(worldId)
            ?: return@runInSimulationContext InfrastructureEditPermitResult.denied(unavailableEdit(worldId))
        val owner = requireEnabled(pluginOwner)
        val acquired = registry.acquireEditPermit(owner, request)
        if (!acquired.decision.successful) {
            return@runInSimulationContext InfrastructureEditPermitResult.denied(acquired.decision)
        }
        val permit = object : InfrastructureEditPermit {
            private val permitClosed = AtomicBoolean()
            override fun owner(): InfrastructureOwner = request.owner
            override fun close() {
                if (permitClosed.compareAndSet(false, true)) {
                    registry.releaseEditPermit(owner, acquired.id)
                }
            }
        }
        InfrastructureEditPermitResult.granted(acquired.decision, permit)
    }

    override fun appliedPlanDependencies(worldId: UUID): List<AppliedPlanDependencySnapshot> =
        registry(worldId)?.appliedPlanDependencySnapshots().orEmpty()

    override fun liveClaims(worldId: UUID): List<InfrastructureClaimSnapshot> =
        registry(worldId)?.claimSnapshots().orEmpty()

    override fun unregisterOwner(pluginOwner: Plugin) {
        BukkitThreadGuardBridge.runInSimulationContext {
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
            val appliedDependencies = snapshot.flatMap { (worldId, runtime) ->
                runtime.registry.appliedPlanDependenciesOwnedBy(pluginOwner).map { worldId to it }
            }
            val dependencyProviderActivity = snapshot.flatMap { (worldId, runtime) ->
                runtime.registry.appliedPlanDependenciesUsingResourceTypes(providedTypeIds).map { worldId to it }
            }
            check(requesterActivity.isEmpty() && providerActivity.isEmpty() && dependencyProviderActivity.isEmpty()) {
                "Cannot unregister infrastructure owner ${pluginOwner.name} while route resources remain active: " +
                    (requesterActivity + providerActivity).distinct().joinToString { (worldId, active) ->
                        "world=$worldId route=${active.owner.ownerId} train=${active.trainId} " +
                            "pending=${active.pendingRequest} heldResources=${active.heldResourceCount}" +
                            active.resourceTypeIds.takeIf(Set<String>::isNotEmpty)
                                ?.joinToString(prefix = " types=", separator = ",").orEmpty()
                    } + dependencyProviderActivity.joinToString(prefix = " appliedPlans=", separator = ",") {
                        (worldId, dependency) ->
                        "$worldId/${dependency.owner.ownerId}@${dependency.owner.revision}"
                    }
            }
            check(appliedDependencies.isEmpty()) {
                "Cannot unregister infrastructure owner ${pluginOwner.name} while applied plans remain active: " +
                    appliedDependencies.joinToString { (worldId, dependency) ->
                        "world=$worldId plan=${dependency.owner.ownerId} revision=${dependency.owner.revision}"
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
        BukkitThreadGuardBridge.runInSimulationContext {
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
                val appliedDependencies = (
                    runtime.registry.appliedPlanDependenciesOwnedBy(pluginOwner) +
                        runtime.registry.appliedPlanDependenciesUsingResourceTypes(providedTypeIds)
                    ).distinctBy { it.owner }
                if (appliedDependencies.isNotEmpty()) {
                    val reason = "Bukkit plugin ${pluginOwner.name} in world $worldId was disabled with active " +
                        "applied plans: " + appliedDependencies.joinToString { dependency ->
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
        BukkitThreadGuardBridge.runInSimulationContext {
            val snapshot = synchronized(lock) {
                if (closed) return@runInSimulationContext
                closed = true
                resourceProviders.clear()
                routeContributors.clear()
                worlds.values.toList().also { worlds.clear() }
            }
            snapshot.forEach { runtime ->
                runtime.manualTurnoutSelection?.close()
                runtime.routeAdmission?.close()
                runtime.registry.close()
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

    private fun unavailableDependencies(worldId: UUID) = AppliedPlanDependencyMutationResult(
        AppliedPlanDependencyMutationStatus.WORLD_UNAVAILABLE,
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
