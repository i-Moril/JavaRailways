package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.Serializable
import work.art1st.javarailways.basic.api.operation.DoorSide
import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.simpleato.timetable.*

@Serializable enum class SavedStationProgramActionType { OPEN_DOORS, CLOSE_DOORS, END, REVERSE, WAIT_UNTIL, WAIT_DURATION, ASYNC, AWAIT, SET_DISPLAY, RESET_DISPLAY, EXTENSION }
@Serializable data class SavedStationProgramAction(
    val type: SavedStationProgramActionType,
    val tick: Long? = null,
    val durationTicks: Long? = null,
    val taskId: String? = null,
    val steps: List<SavedStationProgramAction> = emptyList(),
    val values: JsonObject = JsonObject(emptyMap()),
    val removeKeys: List<String> = emptyList(),
    val extensionCall: SavedTimetableExtensionCallV1? = null,
    val trainInfo: Map<String, SavedTrainInfoField> = emptyMap()
) {
    init {
        require(trainInfo.isEmpty() || type in setOf(SavedStationProgramActionType.SET_DISPLAY, SavedStationProgramActionType.EXTENSION)) { "trainInfo belongs to display or extension actions" }
        immutableTrainInfo(trainInfo.mapValues { TrainInfoField.valueOf(it.value.name) })
    }
    fun restore(): StationProgramAction = when (type) {
        SavedStationProgramActionType.OPEN_DOORS -> StationProgramAction.OpenDoors
        SavedStationProgramActionType.CLOSE_DOORS -> StationProgramAction.CloseDoors
        SavedStationProgramActionType.END -> StationProgramAction.End
        SavedStationProgramActionType.REVERSE -> StationProgramAction.Reverse
        SavedStationProgramActionType.WAIT_UNTIL -> StationProgramAction.WaitUntil(requireNotNull(tick))
        SavedStationProgramActionType.WAIT_DURATION -> StationProgramAction.WaitDuration(requireNotNull(durationTicks))
        SavedStationProgramActionType.AWAIT -> StationProgramAction.Await(requireNotNull(taskId))
        SavedStationProgramActionType.ASYNC -> StationProgramAction.Async(requireNotNull(taskId), java.util.List.copyOf(steps.map { it.restore() }))
        SavedStationProgramActionType.SET_DISPLAY -> StationProgramAction.SetDisplay(values, java.util.List.copyOf(removeKeys), trainInfo.mapValues { TrainInfoField.valueOf(it.value.name) })
        SavedStationProgramActionType.RESET_DISPLAY -> StationProgramAction.ResetDisplay
        SavedStationProgramActionType.EXTENSION -> StationProgramAction.Extension(requireNotNull(extensionCall).restore(), trainInfo.mapValues { TrainInfoField.valueOf(it.value.name) })
    }
    companion object {
        fun capture(action: StationProgramAction): SavedStationProgramAction = when (action) {
            StationProgramAction.OpenDoors -> SavedStationProgramAction(SavedStationProgramActionType.OPEN_DOORS)
            StationProgramAction.CloseDoors -> SavedStationProgramAction(SavedStationProgramActionType.CLOSE_DOORS)
            StationProgramAction.End -> SavedStationProgramAction(SavedStationProgramActionType.END)
            StationProgramAction.Reverse -> SavedStationProgramAction(SavedStationProgramActionType.REVERSE)
            is StationProgramAction.WaitUntil -> SavedStationProgramAction(SavedStationProgramActionType.WAIT_UNTIL, tick = action.tick)
            is StationProgramAction.WaitDuration -> SavedStationProgramAction(SavedStationProgramActionType.WAIT_DURATION, durationTicks = action.ticks)
            is StationProgramAction.Await -> SavedStationProgramAction(SavedStationProgramActionType.AWAIT, taskId = action.taskId)
            is StationProgramAction.Async -> SavedStationProgramAction(SavedStationProgramActionType.ASYNC, taskId = action.taskId, steps = action.steps.map(::capture))
            is StationProgramAction.SetDisplay -> SavedStationProgramAction(SavedStationProgramActionType.SET_DISPLAY, values = action.values, removeKeys = action.removeKeys, trainInfo = action.trainInfo.mapValues { SavedTrainInfoField.valueOf(it.value.name) })
            is StationProgramAction.ResetDisplay -> SavedStationProgramAction(SavedStationProgramActionType.RESET_DISPLAY)
            is StationProgramAction.Extension -> SavedStationProgramAction(SavedStationProgramActionType.EXTENSION, extensionCall = SavedTimetableExtensionCallV1.capture(action.call), trainInfo = action.trainInfo.mapValues { SavedTrainInfoField.valueOf(it.value.name) })
        }
    }
}
@Serializable data class SavedStationStopPrograms(
    val stationId: String,
    val callId: String,
    val role: String,
    val arrival: List<SavedStationProgramAction>,
    val departurePreparation: List<SavedStationProgramAction>,
    val preparationLeadTicks: Long,
    val plannedDepartureTick: Long? = null,
    val destinationId: String? = null,
    val doorSide: DoorSide? = null,
    val minimumDwellTicks: Long? = null
) {
    fun restore() = StationStopPrograms(stationId, callId, StationStopRole.valueOf(role),
        java.util.List.copyOf(arrival.map { it.restore() }), java.util.List.copyOf(departurePreparation.map { it.restore() }),
        preparationLeadTicks, plannedDepartureTick, destinationId, doorSide, minimumDwellTicks)
    companion object {
        fun capture(value: StationStopPrograms) = SavedStationStopPrograms(value.stationId, value.callId, value.role.name,
            value.arrival.map(SavedStationProgramAction::capture), value.departurePreparation.map(SavedStationProgramAction::capture),
            value.preparationLeadTicks, value.plannedDepartureTick, value.destinationId, value.doorSide, value.minimumDwellTicks)
    }
}
