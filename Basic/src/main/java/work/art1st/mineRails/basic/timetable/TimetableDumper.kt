package work.art1st.mineRails.basic.timetable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import work.art1st.mineRails.basic.api.timetable.TimetableCondition
import work.art1st.mineRails.basic.api.timetable.TimetableExtensionCall
import work.art1st.mineRails.basic.api.timetable.TimetableServiceActivation

object TimetableDumper {
    private val json = Json { prettyPrint = true }

    fun dumpToJson(timetable: Timetable): String = json.encodeToString(JsonObject.serializer(), timetable.toJson())

    private fun Timetable.toJson(): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "periodDays" to JsonPrimitive(periodDays),
            "periodTicks" to JsonPrimitive(periodTicks),
            "services" to JsonArray(services.map { it.toJson() }),
            "initialPositions" to JsonArray(initialPositions.map { position -> JsonObject(mapOf(
                "trainSelector" to position.trainSelector.toJson(),
                "wayPointSelector" to position.wayPointSelector.toJson()
            )) }),
            "diagramLines" to JsonArray(diagramLines.map { line -> JsonObject(mapOf(
                "id" to JsonPrimitive(line.id),
                "upStationIds" to JsonArray(line.upStationIds.map(::JsonPrimitive)),
                "downStationIds" to JsonArray(line.downStationIds.map(::JsonPrimitive))
            )) })
        )
    )

    private fun TimetableObjectSelector.toJson(): JsonObject = when (this) {
        is TimetableObjectSelector.Regex -> JsonObject(mapOf(
            "type" to JsonPrimitive("regex"), "pattern" to JsonPrimitive(pattern)
        ))
        is TimetableObjectSelector.And -> JsonObject(mapOf(
            "type" to JsonPrimitive("and"), "selectors" to JsonArray(selectors.map { it.toJson() })
        ))
        is TimetableObjectSelector.Or -> JsonObject(mapOf(
            "type" to JsonPrimitive("or"), "selectors" to JsonArray(selectors.map { it.toJson() })
        ))
        is TimetableObjectSelector.Not -> JsonObject(mapOf(
            "type" to JsonPrimitive("not"), "selector" to selector.toJson()
        ))
        is TimetableObjectSelector.Tags -> JsonObject(mapOf(
            "type" to JsonPrimitive("tags"), "tags" to JsonArray(required.sorted().map(::JsonPrimitive))
        ))
    }

    private fun TimetableService.toJson(): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "trainSelector" to trainSelector.toJson(),
            "activation" to activation.toJson(),
            "steps" to JsonArray(steps.map { it.toJson() })
        )
    )

    private fun TimetableServiceActivation.toJson(): JsonObject = when (this) {
        TimetableServiceActivation.Scheduled -> JsonObject(
            mapOf("type" to JsonPrimitive("scheduled"))
        )
        TimetableServiceActivation.Manual -> JsonObject(
            mapOf("type" to JsonPrimitive("manual"))
        )
        is TimetableServiceActivation.Conditional -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("conditional"),
                "condition" to condition.toJson(),
                "falseBehavior" to JsonPrimitive(falseBehavior.name)
            )
        )
    }

    private fun TimetableCondition.toJson(): JsonObject = when (this) {
        is TimetableCondition.Predicate -> JsonObject(
            mapOf("type" to JsonPrimitive("predicate"), "call" to call.toJson())
        )
        is TimetableCondition.All -> JsonObject(
            mapOf("type" to JsonPrimitive("all"), "conditions" to JsonArray(conditions.map { it.toJson() }))
        )
        is TimetableCondition.Any -> JsonObject(
            mapOf("type" to JsonPrimitive("any"), "conditions" to JsonArray(conditions.map { it.toJson() }))
        )
        is TimetableCondition.Not -> JsonObject(
            mapOf("type" to JsonPrimitive("not"), "condition" to condition.toJson())
        )
    }

    private fun TimetableExtensionCall.toJson(): JsonObject = JsonObject(
        mapOf(
            "typeId" to JsonPrimitive(typeId),
            "version" to JsonPrimitive(version),
            "parameters" to parameters
        )
    )

    private fun TrainSelector.toJson(): JsonObject = when (this) {
        TrainSelector.AnyTrain -> JsonObject(mapOf("type" to JsonPrimitive("anyTrain")))
        is TrainSelector.TrainId -> JsonObject(
            mapOf("type" to JsonPrimitive("trainId"), "trainId" to JsonPrimitive(trainId))
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
        is TimetableAction.TakeFromDepot -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("takeFromDepot"),
                "depotId" to JsonPrimitive(selector.depotId.value),
                "tags" to JsonArray(selector.requiredTags.sorted().map(::JsonPrimitive)),
                "minimumCars" to (selector.minimumCars?.let(::JsonPrimitive) ?: JsonNull),
                "maximumCars" to (selector.maximumCars?.let(::JsonPrimitive) ?: JsonNull),
                "minimumSpeedMetersPerSecond" to
                    (selector.minimumSpeedMetersPerSecond?.let(::JsonPrimitive) ?: JsonNull),
                "capabilities" to
                    JsonArray(selector.requiredCapabilities.sorted().map(::JsonPrimitive))
            )
        )
        is TimetableAction.TakeFromWaypoint -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("takeFromWaypoint"),
                "wayPointIds" to JsonArray(wayPointIds.map(::JsonPrimitive))
            )
        )
        is TimetableAction.StoreAtDepot -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("storeAtDepot"),
                "depotId" to JsonPrimitive(depotId.value),
                "stablingPoints" to JsonArray(stablingPointIds.sorted().map(::JsonPrimitive))
            )
        )
        is TimetableAction.GoTo -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("destination"),
                "destinationId" to JsonPrimitive(wayPointId)
            )
        )
        is TimetableAction.GoVia -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("destination"),
                "passWayPointIds" to JsonArray(wayPointIds.dropLast(1).map(::JsonPrimitive)),
                "destinationId" to (wayPointIds.lastOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            )
        )
        is TimetableAction.Stop -> JsonObject(
            mapOf("type" to JsonPrimitive("stop"), "ticks" to JsonPrimitive(ticks))
        )
        TimetableAction.Halt -> JsonObject(
            mapOf("type" to JsonPrimitive("halt"))
        )
        TimetableAction.Reverse -> JsonObject(
            mapOf("type" to JsonPrimitive("reverse"))
        )
        is TimetableAction.DestinationOptions -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("destinationOptions"),
                "destinationId" to JsonPrimitive(destinationId),
                "arrivalTick" to (arrivalTick?.let(::JsonPrimitive) ?: JsonNull),
                "minimumDwellTicks" to (minimumDwellTicks?.let(::JsonPrimitive) ?: JsonNull),
                "stoppingOffsetMetersBefore" to
                    (stoppingOffsetMetersBefore?.let(::JsonPrimitive) ?: JsonNull),
                "doorSide" to (doorSide?.name?.let(::JsonPrimitive) ?: JsonNull)
            )
        )
        is TimetableAction.Extension -> JsonObject(
            mapOf("type" to JsonPrimitive("extension"), "call" to call.toJson())
        )
        is TimetableAction.Conditional -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("conditional"),
                "condition" to condition.toJson(),
                "thenActions" to JsonArray(thenActions.map { it.toJson() }),
                "elseActions" to JsonArray(elseActions.map { it.toJson() })
            )
        )
        is TimetableAction.CallStation -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("stationDestinationOptions"),
                "destinationId" to JsonPrimitive("$stationId@$berthId"),
                "arrivalTick" to (arrivalTick?.let(::JsonPrimitive) ?: JsonNull),
                "minimumDwellTicks" to (minimumDwellTicks?.let(::JsonPrimitive) ?: JsonNull),
                "stoppingOffsetMetersBefore" to
                    (stoppingOffsetMetersBefore?.let(::JsonPrimitive) ?: JsonNull),
                "doorSideOverride" to (doorSideOverride?.name?.let(::JsonPrimitive) ?: JsonNull)
            )
        )
    }
}
