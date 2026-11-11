package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.json.*
import work.art1st.javarailways.bukkit.api.model.extension.immutablePartExtensionJson
import work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus
import work.art1st.javarailways.simpleato.timetable.TimetableDisplayPhase
import work.art1st.javarailways.simpleato.timetable.TimetableUnitDisplayContext
import work.art1st.javarailways.simpleato.timetable.TimetableUnitStopSnapshot

/** Detached operational values only; neither domain objects nor the owner-side name resolver escape. */
internal class OperationalPartRenderProjection(unit: JsonElement, operational: JsonElement, aliases: JsonObject) {
    val unit: JsonElement = immutablePartExtensionJson(unit, 0)
    val operational: JsonElement = immutablePartExtensionJson(operational, 0)
    val aliases: JsonObject = immutablePartExtensionJson(aliases, 0) as JsonObject
    val contextKey: JsonObject = immutablePartExtensionJson(buildJsonObject {
        put("unit", this@OperationalPartRenderProjection.unit)
        put("operational", this@OperationalPartRenderProjection.operational)
    }, 0) as JsonObject

    companion object {
        val EMPTY = OperationalPartRenderProjection(JsonNull, JsonNull, emptyOperationalAliases())
    }
}

private fun emptyOperationalAliases(): JsonObject = buildJsonObject {
    put("destination", "")
    put("runType", "")
    put("currentStationName", "")
    put("nextStationName", "")
}

/** Projects exact owner-captured unit facts. Callers supply only a station-ID-to-name lookup. */
internal fun projectOperationalPartRender(
    context: TimetableUnitDisplayContext?,
    stationMetadata: (String) -> JsonObject = { JsonObject(emptyMap()) },
    stationName: (String) -> String?,
): OperationalPartRenderProjection {
    if (context == null) return OperationalPartRenderProjection.EMPTY
    val unit = buildJsonObject {
        put("id", context.unitId)
        put("carIds", JsonArray(context.carIds.map(::JsonPrimitive)))
    }
    val display = context.display?.takeIf {
        it.active && it.unitId == context.unitId && context.timetableId != null
    } ?: return OperationalPartRenderProjection(unit, JsonNull, emptyOperationalAliases())

    fun stop(value: TimetableUnitStopSnapshot): JsonObject = buildJsonObject {
        put("serviceId", value.serviceId)
        put("runId", value.runId)
        put("callId", value.call.id)
        put("stationId", value.call.stationId)
        put("stationName", stationName(value.call.stationId)?.let(::JsonPrimitive) ?: JsonNull)
        put("metadata", stationMetadata(value.call.stationId))
        put("destinationId", value.call.destinationId)
        put("status", value.call.status.name)
        put("arrivalTick", value.call.arrivalTick?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("departureTick", value.call.departureTick?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("completed", value.completed)
    }
    val currentBerth = context.currentBerth
    val current = currentBerth?.let { berth -> buildJsonObject {
        put("stationId", berth.stationId.value)
        put("stationName", stationName(berth.stationId.value)?.let(::JsonPrimitive) ?: JsonNull)
        put("metadata", stationMetadata(berth.stationId.value))
        put("berthId", berth.berthId)
    } }
    val nextStart = when (context.progress.phase) {
        TimetableDisplayPhase.IN_TRANSIT -> context.progress.stationIndex
        TimetableDisplayPhase.AT_STATION -> context.progress.stationIndex?.plus(1)
        TimetableDisplayPhase.FINISHED, TimetableDisplayPhase.UNKNOWN -> null
    }
    val next = nextStart?.takeIf { display.unresolvedRuns.isEmpty() }?.let { start ->
        display.plannedStops.drop(start).firstOrNull {
            it.call.status == TimetableServiceStopStatus.STOP || it.call.status == TimetableServiceStopStatus.TERMINAL
        }?.let(::stop)
    }
    // Presence, rather than value/type/truthiness, defines an authored destination override.
    // This is a presentation default; the captured operational values remain untouched.
    val defaultTerminal = context.currentRun?.terminalStationId?.takeIf { "destination" !in display.values }
    val defaultStationName = defaultTerminal?.let(stationName)?.takeIf { it.isNotBlank() }
    val defaultDestination = defaultTerminal?.let { id ->
        when (val name = stationMetadata(id)["name"]) {
            is JsonPrimitive -> name.takeIf { it.isString && it.content.isNotBlank() }
            is JsonObject -> buildJsonObject {
                for (language in listOf("zh", "en")) {
                    (name[language] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }
                        ?.let { put(language, it) }
                }
            }.takeIf { it.isNotEmpty() }
            else -> null
        } ?: defaultStationName?.let(::JsonPrimitive)
    }
    val defaultDestinationAlias = when (defaultDestination) {
        is JsonPrimitive -> defaultDestination.content
        is JsonObject -> defaultStationName ?: listOf("zh", "en").firstNotNullOfOrNull {
            (defaultDestination[it] as? JsonPrimitive)?.content
        }.orEmpty()
        else -> ""
    }
    val effectiveValues = if (defaultDestination != null) buildJsonObject {
        display.values.forEach { (key, value) -> put(key, value) }
        put("destination", defaultDestination)
    } else display.values
    val operational = buildJsonObject {
        put("timetableId", checkNotNull(context.timetableId))
        put("operationalTripId", display.operationalTripId)
        put("cycleIndex", display.cycleIndex.toString())
        put("doorSide", context.doorSide?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("values", effectiveValues)
        put("stops", JsonArray(display.plannedStops.map(::stop)))
        put("stationIndex", context.progress.stationIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("phase", when (context.progress.phase) {
            TimetableDisplayPhase.AT_STATION -> "at_station"
            TimetableDisplayPhase.IN_TRANSIT -> "in_transit"
            TimetableDisplayPhase.FINISHED -> "finished"
            TimetableDisplayPhase.UNKNOWN -> "unknown"
        })
        put("plannedStopCount", display.plannedStops.size)
        put("completedStopCount", display.completedStops.size)
        put("unresolvedRuns", JsonArray(display.unresolvedRuns.map(::JsonPrimitive)))
        put("currentStation", current ?: JsonNull)
        put("nextStation", next ?: JsonNull)
        put("currentRun", context.currentRun?.let { run -> buildJsonObject {
            put("serviceId", run.serviceId)
            put("runId", run.runId)
            put("lineId", run.lineId)
            put("terminalStationId", run.terminalStationId?.let(::JsonPrimitive) ?: JsonNull)
            put("metadata", run.metadata)
        } } ?: JsonNull)
    }
    fun authoredString(key: String): String = (display.values[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
    fun stationString(value: JsonObject?): String = (value?.get("stationName") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
    val aliases = buildJsonObject {
        put("destination", if (defaultDestination != null) defaultDestinationAlias else authoredString("destination"))
        put("runType", authoredString("runType"))
        put("currentStationName", stationString(current))
        put("nextStationName", stationString(next))
    }
    return OperationalPartRenderProjection(unit, operational, aliases)
}
