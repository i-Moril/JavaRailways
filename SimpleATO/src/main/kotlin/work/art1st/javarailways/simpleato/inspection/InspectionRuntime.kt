package work.art1st.javarailways.simpleato.inspection

import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionBindingInput
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateHandler
import work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateInput
import work.art1st.javarailways.simpleato.api.inspection.InspectionClearanceSnapshot
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.routing.DirectedRouteContinuation
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID

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
        val rangeDeclarationIds: Set<String> = java.util.Set.copyOf(
            compiled.binding.speedRanges.map { it.declarationId }
        )
    }

    private data class PhysicsSession(
        val definition: CompiledSourceBinding,
        val scheduler: InspectionScheduler = InspectionScheduler(definition.compiled),
        var baselinePending: Boolean = true
    )

    internal class ActiveRoute(
        @Volatile var observationStamp: RailwayStateStamp,
        val route: RouteSnapshot,
        var trainLengthMeters: Double? = null,
        var authorityStoppingMarginMeters: Double = 0.0
    ) {
        val geometryLength = ConservativeTrainLengthEnvelope()
        @Volatile var available: Boolean = true
        val railIds: Set<UUID> = route.segments.mapTo(linkedSetOf()) { it.railId }
    }

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
    private val routesByRail = ConcurrentHashMap<UUID, Set<ActiveRoute>>()
    @Volatile private var observedTopologyRevision: Long? = null
    @Volatile private var completeTopologyHandoff = true

    /** Called in the topology transaction before a changed root can drive the next physics epoch. */
    fun invalidateTopology(change: RailTopologyChange) {
        observedTopologyRevision?.let { known ->
            if (known != change.previous.revision) completeTopologyHandoff = false
        }
        observedTopologyRevision = change.current.revision
        change.affectedRailIds.forEach { rail ->
            routesByRail[rail].orEmpty().forEach { route -> route.available = false }
        }
    }

    /** The same frozen-route rail index also fences direction edits without a signal plugin. */
    fun invalidateOperatingDirections(railIds: Set<UUID>) {
        railIds.forEach { rail ->
            routesByRail[rail].orEmpty().forEach { route -> route.available = false }
        }
    }

    private fun forgetRoute(trainId: String) {
        activeRoutesByTrain.remove(trainId)?.let { route ->
            route.railIds.forEach { rail ->
                routesByRail.computeIfPresent(rail) { _, routes -> (routes - route).takeIf { it.isNotEmpty() } }
            }
        }
    }

    /** Low-frequency geometry completion; each source sees the same original route exactly once. */
    fun completeTerminalRoute(route: RouteSnapshot, proposedMovingRoute: RouteSnapshot = route,
        movingContinuationFailure: String? = null): RouteSnapshot {
        val sources = sourcesById
        var suffix = emptyList<work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot>()
        var handled = false
        var explicitEnd = false
        sources.values.forEach { source ->
            try {
                val offered = source.terminalRouteContinuation.continuation(route, proposedMovingRoute)
                    ?: return@forEach
                val candidate = java.util.List.copyOf(offered)
                handled = true
                if (candidate.isEmpty()) {
                    require(suffix.isEmpty()) { "terminal policies disagree on an explicit authorization end" }
                    explicitEnd = true
                }
                if (candidate.isNotEmpty()) {
                    require(!explicitEnd) { "terminal policies disagree on an explicit authorization end" }
                    require(candidate.all { it.lengthMeters > 0.0 }) { "continuation contains an empty segment" }
                    val end = requireNotNull(route.segments.lastOrNull()) { "cannot extend an empty route" }
                    val start = candidate.first()
                    require(end.railId != start.railId || (end.facing == start.facing &&
                        kotlin.math.abs(end.endArcLengthMeters - start.startArcLengthMeters) <= 1.0e-6)
                    ) { "continuation does not start at the selected endpoint" }
                    val unmatched = DirectedRouteContinuation.unmatchedSuffix(suffix, candidate)
                    if (unmatched.isNotEmpty()) suffix = suffix + unmatched
                }
            } catch (failure: Exception) {
                throw IllegalArgumentException("inspection source ${source.sourceId} could not complete route " +
                    "${route.routeId}: ${failure.message ?: failure.javaClass.name}", failure)
            }
        }
        if (!handled) {
            require(movingContinuationFailure == null) { movingContinuationFailure ?: "terminal margin is unavailable" }
            return proposedMovingRoute
        }
        return if (suffix.isEmpty()) route else RouteSnapshot(route.routeId, route.revision,
            route.topologyRevision, route.segments + suffix)
    }

    /** Collects static edit references without creating train bindings or publishing restrictions. */
    fun collectRouteInfrastructureDependencies(
        routes: Collection<RouteSnapshot>
    ): Set<StableInfrastructureResource> {
        val sources = sourcesById
        val resources = linkedSetOf<StableInfrastructureResource>()
        sources.values.forEach { source ->
            routes.forEach { route ->
                try {
                    resources.addAll(source.routeInfrastructureDependencies.dependencies(route))
                } catch (failure: Exception) {
                    throw IllegalArgumentException(
                        "inspection source ${source.sourceId} could not collect dependencies for " +
                            "route ${route.routeId}: ${failure.message ?: failure.javaClass.name}",
                        failure
                    )
                }
            }
        }
        return java.util.Collections.unmodifiableSet(resources)
    }

    @Volatile
    private var publishedBindingsByTrain: Map<String, TrainBinding> = emptyMap()

    /** Compiled definitions captured together with the controller's frozen-route mapping. */
    inner class TrainBinding internal constructor(
        internal val definitions: Map<String, CompiledSourceBinding>,
        internal val activeRoute: ActiveRoute? = null
    ) {
        internal val runtime: InspectionRuntime get() = this@InspectionRuntime
        internal fun currentTrainLength(frame: TrainFrameSnapshot?, trainId: String, physicsEpoch: Long): Double? =
            activeRoute?.geometryLength?.observe(currentTrainLengthMeters(frame, trainId, physicsEpoch), physicsEpoch)
        val maximumDeclaredLeadTimeSeconds: Double = definitions.values.maxOfOrNull {
            it.compiled.maximumDeclaredInspectionLeadTimeSeconds
        } ?: 0.0
        internal val entryClearances = CompiledEntryClearances(definitions.flatMap { (sourceId, binding) ->
            binding.compiled.binding.entryClearances.map { BoundEntryClearance(sourceId, it) }
        })
    }

    fun bindingFor(trainId: String): TrainBinding =
        publishedBindingsByTrain[trainId] ?: TrainBinding(emptyMap())

    @Volatile
    private var authorityRequestFloorsByTrain = ConcurrentHashMap<String, CompiledAuthorityRequestFloors>()

    private val clearanceLock = Any()
    private val clearanceSnapshots = linkedMapOf<Pair<String, String>, InspectionClearanceSnapshot>()
    private val changedClearanceSnapshots = linkedMapOf<Pair<String, String>, InspectionClearanceSnapshot>()

    /** Worker-owned proofs are immutable; the serialized signal owner consumes only changes. */
    fun publishClearance(snapshot: InspectionClearanceSnapshot) = synchronized(clearanceLock) {
        val current = publishedBindingsByTrain[snapshot.trainId]?.definitions?.get(snapshot.sourceId)
        if (current?.compiled?.binding?.route != snapshot.route) return@synchronized
        val key = snapshot.sourceId to snapshot.trainId
        val old = clearanceSnapshots[key]
        clearanceSnapshots[key] = snapshot
        if (old?.route != snapshot.route || old.clearances != snapshot.clearances)
            changedClearanceSnapshots[key] = snapshot
    }

    internal fun takeClearanceUpdates(baseline: Boolean): List<InspectionClearanceSnapshot> = synchronized(clearanceLock) {
        val updates = if (baseline) clearanceSnapshots + changedClearanceSnapshots else changedClearanceSnapshots
        java.util.List.copyOf(updates.values).also { changedClearanceSnapshots.clear() }
    }

    private fun clearClearances(predicate: (Pair<String, String>) -> Boolean) = synchronized(clearanceLock) {
        clearanceSnapshots.keys.filter(predicate).forEach { key ->
            val old = clearanceSnapshots.remove(key)!!
            changedClearanceSnapshots[key] = InspectionClearanceSnapshot(old.sourceId, old.trainId,
                old.observationStamp, old.route, emptyList())
        }
    }

    /** A vanished physical train cannot leave a reusable ready proof behind. */
    internal fun withdrawClearancesForTrain(trainId: String) = clearClearances { it.second == trainId }

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
        route: RouteSnapshot,
        authorityStoppingMarginMeters: Double = 0.0,
        publishedFrame: TrainFrameSnapshot? = null
    ): Double {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        require(authorityStoppingMarginMeters.isFinite() && authorityStoppingMarginMeters >= 0.0)
        if (observedTopologyRevision == null) observedTopologyRevision = observationStamp.topologyRevision
        check(completeTopologyHandoff && observationStamp.topologyRevision <= checkNotNull(observedTopologyRevision)) {
            "inspection topology changed without a transaction handoff"
        }
        val current = activeRoutesByTrain[trainId]
        fun currentLength(bindings: Map<String, CompiledSourceBinding>, owner: ActiveRoute): Double? {
            if (bindings.values.none { it.compiled.binding.entryClearances.isNotEmpty() }) return null
            return checkNotNull(owner.geometryLength.observe(
                currentTrainLengthMeters(publishedFrame, trainId, observationStamp.physicsEpoch), observationStamp.physicsEpoch
            )) { "Cannot request route authority for train $trainId: current published frame/body geometry is unavailable" }
        }
        if (current?.route == route && compiledBindingsByTrain[trainId]?.keys == sourcesById.keys) {
            check(current.available) { "inspection route intersects an edited rail; explicit replan is required" }
            val length = currentLength(compiledBindingsByTrain.getValue(trainId), current)
            current.observationStamp = observationStamp
            if (current.trainLengthMeters != length ||
                current.authorityStoppingMarginMeters != authorityStoppingMarginMeters) {
                current.trainLengthMeters = length
                current.authorityStoppingMarginMeters = authorityStoppingMarginMeters
                compiledBindingsByTrain.getValue(trainId).takeIf { it.isNotEmpty() }?.let {
                    authorityRequestFloorsByTrain[trainId] = compileAuthorityFloors(trainId, it)
                }
            }
            return maximumDeclaredLeadTimeSeconds(trainId)
        }
        val activeRoute = ActiveRoute(observationStamp, route, null, authorityStoppingMarginMeters)
        val compiled = try {
            compileBindings(trainId, activeRoute, sourcesById, compiledBindingsByTrain[trainId])
        } catch (failure: BindingCompilationFailure) {
            notifyUnavailable(failure.key, failure)
            throw failure
        }
        activeRoute.trainLengthMeters = currentLength(compiled, activeRoute)
        forgetRoute(trainId)
        clearClearances { it.second == trainId }
        activeRoutesByTrain[trainId] = activeRoute
        activeRoute.railIds.forEach { rail ->
            routesByRail.compute(rail) { _, routes -> routes.orEmpty() + activeRoute }
        }
        compiledBindingsByTrain[trainId] = compiled
        publishCompiledViews()
        return maximumDeclaredLeadTimeSeconds(trainId)
    }

    /** Marks matching declarations in every current train binding for one signal source. */
    fun markDirty(sourceId: String, declarationIds: Collection<String>) {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        if (declarationIds.isEmpty()) return
        publishedBindingsByTrain.values.forEach { binding ->
            binding.definitions[sourceId]?.let { session ->
                val present = declarationIds.filter {
                    session.compiled.containsDeclaration(it) || it in session.rangeDeclarationIds
                }
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

    /** A Basic retry hint is only usable after preserving every compiled atomic request window. */
    fun limitRequestedAuthorityBoundary(
        trainId: String, currentRoute: FrozenRouteBinding, ceiling: RouteBoundary
    ): RouteBoundary = authorityRequestFloorsByTrain[trainId]?.limitTo(currentRoute, ceiling) ?: ceiling

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
            binding.activeRoute?.let { active ->
                check(active.available) { "inspection route intersects an edited rail; explicit replan is required" }
                check(completeTopologyHandoff && observationStamp.topologyRevision <= checkNotNull(observedTopologyRevision)) {
                    "inspection topology changed without a transaction handoff"
                }
                require(active.observationStamp.worldId == observationStamp.worldId) {
                    "inspection observation belongs to another world"
                }
            }
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
            if (trainSessions.isEmpty()) {
                check(binding.activeRoute == null || FrozenRouteBinding.from(binding.activeRoute.route) == currentRoute) {
                    "inspection context does not match the bound frozen route"
                }
                return
            }

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

            val pending = ArrayList<PendingUpdate>()
            trainSessions.forEach { (key, session) ->
                try {
                    val dirty = session.definition.invalidations.getAndSet(emptySet())
                    session.scheduler.markPresentDirty(dirty)
                    val rangeDirty = dirty.any(session.definition.rangeDeclarationIds::contains)
                    if (rangeDirty) session.scheduler.retry()
                    val activeDeclarations = session.scheduler.updatesFor(
                        routeProgressMeters,
                        dynamicLookaheadMeters,
                        perception.train.speedMetersPerSecond,
                        maximumSpeedMetersPerSecond,
                        accelerationMetersPerSecondSquared
                    )
                    if (activeDeclarations == null && !session.baselinePending && !rangeDirty) return@forEach
                    val input = InspectionUpdateInput.create(
                        observationStamp,
                        currentRoute,
                        routeProgressMeters,
                        perception,
                        activeDeclarations.orEmpty(),
                        session.definition.compiled.binding.speedRanges
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
        forgetRoute(trainId)
        clearClearances { it.second == trainId }
        publishCompiledViews()
    }

    fun removeSource(sourceId: String) {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        compiledBindingsByTrain.entries.removeIf { (_, bySource) ->
            bySource.remove(sourceId)
            bySource.isEmpty()
        }
        sourcesById.remove(sourceId)
        clearClearances { it.first == sourceId }
        publishCompiledViews()
    }

    /** World teardown only: discards all callbacks and route-local state without an unload stall. */
    fun clear() {
        synchronized(clearanceLock) { clearanceSnapshots.clear(); changedClearanceSnapshots.clear() }
        compiledBindingsByTrain.clear()
        activeRoutesByTrain.clear()
        routesByRail.clear()
        observedTopologyRevision = null
        completeTopologyHandoff = true
        sourcesById.clear()
        publishCompiledViews()
    }

    /** Removes sessions for trains whose production route lifecycle has ended. */
    fun retainTrains(trainIds: Set<String>) {
        clearClearances { it.second !in trainIds }
        compiledBindingsByTrain.keys.removeIf { it !in trainIds }
        activeRoutesByTrain.keys.filter { it !in trainIds }.forEach(::forgetRoute)
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
        publishedBindingsByTrain = compiledBindingsByTrain.mapValues { (trainId, bySource) ->
            TrainBinding(java.util.Collections.unmodifiableMap(LinkedHashMap(bySource)), activeRoutesByTrain[trainId])
        }.toMap()
        authorityRequestFloorsByTrain = ConcurrentHashMap(compiledBindingsByTrain.mapNotNull { (trainId, bySource) ->
            if (bySource.isEmpty()) null else trainId to compileAuthorityFloors(trainId, bySource)
        }.toMap())
    }

    private fun compileAuthorityFloors(trainId: String, bySource: Map<String, CompiledSourceBinding>): CompiledAuthorityRequestFloors {
        val routes = bySource.values.map { it.compiled.binding.route }.distinct()
        check(routes.size == 1) { "inspection sources for one train must bind one frozen route" }
        return CompiledAuthorityRequestFloors.compile(
                routes.single(),
                bySource.values.flatMap { it.compiled.authorityRequestFloors } +
                    bySource.values.flatMap { it.compiled.binding.entryClearances }.map { clearance ->
                        val active = activeRoutesByTrain.getValue(trainId)
                        val length = checkNotNull(active.trainLengthMeters) {
                            "Cannot request route authority for train $trainId: current body geometry is unavailable"
                        }
                        val end = clearance.tailClearRouteDistanceMeters + length + active.authorityStoppingMarginMeters
                        check(end.isFinite()) { "Non-finite clearance boundary for train $trainId" }
                        // A clipped request is NOT clearance proof. The controller and selector
                        // retain the un-clipped target and keep the entrance closed if it cannot fit.
                        AuthorityRequestFloor(clearance.entryRouteDistanceMeters,
                            minOf(end, routes.single().totalLengthMeters),
                            clearance.declarationId)
                    }
            )
    }
}
