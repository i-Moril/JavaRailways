package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.*
import work.art1st.javarailways.simpleato.api.station.Station
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.api.station.StationMetadataConflict
import work.art1st.javarailways.simpleato.api.station.StationStore

internal data class StationMetadataUpdate(val metadata: JsonObject, val expectedMetadata: JsonObject)

internal fun parseStationMetadataUpdate(body: String): StationMetadataUpdate {
    val root = Json.parseToJsonElement(body) as? JsonObject
        ?: throw HttpFailure(400, "station metadata request must be an object")
    if (root.keys != setOf("metadata", "expectedMetadata")) {
        throw HttpFailure(400, "station metadata request requires only metadata and expectedMetadata")
    }
    return StationMetadataUpdate(
        root["metadata"] as? JsonObject ?: throw HttpFailure(400, "metadata must be an object"),
        root["expectedMetadata"] as? JsonObject ?: throw HttpFailure(400, "expectedMetadata must be an object")
    )
}

internal fun stationMetadataJson(station: Station): JsonObject = buildJsonObject {
    put("id", station.id.value)
    put("displayName", station.displayName)
    put("metadata", station.metadata)
}

internal fun updateStationMetadataJson(store: StationStore, id: String, update: StationMetadataUpdate): JsonObject {
    val stationId = StationId(id)
    if (store[stationId] == null) throw HttpFailure(404, "station not found: $id")
    val updated = try {
        store.updateMetadata(stationId, update.metadata, update.expectedMetadata)
    } catch (conflict: StationMetadataConflict) {
        throw HttpFailure(409, conflict.message ?: "station metadata changed; reload before saving", "STATION_METADATA_CONFLICT")
    } catch (missing: NoSuchElementException) {
        throw HttpFailure(404, "station not found: $id")
    }
    return stationMetadataJson(updated)
}
