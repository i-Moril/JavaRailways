package work.art1st.javarailways.scriptworker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable enum class SavedStationProgramActionType { OPEN_DOORS, CLOSE_DOORS, END, REVERSE, WAIT_UNTIL, WAIT_DURATION, ASYNC, AWAIT, SET_DISPLAY, RESET_DISPLAY, EXTENSION }
@Serializable data class SavedStationProgramAction(
    val type: SavedStationProgramActionType,
    val tick: Long? = null,
    val durationTicks: Long? = null,
    val taskId: String? = null,
    val steps: List<SavedStationProgramAction> = emptyList(),
    val unitSlots: List<Int> = emptyList(),
    val values: JsonObject = JsonObject(emptyMap()),
    val removeKeys: List<String> = emptyList(),
    val extensionCall: SavedTimetableExtensionCallV1? = null,
    val trainInfo: Map<String, SavedTrainInfoField> = emptyMap()
)
@Serializable data class SavedStationStopPrograms(
    val stationId: String,
    val callId: String,
    val role: String,
    val arrival: List<SavedStationProgramAction>,
    val departurePreparation: List<SavedStationProgramAction>,
    val preparationLeadTicks: Long,
    val plannedDepartureTick: Long? = null,
    val destinationId: String? = null,
    val doorSide: String? = null,
    val minimumDwellTicks: Long? = null
)
