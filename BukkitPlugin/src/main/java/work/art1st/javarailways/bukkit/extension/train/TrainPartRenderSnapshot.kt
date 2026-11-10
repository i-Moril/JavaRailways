package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.bukkit.api.model.extension.immutablePartExtensionJson

/** Presentation values captured on the simulation owner, transported with the same car frame. */
internal class TrainPartRenderSnapshot(data: JsonObject, val tick: Long) {
    val data: JsonObject = immutablePartExtensionJson(data, 0) as JsonObject
    init { require(tick >= 0L) { "Train part render tick must be non-negative" } }
}

/** No live train/car getters: indices are physical frame order, independent of travel direction. */
internal fun captureTrainPartRenderSnapshot(
    frame: TrainFrameSnapshot,
    carIndex: Int,
    trainType: String?,
    tick: Long
): TrainPartRenderSnapshot {
    val car = frame.cars[carIndex]
    val train = buildJsonObject {
        put("id", frame.trainId)
        put("name", frame.trainName)
        if (trainType == null) put("type", JsonNull) else put("type", trainType)
        put("speedMetersPerSecond", frame.speedMetersPerSecond)
        put("reversed", frame.reversed)
        put("derailed", frame.derailed)
        put("carCount", frame.cars.size)
    }
    val data = buildJsonObject {
        put("train", train)
        put("car", buildJsonObject {
            put("id", car.carId)
            put("index", carIndex)
            put("number", carIndex + 1)
        })
        // SimpleDisplays XML bind accepts top-level strings; JS keeps the typed objects above.
        put("trainId", frame.trainId)
        put("trainName", frame.trainName)
        put("trainType", trainType.orEmpty())
        put("carId", car.carId)
        put("carNumber", (carIndex + 1).toString())
    }
    return TrainPartRenderSnapshot(data, tick)
}
