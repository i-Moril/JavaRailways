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

/**
 * Serialized platform-neutral owner of compiled inspection bindings and activation state.
 *
 * Source replacement, dirty publication, update dispatch and removal must run on the same railway
 * owner; this class deliberately adds no second executor, retry loop or plugin registry.
 */
class InspectionRuntime(
    private val publisher: TrainRestrictionPublisher,
    private val sourceUnavailable: (sourceId: String, trainId: String, failure: Exception) -> Unit
) {
    private data class BindingKey(val sourceId: String, val trainId: String)

    private data class Session(
        val compiled: CompiledInspectionBinding,
        val scheduler: InspectionScheduler,
        val handler: InspectionUpdateHandler,
        val source: InspectionSource,
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
        val session: Session,
        val publication: TrainRestrictionSnapshot
    )

    private val sessionsByTrain = linkedMapOf<String, TreeMap<String, Session>>()
    private var sourcesById = sortedMapOf<String, InspectionSource>()
    private val activeRoutesByTrain = linkedMapOf<String, ActiveRoute>()

    @Volatile
    private var maximumLeadTimesByTrain: Map<String, Double> = emptyMap()

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
        val candidateSessions = linkedMapOf<String, TreeMap<String, Session>>()
        try {
            activeRoutesByTrain.forEach { (trainId, activeRoute) ->
                candidateSessions[trainId] = compileSessions(
                    trainId,
                    activeRoute,
                    candidateSources,
                    sessionsByTrain[trainId]
                )
            }
        } catch (failure: BindingCompilationFailure) {
            notifyUnavailable(failure.key, failure)
            throw failure
        }
        val unsafeChanges = activeRoutesByTrain.keys.flatMap { trainId ->
            val current = sessionsByTrain[trainId].orEmpty()
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
        sessionsByTrain.clear()
        sessionsByTrain.putAll(candidateSessions)
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
        if (current?.route == route && sessionsByTrain[trainId]?.keys == sourcesById.keys) {
            activeRoutesByTrain[trainId] = activeRoute
            return maximumDeclaredLeadTimeSeconds(trainId)
        }
        val compiled = try {
            compileSessions(trainId, activeRoute, sourcesById, sessionsByTrain[trainId])
        } catch (failure: BindingCompilationFailure) {
            notifyUnavailable(failure.key, failure)
            throw failure
        }
        activeRoutesByTrain[trainId] = activeRoute
        if (compiled.isEmpty()) sessionsByTrain.remove(trainId) else sessionsByTrain[trainId] = compiled
        publishCompiledViews()
        return maximumDeclaredLeadTimeSeconds(trainId)
    }

    /** Marks matching declarations in every current train binding for one signal source. */
    fun markDirty(sourceId: String, declarationIds: Collection<String>) {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        if (declarationIds.isEmpty()) return
        sessionsByTrain.values.forEach { sessions ->
            sessions[sourceId]?.scheduler?.markPresentDirty(declarationIds)
        }
    }

    /** Thread-safe immutable maximum lead-time view used to size the shared perception scan. */
    fun maximumDeclaredLeadTimeSeconds(trainId: String): Double {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        return maximumLeadTimesByTrain[trainId] ?: 0.0
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
        return sessionsByTrain[trainId]?.isNotEmpty() == true
    }

    /** Low-frequency lifecycle query used to distinguish idle config rejection from live failure. */
    fun hasActiveRoutes(): Boolean = activeRoutesByTrain.isNotEmpty()

    /** Stable diagnostics for lifecycle checks; this does not expose sessions or callbacks. */
    fun activeBindings(sourceIds: Set<String>): List<String> {
        if (sourceIds.isEmpty()) return emptyList()
        return sessionsByTrain.entries.flatMap { (trainId, sessions) ->
            sessions.keys.asSequence()
                .filter(sourceIds::contains)
                .map { sourceId -> "$trainId/$sourceId" }
                .toList()
        }.sorted()
    }

    /**
     * Dispatches binding baselines and changed or invalidated complete active sets in stable source
     * order.
     *
     * All callback results are validated before the first publication. Any failure notifies the
     * fail-closed owner for the affected source and is then rethrown to the serialized caller.
     */
    fun update(
        observationStamp: RailwayStateStamp,
        currentRoute: FrozenRouteBinding,
        routeProgressMeters: Double,
        dynamicLookaheadMeters: Double,
        maximumSpeedMetersPerSecond: Double,
        accelerationMetersPerSecondSquared: Double,
        perception: PerceptionSnapshot
    ) {
        val trainId = perception.train.trainId
        val trainSessions = sessionsByTrain[trainId].orEmpty().entries
            .map { (sourceId, session) -> BindingKey(sourceId, trainId) to session }
        if (trainSessions.isEmpty()) return

        val staleSessions = trainSessions.filter { (_, session) ->
            session.compiled.binding.route != currentRoute
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
                val publications = java.util.List.copyOf(session.handler.update(input))
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

    fun removeTrain(trainId: String) {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        sessionsByTrain.remove(trainId)
        activeRoutesByTrain.remove(trainId)
        publishCompiledViews()
    }

    fun removeSource(sourceId: String) {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        sessionsByTrain.entries.removeIf { (_, bySource) ->
            bySource.remove(sourceId)
            bySource.isEmpty()
        }
        sourcesById.remove(sourceId)
        publishCompiledViews()
    }

    /** World teardown only: discards all callbacks and route-local state without an unload stall. */
    fun clear() {
        sessionsByTrain.clear()
        activeRoutesByTrain.clear()
        sourcesById.clear()
        publishCompiledViews()
    }

    /** Removes sessions for trains whose production route lifecycle has ended. */
    fun retainTrains(trainIds: Set<String>) {
        sessionsByTrain.keys.removeIf { it !in trainIds }
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

    private fun compileSessions(
        trainId: String,
        activeRoute: ActiveRoute,
        sources: Map<String, InspectionSource>,
        current: Map<String, Session>?
    ): TreeMap<String, Session> {
        val routeBinding = FrozenRouteBinding.from(activeRoute.route)
        val compiled = TreeMap<String, Session>()
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
                compiled[sourceId] = Session(
                    compiledBinding,
                    InspectionScheduler(compiledBinding),
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
        maximumLeadTimesByTrain = sessionsByTrain.mapValues { (_, bySource) ->
            bySource.values.maxOfOrNull {
                it.scheduler.maximumDeclaredInspectionLeadTimeSeconds
            } ?: 0.0
        }.toMap()
        authorityRequestFloorsByTrain = sessionsByTrain.mapNotNull { (trainId, bySource) ->
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
