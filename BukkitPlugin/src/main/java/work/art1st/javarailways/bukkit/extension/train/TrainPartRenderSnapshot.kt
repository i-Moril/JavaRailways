package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.bukkit.api.model.extension.immutablePartExtensionJson

/** Presentation values captured on the simulation owner, transported with the same car frame. */
internal class TrainPartRenderSnapshot(
    data: JsonObject,
    val tick: Long,
    contextKey: JsonObject = OperationalPartRenderProjection.EMPTY.contextKey,
) {
    val data: JsonObject = immutablePartExtensionJson(data, 0) as JsonObject
    val contextKey: JsonObject = immutablePartExtensionJson(contextKey, 0) as JsonObject
    init { require(tick >= 0L) { "Train part render tick must be non-negative" } }
}

/** No live train/car getters: indices are physical frame order, independent of travel direction. */
internal fun captureTrainPartRenderSnapshot(
    frame: TrainFrameSnapshot,
    carIndex: Int,
    trainType: String?,
    tick: Long,
    operational: OperationalPartRenderProjection = OperationalPartRenderProjection.EMPTY,
    lines: JsonObject = JsonObject(emptyMap()),
    operatingTail: Boolean? = null,
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
        put("unit", operational.unit)
        put("operational", operational.operational)
        put("lines", lines)
        operational.aliases.forEach { (key, value) -> put(key, value) }
        put("car", buildJsonObject {
            put("id", car.carId)
            put("index", carIndex)
            put("number", carIndex + 1)
            put("operatingEnd", operatingTail?.let { if (it) "tail" else "head" }?.let(::JsonPrimitive) ?: JsonNull)
        })
        // SimpleDisplays XML bind accepts top-level strings; JS keeps the typed objects above.
        put("trainId", frame.trainId)
        put("trainName", frame.trainName)
        put("trainType", trainType.orEmpty())
        put("carId", car.carId)
        put("carNumber", (carIndex + 1).toString())
    }
    val key = if (operatingTail == null) operational.contextKey else buildJsonObject {
        operational.contextKey.forEach { (name, value) -> put(name, value) }
        put("carOperatingEnd", if (operatingTail) "tail" else "head")
    }
    return TrainPartRenderSnapshot(data, tick, key)
}
