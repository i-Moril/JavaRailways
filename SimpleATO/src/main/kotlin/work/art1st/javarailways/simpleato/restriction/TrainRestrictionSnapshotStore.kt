package work.art1st.javarailways.simpleato.restriction

import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/** Read-only world-local view of the latest signal restriction snapshots for one train. */
fun interface TrainRestrictionSnapshotProvider {
    fun snapshotsFor(trainId: String): List<TrainRestrictionSnapshot>
}

/**
 * World-local publication store between the serialized signal epoch and Dispatcher.
 *
 * The store retains values only. It never resolves or keeps live trains/controllers. A successful
 * publication replaces one `(trainId, sourceId)` value and atomically publishes a new immutable
 * per-train snapshot; readers therefore observe either the complete old value or complete new value.
 */
class TrainRestrictionSnapshotStore(
    private val worldId: String
) : TrainRestrictionPublisher, TrainRestrictionSnapshotProvider {
    private val snapshotsByTrain = ConcurrentHashMap<String, List<TrainRestrictionSnapshot>>()

    init {
        require(worldId.isNotBlank()) { "worldId must not be blank" }
    }

    override fun publish(snapshot: TrainRestrictionSnapshot): TrainRestrictionPublicationResult {
        require(snapshot.observationStamp.worldId == worldId) {
            "restriction belongs to ${snapshot.observationStamp.worldId}, expected $worldId"
        }
        var publication: TrainRestrictionPublicationResult? = null
        snapshotsByTrain.compute(snapshot.trainId) { _, currentSnapshots ->
            val current = currentSnapshots.orEmpty()
            val previous = current.firstOrNull { it.sourceId == snapshot.sourceId }
            if (previous != null && snapshot.revision <= previous.revision) {
                publication = TrainRestrictionPublicationResult(
                    TrainRestrictionPublicationStatus.STALE_REVISION,
                    previous.revision
                )
                return@compute currentSnapshots
            }

            val updatedTrain = TreeMap<String, TrainRestrictionSnapshot>()
            current.forEach { updatedTrain[it.sourceId] = it }
            updatedTrain[snapshot.sourceId] = snapshot
            publication = TrainRestrictionPublicationResult(
                TrainRestrictionPublicationStatus.ACCEPTED,
                snapshot.revision
            )
            java.util.List.copyOf(updatedTrain.values)
        }
        return checkNotNull(publication)
    }

    override fun snapshotsFor(trainId: String): List<TrainRestrictionSnapshot> {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        return snapshotsByTrain[trainId].orEmpty()
    }

    /**
     * Returns stable diagnostics for non-empty streams that must be withdrawn before their source
     * can be removed from a live world. This is a low-frequency lifecycle query, not a tick path.
     */
    fun activeRestrictionStreams(sourceIds: Set<String>): List<String> {
        if (sourceIds.isEmpty()) return emptyList()
        return snapshotsByTrain.entries
            .flatMap { (trainId, snapshots) ->
                snapshots.asSequence()
                    .filter { it.sourceId in sourceIds && it.restrictions.isNotEmpty() }
                    .map { "$trainId/${it.sourceId}@${it.revision}" }
                    .toList()
            }
            .sorted()
    }

    /** Removes values for trains absent from a successfully completed signal epoch. */
    fun retainLiveTrains(trainIds: Set<String>) {
        snapshotsByTrain.keys.removeIf { it !in trainIds }
    }
}
