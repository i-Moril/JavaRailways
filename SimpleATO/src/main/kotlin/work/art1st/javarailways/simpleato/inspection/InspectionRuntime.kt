package work.art1st.javarailways.simpleato.inspection

import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionBindingInput
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateHandler
import work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateInput
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Railway-owner compiler and publisher of inspection bindings.
 *
 * Lifecycle operations publish immutable compiled views. Each controller owns its [TrainInspection]
 * activation state; no lifecycle operation mutates a physics scheduler. Signal epochs run after
 * train workers join and hand off only the bounded set of invalidated declaration IDs.
 */
class InspectionRuntime(
    private val publisher: TrainRestrictionPublisher,
    private val sourceUnavailable: (sourceId: String, trainId: String, failure: Exception) -> Unit
) {
    private data class BindingKey(val sourceId: String, val trainId: String)

    internal data class CompiledSourceBinding(
        val compiled: CompiledInspectionBinding,
        val handler: InspectionUpdateHandler,
        val source: InspectionSource
    ) {
        val invalidations = AtomicReference<Set<String>>(emptySet())
    }

    private data class PhysicsSession(
        val definition: CompiledSourceBinding,
        val scheduler: InspectionScheduler = InspectionScheduler(definition.compiled),
        var baselinePending: Boolean = true
    )

    private data class ActiveRoute(
        val observationStamp: RailwayStateStamp,
        val route: RouteSnapshot
    )

    private class BindingCompilationFailure(
        val key: BindingKey,
        cause: Exception
    ) : IllegalArgumentException(
        "inspection source ${key.sourceId} could not bind train ${key.trainId}: " +
            (cause.message ?: cause.javaClass.name),
        cause
    )

    private data class PendingUpdate(
        val key: BindingKey,
        val session: PhysicsSession,
        val publication: TrainRestrictionSnapshot
    )

    private val compiledBindingsByTrain = linkedMapOf<String, TreeMap<String, CompiledSourceBinding>>()
    private var sourcesById = sortedMapOf<String, InspectionSource>()
    private val activeRoutesByTrain = linkedMapOf<String, ActiveRoute>()

    @Volatile
    private var publishedBindingsByTrain: Map<String, TrainBinding> = emptyMap()

    /** Compiled definitions captured together with the controller's frozen-route mapping. */
    inner class TrainBinding internal constructor(internal val definitions: Map<String, CompiledSourceBinding>) {
        internal val runtime: InspectionRuntime get() = this@InspectionRuntime
        val maximumDeclaredLeadTimeSeconds: Double = definitions.values.maxOfOrNull {
            it.compiled.maximumDeclaredInspectionLeadTimeSeconds
        } ?: 0.0
    }

    fun bindingFor(trainId: String): TrainBinding =
        publishedBindingsByTrain[trainId] ?: TrainBinding(emptyMap())

    @Volatile
    private var authorityRequestFloorsByTrain: Map<String, CompiledAuthorityRequestFloors> = emptyMap()

    /**
     * Atomically replaces the complete world source set.
     *
     * Every candidate source is first compiled for every currently active train/route. A provider
     * failure or invalid binding leaves both the installed source set and all sessions unchanged.
     */
    fun replaceSources(sources: Collection<InspectionSource>) {
        val candidateSources = TreeMap<String, InspectionSource>()
        sources.forEach { source ->
            require(candidateSources.put(source.sourceId, source) == null) {
                "inspection source IDs must be unique"
            }
        }
        val candidateSessions = linkedMapOf<String, TreeMap<String, CompiledSourceBinding>>()
        try {
            activeRoutesByTrain.forEach { (trainId, activeRoute) ->
                candidateSessions[trainId] = compileBindings(
                    trainId,
                    activeRoute,
                    candidateSources,
                    compiledBindingsByTrain[trainId]
                )
            }
        } catch (failure: BindingCompilationFailure) {
            notifyUnavailable(failure.key, failure)
            throw failure
        }
        val unsafeChanges = activeRoutesByTrain.keys.flatMap { trainId ->
            val current = compiledBindingsByTrain[trainId].orEmpty()
            val candidate = candidateSessions[trainId].orEmpty()
            (current.keys + candidate.keys).mapNotNull { sourceId ->
                if (current[sourceId]?.source === candidate[sourceId]?.source) null
                else BindingKey(sourceId, trainId)
            }
        }
        if (unsafeChanges.isNotEmpty()) {
            val failure = IllegalStateException(
                "cannot install a new inspection binding while its train has an active route; " +
                    "the world is stalled before another physics epoch"
            )
            unsafeChanges.forEach { notifyUnavailable(it, failure) }
            throw failure
        }
        sourcesById = candidateSources
        compiledBindingsByTrain.clear()
        compiledBindingsByTrain.putAll(candidateSessions)
        publishCompiledViews()
    }

    /**
     * Binds every installed source to one exact host-selected train route in one atomic replacement.
     */
    fun synchronizeRoute(
        observationStamp: RailwayStateStamp,
        trainId: String,
        route: RouteSnapshot
    ): Double {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        val activeRoute = ActiveRoute(observationStamp, route)
        val current = activeRoutesByTrain[trainId]
        if (current?.route == route && compiledBindingsByTrain[trainId]?.keys == sourcesById.keys) {
            activeRoutesByTrain[trainId] = activeRoute
            return maximumDeclaredLeadTimeSeconds(trainId)
        }
        val compiled = try {
            compileBindings(trainId, activeRoute, sourcesById, compiledBindingsByTrain[trainId])
        } catch (failure: BindingCompilationFailure) {
            notifyUnavailable(failure.key, failure)
            throw failure
        }
        activeRoutesByTrain[trainId] = activeRoute
        if (compiled.isEmpty()) compiledBindingsByTrain.remove(trainId) else compiledBindingsByTrain[trainId] = compiled
        publishCompiledViews()
        return maximumDeclaredLeadTimeSeconds(trainId)
    }

    /** Marks matching declarations in every current train binding for one signal source. */
    fun markDirty(sourceId: String, declarationIds: Collection<String>) {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        if (declarationIds.isEmpty()) return
        publishedBindingsByTrain.values.forEach { binding ->
            binding.definitions[sourceId]?.let { session ->
                val present = declarationIds.filter(session.compiled::containsDeclaration)
                if (present.isNotEmpty()) {
                    session.invalidations.updateAndGet { it + present }
                }
            }
        }
    }

    /** Thread-safe immutable maximum lead-time view used to size the shared perception scan. */
    fun maximumDeclaredLeadTimeSeconds(trainId: String): Double {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        return publishedBindingsByTrain[trainId]?.maximumDeclaredLeadTimeSeconds ?: 0.0
    }

    /** Applies precompiled route-local request floors without invoking a signal plugin callback. */
    fun adjustRequestedAuthorityBoundary(
        trainId: String,
        currentRoute: FrozenRouteBinding,
        nominalBoundary: RouteBoundary
    ): RouteBoundary {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        return authorityRequestFloorsByTrain[trainId]
            ?.adjust(currentRoute, nominalBoundary)
            ?: nominalBoundary
    }

    fun hasBindings(trainId: String): Boolean {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        return compiledBindingsByTrain[trainId]?.isNotEmpty() == true
    }

    /** Low-frequency lifecycle query used to distinguish idle config rejection from live failure. */
    fun hasActiveRoutes(): Boolean = activeRoutesByTrain.isNotEmpty()

    /** Stable diagnostics for lifecycle checks; this does not expose sessions or callbacks. */
    fun activeBindings(sourceIds: Set<String>): List<String> {
        if (sourceIds.isEmpty()) return emptyList()
        return compiledBindingsByTrain.entries.flatMap { (trainId, sessions) ->
            sessions.keys.asSequence()
                .filter(sourceIds::contains)
                .map { sourceId -> "$trainId/$sourceId" }
                .toList()
        }.sorted()
    }

    /** Creates state owned exclusively by this train's controller/physics worker. */
    fun createTrainInspection(trainId: String): TrainInspection {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        return TrainInspection(trainId)
    }

    /**
     * Consumes compiled bindings and invalidations before motion planning, using the existing scan.
     * Different trains may invoke their handlers concurrently. Results for one train validate before
     * its first publication; failures notify the existing fail-closed owner and abort control.
     */
    inner class TrainInspection internal constructor(private val trainId: String) {
        internal val runtime: InspectionRuntime get() = this@InspectionRuntime
        private var sessions: List<PhysicsSession> = emptyList()

        fun update(
            observationStamp: RailwayStateStamp,
            currentRoute: FrozenRouteBinding,
            routeProgressMeters: Double,
            dynamicLookaheadMeters: Double,
            maximumSpeedMetersPerSecond: Double,
            accelerationMetersPerSecondSquared: Double,
            perception: PerceptionSnapshot,
            binding: TrainBinding = bindingFor(trainId)
        ) {
            require(perception.train.trainId == trainId) { "inspection perception belongs to another train" }
            // A controller captures route + compiled definitions in one publication. A route append
            // must not mix its old mapping with newly published world definitions. Teardown, however,
            // discards the worker's state without calling a removed source.
            val current = publishedBindingsByTrain[trainId]?.definitions.orEmpty()
            val definitions = binding.definitions.values.filter { captured ->
                current[captured.source.sourceId]?.source === captured.source
            }
            if (definitions.size != sessions.size || definitions.indices.any {
                    definitions[it] !== sessions[it].definition
                }) {
                // Lifecycle only swaps definitions. Scheduler/baseline reset happens on this worker.
                val existingBySource = sessions.associateBy { it.definition.source.sourceId }
                sessions = definitions.map { definition ->
                    existingBySource[definition.source.sourceId]
                        ?.takeIf { it.definition === definition } ?: PhysicsSession(definition)
                }
            }
            val trainSessions = sessions.map { BindingKey(it.definition.source.sourceId, trainId) to it }
            if (trainSessions.isEmpty()) return

            val staleSessions = trainSessions.filter { (_, session) ->
                session.definition.compiled.binding.route != currentRoute
            }
            if (staleSessions.isNotEmpty()) {
                val failure = IllegalStateException(
                    "inspection binding does not match the current frozen route"
                )
                staleSessions.forEach { (key, _) -> notifyUnavailable(key, failure) }
                throw failure
            }
            require(observationStamp.topologyRevision == currentRoute.topologyRevision) {
                "observation and route must use the same topology revision"
            }
            require(perception.route.topologyRevision == currentRoute.topologyRevision) {
                "perception and route must use the same topology revision"
            }

            val pending = ArrayList<PendingUpdate>()
            trainSessions.forEach { (key, session) ->
                try {
                    session.scheduler.markPresentDirty(session.definition.invalidations.getAndSet(emptySet()))
                    val activeDeclarations = session.scheduler.updatesFor(
                        routeProgressMeters,
                        dynamicLookaheadMeters,
                        perception.train.speedMetersPerSecond,
                        maximumSpeedMetersPerSecond,
                        accelerationMetersPerSecondSquared
                    )
                    if (activeDeclarations == null && !session.baselinePending) return@forEach
                    val input = InspectionUpdateInput.create(
                        observationStamp,
                        currentRoute,
                        routeProgressMeters,
                        perception,
                        activeDeclarations.orEmpty()
                    )
                    val publications = java.util.List.copyOf(session.definition.handler.update(input))
                    validatePublications(key, observationStamp, currentRoute, publications)
                    require(publications.size == 1) {
                        "inspection source ${key.sourceId} must publish one complete snapshot for " +
                            key.trainId
                    }
                    pending += PendingUpdate(key, session, publications.single())
                } catch (failure: Exception) {
                    pending.forEach(::requeue)
                    session.scheduler.retry()
                    failSource(key, failure)
                }
            }

            pending.forEachIndexed { index, update ->
                val snapshot = update.publication
                try {
                    val result = publisher.publish(snapshot)
                    check(result.status == TrainRestrictionPublicationStatus.ACCEPTED) {
                        "restriction publication for ${snapshot.sourceId}/${snapshot.trainId} was ${result.status}"
                    }
                    update.session.baselinePending = false
                } catch (failure: Exception) {
                    pending.subList(index, pending.size).forEach(::requeue)
                    failSource(update.key, failure)
                }
            }
        }
    }

    fun removeTrain(trainId: String) {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        compiledBindingsByTrain.remove(trainId)
        activeRoutesByTrain.remove(trainId)
        publishCompiledViews()
    }

    fun removeSource(sourceId: String) {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        compiledBindingsByTrain.entries.removeIf { (_, bySource) ->
            bySource.remove(sourceId)
            bySource.isEmpty()
        }
        sourcesById.remove(sourceId)
        publishCompiledViews()
    }

    /** World teardown only: discards all callbacks and route-local state without an unload stall. */
    fun clear() {
        compiledBindingsByTrain.clear()
        activeRoutesByTrain.clear()
        sourcesById.clear()
        publishCompiledViews()
    }

    /** Removes sessions for trains whose production route lifecycle has ended. */
    fun retainTrains(trainIds: Set<String>) {
        compiledBindingsByTrain.keys.removeIf { it !in trainIds }
        activeRoutesByTrain.keys.removeIf { it !in trainIds }
        publishCompiledViews()
    }

    private fun validatePublications(
        key: BindingKey,
        observationStamp: RailwayStateStamp,
        route: FrozenRouteBinding,
        publications: List<TrainRestrictionSnapshot>
    ) {
        require(publications.size <= 1) {
            "inspection source ${key.sourceId} returned more than one snapshot for ${key.trainId}"
        }
        publications.forEach { snapshot ->
            require(snapshot.sourceId == key.sourceId) {
                "inspection source ${key.sourceId} returned snapshot for ${snapshot.sourceId}"
            }
            require(snapshot.trainId == key.trainId) {
                "inspection source ${key.sourceId} returned snapshot for ${snapshot.trainId}"
            }
            require(snapshot.observationStamp == observationStamp) {
                "inspection source ${key.sourceId} returned a snapshot for another observation"
            }
            require(snapshot.route == route) {
                "inspection source ${key.sourceId} returned a snapshot for another frozen route"
            }
        }
    }

    private fun failSource(key: BindingKey, failure: Exception): Nothing {
        notifyUnavailable(key, failure)
        throw failure
    }

    private fun notifyUnavailable(key: BindingKey, failure: Exception) {
        try {
            sourceUnavailable(key.sourceId, key.trainId, failure)
        } catch (notificationFailure: Exception) {
            failure.addSuppressed(notificationFailure)
        }
    }

    private fun requeue(update: PendingUpdate) = update.session.scheduler.retry()

    private fun compileBindings(
        trainId: String,
        activeRoute: ActiveRoute,
        sources: Map<String, InspectionSource>,
        current: Map<String, CompiledSourceBinding>?
    ): TreeMap<String, CompiledSourceBinding> {
        val routeBinding = FrozenRouteBinding.from(activeRoute.route)
        val compiled = TreeMap<String, CompiledSourceBinding>()
        sources.forEach { (sourceId, source) ->
            val existing = current?.get(sourceId)
            if (existing?.source === source && existing.compiled.binding.route == routeBinding) {
                compiled[sourceId] = existing
                return@forEach
            }
            try {
                val binding = source.bindingProvider.bind(
                    InspectionBindingInput(activeRoute.observationStamp, trainId, activeRoute.route)
                )
                require(binding.sourceId == sourceId) {
                    "inspection source $sourceId returned binding for ${binding.sourceId}"
                }
                require(binding.trainId == trainId) {
                    "inspection source $sourceId returned binding for ${binding.trainId}"
                }
                val compiledBinding = CompiledInspectionBinding.compile(binding, activeRoute.route)
                compiled[sourceId] = CompiledSourceBinding(
                    compiledBinding,
                    source.updateHandler,
                    source
                )
            } catch (failure: Exception) {
                throw BindingCompilationFailure(BindingKey(sourceId, trainId), failure)
            }
        }
        return compiled
    }

    private fun publishCompiledViews() {
        publishedBindingsByTrain = compiledBindingsByTrain.mapValues { (_, bySource) ->
            TrainBinding(java.util.Collections.unmodifiableMap(LinkedHashMap(bySource)))
        }.toMap()
        authorityRequestFloorsByTrain = compiledBindingsByTrain.mapNotNull { (trainId, bySource) ->
            if (bySource.isEmpty()) return@mapNotNull null
            val routes = bySource.values.map { it.compiled.binding.route }.distinct()
            check(routes.size == 1) { "inspection sources for one train must bind one frozen route" }
            trainId to CompiledAuthorityRequestFloors.compile(
                routes.single(),
                bySource.values.flatMap { it.compiled.authorityRequestFloors }
            )
        }.toMap()
    }
}
