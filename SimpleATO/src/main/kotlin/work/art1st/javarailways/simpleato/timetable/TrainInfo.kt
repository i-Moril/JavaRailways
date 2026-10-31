package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/** Direction is canonical formation orientation, never a timetable UP/DOWN direction. */
enum class TrainInfoField { NAME, CAR_COUNT, DIRECTION }

internal fun immutableTrainInfo(bindings: Map<String, TrainInfoField>): Map<String, TrainInfoField> {
    require(bindings.size <= 64 && bindings.keys.all { it.isNotBlank() && it.length <= 256 }) { "invalid train information bindings" }
    return java.util.Map.copyOf(bindings)
}

/** One published frame supplies every requested whole-train property. */
internal fun resolveTrainInfo(bindings: Map<String, TrainInfoField>, frame: TrainFrameSnapshot): JsonObject {
    return JsonObject(bindings.mapValues { (_, field) -> when (field) {
        TrainInfoField.NAME -> JsonPrimitive(frame.trainName)
        TrainInfoField.CAR_COUNT -> JsonPrimitive(frame.cars.size)
        TrainInfoField.DIRECTION -> JsonPrimitive(if (frame.reversed) "REVERSED" else "FORWARD")
    } })
}
