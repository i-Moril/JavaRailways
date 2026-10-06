package work.art1st.javarailways.simpleato.signal

import work.art1st.javarailways.basic.api.observation.RailwayAreaOccupancyEngine
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.RailwayEpochProcessor
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.signal.SignalEpochInput
import work.art1st.javarailways.simpleato.api.signal.SignalRailContinuationUpdate
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime

/**
 * Platform-neutral serialized host for runtime-replaceable signal sources in one Core world.
 *
 * It derives monitored-area changes once, invokes only their owning source, validates every source
 * result before the first publication, and then publishes in stable source/train order. Any
 * callback, validation, or publication failure escapes [process] so Core stalls the epoch closed.
 */
class SignalEpochRuntime(
    private val worldId: String,
    private val publisher: TrainRestrictionPublisher,
    private val completeEpoch: (RailwayStateStamp, Set<String>) -> Unit = { _, _ -> },
    private val invalidateInspectionDeclarations: (String, Set<String>) -> Unit = { _, _ -> }
) : RailwayEpochProcessor {
    private var inspectionRuntime: InspectionRuntime? = null
    private var previousClearanceLiveTrainIds: Set<String> = emptySet()

    fun attachInspectionRuntime(runtime: InspectionRuntime) {
        check(inspectionRuntime == null || inspectionRuntime === runtime)
        inspectionRuntime = runtime
    }

    @Volatile
    private var state = SourceState.create(emptyList())
    private val topologyHandoffLock = Any()
    private class SourcePreparation {
        var candidate: SourceState? = null
        val changesDuringCompilation = arrayListOf<RailTopologyChange>()
    }
    private var preparingSources: SourcePreparation? = null

    /** Bounded transaction handoff. Area geometry is only recompiled by source replacement. */
    fun invalidateTopology(change: RailTopologyChange) = synchronized(topologyHandoffLock) {
        state.invalidateTopology(change)
        preparingSources?.let { preparation ->
            val candidate = preparation.candidate
            if (candidate == null) preparation.changesDuringCompilation += change
            else candidate.invalidateTopology(change)
        }
    }

    // Only detached values cross from simulation admission to the physics epoch. Repeated changes
    // before the next epoch replace one owner entry; an idle epoch takes no snapshot of all owners.
    private val admissionLock = Any()
    private val activeAdmissions = linkedMapOf<InfrastructureOwner, ActiveRouteAdmissionUpdate>()
    private data class AdmissionChange(
        val update: ActiveRouteAdmissionUpdate,
        // Kept only until this latest change is consumed, so a replacement baseline can route a removal.
        val previous: ActiveRouteAdmissionUpdate? = null
    )
    private var changedAdmissions = linkedMapOf<InfrastructureOwner, AdmissionChange>()
    private val directionLock = Any()
    private var changedDirectionRails = linkedSetOf<java.util.UUID>()

    /** Store writers call this after releasing the policy lock. Multiple writes coalesce per epoch. */
    fun publishOperatingDirectionChanges(railIds: Set<java.util.UUID>) = synchronized(directionLock) {
        changedDirectionRails.addAll(railIds)
        Unit
    }

    private fun takeOperatingDirectionChanges(): Set<java.util.UUID> = synchronized(directionLock) {
        java.util.Set.copyOf(changedDirectionRails).also { changedDirectionRails = linkedSetOf() }
    }

    fun publishAdmissionUpdate(update: ActiveRouteAdmissionUpdate) = synchronized(admissionLock) {
        val pendingChange = changedAdmissions[update.owner]
        val previous = if (pendingChange == null) activeAdmissions[update.owner] else pendingChange.previous
        if (update.current == null) activeAdmissions.remove(update.owner)
        else activeAdmissions[update.owner] = update
        changedAdmissions[update.owner] = AdmissionChange(update, previous)
    }

    private fun takeAdmissionUpdates(baseline: Boolean): List<AdmissionChange> =
        synchronized(admissionLock) {
            val values = if (baseline) {
                val current = activeAdmissions.mapValuesTo(linkedMapOf()) { (_, update) -> AdmissionChange(update) }
                changedAdmissions.forEach { (owner, change) -> current[owner] = change }
                current.values
            } else changedAdmissions.values
            java.util.List.copyOf(values).also { changedAdmissions = linkedMapOf() }
        }

    init {
        require(worldId.isNotBlank()) { "worldId must not be blank" }
    }

    /**
     * Replaces the complete installed source set after validating and compiling it off the live
     * epoch path. An in-progress [process] keeps its captured old state; the next epoch observes the
     * new state and establishes a fresh monitored-area baseline.
     */
    fun replaceSources(sources: Collection<SignalEpochSource>) {
        replaceSourcesAtomically(sources) {}
    }

    /**
     * Compiles a complete source state before [commitAction], then installs it only after the
     * callback returns successfully. Replacements are serialized by the caller; [process] may
     * finish its captured old state concurrently, without waiting for the commit callback.
     */
    fun replaceSourcesAtomically(
        sources: Collection<SignalEpochSource>,
        commitAction: () -> Unit
    ) {
        val preparation = SourcePreparation()
        val previous = synchronized(topologyHandoffLock) {
            check(preparingSources == null) { "signal source replacements must be serialized" }
            preparingSources = preparation
            state
        }
        try {
            val candidate = SourceState.create(sources, previous.occupancyEngine)
            synchronized(topologyHandoffLock) {
                // Retain events only for this finite compile, then deliver directly to both states.
                // The caller's potentially paused commit never retains transaction/root history.
                preparation.changesDuringCompilation.forEach(candidate::invalidateTopology)
                preparation.changesDuringCompilation.clear()
                preparation.candidate = candidate
            }
            commitAction()
            synchronized(topologyHandoffLock) { state = candidate }
        } finally {
            synchronized(topologyHandoffLock) { preparingSources = null }
        }
    }

    override fun process(frame: RailwayWorldFrameSnapshot) {
        val current = state
        val stamp = RailwayStateStamp(worldId, frame.physicsEpoch, frame.topologyRevision)
        val liveTrainIds = frame.trains.mapTo(hashSetOf()) { it.trainId }
        (previousClearanceLiveTrainIds - liveTrainIds).forEach { inspectionRuntime?.withdrawClearancesForTrain(it) }
        previousClearanceLiveTrainIds = liveTrainIds
        if (current.sources.isEmpty()) {
            inspectionRuntime?.takeClearanceUpdates(false)
            completeEpoch(stamp, java.util.Set.copyOf(liveTrainIds))
            return
        }

        val observation = RailwayObservation.fromCore(worldId, frame)
        val validated = ArrayList<TrainRestrictionSnapshot>()
        val invalidations = linkedMapOf<String, Set<String>>()
        val topologySources = current.takeTopologyChanges(observation.stamp.topologyRevision)
        val initialBaseline = current.previousTopologyRevision == null
        val clearanceUpdates = inspectionRuntime?.takeClearanceUpdates(initialBaseline).orEmpty().groupBy { it.sourceId }
        val admissionUpdatesBySource = current.consumeAdmissionUpdates(
            takeAdmissionUpdates(current.previousTopologyRevision == null), liveTrainIds
        )
        current.previousTopologyRevision = observation.stamp.topologyRevision

        val areaBatch = current.occupancyEngine.consume(observation)
        val updatesBySource = areaBatch.updates.groupBy { update ->
            current.ownerByArea.getValue(update.areaId)
        }
        val continuationUpdatesBySource = current.consumeContinuationUpdates(
            observation.activeContinuations
        )
        val directionChangesBySource = current.directionChangesBySource(takeOperatingDirectionChanges())
        current.sources.forEach { source ->
            val topologyBaseline = initialBaseline || source.sourceId in topologySources
            val updates = updatesBySource[source.sourceId].orEmpty()
            val continuationUpdates = continuationUpdatesBySource[source.sourceId].orEmpty()
            val admissionUpdates = admissionUpdatesBySource[source.sourceId].orEmpty()
            val directionChanges = directionChangesBySource[source.sourceId].orEmpty()
            val readiness = source.watchedInspectionSourceIds.flatMap { clearanceUpdates[it].orEmpty() }
            if (updates.isEmpty() && continuationUpdates.isEmpty() && admissionUpdates.isEmpty() &&
                directionChanges.isEmpty() && readiness.isEmpty() && !topologyBaseline
            ) return@forEach
            val result = source.handler.update(
                SignalEpochInput.create(observation, updates, continuationUpdates, admissionUpdates, topologyBaseline, directionChanges, readiness)
            )
            validateResult(source, observation, liveTrainIds, result.publications)
            validated += result.publications
            if (result.invalidatedInspectionDeclarationIds.isNotEmpty()) {
                invalidations[source.sourceId] = result.invalidatedInspectionDeclarationIds
            }
        }

        validated.sortedWith(compareBy(TrainRestrictionSnapshot::sourceId, TrainRestrictionSnapshot::trainId))
            .forEach { snapshot ->
                val result = publisher.publish(snapshot)
                check(result.status == TrainRestrictionPublicationStatus.ACCEPTED) {
                    "restriction publication for ${snapshot.sourceId}/${snapshot.trainId} was ${result.status}"
                }
            }
        invalidations.forEach(invalidateInspectionDeclarations)
        completeEpoch(stamp, java.util.Set.copyOf(liveTrainIds))
    }

    private fun validateResult(
        source: SignalEpochSource,
        observation: RailwayObservation,
        liveTrainIds: Set<String>,
        restrictions: List<TrainRestrictionSnapshot>
    ) {
        require(restrictions.map(TrainRestrictionSnapshot::trainId).distinct().size == restrictions.size) {
            "signal source ${source.sourceId} returned more than one snapshot for a train"
        }
        restrictions.forEach { snapshot ->
            require(snapshot.sourceId == source.sourceId) {
                "signal source ${source.sourceId} returned snapshot for ${snapshot.sourceId}"
            }
            require(snapshot.observationStamp == observation.stamp) {
                "signal source ${source.sourceId} returned a snapshot for another observation"
            }
            require(snapshot.trainId in liveTrainIds) {
                "signal source ${source.sourceId} returned snapshot for missing train ${snapshot.trainId}"
            }
        }
    }

    private class SourceState(
        val sources: List<SignalEpochSource>,
        val ownerByArea: Map<RailObjectRef, String>,
        val occupancyEngine: RailwayAreaOccupancyEngine,
        private val ownersByContinuationEntry: Map<RailEndpointRef, Set<String>>,
        private val ownersByAdmissionRail: Map<java.util.UUID, Set<String>>,
        private val ownersByDirectionRail: Map<java.util.UUID, Set<String>>
    ) {
        var previousTopologyRevision: Long? = null
        private var notifiedTopologyRevision: Long? = null
        private val topologyLock = Any()
        private val changedTopologySources = linkedSetOf<String>()

        fun invalidateTopology(change: RailTopologyChange) = synchronized(topologyLock) {
            occupancyEngine.invalidateTopology(change).forEach { area ->
                ownerByArea[area]?.let(changedTopologySources::add)
            }
            change.affectedRailIds.forEach { rail ->
                changedTopologySources.addAll(ownersByAdmissionRail[rail].orEmpty())
                changedTopologySources.addAll(ownersByDirectionRail[rail].orEmpty())
                changedTopologySources.addAll(ownersByTopologyRail[rail].orEmpty())
            }
            notifiedTopologyRevision = change.current.revision
        }

        fun takeTopologyChanges(revision: Long): Set<String> = synchronized(topologyLock) {
            check(previousTopologyRevision == null || previousTopologyRevision == revision ||
                notifiedTopologyRevision == revision) { "topology changed without a transaction handoff" }
            java.util.Set.copyOf(changedTopologySources).also { changedTopologySources.clear() }
        }

        private val ownersByTopologyRail: Map<java.util.UUID, Set<String>> =
            ownersByContinuationEntry.entries.groupBy { it.key.railId }
                .mapValues { (_, entries) -> entries.flatMapTo(linkedSetOf()) { it.value } }
        private var previousContinuations: ActiveRailContinuationsSnapshot? = null
        private data class AdmissionInterest(
            val update: ActiveRouteAdmissionUpdate,
            val sourceIds: Set<String>
        ) {
            val snapshot get() = checkNotNull(update.current)
        }
        private val admissionInterests = hashMapOf<InfrastructureOwner, AdmissionInterest>()
        private val admissionOwnersByTrain = hashMapOf<String, MutableSet<InfrastructureOwner>>()
        private var previousLiveTrainIds: Set<String>? = null

        fun directionChangesBySource(rails: Set<java.util.UUID>): Map<String, Set<java.util.UUID>> {
            val result = linkedMapOf<String, MutableSet<java.util.UUID>>()
            rails.forEach { rail -> ownersByDirectionRail[rail].orEmpty().forEach { source ->
                result.getOrPut(source, ::linkedSetOf).add(rail)
            } }
            return result.mapValues { java.util.Set.copyOf(it.value) }
        }

        fun consumeAdmissionUpdates(
            updates: List<AdmissionChange>, liveTrainIds: Set<String>
        ): Map<String, List<ActiveRouteAdmissionUpdate>> {
            val bySource = linkedMapOf<String, MutableMap<InfrastructureOwner, ActiveRouteAdmissionUpdate>>()
            updates.forEach { change ->
                val update = change.update
                val previous = admissionInterests[update.owner] ?: change.previous?.let { old ->
                    AdmissionInterest(old, checkNotNull(old.current).route.segments.flatMapTo(linkedSetOf()) {
                        ownersByAdmissionRail[it.railId].orEmpty()
                    })
                }
                previous?.let { old ->
                    admissionOwnersByTrain[old.snapshot.trainId]?.let { owners ->
                        owners.remove(update.owner)
                        if (owners.isEmpty()) admissionOwnersByTrain.remove(old.snapshot.trainId)
                    }
                }
                val route = update.current?.route
                val interest = when {
                    route == null -> null
                    previous?.snapshot?.route === route -> previous.copy(update = update)
                    else -> AdmissionInterest(update, route.segments.flatMapTo(linkedSetOf()) {
                        ownersByAdmissionRail[it.railId].orEmpty()
                    })
                }
                if (interest == null) admissionInterests.remove(update.owner)
                else {
                    admissionInterests[update.owner] = interest
                    admissionOwnersByTrain.getOrPut(interest.snapshot.trainId, ::linkedSetOf) += update.owner
                }
                val targets = previous?.sourceIds.orEmpty() + interest?.sourceIds.orEmpty()
                targets.forEach { sourceId ->
                    bySource.getOrPut(sourceId, ::linkedMapOf)[update.owner] = update
                }
            }
            val previousLive = previousLiveTrainIds
            if (previousLive != null && previousLive != liveTrainIds) {
                val changedTrains = (previousLive - liveTrainIds) + (liveTrainIds - previousLive)
                changedTrains.forEach { trainId ->
                    admissionOwnersByTrain[trainId].orEmpty().forEach { owner ->
                        val interest = admissionInterests.getValue(owner)
                        interest.sourceIds.forEach { sourceId ->
                            bySource.getOrPut(sourceId, ::linkedMapOf)[owner] = interest.update
                        }
                    }
                }
            }
            previousLiveTrainIds = liveTrainIds
            return bySource.mapValues { java.util.List.copyOf(it.value.values) }
        }

        fun consumeContinuationUpdates(
            current: ActiveRailContinuationsSnapshot
        ): Map<String, List<SignalRailContinuationUpdate>> {
            if (ownersByContinuationEntry.isEmpty()) return emptyMap()
            val previous = previousContinuations
            if (previous != null &&
                previous.topologyRevision == current.topologyRevision &&
                previous.selectionRevision == current.selectionRevision
            ) {
                return emptyMap()
            }
            val updatesBySource = linkedMapOf<String, MutableList<SignalRailContinuationUpdate>>()
            ownersByContinuationEntry.forEach { (entry, owners) ->
                val selectedExit = current.selectedContinuation(entry)
                if (previous != null && previous.selectedContinuation(entry) == selectedExit) {
                    return@forEach
                }
                val update = SignalRailContinuationUpdate(entry, selectedExit)
                owners.forEach { owner ->
                    updatesBySource.getOrPut(owner, ::arrayListOf) += update
                }
            }
            previousContinuations = current
            return updatesBySource.mapValues { (_, updates) -> java.util.List.copyOf(updates) }
        }

        companion object {
            fun create(
                sources: Collection<SignalEpochSource>,
                previousOccupancy: RailwayAreaOccupancyEngine? = null
            ): SourceState {
                val ordered = java.util.List.copyOf(sources.sortedBy(SignalEpochSource::sourceId))
                require(ordered.map(SignalEpochSource::sourceId).distinct().size == ordered.size) {
                    "signal source IDs must be unique"
                }
                val areaOwners = linkedMapOf<RailObjectRef, String>()
                val continuationOwners = linkedMapOf<RailEndpointRef, MutableSet<String>>()
                val admissionOwners = linkedMapOf<java.util.UUID, MutableSet<String>>()
                val directionOwners = linkedMapOf<java.util.UUID, MutableSet<String>>()
                ordered.forEach { source ->
                    source.areas.forEach { area ->
                        require(areaOwners.putIfAbsent(area.areaId, source.sourceId) == null) {
                            "monitored area ${area.areaId} belongs to more than one signal source"
                        }
                    }
                    source.watchedContinuationEntries.forEach { entry ->
                        continuationOwners.getOrPut(entry, ::sortedSetOf) += source.sourceId
                    }
                    source.watchedAdmissionRailIds.forEach { rail ->
                        admissionOwners.getOrPut(rail, ::sortedSetOf) += source.sourceId
                    }
                    source.watchedOperatingDirectionRailIds.forEach { rail ->
                        directionOwners.getOrPut(rail, ::sortedSetOf) += source.sourceId
                    }
                }
                return SourceState(
                    ordered,
                    java.util.Map.copyOf(areaOwners),
                    ordered.flatMap(SignalEpochSource::areas).let { areas ->
                        previousOccupancy?.prepareReplacement(areas) ?: RailwayAreaOccupancyEngine.create(areas)
                    },
                    java.util.Collections.unmodifiableMap(
                        LinkedHashMap<RailEndpointRef, Set<String>>().also { copied ->
                            continuationOwners.toSortedMap().forEach { (entry, owners) ->
                                copied[entry] = java.util.Collections.unmodifiableSet(
                                    LinkedHashSet(owners)
                                )
                            }
                        }
                    ),
                    java.util.Map.copyOf(admissionOwners.mapValues { java.util.Set.copyOf(it.value) }),
                    java.util.Map.copyOf(directionOwners.mapValues { java.util.Set.copyOf(it.value) })
                )
            }
        }
    }
}
