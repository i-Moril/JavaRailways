package work.art1st.javarailways.simpleato.signal

import work.art1st.javarailways.basic.api.observation.RailwayAreaOccupancyEngine
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.RailwayEpochProcessor
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.signal.SignalEpochInput
import work.art1st.javarailways.simpleato.api.signal.SignalRailContinuationUpdate
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource

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
    @Volatile
    private var state = SourceState.create(emptyList())

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
     * callback returns successfully. The caller owns serialization with [process].
     */
    fun replaceSourcesAtomically(
        sources: Collection<SignalEpochSource>,
        commitAction: () -> Unit
    ) {
        val candidate = SourceState.create(sources)
        commitAction()
        state = candidate
    }

    override fun process(frame: RailwayWorldFrameSnapshot) {
        val current = state
        val stamp = RailwayStateStamp(worldId, frame.physicsEpoch, frame.topologyRevision)
        val liveTrainIds = frame.trains.mapTo(hashSetOf()) { it.trainId }
        if (current.sources.isEmpty()) {
            completeEpoch(stamp, java.util.Set.copyOf(liveTrainIds))
            return
        }

        val observation = RailwayObservation.fromCore(worldId, frame)
        val validated = ArrayList<TrainRestrictionSnapshot>()
        val invalidations = linkedMapOf<String, Set<String>>()

        val areaBatch = current.occupancyEngine.consume(observation)
        val updatesBySource = areaBatch.updates.groupBy { update ->
            current.ownerByArea.getValue(update.areaId)
        }
        val continuationUpdatesBySource = current.consumeContinuationUpdates(
            observation.activeContinuations
        )
        current.sources.forEach { source ->
            val updates = updatesBySource[source.sourceId].orEmpty()
            val continuationUpdates = continuationUpdatesBySource[source.sourceId].orEmpty()
            if (updates.isEmpty() && continuationUpdates.isEmpty()) return@forEach
            val result = source.handler.update(
                SignalEpochInput.create(observation, updates, continuationUpdates)
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
        private val ownersByContinuationEntry: Map<RailEndpointRef, Set<String>>
    ) {
        private var previousContinuations: ActiveRailContinuationsSnapshot? = null

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
                if (previous != null &&
                    previous.topologyRevision == current.topologyRevision &&
                    previous.selectedContinuation(entry) == selectedExit
                ) {
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
            fun create(sources: Collection<SignalEpochSource>): SourceState {
                val ordered = java.util.List.copyOf(sources.sortedBy(SignalEpochSource::sourceId))
                require(ordered.map(SignalEpochSource::sourceId).distinct().size == ordered.size) {
                    "signal source IDs must be unique"
                }
                val areaOwners = linkedMapOf<RailObjectRef, String>()
                val continuationOwners = linkedMapOf<RailEndpointRef, MutableSet<String>>()
                ordered.forEach { source ->
                    source.areas.forEach { area ->
                        require(areaOwners.putIfAbsent(area.areaId, source.sourceId) == null) {
                            "monitored area ${area.areaId} belongs to more than one signal source"
                        }
                    }
                    source.watchedContinuationEntries.forEach { entry ->
                        continuationOwners.getOrPut(entry, ::sortedSetOf) += source.sourceId
                    }
                }
                return SourceState(
                    ordered,
                    java.util.Map.copyOf(areaOwners),
                    RailwayAreaOccupancyEngine.create(ordered.flatMap(SignalEpochSource::areas)),
                    java.util.Collections.unmodifiableMap(
                        LinkedHashMap<RailEndpointRef, Set<String>>().also { copied ->
                            continuationOwners.toSortedMap().forEach { (entry, owners) ->
                                copied[entry] = java.util.Collections.unmodifiableSet(
                                    LinkedHashSet(owners)
                                )
                            }
                        }
                    )
                )
            }
        }
    }
}
