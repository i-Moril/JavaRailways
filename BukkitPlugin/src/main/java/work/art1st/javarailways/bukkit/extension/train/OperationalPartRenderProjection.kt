package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.json.*
import work.art1st.javarailways.bukkit.api.model.extension.immutablePartExtensionJson
import work.art1st.javarailways.simpleato.timetable.TimetableServiceStopStatus
import work.art1st.javarailways.simpleato.timetable.TimetableDisplayPhase
import work.art1st.javarailways.simpleato.timetable.TimetableUnitDisplayContext
import work.art1st.javarailways.simpleato.timetable.TimetableUnitStopSnapshot
import work.art1st.javarailways.simpleato.timetable.TimetableRunDisplayContext
import work.art1st.javarailways.simpleato.timetable.TimetableDisplayProgress
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.api.station.StationMetadata
import work.art1st.javarailways.basic.api.operation.DoorSide

/** Detached operational values only; neither domain objects nor the owner-side metadata resolver escape. */
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

/** Projects exact owner-captured unit facts. Callers supply the station metadata lookup. */
internal fun projectOperationalPartRender(
    context: TimetableUnitDisplayContext?,
    stationMetadata: (String) -> JsonObject = { JsonObject(emptyMap()) },
): OperationalPartRenderProjection {
    if (context == null) return OperationalPartRenderProjection.EMPTY
    val unit = buildJsonObject {
        put("id", context.unitId)
        put("carIds", JsonArray(context.carIds.map(::JsonPrimitive)))
    }
    val display = context.display?.takeIf {
        it.active && it.unitId == context.unitId && context.timetableId != null
    } ?: return OperationalPartRenderProjection(unit, JsonNull, emptyOperationalAliases())

    return projectOperationalFacts(unit, checkNotNull(context.timetableId), display.operationalTripId,
        display.cycleIndex, display.values, display.plannedStops, display.completedStops.size,
        display.unresolvedRuns, context.currentBerth, context.currentRun, context.progress, context.doorSide,
        stationMetadata)
}

private fun projectOperationalFacts(
    unit: JsonElement,
    timetableId: String,
    operationalTripId: String?,
    cycleIndex: Long,
    values: JsonObject,
    stops: List<TimetableUnitStopSnapshot>,
    completedStopCount: Int,
    unresolvedRuns: List<String>,
    currentBerth: StationBerthRef?,
    currentRun: TimetableRunDisplayContext?,
    progress: TimetableDisplayProgress,
    doorSide: DoorSide?,
    stationMetadata: (String) -> JsonObject,
): OperationalPartRenderProjection {
    fun stop(value: TimetableUnitStopSnapshot): JsonObject = buildJsonObject {
        put("serviceId", value.serviceId)
        put("runId", value.runId)
        put("callId", value.call.id)
        put("stationId", value.call.stationId)
        val metadata = stationMetadata(value.call.stationId)
        put("stationName", StationMetadata.preferredName(StationMetadata.name(metadata))?.let(::JsonPrimitive) ?: JsonNull)
        put("metadata", metadata)
        put("destinationId", value.call.destinationId)
        put("status", value.call.status.name)
        put("arrivalTick", value.call.arrivalTick?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("departureTick", value.call.departureTick?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("completed", value.completed)
    }
    val current = currentBerth?.let { berth -> buildJsonObject {
        put("stationId", berth.stationId.value)
        val metadata = stationMetadata(berth.stationId.value)
        put("stationName", StationMetadata.preferredName(StationMetadata.name(metadata))?.let(::JsonPrimitive) ?: JsonNull)
        put("metadata", metadata)
        put("berthId", berth.berthId)
    } }
    val nextStart = when (progress.phase) {
        TimetableDisplayPhase.IN_TRANSIT -> progress.stationIndex
        TimetableDisplayPhase.AT_STATION -> progress.stationIndex?.plus(1)
        TimetableDisplayPhase.FINISHED, TimetableDisplayPhase.UNKNOWN -> null
    }
    val next = nextStart?.takeIf { unresolvedRuns.isEmpty() }?.let { start ->
        stops.drop(start).firstOrNull {
            it.call.status == TimetableServiceStopStatus.STOP || it.call.status == TimetableServiceStopStatus.TERMINAL
        }?.let(::stop)
    }
    // Presence, rather than value/type/truthiness, defines an authored destination override.
    // This is a presentation default; the captured operational values remain untouched.
    val defaultTerminal = currentRun?.terminalStationId?.takeIf { "destination" !in values }
    val defaultDestination = defaultTerminal?.let { StationMetadata.name(stationMetadata(it)) }
    val effectiveValues = if (defaultDestination != null) buildJsonObject {
        values.forEach { (key, value) -> put(key, value) }
        put("destination", defaultDestination)
    } else values
    val operational = buildJsonObject {
        put("timetableId", timetableId)
        put("operationalTripId", operationalTripId?.let(::JsonPrimitive) ?: JsonNull)
        put("cycleIndex", cycleIndex.toString())
        put("doorSide", doorSide?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("values", effectiveValues)
        put("stops", JsonArray(stops.map(::stop)))
        put("stationIndex", progress.stationIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("phase", when (progress.phase) {
            TimetableDisplayPhase.AT_STATION -> "at_station"
            TimetableDisplayPhase.IN_TRANSIT -> "in_transit"
            TimetableDisplayPhase.FINISHED -> "finished"
            TimetableDisplayPhase.UNKNOWN -> "unknown"
        })
        put("plannedStopCount", stops.size)
        put("completedStopCount", completedStopCount)
        put("unresolvedRuns", JsonArray(unresolvedRuns.map(::JsonPrimitive)))
        put("currentStation", current ?: JsonNull)
        put("nextStation", next ?: JsonNull)
        put("currentRun", currentRun?.let { run -> buildJsonObject {
            put("serviceId", run.serviceId)
            put("runId", run.runId)
            put("lineId", run.lineId)
            put("terminalStationId", run.terminalStationId?.let(::JsonPrimitive) ?: JsonNull)
            put("metadata", run.metadata)
        } } ?: JsonNull)
    }
    fun authoredString(key: String): String = (values[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
    fun stationString(value: JsonObject?): String = (value?.get("stationName") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
    val aliases = buildJsonObject {
        put("destination", if (defaultDestination != null) StationMetadata.preferredName(defaultDestination).orEmpty() else authoredString("destination"))
        put("runType", authoredString("runType"))
        put("currentStationName", stationString(current))
        put("nextStationName", stationString(next))
    }
    return OperationalPartRenderProjection(unit, operational, aliases)
}
