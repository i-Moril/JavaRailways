package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.timetable.*
import work.art1st.javarailways.simpleato.api.station.*
import work.art1st.javarailways.simpleato.timetable.*

@Serializable
enum class SavedTrainSelectorTypeV1 { ANY, TRAIN_ID }

@Serializable
enum class SavedTimetableStepTypeV1 { CHECK, AT }

@Serializable
data class SavedTimetableExtensionCallV1(
    val typeId: String,
    val version: Int = 1,
    val parameters: JsonObject = JsonObject(emptyMap())
) {
    fun restore() = TimetableExtensionCall(typeId, version, parameters)

    companion object {
        fun capture(value: TimetableExtensionCall) = SavedTimetableExtensionCallV1(
            value.typeId, value.version, value.parameters
        )
    }
}

@Serializable
enum class SavedTimetableConditionTypeV1 { PREDICATE, ALL, ANY, NOT }

@Serializable
data class SavedTimetableConditionV1(
    val type: SavedTimetableConditionTypeV1,
    val call: SavedTimetableExtensionCallV1? = null,
    val conditions: List<SavedTimetableConditionV1> = emptyList()
) {
    fun restore(): TimetableCondition = when (type) {
        SavedTimetableConditionTypeV1.PREDICATE ->
            TimetableCondition.Predicate(requireNotNull(call).restore())
        SavedTimetableConditionTypeV1.ALL -> TimetableCondition.All(conditions.map { it.restore() })
        SavedTimetableConditionTypeV1.ANY -> TimetableCondition.Any(conditions.map { it.restore() })
        SavedTimetableConditionTypeV1.NOT -> TimetableCondition.Not(conditions.single().restore())
    }

    companion object {
        fun capture(value: TimetableCondition): SavedTimetableConditionV1 = when (value) {
            is TimetableCondition.Predicate -> SavedTimetableConditionV1(
                SavedTimetableConditionTypeV1.PREDICATE,
                call = SavedTimetableExtensionCallV1.capture(value.call)
            )
            is TimetableCondition.All -> SavedTimetableConditionV1(
                SavedTimetableConditionTypeV1.ALL,
                conditions = value.conditions.map(::capture)
            )
            is TimetableCondition.Any -> SavedTimetableConditionV1(
                SavedTimetableConditionTypeV1.ANY,
                conditions = value.conditions.map(::capture)
            )
            is TimetableCondition.Not -> SavedTimetableConditionV1(
                SavedTimetableConditionTypeV1.NOT,
                conditions = listOf(capture(value.condition))
            )
        }
    }
}

@Serializable
enum class SavedTimetableActivationTypeV1 { SCHEDULED, MANUAL, CONDITIONAL }

@Serializable
data class SavedTimetableActivationV1(
    val type: SavedTimetableActivationTypeV1 = SavedTimetableActivationTypeV1.SCHEDULED,
    val condition: SavedTimetableConditionV1? = null,
    val falseBehavior: TimetableActivationFalseBehavior? = null
) {
    fun restore(): TimetableServiceActivation = when (type) {
        SavedTimetableActivationTypeV1.SCHEDULED -> TimetableServiceActivation.Scheduled
        SavedTimetableActivationTypeV1.MANUAL -> TimetableServiceActivation.Manual
        SavedTimetableActivationTypeV1.CONDITIONAL -> TimetableServiceActivation.Conditional(
            requireNotNull(condition).restore(), requireNotNull(falseBehavior)
        )
    }

    companion object {
        fun capture(value: TimetableServiceActivation) = when (value) {
            TimetableServiceActivation.Scheduled -> SavedTimetableActivationV1()
            TimetableServiceActivation.Manual -> SavedTimetableActivationV1(
                SavedTimetableActivationTypeV1.MANUAL
            )
            is TimetableServiceActivation.Conditional -> SavedTimetableActivationV1(
                SavedTimetableActivationTypeV1.CONDITIONAL,
                SavedTimetableConditionV1.capture(value.condition),
                value.falseBehavior
            )
        }
    }
}

@Serializable
enum class SavedTimetableActionTypeV1 {
    OPEN_DOORS, CLOSE_DOORS,
    TASK_START, TASK_END,
    TAKE_FROM_DEPOT,
    TAKE_FROM_WAYPOINT,
    TAKE_FROM_FORMATION_OUTPUT,
    COUPLE,
    SPLIT,
    STORE_AT_DEPOT,
    DESTINATION,
    DESTINATION_OPTIONS,
    STOP,
    WAIT_UNTIL,
    HALT,
    REVERSE,
    STATION_DESTINATION_OPTIONS,
    SELECT_STOP,
    BEGIN_OPERATIONAL_TRIP,
    BEGIN_STATION_STOP,
    SET_DISPLAY,
    RESET_DISPLAY,
    EXTENSION,
    CONDITIONAL
}

@Serializable
data class SavedTimetableActionV1(
    val type: SavedTimetableActionTypeV1,
    val taskId: String? = null,
    val depotId: String? = null,
    val requiredTags: Set<String> = emptySet(),
    val minimumCars: Int? = null,
    val maximumCars: Int? = null,
    val minimumSpeedMetersPerSecond: Double? = null,
    val requiredCapabilities: Set<String> = emptySet(),
    val stablingPointIds: Set<String> = emptySet(),
    val wayPointIds: List<String> = emptyList(),
    val stopTicks: Long? = null,
    val stationId: String? = null,
    val berthId: String? = null,
    /** Presentation-only planned arrival used by running-graph clients; ignored by restore(). */
    val arrivalTick: Long? = null,
    val minimumDwellTicks: Long? = null,
    val stoppingOffsetMetersBefore: Double? = null,
    val doorSideOverride: DoorSide? = null,
    val extensionCall: SavedTimetableExtensionCallV1? = null,
    val condition: SavedTimetableConditionV1? = null,
    val thenSteps: List<SavedTimetableStepV1> = emptyList(),
    val elseSteps: List<SavedTimetableStepV1> = emptyList(),
    val eventId: String? = null,
    val outputId: String? = null,
    val berthCandidates: List<SavedStationStopCandidateV1> = emptyList(),
    val selectionPolicy: SavedStationStopSelectionPolicyV1? = null,
    val operationalTripId: String? = null,
    val runId: String? = null,
    val unitSlots: List<Int> = emptyList(),
    val displayValues: JsonObject = JsonObject(emptyMap()),
    val removeDisplayKeys: List<String> = emptyList(),
    val stopPrograms: SavedStationStopPrograms? = null,
    val trainInfo: Map<String, SavedTrainInfoField> = emptyMap()
) {
    init {
        require(type != SavedTimetableActionTypeV1.OPEN_DOORS || doorSideOverride != DoorSide.NONE) {
            "door override requires LEFT, RIGHT or BOTH"
        }
        require(type != SavedTimetableActionTypeV1.CLOSE_DOORS || doorSideOverride == null) {
            "CLOSE_DOORS always closes BOTH and accepts no side override"
        }
        require(trainInfo.isEmpty() || type in setOf(SavedTimetableActionTypeV1.SET_DISPLAY, SavedTimetableActionTypeV1.EXTENSION)) { "trainInfo belongs to display or extension actions" }
        immutableTrainInfo(trainInfo.mapValues { TrainInfoField.valueOf(it.value.name) })
        require((type in setOf(SavedTimetableActionTypeV1.TASK_START, SavedTimetableActionTypeV1.TASK_END)) == (taskId != null)) { "taskId belongs to task boundaries" }
        require(taskId == null || (taskId.isNotBlank() && taskId.length <= 128)) { "invalid taskId" }
        require(stopPrograms == null || type in setOf(SavedTimetableActionTypeV1.BEGIN_STATION_STOP,
            SavedTimetableActionTypeV1.DESTINATION_OPTIONS, SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS,
            SavedTimetableActionTypeV1.SELECT_STOP)) { "stopPrograms is only valid on station stop boundaries" }
    }

    fun restore(): TimetableAction = when (type) {
        SavedTimetableActionTypeV1.OPEN_DOORS -> TimetableAction.OpenDoors(doorSideOverride)
        SavedTimetableActionTypeV1.CLOSE_DOORS -> TimetableAction.CloseDoors
        SavedTimetableActionTypeV1.TASK_START -> TimetableAction.TaskStart(requireNotNull(taskId))
        SavedTimetableActionTypeV1.TASK_END -> TimetableAction.TaskEnd(requireNotNull(taskId))
        SavedTimetableActionTypeV1.BEGIN_STATION_STOP -> TimetableAction.BeginStationStop(requireNotNull(stopPrograms).restore())
        SavedTimetableActionTypeV1.BEGIN_OPERATIONAL_TRIP -> TimetableAction.BeginOperationalTrip(requireNotNull(operationalTripId),requireNotNull(runId),unitSlots)
        SavedTimetableActionTypeV1.SET_DISPLAY -> TimetableAction.SetDisplay(unitSlots, displayValues, removeDisplayKeys, trainInfo.mapValues { TrainInfoField.valueOf(it.value.name) })
        SavedTimetableActionTypeV1.RESET_DISPLAY -> TimetableAction.ResetDisplay(unitSlots)
        SavedTimetableActionTypeV1.SELECT_STOP -> TimetableAction.SelectStop(
            StationStopSelection(StationId(requireNotNull(stationId)),
                berthCandidates.map { StationStopCandidate(it.berthId, it.priority) },
                StationStopSelectionPolicy.valueOf(requireNotNull(selectionPolicy).name)),
            minimumDwellTicks, stoppingOffsetMetersBefore, doorSideOverride, stopPrograms?.restore())
        SavedTimetableActionTypeV1.COUPLE -> TimetableAction.Couple(requireNotNull(eventId))
        SavedTimetableActionTypeV1.SPLIT -> TimetableAction.Split(requireNotNull(eventId))
        SavedTimetableActionTypeV1.TAKE_FROM_FORMATION_OUTPUT ->
            TimetableAction.TakeFromFormationOutput(requireNotNull(eventId), requireNotNull(outputId))
        SavedTimetableActionTypeV1.TAKE_FROM_DEPOT -> TimetableAction.TakeFromDepot(
            DepotTrainSelector(
                depotId = DepotId(requireNotNull(depotId)),
                requiredTags = requiredTags,
                minimumCars = minimumCars,
                maximumCars = maximumCars,
                minimumSpeedMetersPerSecond = minimumSpeedMetersPerSecond,
                requiredCapabilities = requiredCapabilities
            )
        )
        SavedTimetableActionTypeV1.TAKE_FROM_WAYPOINT ->
            TimetableAction.TakeFromWaypoint(wayPointIds)
        SavedTimetableActionTypeV1.STORE_AT_DEPOT -> TimetableAction.StoreAtDepot(
            DepotId(requireNotNull(depotId)),
            stablingPointIds
        )
        SavedTimetableActionTypeV1.DESTINATION -> when (wayPointIds.size) {
            0 -> error("destination requires at least one target")
            1 -> TimetableAction.GoTo(wayPointIds.single())
            else -> TimetableAction.GoVia(wayPointIds)
        }
        SavedTimetableActionTypeV1.DESTINATION_OPTIONS -> TimetableAction.DestinationOptions(
            destinationId = wayPointIds.single(),
            minimumDwellTicks = minimumDwellTicks,
            stoppingOffsetMetersBefore = stoppingOffsetMetersBefore,
            doorSide = doorSideOverride,
            stopPrograms = stopPrograms?.restore()
        )
        SavedTimetableActionTypeV1.STOP -> TimetableAction.Stop(requireNotNull(stopTicks))
        SavedTimetableActionTypeV1.WAIT_UNTIL -> TimetableAction.WaitUntil
        SavedTimetableActionTypeV1.HALT -> TimetableAction.Halt
        SavedTimetableActionTypeV1.REVERSE -> TimetableAction.Reverse
        SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS -> TimetableAction.CallStation(
            stationId = requireNotNull(stationId),
            berthId = requireNotNull(berthId),
            minimumDwellTicks = minimumDwellTicks,
            stoppingOffsetMetersBefore = stoppingOffsetMetersBefore,
            doorSideOverride = doorSideOverride,
            stopPrograms = stopPrograms?.restore()
        )
        SavedTimetableActionTypeV1.EXTENSION -> TimetableAction.Extension(
            requireNotNull(extensionCall).restore(), trainInfo.mapValues { TrainInfoField.valueOf(it.value.name) }
        )
        SavedTimetableActionTypeV1.CONDITIONAL -> TimetableAction.Conditional(
            condition = requireNotNull(condition).restore(),
            thenSteps = thenSteps.map { requireNotNull(it.restore() as? TimetableStep.At) { "Conditional children must be AT steps" } },
            elseSteps = elseSteps.map { requireNotNull(it.restore() as? TimetableStep.At) { "Conditional children must be AT steps" } }
        )
    }

    companion object {
        fun capture(value: TimetableAction): SavedTimetableActionV1 = when (value) {
            is TimetableAction.OpenDoors -> SavedTimetableActionV1(SavedTimetableActionTypeV1.OPEN_DOORS, doorSideOverride = value.doorSideOverride)
            TimetableAction.CloseDoors -> SavedTimetableActionV1(SavedTimetableActionTypeV1.CLOSE_DOORS)
            is TimetableAction.TaskStart -> SavedTimetableActionV1(SavedTimetableActionTypeV1.TASK_START, taskId = value.taskId)
            is TimetableAction.TaskEnd -> SavedTimetableActionV1(SavedTimetableActionTypeV1.TASK_END, taskId = value.taskId)
            is TimetableAction.BeginStationStop -> SavedTimetableActionV1(SavedTimetableActionTypeV1.BEGIN_STATION_STOP, stopPrograms = SavedStationStopPrograms.capture(value.stopPrograms))
            is TimetableAction.BeginOperationalTrip -> SavedTimetableActionV1(SavedTimetableActionTypeV1.BEGIN_OPERATIONAL_TRIP,operationalTripId=value.tripId,runId=value.runId,unitSlots=value.unitSlots)
            is TimetableAction.SetDisplay -> SavedTimetableActionV1(SavedTimetableActionTypeV1.SET_DISPLAY, unitSlots = value.unitSlots, displayValues = value.values, removeDisplayKeys = value.removeKeys, trainInfo = value.trainInfo.mapValues { SavedTrainInfoField.valueOf(it.value.name) })
            is TimetableAction.ResetDisplay -> SavedTimetableActionV1(SavedTimetableActionTypeV1.RESET_DISPLAY, unitSlots = value.unitSlots)
            is TimetableAction.SelectStop -> SavedTimetableActionV1(SavedTimetableActionTypeV1.SELECT_STOP,
                stationId = value.selection.stationId.value,
                berthCandidates = value.selection.candidates.map { SavedStationStopCandidateV1(it.berthId, it.priority) },
                selectionPolicy = SavedStationStopSelectionPolicyV1.valueOf(value.selection.policy.name),
                minimumDwellTicks = value.minimumDwellTicks, stoppingOffsetMetersBefore = value.stoppingOffsetMetersBefore,
                doorSideOverride = value.doorSideOverride,
                stopPrograms = value.stopPrograms?.let(SavedStationStopPrograms::capture))
            is TimetableAction.Couple -> SavedTimetableActionV1(SavedTimetableActionTypeV1.COUPLE, eventId = value.eventId)
            is TimetableAction.Split -> SavedTimetableActionV1(SavedTimetableActionTypeV1.SPLIT, eventId = value.eventId)
            is TimetableAction.TakeFromFormationOutput -> SavedTimetableActionV1(SavedTimetableActionTypeV1.TAKE_FROM_FORMATION_OUTPUT,
                eventId = value.eventId, outputId = value.outputId)
            is TimetableAction.TakeFromDepot -> SavedTimetableActionV1(
                type = SavedTimetableActionTypeV1.TAKE_FROM_DEPOT,
                depotId = value.selector.depotId.value,
                requiredTags = value.selector.requiredTags,
                minimumCars = value.selector.minimumCars,
                maximumCars = value.selector.maximumCars,
                minimumSpeedMetersPerSecond = value.selector.minimumSpeedMetersPerSecond,
                requiredCapabilities = value.selector.requiredCapabilities
            )
            is TimetableAction.TakeFromWaypoint -> SavedTimetableActionV1(
                type = SavedTimetableActionTypeV1.TAKE_FROM_WAYPOINT,
                wayPointIds = value.wayPointIds
            )
            is TimetableAction.StoreAtDepot -> SavedTimetableActionV1(
                type = SavedTimetableActionTypeV1.STORE_AT_DEPOT,
                depotId = value.depotId.value,
                stablingPointIds = value.stablingPointIds
            )
            is TimetableAction.GoTo -> SavedTimetableActionV1(
                SavedTimetableActionTypeV1.DESTINATION,
                wayPointIds = listOf(value.wayPointId)
            )
            is TimetableAction.GoVia -> SavedTimetableActionV1(
                SavedTimetableActionTypeV1.DESTINATION,
                wayPointIds = value.wayPointIds
            )
            is TimetableAction.DestinationOptions -> SavedTimetableActionV1(
                type = SavedTimetableActionTypeV1.DESTINATION_OPTIONS,
                wayPointIds = listOf(value.destinationId),
                minimumDwellTicks = value.minimumDwellTicks,
                stoppingOffsetMetersBefore = value.stoppingOffsetMetersBefore,
                doorSideOverride = value.doorSide,
                stopPrograms = value.stopPrograms?.let(SavedStationStopPrograms::capture)
            )
            is TimetableAction.Stop -> SavedTimetableActionV1(
                SavedTimetableActionTypeV1.STOP,
                stopTicks = value.ticks
            )
            TimetableAction.WaitUntil -> SavedTimetableActionV1(SavedTimetableActionTypeV1.WAIT_UNTIL)
            TimetableAction.Halt -> SavedTimetableActionV1(
                SavedTimetableActionTypeV1.HALT
            )
            TimetableAction.Reverse -> SavedTimetableActionV1(
                SavedTimetableActionTypeV1.REVERSE
            )
            is TimetableAction.CallStation -> SavedTimetableActionV1(
                type = SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS,
                stationId = value.stationId,
                berthId = value.berthId,
                minimumDwellTicks = value.minimumDwellTicks,
                stoppingOffsetMetersBefore = value.stoppingOffsetMetersBefore,
                doorSideOverride = value.doorSideOverride,
                stopPrograms = value.stopPrograms?.let(SavedStationStopPrograms::capture)
            )
            is TimetableAction.Extension -> SavedTimetableActionV1(
                type = SavedTimetableActionTypeV1.EXTENSION,
                extensionCall = SavedTimetableExtensionCallV1.capture(value.call),
                trainInfo = value.trainInfo.mapValues { SavedTrainInfoField.valueOf(it.value.name) }
            )
            is TimetableAction.Conditional -> SavedTimetableActionV1(
                type = SavedTimetableActionTypeV1.CONDITIONAL,
                condition = SavedTimetableConditionV1.capture(value.condition),
                thenSteps = value.thenSteps.map(SavedTimetableStepV1::capture),
                elseSteps = value.elseSteps.map(SavedTimetableStepV1::capture)
            )
        }
    }
}

@Serializable
data class SavedTimetableStepV1(
    val type: SavedTimetableStepTypeV1,
    val tick: Long? = null,
    val wayPointId: String? = null,
    val action: SavedTimetableActionV1? = null
) {
    fun restore(): TimetableStep = when (type) {
        SavedTimetableStepTypeV1.CHECK -> TimetableStep.Check(requireNotNull(wayPointId))
        SavedTimetableStepTypeV1.AT -> TimetableStep.At(requireNotNull(tick), requireNotNull(action).restore())
    }

    companion object {
        fun capture(value: TimetableStep): SavedTimetableStepV1 = when (value) {
            is TimetableStep.Check -> SavedTimetableStepV1(
                SavedTimetableStepTypeV1.CHECK,
                wayPointId = value.wayPointId
            )
            is TimetableStep.At -> SavedTimetableStepV1(
                SavedTimetableStepTypeV1.AT,
                tick = value.tick,
                action = SavedTimetableActionV1.capture(value.action)
            )
        }
    }
}

@Serializable
enum class SavedServiceStopStatusV1 { ORIGIN, STOP, PASS, TERMINAL }

@Serializable
enum class SavedDiagramDirectionV1 { UP, DOWN }

@Serializable
data class SavedTimetableServiceV1(
    val id: String,
    val selectorType: SavedTrainSelectorTypeV1,
    val trainId: String? = null,
    val steps: List<SavedTimetableStepV1>,
    val activation: SavedTimetableActivationV1 = SavedTimetableActivationV1(),
    /** Presentation-only running-graph grouping; this is not an infrastructure LineId. */
    val diagramLineId: String? = null,
    /** Presentation/editor hint only; generated runtime steps remain authoritative. */
    val lineDirection: SavedDiagramDirectionV1? = null,
    /** Actual Basic infrastructure line; independent from diagramLineId. */
    val lineId: String? = null,
    val runSegments: List<SavedTimetableRunSegmentV1> = emptyList()
) {
    fun restore(): TimetableService = TimetableService(
        id = id,
        trainSelector = when (selectorType) {
            SavedTrainSelectorTypeV1.ANY -> TrainSelector.AnyTrain
            SavedTrainSelectorTypeV1.TRAIN_ID -> TrainSelector.TrainId(requireNotNull(trainId))
        },
        steps = steps.map(SavedTimetableStepV1::restore),
        activation = activation.restore(),
        lineId = lineId?.let(::LineId),
        runSegments = java.util.List.copyOf(runSegments.map { it.restore() })
    )

    companion object {
        fun capture(value: TimetableService): SavedTimetableServiceV1 = SavedTimetableServiceV1(
            id = value.id,
            selectorType = if (value.trainSelector is TrainSelector.TrainId) {
                SavedTrainSelectorTypeV1.TRAIN_ID
            } else SavedTrainSelectorTypeV1.ANY,
            trainId = (value.trainSelector as? TrainSelector.TrainId)?.trainId,
            steps = value.steps.map(SavedTimetableStepV1::capture),
            activation = SavedTimetableActivationV1.capture(value.activation),
            lineId = value.lineId?.value,
            runSegments = value.runSegments.map(SavedTimetableRunSegmentV1::capture)
        )
    }
}

@Serializable
data class SavedDiagramLineV1(
    val id: String,
    val upStationIds: List<String>,
    val downStationIds: List<String>
) {
    init {
        require(id.isNotBlank())
        require(upStationIds.isNotEmpty() && downStationIds.isNotEmpty())
        require(upStationIds.distinct().size == upStationIds.size)
        require(downStationIds.distinct().size == downStationIds.size)
        require((upStationIds + downStationIds).all(String::isNotBlank))
    }
}

@Serializable
enum class SavedObjectSelectorTypeV1 { REGEX, AND, OR, NOT, TAGS }

@Serializable
data class SavedObjectSelectorV1(
    val type: SavedObjectSelectorTypeV1,
    val pattern: String? = null,
    val selectors: List<SavedObjectSelectorV1> = emptyList(),
    val tags: Set<String> = emptySet()
) {
    fun restore(): TimetableObjectSelector = when (type) {
        SavedObjectSelectorTypeV1.REGEX -> TimetableObjectSelector.Regex(requireNotNull(pattern))
        SavedObjectSelectorTypeV1.AND -> TimetableObjectSelector.And(selectors.map { it.restore() })
        SavedObjectSelectorTypeV1.OR -> TimetableObjectSelector.Or(selectors.map { it.restore() })
        SavedObjectSelectorTypeV1.NOT -> TimetableObjectSelector.Not(selectors.single().restore())
        SavedObjectSelectorTypeV1.TAGS -> TimetableObjectSelector.Tags(tags)
    }

    companion object {
        fun capture(value: TimetableObjectSelector): SavedObjectSelectorV1 = when (value) {
            is TimetableObjectSelector.Regex -> SavedObjectSelectorV1(
                SavedObjectSelectorTypeV1.REGEX, pattern = value.pattern
            )
            is TimetableObjectSelector.And -> SavedObjectSelectorV1(
                SavedObjectSelectorTypeV1.AND, selectors = value.selectors.map(::capture)
            )
            is TimetableObjectSelector.Or -> SavedObjectSelectorV1(
                SavedObjectSelectorTypeV1.OR, selectors = value.selectors.map(::capture)
            )
            is TimetableObjectSelector.Not -> SavedObjectSelectorV1(
                SavedObjectSelectorTypeV1.NOT, selectors = listOf(capture(value.selector))
            )
            is TimetableObjectSelector.Tags -> SavedObjectSelectorV1(
                SavedObjectSelectorTypeV1.TAGS, tags = value.required
            )
        }
    }
}

@Serializable
data class SavedInitialPositionV1(
    val trainSelector: SavedObjectSelectorV1,
    val wayPointSelector: SavedObjectSelectorV1
) {
    fun restore() = TimetableInitialPosition(trainSelector.restore(), wayPointSelector.restore())
    companion object {
        fun capture(value: TimetableInitialPosition) = SavedInitialPositionV1(
            SavedObjectSelectorV1.capture(value.trainSelector),
            SavedObjectSelectorV1.capture(value.wayPointSelector)
        )
    }
}

@Serializable
data class SavedCompilationTraceV1(
    val serviceId: String,
    val stepIndex: Int,
    val origin: String,
    val branchPath: List<SavedTimetableBranchPathEntryV1> = emptyList()
)

@Serializable
data class SavedTimetableLineSpeedLimitV1(
    val lineId: String,
    val maximumSpeedMetersPerSecond: Double
) {
    fun restore() = TimetableLineSpeedLimit(LineId(lineId), maximumSpeedMetersPerSecond)

    companion object {
        fun capture(value: TimetableLineSpeedLimit) = SavedTimetableLineSpeedLimitV1(
            value.lineId.value,
            value.maximumSpeedMetersPerSecond
        )
    }
}

/**
 * Persistence projection of a frozen [Timetable]. Capture may share immutable nested values with
 * the source; it is not an isolation boundary for caller-owned mutable collections. Both the source
 * graph and this DTO must remain unchanged while retained or queued for asynchronous encoding.
 */
@Serializable
data class SavedTimetableV1(
    val id: String,
    val periodDays: Long,
    val services: List<SavedTimetableServiceV1>,
    /** Presentation-only lane definitions; ignored by restore(). */
    val diagramLines: List<SavedDiagramLineV1> = emptyList(),
    val initialPositions: List<SavedInitialPositionV1> = emptyList(),
    /** Presentation-only compiler trace; ignored by restore(). */
    val compilationTrace: List<SavedCompilationTraceV1> = emptyList(),
    val lineSpeedLimits: List<SavedTimetableLineSpeedLimitV1> = emptyList(),
    val formationEvents: List<SavedFormationEventV1> = emptyList(),
    val operationalTrips: List<SavedTimetableOperationalTripV1> = emptyList()
) {
    fun restore(): Timetable = Timetable(
        id = id,
        periodDays = periodDays,
        services = services.map(SavedTimetableServiceV1::restore),
        initialPositions = initialPositions.map(SavedInitialPositionV1::restore),
        lineSpeedLimits = lineSpeedLimits.map(SavedTimetableLineSpeedLimitV1::restore),
        formationEvents = TimetableFormationCatalog(formationEvents.map { it.restore() }),
        operationalTrips = java.util.List.copyOf(operationalTrips.map { it.restore() })
    )

    companion object {
        fun capture(value: Timetable): SavedTimetableV1 = SavedTimetableV1(
            value.id,
            value.periodDays,
            value.services.map(SavedTimetableServiceV1::capture),
            initialPositions = value.initialPositions.map(SavedInitialPositionV1::capture),
            lineSpeedLimits = value.lineSpeedLimits.map(SavedTimetableLineSpeedLimitV1::capture),
            formationEvents = value.formationEvents.map(::captureFormationEvent),
            operationalTrips = value.operationalTrips.map(SavedTimetableOperationalTripV1::capture)
        )
    }
}

object TimetableSnapshotCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }
    fun encode(value: SavedTimetableV1): String = json.encodeToString(SavedTimetableV1.serializer(), value)
    fun decode(value: String): SavedTimetableV1 = json.decodeFromString(SavedTimetableV1.serializer(), value)
}

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

private fun SavedFormationDispositionV1.restore(): FormationOutputDisposition = when (type) {
    SavedFormationDispositionType.DUTY -> FormationOutputDisposition.Duty(requireNotNull(dutyId))
    SavedFormationDispositionType.HOLD -> FormationOutputDisposition.Hold
}
private fun SavedFormationOutputV1.restore() = TimetableFormationOutput(id, disposition.restore())
private fun SavedFormationSplitOutputV1.restore() = TimetableFormationOutput(id, disposition.restore())
private fun SavedFormationEventV1.restore(): TimetableFormationEvent = when (kind) {
    SavedFormationEventKind.COUPLE -> TimetableFormationEvent.Couple(id, coordinatorDutyId,
        requireNotNull(approachingDutyId), requireNotNull(stationaryDutyId),
        FormationStationaryEnd.valueOf(requireNotNull(stationaryEnd).name),
        requireNotNull(rendezvous).let { FormationRendezvous(it.approachDestinationId, it.stationaryDestinationId) },
        requireNotNull(maximumApproachSpeedMetersPerSecond), requireNotNull(output).restore())
    SavedFormationEventKind.SPLIT -> TimetableFormationEvent.Split(id, coordinatorDutyId,
        requireNotNull(partition).firstUnitCount,
        outputs.single { it.group == SavedFormationSplitGroup.FIRST }.restore(),
        outputs.single { it.group == SavedFormationSplitGroup.REST }.restore())
}
private fun captureDisposition(value: FormationOutputDisposition): SavedFormationDispositionV1 = when (value) {
    is FormationOutputDisposition.Duty -> SavedFormationDispositionV1(SavedFormationDispositionType.DUTY, value.dutyId)
    FormationOutputDisposition.Hold -> SavedFormationDispositionV1(SavedFormationDispositionType.HOLD)
}
fun captureFormationEvent(value: TimetableFormationEvent): SavedFormationEventV1 = when (value) {
    is TimetableFormationEvent.Couple -> SavedFormationEventV1(value.id, SavedFormationEventKind.COUPLE, value.coordinatorDutyId,
        value.approachingDutyId, value.stationaryDutyId, SavedFormationStationaryEnd.valueOf(value.stationaryEnd.name),
        SavedFormationRendezvousV1(value.rendezvous.approachDestinationId, value.rendezvous.stationaryDestinationId),
        value.maximumApproachSpeedMetersPerSecond, SavedFormationOutputV1(value.output.id, captureDisposition(value.output.disposition)))
    is TimetableFormationEvent.Split -> SavedFormationEventV1(value.id, SavedFormationEventKind.SPLIT, value.coordinatorDutyId,
        partition = SavedFormationPartitionV1(SavedFormationPartitionFrom.HEAD, value.firstUnitCount), outputs = listOf(
            SavedFormationSplitOutputV1(value.first.id, SavedFormationSplitGroup.FIRST, captureDisposition(value.first.disposition)),
            SavedFormationSplitOutputV1(value.rest.id, SavedFormationSplitGroup.REST, captureDisposition(value.rest.disposition))))
}

@Serializable enum class SavedTimetableBranchArmV1 { THEN, ELSE }
@Serializable data class SavedTimetableBranchPathEntryV1(val arm: SavedTimetableBranchArmV1, val index: Int) {
    init { require(index >= 0) }
}

@Serializable enum class SavedStationStopSelectionPolicyV1 { FIXED_PREFERENCE, AVAILABLE_PRIORITY }
@Serializable data class SavedStationStopCandidateV1(val berthId: String, val priority: Int) {
    init { require(berthId.isNotBlank() && berthId == berthId.trim() && priority >= 0) }
}


@Serializable
data class SavedTimetableRunCallV1(val id: String, val stationId: String, val destinationId: String,
    val status: SavedServiceStopStatusV1, val arrivalTick: Long? = null, val departureTick: Long? = null, val viaIds: List<String> = emptyList()) {
    fun restore() = TimetableRunCall(id,stationId,destinationId,TimetableServiceStopStatus.valueOf(status.name),arrivalTick,departureTick,java.util.List.copyOf(viaIds))
    companion object { fun capture(value: TimetableRunCall) = SavedTimetableRunCallV1(value.id,value.stationId,value.destinationId,SavedServiceStopStatusV1.valueOf(value.status.name),value.arrivalTick,value.departureTick,value.viaIds) }
}
@Serializable
data class SavedTimetableRunSegmentV1(val runId: String, val lineId: String, val startStepIndex: Int,
    val endStepIndexExclusive: Int, val diagramLineId: String? = null, val lineDirection: SavedDiagramDirectionV1? = null,
    val calls: List<SavedTimetableRunCallV1> = emptyList(), val metadata: JsonObject = JsonObject(emptyMap())) {
    fun restore() = TimetableRunSegment(runId,LineId(lineId),startStepIndex,endStepIndexExclusive,diagramLineId,
        lineDirection?.let { work.art1st.javarailways.simpleato.api.line.LineDirection.valueOf(it.name) },calls.map { it.restore() },metadata)
    companion object { fun capture(value: TimetableRunSegment) = SavedTimetableRunSegmentV1(value.runId,value.lineId.value,value.startStepIndex,value.endStepIndexExclusive,value.diagramLineId,value.lineDirection?.let { SavedDiagramDirectionV1.valueOf(it.name) },value.calls.map(SavedTimetableRunCallV1::capture),value.metadata) }
}
@Serializable
data class SavedTimetableOperationalTripSegmentV1(val serviceId: String, val runId: String, val unitSlots: List<Int>,
    val unassignedUnits: Boolean = false) {
    fun restore() = TimetableOperationalTripSegment(serviceId,runId,unitSlots,unassignedUnits)
    companion object { fun capture(value: TimetableOperationalTripSegment) = SavedTimetableOperationalTripSegmentV1(value.serviceId,value.runId,value.unitSlots,value.unassignedUnits) }
}
@Serializable
data class SavedTimetableOperationalTripV1(val id: String, val defaults: JsonObject = JsonObject(emptyMap()), val segments: List<SavedTimetableOperationalTripSegmentV1>) {
    fun restore() = TimetableOperationalTrip(id,defaults,segments.map { it.restore() })
    companion object { fun capture(value: TimetableOperationalTrip) = SavedTimetableOperationalTripV1(value.id,value.defaults,value.segments.map(SavedTimetableOperationalTripSegmentV1::capture)) }
}
