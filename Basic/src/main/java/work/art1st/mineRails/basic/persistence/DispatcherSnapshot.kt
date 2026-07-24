package work.art1st.mineRails.basic.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedTimetableClockV1(
    val timetableId: String,
    val epochDispatcherTick: Long,
    val periodTicks: Long,
    val nextCycleIndex: Long,
    val disabledServiceIds: Set<String> = emptySet(),
    val pendingActivationCycles: Map<String, Long> = emptyMap()
)

@Serializable
data class SavedActiveRunCheckpointV1(
    val timetableId: String,
    val serviceId: String,
    val cycleIndex: Long,
    val trainId: String,
    val nextStepIndex: Int,
    val currentWayPointId: String,
    val targetWayPointId: String,
    val originalState: String,
    val timetableElapsedTicks: Long,
    val dwellTicksRemaining: Long,
    val missionRevision: Long = 0L,
    val lastScheduledActionTick: Long? = null,
    val lastLatenessTicks: Long = 0L,
    val controlMode: String = "AUTOMATIC",
    val message: String? = null,
    val headProgressMeters: Double? = null,
    val tailProgressMeters: Double? = null,
    val missionTotalLengthMeters: Double? = null,
    val atoMissionRevision: Long? = null,
    val atoOdometerProgressMeters: Double? = null,
    val atoProjectedProgressMeters: Double? = null,
    val movementAuthorityRevision: Long? = null,
    val movementAuthorityLimitMeters: Double? = null,
    val activeStationCallId: String? = null,
    val stationTargetProgressMeters: Double? = null,
    val stationPositionErrorMeters: Double? = null,
    val atoPermittedSpeedMetersPerSecond: Double? = null,
    val atoConstraints: List<String> = emptyList(),
    val stationCallSubmittedToAto: Boolean? = null,
    val berthHeld: Boolean? = null
)

@Serializable
data class SavedDispatcherV1(
    val formatVersion: Int = 1,
    val dispatcherTick: Long,
    val missionRevision: Long,
    val clocks: List<SavedTimetableClockV1> = emptyList(),
    val runs: List<SavedActiveRunCheckpointV1> = emptyList()
)

object DispatcherSnapshotCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    fun encode(value: SavedDispatcherV1): String = json.encodeToString(SavedDispatcherV1.serializer(), value)
    fun decode(value: String): SavedDispatcherV1 = json.decodeFromString(SavedDispatcherV1.serializer(), value)
}
