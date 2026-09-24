package work.art1st.javarailways.simpleato.signal

import work.art1st.javarailways.basic.api.observation.RailwayAreaOccupancyEngine
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.core.api.physics.RailwayEpochProcessor
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.signal.SignalEpochInput
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource

/**
 * Platform-neutral serialized host for static signal sources in one Core world.
 *
 * It derives monitored-area changes once, invokes only their owning source, validates every source
 * result before the first publication, and then publishes in stable source/train order. Any
 * callback, validation, or publication failure escapes [process] so Core stalls the epoch closed.
 */
class SignalEpochRuntime(
    private val worldId: String,
    sources: Collection<SignalEpochSource>,
    private val publisher: TrainRestrictionPublisher
) : RailwayEpochProcessor {
    private val sources: List<SignalEpochSource>
    private val ownerByArea: Map<RailObjectRef, String>
    private val occupancyEngine: RailwayAreaOccupancyEngine

    init {
        require(worldId.isNotBlank()) { "worldId must not be blank" }
        this.sources = java.util.List.copyOf(sources.sortedBy(SignalEpochSource::sourceId))
        require(this.sources.map(SignalEpochSource::sourceId).distinct().size == this.sources.size) {
            "signal source IDs must be unique"
        }
        val areaOwners = linkedMapOf<RailObjectRef, String>()
        this.sources.forEach { source ->
            source.areas.forEach { area ->
                require(areaOwners.putIfAbsent(area.areaId, source.sourceId) == null) {
                    "monitored area ${area.areaId} belongs to more than one signal source"
                }
            }
        }
        ownerByArea = java.util.Map.copyOf(areaOwners)
        occupancyEngine = RailwayAreaOccupancyEngine.create(this.sources.flatMap(SignalEpochSource::areas))
    }

    override fun process(frame: RailwayWorldFrameSnapshot) {
        val observation = RailwayObservation.fromCore(worldId, frame)
        val areaBatch = occupancyEngine.consume(observation)
        val updatesBySource = areaBatch.updates.groupBy { update -> ownerByArea.getValue(update.areaId) }
        val liveTrainIds = observation.trains.mapTo(hashSetOf()) { it.trainId }
        val validated = ArrayList<TrainRestrictionSnapshot>()

        sources.forEach { source ->
            val updates = updatesBySource[source.sourceId].orEmpty()
            if (updates.isEmpty()) return@forEach
            val result = source.handler.update(SignalEpochInput.create(observation, updates))
            validateResult(source, observation, liveTrainIds, result.publications)
            validated += result.publications
        }

        validated.sortedWith(compareBy(TrainRestrictionSnapshot::sourceId, TrainRestrictionSnapshot::trainId))
            .forEach { snapshot ->
                val result = publisher.publish(snapshot)
                check(result.status == TrainRestrictionPublicationStatus.ACCEPTED) {
                    "restriction publication for ${snapshot.sourceId}/${snapshot.trainId} was ${result.status}"
                }
            }
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
}
