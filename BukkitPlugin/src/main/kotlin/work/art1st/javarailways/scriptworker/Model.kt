package work.art1st.javarailways.scriptworker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable enum class SavedTrainSelectorTypeV1 { ANY, TRAIN_ID }
@Serializable enum class SavedLineDirectionV1 { UP, DOWN }
@Serializable enum class SavedTimetableStepTypeV1 { CHECK, AT }
@Serializable enum class SavedTimetableConditionTypeV1 { PREDICATE, ALL, ANY, NOT }
@Serializable enum class SavedTimetableActivationTypeV1 { SCHEDULED, MANUAL, CONDITIONAL }
@Serializable enum class SavedTimetableActionTypeV1 { TAKE_FROM_DEPOT, TAKE_FROM_WAYPOINT, TAKE_FROM_FORMATION_OUTPUT, COUPLE, SPLIT, STORE_AT_DEPOT, DESTINATION, DESTINATION_OPTIONS, STOP, HALT, REVERSE, WAIT_UNTIL, STATION_DESTINATION_OPTIONS, SELECT_STOP, EXTENSION, CONDITIONAL, SET_DISPLAY, RESET_DISPLAY, BEGIN_OPERATIONAL_TRIP }

@Serializable data class SavedTimetableExtensionCallV1(val typeId: String, val version: Int = 1, val parameters: JsonObject = JsonObject(emptyMap()))
@Serializable data class SavedTimetableConditionV1(val type: SavedTimetableConditionTypeV1, val call: SavedTimetableExtensionCallV1? = null, val conditions: List<SavedTimetableConditionV1> = emptyList())
@Serializable data class SavedTimetableActivationV1(val type: SavedTimetableActivationTypeV1 = SavedTimetableActivationTypeV1.SCHEDULED, val condition: SavedTimetableConditionV1? = null, val falseBehavior: String? = null)
@Serializable data class SavedTimetableActionV1(
    val type: SavedTimetableActionTypeV1,
    val depotId: String? = null,
    val requiredTags: Set<String> = emptySet(), val minimumCars: Int? = null, val maximumCars: Int? = null,
    val minimumSpeedMetersPerSecond: Double? = null, val requiredCapabilities: Set<String> = emptySet(),
    val stablingPointIds: Set<String> = emptySet(), val wayPointIds: List<String> = emptyList(),
    val stopTicks: Long? = null, val stationId: String? = null, val berthId: String? = null,
    /** Presentation-only planned arrival; the runtime snapshot restore deliberately ignores it. */
    val arrivalTick: Long? = null, val minimumDwellTicks: Long? = null, val stoppingOffsetMetersBefore: Double? = null,
    val doorSideOverride: String? = null, val extensionCall: SavedTimetableExtensionCallV1? = null,
    val condition: SavedTimetableConditionV1? = null, val thenSteps: List<SavedTimetableStepV1> = emptyList(),
    val elseSteps: List<SavedTimetableStepV1> = emptyList(),
    val arrivalSteps: List<SavedTimetableStepV1> = emptyList(),
    val eventId: String? = null, val outputId: String? = null,
    val berthCandidates: List<SavedStationStopCandidateV1> = emptyList(),
    val selectionPolicy: SavedStationStopSelectionPolicyV1? = null,
    val unitSlots: List<Int> = emptyList(), val displayValues: JsonObject = JsonObject(emptyMap()),
    val removeDisplayKeys: List<String> = emptyList(),
    val operationalTripId: String? = null, val runId: String? = null
) {
    init {
        require(arrivalSteps.isEmpty() || type in setOf(SavedTimetableActionTypeV1.DESTINATION_OPTIONS,
            SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS, SavedTimetableActionTypeV1.SELECT_STOP)) {
            "arrivalSteps is only valid on destination options and station stops"
        }
    }
}
@Serializable data class SavedTimetableStepV1(val type: SavedTimetableStepTypeV1, val tick: Long? = null, val wayPointId: String? = null, val action: SavedTimetableActionV1? = null)
@Serializable data class SavedTimetableServiceV1(val id: String, val selectorType: SavedTrainSelectorTypeV1, val trainId: String? = null, val steps: List<SavedTimetableStepV1>, val activation: SavedTimetableActivationV1 = SavedTimetableActivationV1(), val diagramLineId: String? = null, val lineDirection: SavedLineDirectionV1? = null, val lineId: String? = null, val runSegments: List<SavedTimetableRunSegmentV1> = emptyList())
@Serializable data class SavedDiagramLineV1(val id: String, val upStationIds: List<String>, val downStationIds: List<String>)
@Serializable enum class SavedObjectSelectorTypeV1 { REGEX, AND, OR, NOT, TAGS }
@Serializable data class SavedObjectSelectorV1(val type: SavedObjectSelectorTypeV1, val pattern: String? = null, val selectors: List<SavedObjectSelectorV1> = emptyList(), val tags: Set<String> = emptySet())
@Serializable data class SavedInitialPositionV1(val trainSelector: SavedObjectSelectorV1, val wayPointSelector: SavedObjectSelectorV1)
@Serializable data class SavedCompilationTraceV1(val serviceId: String, val stepIndex: Int, val origin: String, val branchPath: List<SavedTimetableBranchPathEntryV1> = emptyList())
@Serializable data class SavedTimetableLineSpeedLimitV1(val lineId: String, val maximumSpeedMetersPerSecond: Double)
@Serializable data class SavedTimetableV1(
    val id: String,
    val periodDays: Long,
    val services: List<SavedTimetableServiceV1>,
    val diagramLines: List<SavedDiagramLineV1> = emptyList(),
    val initialPositions: List<SavedInitialPositionV1> = emptyList(),
    val compilationTrace: List<SavedCompilationTraceV1> = emptyList(),
    val lineSpeedLimits: List<SavedTimetableLineSpeedLimitV1> = emptyList(),
    val formationEvents: List<SavedFormationEventV1> = emptyList(),
    val operationalTrips: List<SavedTimetableOperationalTripV1> = emptyList()
)

@Serializable enum class SavedFormationEventKind { COUPLE, SPLIT }
@Serializable enum class SavedFormationStationaryEnd { HEAD, TAIL }
@Serializable enum class SavedFormationDispositionType { DUTY, HOLD }
@Serializable enum class SavedFormationSplitGroup { FIRST, REST }
@Serializable enum class SavedFormationPartitionFrom { HEAD }
@Serializable data class SavedFormationDispositionV1(val type: SavedFormationDispositionType, val dutyId: String? = null) {
    init { require(if (type == SavedFormationDispositionType.DUTY) !dutyId.isNullOrBlank() && dutyId == dutyId.trim() else dutyId == null) }
}
@Serializable data class SavedFormationOutputV1(val id: String, val disposition: SavedFormationDispositionV1) {
    init { require(id.isNotBlank() && id == id.trim()) }
}
@Serializable data class SavedFormationSplitOutputV1(val id: String, val group: SavedFormationSplitGroup, val disposition: SavedFormationDispositionV1) {
    init { require(id.isNotBlank() && id == id.trim()) }
}
@Serializable data class SavedFormationRendezvousV1(val approachDestinationId: String, val stationaryDestinationId: String) {
    init { require(listOf(approachDestinationId, stationaryDestinationId).all { it.isNotBlank() && it == it.trim() }) }
}
@Serializable data class SavedFormationPartitionV1(val from: SavedFormationPartitionFrom, val firstUnitCount: Int) {
    init { require(firstUnitCount > 0) { "HEAD partition firstUnitCount must be positive" } }
}
@Serializable data class SavedFormationEventV1(
    val id: String,
    val kind: SavedFormationEventKind,
    val coordinatorDutyId: String,
    val approachingDutyId: String? = null,
    val stationaryDutyId: String? = null,
    val stationaryEnd: SavedFormationStationaryEnd? = null,
    val rendezvous: SavedFormationRendezvousV1? = null,
    val maximumApproachSpeedMetersPerSecond: Double? = null,
    val output: SavedFormationOutputV1? = null,
    val partition: SavedFormationPartitionV1? = null,
    val outputs: List<SavedFormationSplitOutputV1> = emptyList()
) {
    init {
        require(id.isNotBlank() && id == id.trim() && coordinatorDutyId.isNotBlank() && coordinatorDutyId == coordinatorDutyId.trim())
        when (kind) {
            SavedFormationEventKind.COUPLE -> {
                require(!approachingDutyId.isNullOrBlank() && approachingDutyId == approachingDutyId.trim())
                require(!stationaryDutyId.isNullOrBlank() && stationaryDutyId == stationaryDutyId.trim())
                require(approachingDutyId != stationaryDutyId && coordinatorDutyId in setOf(approachingDutyId, stationaryDutyId))
                require(stationaryEnd != null && rendezvous != null && output != null)
                require(maximumApproachSpeedMetersPerSecond != null && maximumApproachSpeedMetersPerSecond.isFinite() && maximumApproachSpeedMetersPerSecond > 0.0)
                require(partition == null && outputs.isEmpty()) { "COUPLE cannot declare a split partition" }
            }
            SavedFormationEventKind.SPLIT -> {
                require(partition != null && outputs.size == 2 && outputs.map { it.group }.toSet() == SavedFormationSplitGroup.entries.toSet())
                require(outputs.map { it.id }.distinct().size == 2)
                val duties = outputs.mapNotNull { it.disposition.dutyId }
                require(duties.distinct().size == duties.size) { "split outputs cannot share a duty" }
                require(listOf(approachingDutyId, stationaryDutyId, stationaryEnd, rendezvous, maximumApproachSpeedMetersPerSecond, output).all { it == null })
            }
        }
    }
}

@Serializable enum class SavedTimetableBranchArmV1 { THEN, ELSE, ARRIVAL }
@Serializable data class SavedTimetableBranchPathEntryV1(val arm: SavedTimetableBranchArmV1, val index: Int) {
    init { require(index >= 0) }
}

@Serializable enum class SavedStationStopSelectionPolicyV1 { FIXED_PREFERENCE, AVAILABLE_PRIORITY }
@Serializable data class SavedStationStopCandidateV1(val berthId: String, val priority: Int) {
    init { require(berthId.isNotBlank() && berthId == berthId.trim() && priority >= 0) }
}

@Serializable enum class SavedRunCallStatusV1 { ORIGIN, STOP, PASS, TERMINAL }
@Serializable data class SavedTimetableRunCallV1(val id: String, val stationId: String, val destinationId: String,
    val status: SavedRunCallStatusV1, val arrivalTick: Long? = null, val departureTick: Long? = null)
@Serializable data class SavedTimetableRunSegmentV1(val runId: String, val lineId: String,
    val startStepIndex: Int, val endStepIndexExclusive: Int, val diagramLineId: String? = null,
    val lineDirection: SavedLineDirectionV1? = null, val calls: List<SavedTimetableRunCallV1> = emptyList())
@Serializable data class SavedTimetableOperationalTripSegmentV1(val serviceId: String, val runId: String, val unitSlots: List<Int>)
@Serializable data class SavedTimetableOperationalTripV1(val id: String, val defaults: JsonObject = JsonObject(emptyMap()),
    val segments: List<SavedTimetableOperationalTripSegmentV1>)
