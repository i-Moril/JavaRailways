package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.basic.api.depot.DepotId
import work.art1st.javarailways.basic.api.depot.DepotTrainSelector
import work.art1st.javarailways.basic.api.line.LineId
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.simpleato.api.timetable.*
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
    TAKE_FROM_DEPOT,
    TAKE_FROM_WAYPOINT,
    STORE_AT_DEPOT,
    DESTINATION,
    DESTINATION_OPTIONS,
    STOP,
    HALT,
    REVERSE,
    STATION_DESTINATION_OPTIONS,
    EXTENSION,
    CONDITIONAL
}

@Serializable
data class SavedTimetableActionV1(
    val type: SavedTimetableActionTypeV1,
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
    val thenActions: List<SavedTimetableActionV1> = emptyList(),
    val elseActions: List<SavedTimetableActionV1> = emptyList()
) {
    fun restore(): TimetableAction = when (type) {
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
            doorSide = doorSideOverride
        )
        SavedTimetableActionTypeV1.STOP -> TimetableAction.Stop(requireNotNull(stopTicks))
        SavedTimetableActionTypeV1.HALT -> TimetableAction.Halt
        SavedTimetableActionTypeV1.REVERSE -> TimetableAction.Reverse
        SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS -> TimetableAction.CallStation(
            stationId = requireNotNull(stationId),
            berthId = requireNotNull(berthId),
            minimumDwellTicks = minimumDwellTicks,
            stoppingOffsetMetersBefore = stoppingOffsetMetersBefore,
            doorSideOverride = doorSideOverride
        )
        SavedTimetableActionTypeV1.EXTENSION -> TimetableAction.Extension(
            requireNotNull(extensionCall).restore()
        )
        SavedTimetableActionTypeV1.CONDITIONAL -> TimetableAction.Conditional(
            condition = requireNotNull(condition).restore(),
            thenActions = thenActions.map { it.restore() },
            elseActions = elseActions.map { it.restore() }
        )
    }

    companion object {
        fun capture(value: TimetableAction): SavedTimetableActionV1 = when (value) {
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
                doorSideOverride = value.doorSide
            )
            is TimetableAction.Stop -> SavedTimetableActionV1(
                SavedTimetableActionTypeV1.STOP,
                stopTicks = value.ticks
            )
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
                doorSideOverride = value.doorSideOverride
            )
            is TimetableAction.Extension -> SavedTimetableActionV1(
                type = SavedTimetableActionTypeV1.EXTENSION,
                extensionCall = SavedTimetableExtensionCallV1.capture(value.call)
            )
            is TimetableAction.Conditional -> SavedTimetableActionV1(
                type = SavedTimetableActionTypeV1.CONDITIONAL,
                condition = SavedTimetableConditionV1.capture(value.condition),
                thenActions = value.thenActions.map(::capture),
                elseActions = value.elseActions.map(::capture)
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
enum class SavedServiceRowTypeV1 { STATION, SPECIAL_DESTINATION, DEPOT_OPERATION, ACTION_ONLY }

@Serializable
enum class SavedServiceStopStatusV1 { ORIGIN, STOP, PASS, TERMINAL, DISABLED }

/** WebUI authoring metadata. Runtime remains authoritative through the generated steps list. */
@Serializable
data class SavedServiceEditorRowV1(
    val id: String,
    val type: SavedServiceRowTypeV1,
    val status: SavedServiceStopStatusV1? = null,
    val stationId: String? = null,
    val berthId: String? = null,
    val destinationId: String? = null,
    val tick: Long? = null,
    /** Presentation-only planned arrival; ignored by restore(). */
    val arrivalTick: Long? = null,
    val departureTick: Long? = null,
    val depotOperation: SavedTimetableActionTypeV1? = null,
    val depotId: String? = null,
    val actions: List<SavedTimetableActionV1> = emptyList()
) {
    fun restore() = TimetableServiceEditorRow(
        id = id,
        type = TimetableServiceRowType.valueOf(type.name),
        status = status?.let { TimetableServiceStopStatus.valueOf(it.name) },
        stationId = stationId,
        berthId = berthId,
        destinationId = destinationId,
        tick = tick,
        departureTick = departureTick,
        depotOperation = depotOperation?.let {
            when (it) {
                SavedTimetableActionTypeV1.TAKE_FROM_DEPOT -> TimetableServiceDepotOperation.TAKE_FROM_DEPOT
                SavedTimetableActionTypeV1.STORE_AT_DEPOT -> TimetableServiceDepotOperation.STORE_AT_DEPOT
                else -> error("Invalid depot row operation: $it")
            }
        },
        depotId = depotId,
        actions = actions.map(SavedTimetableActionV1::restore)
    )

    companion object {
        fun capture(value: TimetableServiceEditorRow) = SavedServiceEditorRowV1(
            id = value.id,
            type = SavedServiceRowTypeV1.valueOf(value.type.name),
            status = value.status?.let { SavedServiceStopStatusV1.valueOf(it.name) },
            stationId = value.stationId,
            berthId = value.berthId,
            destinationId = value.destinationId,
            tick = value.tick,
            departureTick = value.departureTick,
            depotOperation = value.depotOperation?.let {
                when (it) {
                    TimetableServiceDepotOperation.TAKE_FROM_DEPOT -> SavedTimetableActionTypeV1.TAKE_FROM_DEPOT
                    TimetableServiceDepotOperation.STORE_AT_DEPOT -> SavedTimetableActionTypeV1.STORE_AT_DEPOT
                }
            },
            depotId = value.depotId,
            actions = value.actions.map(SavedTimetableActionV1::capture)
        )
    }
}

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
    val rows: List<SavedServiceEditorRowV1>? = null,
    /** Actual Basic infrastructure line; independent from diagramLineId. */
    val lineId: String? = null
) {
    fun restore(): TimetableService = TimetableService(
        id = id,
        trainSelector = when (selectorType) {
            SavedTrainSelectorTypeV1.ANY -> TrainSelector.AnyTrain
            SavedTrainSelectorTypeV1.TRAIN_ID -> TrainSelector.TrainId(requireNotNull(trainId))
        },
        steps = steps.map(SavedTimetableStepV1::restore),
        activation = activation.restore(),
        editorRows = rows?.map(SavedServiceEditorRowV1::restore),
        lineId = lineId?.let(::LineId)
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
            rows = value.editorRows?.map(SavedServiceEditorRowV1::capture),
            lineId = value.lineId?.value
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
    val origin: String
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
    val lineSpeedLimits: List<SavedTimetableLineSpeedLimitV1> = emptyList()
) {
    fun restore(): Timetable = Timetable(
        id = id,
        periodDays = periodDays,
        services = services.map(SavedTimetableServiceV1::restore),
        initialPositions = initialPositions.map(SavedInitialPositionV1::restore),
        lineSpeedLimits = lineSpeedLimits.map(SavedTimetableLineSpeedLimitV1::restore)
    )

    companion object {
        fun capture(value: Timetable): SavedTimetableV1 = SavedTimetableV1(
            value.id,
            value.periodDays,
            value.services.map(SavedTimetableServiceV1::capture),
            initialPositions = value.initialPositions.map(SavedInitialPositionV1::capture),
            lineSpeedLimits = value.lineSpeedLimits.map(SavedTimetableLineSpeedLimitV1::capture)
        )
    }
}

object TimetableSnapshotCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }
    fun encode(value: SavedTimetableV1): String = json.encodeToString(SavedTimetableV1.serializer(), value)
    fun decode(value: String): SavedTimetableV1 = json.decodeFromString(SavedTimetableV1.serializer(), value)
}
