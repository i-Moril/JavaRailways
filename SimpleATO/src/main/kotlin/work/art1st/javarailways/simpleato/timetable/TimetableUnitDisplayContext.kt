package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.*
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.depot.FixedEmuUnit
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.api.metadata.JsonMetadata
import java.util.Collections

/** Detached owner-captured facts for one complete registered unit in a current Core formation. */
class TimetableUnitDisplayContext(
    val trainId: String,
    val unitId: String,
    carIds: List<String>,
    val timetableId: String?,
    display: TimetableUnitDisplaySnapshot?,
    val currentBerth: StationBerthRef?,
    val currentRun: TimetableRunDisplayContext? = null,
    val progress: TimetableDisplayProgress = TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN),
    /** Intended/effective opening side in the actual operating basis, never animated door aperture. */
    val doorSide: DoorSide? = null,
) {
    val carIds: List<String> = java.util.List.copyOf(carIds)
    val display: TimetableUnitDisplaySnapshot? = display?.let { source ->
        fun stops(values: List<TimetableUnitStopSnapshot>) = java.util.List.copyOf(values.map {
            it.copy(call = it.call.copy(viaIds = java.util.List.copyOf(it.call.viaIds)))
        })
        source.copy(values = freezeDisplayJson(immutableDisplayValues(source.values)) as JsonObject,
            plannedStops = stops(source.plannedStops), completedStops = stops(source.completedStops),
            remainingStops = stops(source.remainingStops), unresolvedRuns = java.util.List.copyOf(source.unresolvedRuns))
    }
    init {
        require(trainId.isNotBlank() && unitId.isNotBlank())
        require(this.carIds.isNotEmpty() && this.carIds.distinct().size == this.carIds.size)
        require((timetableId == null) == (display == null)) { "Display context requires its exact timetable" }
        require(display == null || display.unitId == unitId)
        require(currentRun == null || display != null) { "Current run requires an exact display context" }
        require(doorSide == null || (currentRun != null && progress.phase == TimetableDisplayPhase.AT_STATION)) {
            "Door side requires an exact current run and physically arrived stopping occurrence"
        }
        progress.stationIndex?.let { index ->
            require(display != null && display.active && index in display.plannedStops.indices) {
                "Progress requires an active exact display occurrence"
            }
            require(progress.phase != TimetableDisplayPhase.AT_STATION ||
                display.plannedStops[index].call.status != TimetableServiceStopStatus.PASS) {
                "A passing call cannot be an arrived stopping occurrence"
            }
        }
    }
}

/** Actual physical-train run facts; no operational-trip or registered-unit identity is implied. */
class TimetableTrainDisplayContext(
    val trainId: String,
    carIds: List<String>,
    val timetableId: String,
    val cycleIndex: Long,
    val currentRun: TimetableRunDisplayContext,
    stops: List<TimetableUnitStopSnapshot>,
    val currentBerth: StationBerthRef?,
    val progress: TimetableDisplayProgress = TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN),
    val doorSide: DoorSide? = null,
) {
    val carIds: List<String> = java.util.List.copyOf(carIds)
    val stops: List<TimetableUnitStopSnapshot> = java.util.List.copyOf(stops.map {
        it.copy(call = it.call.copy(viaIds = java.util.List.copyOf(it.call.viaIds)))
    })
    init {
        require(trainId.isNotBlank() && timetableId.isNotBlank())
        require(this.carIds.isNotEmpty() && this.carIds.distinct().size == this.carIds.size)
        require(this.stops.all { it.serviceId == currentRun.serviceId && it.runId == currentRun.runId })
        require(doorSide == null || progress.phase == TimetableDisplayPhase.AT_STATION)
        progress.stationIndex?.let { index ->
            require(index in this.stops.indices)
            require(progress.phase != TimetableDisplayPhase.AT_STATION ||
                this.stops[index].call.status != TimetableServiceStopStatus.PASS)
        }
    }
}

/** Same exact occurrence semantics as unit-trip progress, restricted to this entered run's calls. */
internal fun captureTimetableTrainDisplayProgress(
    stops: List<TimetableUnitStopSnapshot>, arrivedCall: Triple<String, String, String>?, inTransit: Boolean,
): TimetableDisplayProgress {
    val unknown = TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN)
    if (arrivedCall != null) {
        val index = stops.indexOfFirst { Triple(it.serviceId, it.runId, it.call.id) == arrivedCall }
        if (index < 0 || !stops[index].completed || stops[index].call.status == TimetableServiceStopStatus.PASS) return unknown
        return TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION, index)
    }
    if (!inTransit || stops.isEmpty()) return unknown
    val next = stops.indexOfFirst { !it.completed }
    if (next < 0) return TimetableDisplayProgress(TimetableDisplayPhase.FINISHED)
    if (stops.drop(next).any { it.completed }) return unknown
    return TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT, next)
}

/** Only an actually entered physical run scope may supply this owner-captured value. */
class TimetableRunDisplayContext(
    val serviceId: String,
    val runId: String,
    val lineId: String,
    metadata: JsonObject,
    /** Exact terminal of this entered run, independent of later operational-trip legs. */
    val terminalStationId: String? = null,
) {
    val metadata = JsonMetadata.immutableCopy(metadata)
    init {
        require(serviceId.isNotBlank() && runId.isNotBlank() && lineId.isNotBlank())
        require(terminalStationId == null || terminalStationId.isNotBlank())
    }
}

internal fun captureTimetableRunDisplayContext(
    serviceId: String, segment: TimetableRunSegment?, stepIndex: Int,
): TimetableRunDisplayContext? = segment?.takeIf {
    stepIndex >= it.startStepIndex && stepIndex < it.endStepIndexExclusive
}?.let { segment ->
    TimetableRunDisplayContext(serviceId, segment.runId, segment.lineId.value, segment.metadata,
        segment.calls.lastOrNull()?.takeIf { it.status == TimetableServiceStopStatus.TERMINAL }?.stationId)
}

private fun freezeDisplayJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(Collections.unmodifiableMap(value.mapValues { freezeDisplayJson(it.value) }))
    is JsonArray -> JsonArray(Collections.unmodifiableList(value.map(::freezeDisplayJson)))
    is JsonPrimitive -> value
}

/** Presentation does not claim partial units or turn ordinary unregistered cars into domain errors. */
internal fun completeDisplayUnits(carIds: List<String>, units: List<FixedEmuUnit>): List<FixedEmuUnit> {
    if (carIds.distinct().size != carIds.size) return emptyList()
    val indices = carIds.withIndex().associate { it.value to it.index }
    return units.mapNotNull { unit ->
        val positions = unit.carIds.map { indices[it] ?: return@mapNotNull null }
        val start = positions.minOrNull() ?: return@mapNotNull null
        val end = start + unit.carIds.size
        if (end > carIds.size) return@mapNotNull null
        val actual = carIds.subList(start, end)
        if (actual != unit.carIds && actual != unit.carIds.asReversed()) return@mapNotNull null
        start to unit
    }.sortedBy { it.first }.map { it.second }
}

/** Index addresses display.plannedStops, including distinct repeated visits and joined boundaries. */
enum class TimetableDisplayPhase { AT_STATION, IN_TRANSIT, FINISHED, UNKNOWN }

data class TimetableDisplayProgress(val phase: TimetableDisplayPhase, val stationIndex: Int? = null) {
    init {
        require(when (phase) {
            TimetableDisplayPhase.AT_STATION, TimetableDisplayPhase.IN_TRANSIT -> stationIndex != null && stationIndex >= 0
            TimetableDisplayPhase.FINISHED, TimetableDisplayPhase.UNKNOWN -> stationIndex == null
        }) { "Display progress phase and occurrence index disagree" }
    }
}
