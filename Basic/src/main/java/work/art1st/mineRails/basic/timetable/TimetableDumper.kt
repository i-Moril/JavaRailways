package work.art1st.mineRails.basic.timetable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object TimetableDumper {
    private val json = Json { prettyPrint = true }

    fun dumpToJson(timetable: Timetable): String = json.encodeToString(JsonObject.serializer(), timetable.toJson())

    private fun Timetable.toJson(): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "periodTicks" to (periodTicks?.let { JsonPrimitive(it) } ?: JsonNull),
            "services" to JsonArray(services.map { it.toJson() })
        )
    )

    private fun TimetableService.toJson(): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "trainSelector" to trainSelector.toJson(),
            "steps" to JsonArray(steps.map { it.toJson() })
        )
    )

    private fun TrainSelector.toJson(): JsonObject = when (this) {
        TrainSelector.AnyTrain -> JsonObject(mapOf("type" to JsonPrimitive("anyTrain")))
        is TrainSelector.TrainId -> JsonObject(
            mapOf("type" to JsonPrimitive("trainId"), "trainId" to JsonPrimitive(trainId))
        )
        is TrainSelector.WayPointPool -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("wayPointPool"),
                "wayPointIds" to JsonArray(wayPointIds.map { JsonPrimitive(it) })
            )
        )
    }

    private fun TimetableStep.toJson(): JsonObject = when (this) {
        is TimetableStep.Check -> JsonObject(
            mapOf("type" to JsonPrimitive("check"), "wayPointId" to JsonPrimitive(wayPointId))
        )
        is TimetableStep.At -> JsonObject(
            mapOf("type" to JsonPrimitive("at"), "tick" to JsonPrimitive(tick), "action" to action.toJson())
        )
    }

    private fun TimetableAction.toJson(): JsonObject = when (this) {
        is TimetableAction.GoTo -> JsonObject(
            mapOf("type" to JsonPrimitive("goto"), "wayPointId" to JsonPrimitive(wayPointId))
        )
        is TimetableAction.GoVia -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("goVia"),
                "wayPointIds" to JsonArray(wayPointIds.map { JsonPrimitive(it) })
            )
        )
        is TimetableAction.Stop -> JsonObject(
            mapOf("type" to JsonPrimitive("stop"), "ticks" to JsonPrimitive(ticks))
        )
        is TimetableAction.CallStation -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("callStation"),
                "stationId" to JsonPrimitive(stationId),
                "berthId" to JsonPrimitive(berthId),
                "minimumDwellTicks" to (minimumDwellTicks?.let(::JsonPrimitive) ?: JsonNull),
                "plannedDepartureTick" to (plannedDepartureTick?.let(::JsonPrimitive) ?: JsonNull),
                "stoppingOffsetMetersBefore" to
                    (stoppingOffsetMetersBefore?.let(::JsonPrimitive) ?: JsonNull),
                "doorSideOverride" to (doorSideOverride?.name?.let(::JsonPrimitive) ?: JsonNull)
            )
        )
    }
}
