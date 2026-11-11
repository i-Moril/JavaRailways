package work.art1st.javarailways.simpleato.api.station

import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.simpleato.api.metadata.JsonMetadata

/** A stale editor must reload before replacing another writer's station metadata. */
class StationMetadataConflict(val stationId: StationId) :
    IllegalStateException("Station metadata changed: $stationId")

/** Station-facing access to the shared bounded immutable JSON policy. */
object StationMetadata {
    const val MAX_DEPTH = JsonMetadata.MAX_DEPTH
    const val MAX_NODES = JsonMetadata.MAX_NODES
    const val MAX_BYTES = JsonMetadata.MAX_BYTES

    fun immutableCopy(metadata: JsonObject): JsonObject = JsonMetadata.immutableCopy(metadata)
}
