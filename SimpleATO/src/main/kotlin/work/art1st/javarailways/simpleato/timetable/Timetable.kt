package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.route.operatingDirection
import work.art1st.javarailways.basic.api.route.wayPoints
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationIds
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.timetable.TimetableCondition
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionScope
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall
import work.art1st.javarailways.simpleato.api.timetable.TimetableServiceActivation
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.simpleato.api.station.DwellPlan
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.api.station.StationCall
import work.art1st.javarailways.simpleato.api.station.StationCallRequest
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult
import work.art1st.javarailways.simpleato.api.station.StationCallResolver
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.api.station.StationStopSelection
import work.art1st.javarailways.basic.api.route.ExpandedRoute
import work.art1st.javarailways.simpleato.routing.RouteBuildResult
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.routing.OperationalRoutePlanner
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * 自动时刻表的内部 Kotlin 数据模型。JS loader 只负责生成这些数据。
 *
 * Ownership: before validation, Store/Dispatcher handoff or persistence capture, the producer must
 * freeze the entire reachable value graph, including nested action lists and extension JSON backing
 * maps. Retained mutable aliases must not be changed afterwards. Store and running services may
 * share these values; editing creates a new timetable and uses the explicit replacement lifecycle.
 * Kotlin [List] is a read-only view, not a runtime immutability guarantee. These internal models do
 * not defensively copy every collection; [copy] also shares unchanged nested values.
 */
class Timetable(
    val id: String,
    val periodDays: Long = 1L,
    val services: List<TimetableService> = emptyList(),
    val initialPositions: List<TimetableInitialPosition> = emptyList(),
    val lineSpeedLimits: List<TimetableLineSpeedLimit> = emptyList(),
    val formationEvents: TimetableFormationCatalog = TimetableFormationCatalog(emptyList()),
    operationalTrips: List<TimetableOperationalTrip> = emptyList()
) {
    val operationalTrips: List<TimetableOperationalTrip> = defaultOperationalTrips(services, operationalTrips)
    init {
        require(periodDays > 0L) { "Timetable periodDays must be positive" }
        require(lineSpeedLimits.distinctBy(TimetableLineSpeedLimit::lineId).size == lineSpeedLimits.size) {
            "Timetable contains duplicate line speed limits"
        }
    }

    val periodTicks: Long
        get() = Math.multiplyExact(periodDays, TICKS_PER_GAME_DAY)

    fun copy(id: String = this.id, periodDays: Long = this.periodDays,
        services: List<TimetableService> = this.services,
        initialPositions: List<TimetableInitialPosition> = this.initialPositions,
        lineSpeedLimits: List<TimetableLineSpeedLimit> = this.lineSpeedLimits,
        formationEvents: TimetableFormationCatalog = this.formationEvents,
        operationalTrips: List<TimetableOperationalTrip> = this.operationalTrips): Timetable =
        Timetable(id, periodDays, services, initialPositions, lineSpeedLimits, formationEvents, operationalTrips)

    override fun equals(other: Any?) = other is Timetable && id == other.id && periodDays == other.periodDays &&
        services == other.services && initialPositions == other.initialPositions && lineSpeedLimits == other.lineSpeedLimits &&
        formationEvents == other.formationEvents && operationalTrips == other.operationalTrips
    override fun hashCode(): Int = java.util.Objects.hash(id, periodDays, services, initialPositions,
        lineSpeedLimits, formationEvents, operationalTrips)
    override fun toString() = "Timetable(id=$id, periodDays=$periodDays, services=$services, initialPositions=$initialPositions, " +
        "lineSpeedLimits=$lineSpeedLimits, formationEvents=$formationEvents, operationalTrips=$operationalTrips)"

    companion object {
        const val TICKS_PER_GAME_DAY = 24_000L
    }
}

class TimetableStore {
    private val timetables = AtomicReference<Map<String, Timetable>>(emptyMap())

    /** Stores a frozen [Timetable]; returned values remain subject to the same ownership contract. */
    fun add(timetable: Timetable) {
        require(timetable.id.isNotBlank()) { "timetable id must not be blank" }
        val frozen = freeze(timetable)
        timetables.updateAndGet { current -> current + (frozen.id to frozen) }
    }

    fun replaceAll(values: Collection<Timetable>) {
        val frozen = values.map(::freeze)
        require(frozen.map(Timetable::id).distinct().size == frozen.size) { "duplicate timetable id" }
        timetables.set(frozen.associateBy(Timetable::id))
    }

    fun remove(id: String): Timetable? {
        while (true) {
            val current = timetables.get()
            val removed = current[id] ?: return null
            if (timetables.compareAndSet(current, current - id)) return removed
        }
    }

    operator fun get(id: String): Timetable? = timetables.get()[id]

    fun all(): List<Timetable> = timetables.get().values.sortedBy { it.id }

    private fun freeze(timetable: Timetable): Timetable = timetable.copy(
        operationalTrips = java.util.List.copyOf(timetable.operationalTrips),
        services = timetable.services.map { service ->
            service.copy(runSegments = java.util.List.copyOf(service.runSegments))
        }
    )
}

data class TimetableLineSpeedLimit(
    val lineId: LineId,
    val maximumSpeedMetersPerSecond: Double
) {
    init {
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0.0)
    }
}

/** Nested values follow [Timetable] ownership; do not mutate retained lists after handoff. */
data class TimetableService(
    val id: String,
    val trainSelector: TrainSelector = TrainSelector.AnyTrain,
    val steps: List<TimetableStep> = emptyList(),
    val activation: TimetableServiceActivation = TimetableServiceActivation.Scheduled,
    /** Actual Basic infrastructure line; never substitute presentation-only diagramLineId. */
    val lineId: LineId? = null,
    val runSegments: List<TimetableRunSegment> = emptyList()
)

class LineMaximumSpeedResolver(private val timetable: Timetable) {
    fun resolve(service: TimetableService): Double? {
        val lineId = service.runSegments.firstOrNull()?.lineId ?: service.lineId ?: return null
        return timetable.lineSpeedLimits.singleOrNull { it.lineId == lineId }
            ?.maximumSpeedMetersPerSecond
    }

    fun resolve(service: TimetableService, stepIndex: Int): Double? {
        val lineId = service.segmentAtOrBefore(stepIndex)?.lineId ?: service.lineId ?: return null
        return timetable.lineSpeedLimits.singleOrNull { it.lineId == lineId }?.maximumSpeedMetersPerSecond
    }

    companion object {
        fun resolve(timetable: Timetable, service: TimetableService): Double? =
            LineMaximumSpeedResolver(timetable).resolve(service)
    }
}

enum class TimetableServiceStopStatus { ORIGIN, STOP, PASS, TERMINAL }

sealed class TrainSelector {
    data object AnyTrain : TrainSelector()
    data class TrainId(val trainId: String) : TrainSelector()
}

sealed class TimetableStep {
    data class At(val tick: Long, val action: TimetableAction) : TimetableStep()
    data class Check(val wayPointId: String) : TimetableStep()
}

sealed class TimetableAction {
    /** Null inherits the last physically confirmed berth; explicit sides are relative to current facing. */
    data class OpenDoors(val doorSideOverride: DoorSide? = null) : TimetableAction() {
        init { require(doorSideOverride != DoorSide.NONE) { "door override requires LEFT, RIGHT or BOTH" } }
    }
    data object CloseDoors : TimetableAction()

    data class BeginStationStop(val stopPrograms: StationStopPrograms) : TimetableAction() { init { require(stopPrograms.role == StationStopRole.ORIGIN) { "origin stop requires ORIGIN role" } } }
    class BeginOperationalTrip(val tripId: String, val runId: String, unitSlots: List<Int>) : TimetableAction() {
        val unitSlots = if (unitSlots.isEmpty()) emptyList() else immutableUnitSlots(unitSlots)
        init { require(tripId.isNotBlank() && runId.isNotBlank()) }
        override fun equals(other: Any?) = other is BeginOperationalTrip && tripId == other.tripId && runId == other.runId && unitSlots == other.unitSlots
        override fun hashCode() = java.util.Objects.hash(tripId,runId,unitSlots)
    }
    class SetDisplay(unitSlots: List<Int>, values: kotlinx.serialization.json.JsonObject, removeKeys: List<String> = emptyList(), trainInfo: Map<String, TrainInfoField> = emptyMap()) : TimetableAction() {
        val unitSlots = if (unitSlots.isEmpty()) emptyList() else immutableUnitSlots(unitSlots)
        val values = immutableDisplayValues(values)
        val trainInfo = immutableTrainInfo(trainInfo)
        init { require(trainInfo.isEmpty() || values.isEmpty()) { "trainInfo replaces literal values" } }
        val removeKeys: List<String> = java.util.List.copyOf(removeKeys)
        init { require(removeKeys.distinct().size == removeKeys.size && removeKeys.all { it.isNotBlank() }) }
        override fun equals(other: Any?) = other is SetDisplay && unitSlots == other.unitSlots && values == other.values && removeKeys == other.removeKeys && trainInfo == other.trainInfo
        override fun hashCode() = java.util.Objects.hash(unitSlots, values, removeKeys, trainInfo)
    }
    class ResetDisplay(unitSlots: List<Int>) : TimetableAction() {
        val unitSlots = if (unitSlots.isEmpty()) emptyList() else immutableUnitSlots(unitSlots)
        override fun equals(other: Any?) = other is ResetDisplay && unitSlots == other.unitSlots
        override fun hashCode() = unitSlots.hashCode()
    }
    /** Claims exactly this event output from the same source timetable cycle, never a latest match. */
    data class TakeFromFormationOutput(val eventId: String, val outputId: String) : TimetableAction() {
        init { require(eventId.isNotBlank() && eventId == eventId.trim()); require(outputId.isNotBlank() && outputId == outputId.trim()) }
    }
    data class Couple(val eventId: String) : TimetableAction() {
        init { require(eventId.isNotBlank() && eventId == eventId.trim()) }
    }
    data class Split(val eventId: String) : TimetableAction() {
        init { require(eventId.isNotBlank() && eventId == eventId.trim()) }
    }
    /** Select and reserve one compatible AVAILABLE train from its physical Depot. */
    data class TakeFromDepot(val selector: DepotTrainSelector) : TimetableAction()

    /** Acquire an unassigned stopped train from the first matching directed waypoint. */
    data class TakeFromWaypoint(val wayPointIds: List<String>) : TimetableAction() {
        init {
            require(wayPointIds.isNotEmpty())
            require(wayPointIds.distinct().size == wayPointIds.size)
            wayPointIds.forEach { require(it.isNotBlank() && it == it.trim()) }
        }
    }

    data class StoreAtDepot(
        val depotId: DepotId,
        val stablingPointIds: Set<String> = emptySet()
    ) : TimetableAction() {
        init {
            stablingPointIds.forEach { require(it.isNotBlank() && it == it.trim()) }
        }
    }
    data class GoTo(val wayPointId: String) : TimetableAction()
    data class GoVia(val wayPointIds: List<String>) : TimetableAction()
    data class Stop(val ticks: Long) : TimetableAction()

    /** Execute one plugin-supplied action through the world's timetable extension registry. */
    class Extension(val call: TimetableExtensionCall, trainInfo: Map<String, TrainInfoField> = emptyMap()) : TimetableAction() {
        val trainInfo = immutableTrainInfo(trainInfo)
        init { require(trainInfo.isEmpty() || call.parameters.isEmpty()) { "trainInfo replaces literal parameters" } }
        override fun equals(other: Any?) = other is Extension && call == other.call && trainInfo == other.trainInfo
        override fun hashCode() = java.util.Objects.hash(call, trainInfo)
    }

    /**
     * Evaluate once at its At tick and execute the selected timed branch.
     *
     * Finite timed branches share the same addressed execution cursor and retained route validation.
     * Acquisition and fixed circulation events remain outside runtime branches.
     */
    class Conditional(
        val condition: TimetableCondition,
        thenSteps: List<TimetableStep.At>,
        elseSteps: List<TimetableStep.At> = emptyList()
    ) : TimetableAction() {
        val thenSteps: List<TimetableStep.At> = java.util.List.copyOf(thenSteps)
        val elseSteps: List<TimetableStep.At> = java.util.List.copyOf(elseSteps)
        init {
            require(thenSteps.all { it.action.isRuntimeProgramAction() })
            require(elseSteps.all { it.action.isRuntimeProgramAction() })
        }
        override fun equals(other: Any?) = other is Conditional && condition == other.condition &&
            thenSteps == other.thenSteps && elseSteps == other.elseSteps
        override fun hashCode() = 31 * (31 * condition.hashCode() + thenSteps.hashCode()) + elseSteps.hashCode()
    }

    /** Hold at the acquired/checked position until the enclosing At.tick, then continue. */
    data class TaskStart(val taskId: String) : TimetableAction() { init { require(taskId.isNotBlank() && taskId.length <= 128) } }
    data class TaskEnd(val taskId: String) : TimetableAction() { init { require(taskId.isNotBlank() && taskId.length <= 128) } }
    data object WaitUntil : TimetableAction()
    data object Halt : TimetableAction()
    data object Reverse : TimetableAction()
    class DestinationOptions(
        val destinationId: String,
        val minimumDwellTicks: Long? = null,
        val stoppingOffsetMetersBefore: Double? = null,
        val doorSide: DoorSide? = null,
        val stopPrograms: StationStopPrograms? = null
    ) : TimetableAction() {
        init {
            require(stopPrograms?.role != StationStopRole.ORIGIN) { "incoming stop cannot have ORIGIN role" }
            require(destinationId.isNotBlank() && destinationId == destinationId.trim())
            require(minimumDwellTicks == null || minimumDwellTicks >= 0L)
            require(stoppingOffsetMetersBefore == null ||
                stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
        }
        override fun equals(other: Any?) = other is DestinationOptions &&
            destinationId == other.destinationId && minimumDwellTicks == other.minimumDwellTicks && stoppingOffsetMetersBefore == other.stoppingOffsetMetersBefore && doorSide == other.doorSide && stopPrograms == other.stopPrograms
        override fun hashCode() = java.util.Objects.hash(destinationId, minimumDwellTicks, stoppingOffsetMetersBefore, doorSide, stopPrograms)
    }
    /** One selected station movement; successor state is the actual chosen stopping marker. */
    class SelectStop(
        val selection: StationStopSelection,
        val minimumDwellTicks: Long? = null,
        val stoppingOffsetMetersBefore: Double? = null,
        val doorSideOverride: DoorSide? = null,
        val stopPrograms: StationStopPrograms? = null
    ) : TimetableAction() {
        init {
            require(stopPrograms?.role != StationStopRole.ORIGIN) { "incoming stop cannot have ORIGIN role" }
            require(minimumDwellTicks == null || minimumDwellTicks >= 0L)
            require(stoppingOffsetMetersBefore == null || stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
        }
        override fun equals(other: Any?) = other is SelectStop &&
            selection == other.selection && minimumDwellTicks == other.minimumDwellTicks && stoppingOffsetMetersBefore == other.stoppingOffsetMetersBefore && doorSideOverride == other.doorSideOverride && stopPrograms == other.stopPrograms
        override fun hashCode() = java.util.Objects.hash(selection, minimumDwellTicks, stoppingOffsetMetersBefore, doorSideOverride, stopPrograms)
    }

    class CallStation(
        val stationId: String,
        val berthId: String,
        val minimumDwellTicks: Long? = null,
        val stoppingOffsetMetersBefore: Double? = null,
        val doorSideOverride: DoorSide? = null,
        val stopPrograms: StationStopPrograms? = null
    ) : TimetableAction() {
        init {
            require(stopPrograms?.role != StationStopRole.ORIGIN) { "incoming stop cannot have ORIGIN role" }
            require(stationId.isNotBlank() && berthId.isNotBlank())
            require(minimumDwellTicks == null || minimumDwellTicks >= 0L)
            require(stoppingOffsetMetersBefore == null ||
                stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
        }

        val berthRef: StationBerthRef
            get() = StationBerthRef(StationId(stationId), berthId)
        override fun equals(other: Any?) = other is CallStation &&
            stationId == other.stationId && berthId == other.berthId && minimumDwellTicks == other.minimumDwellTicks && stoppingOffsetMetersBefore == other.stoppingOffsetMetersBefore && doorSideOverride == other.doorSideOverride && stopPrograms == other.stopPrograms
        override fun hashCode() = java.util.Objects.hash(stationId, berthId, minimumDwellTicks, stoppingOffsetMetersBefore, doorSideOverride, stopPrograms)
    }

    private fun isRuntimeProgramAction(): Boolean = when (this) {
        is SetDisplay, is ResetDisplay, is Extension, is Stop, WaitUntil, Reverse, is GoTo, is GoVia, is DestinationOptions, is CallStation, is SelectStop -> true
        is Conditional -> thenSteps.all { it.action.isRuntimeProgramAction() } &&
            elseSteps.all { it.action.isRuntimeProgramAction() }
        else -> false
    }
}

/** Candidate retained only after its whole normal continuation has been validated. */
internal data class ValidatedStationSelectionKey(val serviceId: String, val address: TimetableStepAddress,
    val originId: String?, val berthId: String)

/** Transient, validated association; not an authored or persisted identity. */
data class ValidatedTimetableRouteKey(
    val serviceId: String,
    val stepIndex: Int,
    val originId: String,
    val legIndex: Int,
    val targetId: String,
    val startDirection: ViewDirection,
    val address: TimetableStepAddress = TimetableStepAddress(stepIndex),
    val stoppingOffsetMeters: Double? = 0.0
) {
    init { require(address.topLevelIndex == stepIndex) }
}

data class ValidatedTimetableRoute(
    val route: ExpandedRoute,
    val stoppingOffsetMeters: Double?,
    val topology: work.art1st.javarailways.core.api.physics.rail.topology.TopologyState,
    val terminalStationStop: Boolean = false
)

@ConsistentCopyVisibility
data class TimetableValidationResult private constructor(
    val errors: List<String>,
    val routes: List<ExpandedRoute>,
    /** Offset before the endpoint anchor; null denotes a pass-through leg, aligned with [routes]. */
    val routeStoppingOffsetsMeters: List<Double?>,
    val retainedRoutes: Map<ValidatedTimetableRouteKey, ValidatedTimetableRoute>,
    internal val structural: StructuralTimetableValidation,
    val circulation: work.art1st.javarailways.simpleato.api.timetable.TimetableCirculationSummary?
) {
    constructor(errors: Collection<String>, routes: Collection<ExpandedRoute>,
        routeStoppingOffsetsMeters: Collection<Double?> = List(routes.size) { 0.0 },
        retainedRoutes: Map<ValidatedTimetableRouteKey, ValidatedTimetableRoute> = emptyMap()) : this(
        java.util.List.copyOf(errors), java.util.List.copyOf(routes),
        java.util.Collections.unmodifiableList(ArrayList(routeStoppingOffsetsMeters)),
        java.util.Collections.unmodifiableMap(LinkedHashMap(retainedRoutes)), StructuralTimetableValidation(), null)

    internal constructor(errors: Collection<String>, routes: Collection<ExpandedRoute>,
        routeStoppingOffsetsMeters: Collection<Double?>,
        retainedRoutes: Map<ValidatedTimetableRouteKey, ValidatedTimetableRoute>, structural: StructuralTimetableValidation,
        circulation: work.art1st.javarailways.simpleato.api.timetable.TimetableCirculationSummary? = null) : this(
        java.util.List.copyOf(errors), java.util.List.copyOf(routes),
        java.util.Collections.unmodifiableList(ArrayList(routeStoppingOffsetsMeters)),
        java.util.Collections.unmodifiableMap(LinkedHashMap(retainedRoutes)), structural, circulation)

    init {
        require(routes.size == routeStoppingOffsetsMeters.size) { "route stopping offsets must align with routes" }
        require(routeStoppingOffsetsMeters.all { it == null || it.isFinite() && it >= 0.0 }) {
            "route stopping offsets must be finite and non-negative"
        }
    }
    val isSuccess: Boolean get() = errors.isEmpty()
}

class TimetableValidator(
    private val world: World,
    private val standaloneSimulationServiceIds: Set<String> = emptySet()
) {
    init {
        require(standaloneSimulationServiceIds.isEmpty() || world.executionMode == WorldExecutionMode.SIMULATION) {
            "standalone simulation validation cannot be used in a live world"
        }
    }
    private val routePlanner = OperationalRoutePlanner(world)

    fun validate(timetable: Timetable): TimetableValidationResult = validateDefinition(timetable, timetable)

    /** A service start checks its current physical route without shrinking the recurring inventory graph. */
    internal fun validateForServiceStart(timetable: Timetable, serviceId: String): TimetableValidationResult {
        val service = requireNotNull(timetable.services.singleOrNull { it.id == serviceId }) { "Service is absent or duplicated: $serviceId" }
        val routeDefinition = if (timetable.formationEvents.isEmpty()) timetable.copy(services = listOf(service)) else timetable
        return validateDefinition(routeDefinition, timetable)
    }

    private fun validateDefinition(timetable: Timetable, circulationTimetable: Timetable): TimetableValidationResult {
        val errors = mutableListOf<String>()
        val routes = mutableListOf<ExpandedRoute>()
        val routeStoppingOffsets = mutableListOf<Double?>()
        val retainedRoutes = LinkedHashMap<ValidatedTimetableRouteKey, ValidatedTimetableRoute>()
        val retainedSelections = linkedSetOf<ValidatedStationSelectionKey>()

        if (timetable.id.isBlank()) errors.add("时刻表 id 不能为空")
        if (timetable.periodDays <= 0L) errors.add("时刻表周期天数必须 > 0: ${timetable.periodDays}")
        val periodTicks = runCatching { timetable.periodTicks }.getOrElse {
            errors.add("时刻表周期 Tick 溢出: ${timetable.periodDays} days")
            Long.MAX_VALUE
        }

        timetable.lineSpeedLimits.forEach { limit ->
            if (world.simpleAtoWorldData.lines[limit.lineId] == null) {
                errors.add("线路限速引用不存在的 LineId: ${limit.lineId.value}")
            }
        }
        errors += TimetableMetadataValidator.validate(world, circulationTimetable)
        val formationErrors = FormationEventValidator.validate(timetable)
        errors += formationErrors
        timetable.formationEvents.filterIsInstance<TimetableFormationEvent.Couple>().forEach { event ->
            listOf(event.rendezvous.approachDestinationId, event.rendezvous.stationaryDestinationId).forEach { id ->
                if (!wayPointExists(id)) errors += "formation event ${event.id} destination does not exist: $id"
            }
        }
        val serviceIds = mutableSetOf<String>()
        for (service in timetable.services) {
            if (!serviceIds.add(service.id)) errors.add("重复 service id: ${service.id}")
            if (service.activation is TimetableServiceActivation.Conditional) errors += "[${timetable.id}/${service.id}] runtime service activation conditions are not editable-plan deterministic"
            val program = TimetableServiceTraversal(service)
            val branchErrors = TimetableBranchGuard.validate(program,
                conditionalActivation = service.activation is TimetableServiceActivation.Conditional)
            errors += branchErrors.map { "[${timetable.id}/${service.id}] ${it.address} ${it.code}: ${it.detail}" }
            if (branchErrors.any { it.code == "PROGRAM_LIMIT" }) continue
            errors += TimetableProgramSchedule.validate(program, periodTicks).map {
                "[${timetable.id}/${service.id}] ${it.address} ${it.code}: ${it.detail}"
            }
            var unsupportedRuntimeCondition = service.activation is TimetableServiceActivation.Conditional
            fun validateArrivals(steps: List<TimetableStep>, parent: TimetableStepAddress? = null, arm: TimetableBranchArm? = null) {
                steps.forEachIndexed { index, step ->
                    val address = parent?.child(checkNotNull(arm), index) ?: TimetableStepAddress(index)
                    val action = (step as? TimetableStep.At)?.action ?: return@forEachIndexed
                    val stopPrograms = when (action) {
                        is TimetableAction.BeginStationStop -> action.stopPrograms
                        is TimetableAction.DestinationOptions -> action.stopPrograms
                        is TimetableAction.CallStation -> action.stopPrograms
                        is TimetableAction.SelectStop -> action.stopPrograms
                        else -> null
                    }
                    if (stopPrograms != null) {
                        if (stopPrograms.plannedDepartureTick?.let { it >= periodTicks } == true) errors += "[$timetable/${service.id}] $address station departure outside timetable period"
                        fun validateStationActions(actions: List<StationProgramAction>) {
                            for (instruction in actions) when (instruction) {
                                is StationProgramAction.WaitUntil -> if (instruction.tick >= periodTicks) errors += "[${timetable.id}/${service.id}] $address station wait outside timetable period"
                                is StationProgramAction.Async -> validateStationActions(instruction.steps)
                                is StationProgramAction.Extension -> validateExtensionAction("[${timetable.id}/${service.id}]", address, TimetableAction.Extension(instruction.call, instruction.trainInfo), errors)
                                else -> Unit
                            }
                        }
                        validateStationActions(stopPrograms.arrival)
                        validateStationActions(stopPrograms.departurePreparation)
                    }
                    if (action is TimetableAction.Conditional) {
                        unsupportedRuntimeCondition = true
                        errors += "[${timetable.id}/${service.id}] $address runtime conditions are not editable-plan deterministic; compile planned branches first"
                        validateArrivals(action.thenSteps, address, TimetableBranchArm.THEN)
                        validateArrivals(action.elseSteps, address, TimetableBranchArm.ELSE)
                    }
                }
            }
            validateArrivals(service.steps)
            if (unsupportedRuntimeCondition) continue
            validateServiceLine(timetable, service, errors)
            validateService(timetable.id, periodTicks, service, errors, routes, routeStoppingOffsets, retainedRoutes, retainedSelections)
        }
        val structural = if (timetable.formationEvents.isEmpty() || formationErrors.isNotEmpty()) StructuralTimetableValidation()
        else try {
            val validateStructural = {
                StructuralTimetableValidator(world, retainedRoutes, retainedSelections) { selector ->
                    depotValidationOrigins(selector).map { it.point.destinationId }
                }.validate(timetable)
            }
            if (timetable.formationEvents.any { it is TimetableFormationEvent.Couple }) {
                val core = world as? work.art1st.javarailways.core.physics.CoreWorld
                    ?: error("coupling static proof requires the Core serialized owner")
                core.physicsCoordinator.withStructuralMutation { validateStructural() }
            } else validateStructural()
        } catch (failure: Exception) {
            StructuralTimetableValidation(listOf("formation output has no retained onward-route proof: ${failure.message ?: failure.javaClass.simpleName}"))
        }
        errors += structural.errors
        if (structural.errors.isEmpty()) errors += TimetableMetadataValidator.validatePhysical(world,circulationTimetable,structural)
        val requiresCirculationAdmission = circulationTimetable.formationEvents.isNotEmpty()
        val circulation = CurrentUnitCirculation.analyze(world, circulationTimetable)
            .summary(blockingInventoryFindings = requiresCirculationAdmission)
        // Ordinary physical trains now have Core units too. That does not opt a nonstructural
        // service into recurring inventory admission; retain the complete diagnostic report.
        if (requiresCirculationAdmission) {
            errors += circulation.findings.filter { it.blocking }.map { "[${timetable.id}] circulation ${it.code}: ${it.detail}" }
        }
        return TimetableValidationResult(errors, routes, routeStoppingOffsets, retainedRoutes, structural, circulation)
    }

    private enum class DoorProof { UNKNOWN, SECURED, OPEN }

    private fun validateServiceLine(
        timetable: Timetable,
        service: TimetableService,
        errors: MutableList<String>
    ) {
        val prefix = "[${timetable.id}/${service.id}]"
        val lines = if (service.runSegments.isEmpty()) listOfNotNull(service.lineId) else service.runSegments.map { it.lineId }.distinct()
        lines.forEach { lineId ->
            if (world.simpleAtoWorldData.lines[lineId] == null) errors += "$prefix 引用不存在的 LineId: ${lineId.value}"
            if (timetable.lineSpeedLimits.none { it.lineId == lineId }) errors += "$prefix 未配置线路最高速度: ${lineId.value}"
        }
    }

    private fun validateService(
        timetableId: String,
        periodTicks: Long,
        service: TimetableService,
        errors: MutableList<String>,
        routes: MutableList<ExpandedRoute>,
        routeStoppingOffsets: MutableList<Double?>,
        retainedRoutes: MutableMap<ValidatedTimetableRouteKey, ValidatedTimetableRoute>,
        retainedSelections: MutableSet<ValidatedStationSelectionKey>
    ) {
        val prefix = "[$timetableId/${service.id}]"
        val standaloneSimulation = service.id in standaloneSimulationServiceIds
        var initialSimulationDirection: ViewDirection? = null
        if (standaloneSimulation) {
            val take = (service.steps.firstOrNull() as? TimetableStep.At)?.action as? TimetableAction.TakeFromWaypoint
            val point = take?.wayPointIds?.singleOrNull()?.let { world.wayPoints[it] }
            initialSimulationDirection = point?.let {
                world.simpleAtoWorldData.destinations[it.id]?.approachDirection
                    ?: world.operatingDirection(it.railId)
            }
            if (point == null || world.getRail(point.railId) == null || initialSimulationDirection == null)
                errors += "$prefix simulation initial waypoint must have an explicit waypoint or rail direction"
        }
        if (service.id.isBlank()) errors.add("$prefix service id 不能为空")
        when (val activation = service.activation) {
            TimetableServiceActivation.Scheduled,
            TimetableServiceActivation.Manual -> Unit
            is TimetableServiceActivation.Conditional -> {
                world.simpleAtoWorldData.timetableExtensions.validateCondition(
                    activation.condition,
                    TimetableConditionScope.SERVICE_ACTIVATION
                )
                    .forEach { errors.add("$prefix activation: $it") }
            }
        }

        data class State(
            val currentWayPointId: String?, val requiredStartDirections: Set<ViewDirection?> = setOf(null),
            val previousAtTick: Long? = null,
            val pendingStationCall: Pair<Int, TimetableAction.CallStation>? = null,
            val pendingDestinationOptions: Pair<Int, TimetableAction.DestinationOptions>? = null,
            val dynamicOriginCandidates: List<String>? = null, val takeActionCount: Int = 0,
            val takeFromDepotSelector: DepotTrainSelector? = null, val hasMovement: Boolean = false,
            val hasFormationOrigin: Boolean = false, val doors: DoorProof = DoorProof.UNKNOWN)
        class ProofSink(
            val errors: MutableList<String> = arrayListOf(),
            val routes: MutableList<ExpandedRoute> = arrayListOf(),
            val offsets: MutableList<Double?> = arrayListOf(),
            val retained: MutableMap<ValidatedTimetableRouteKey, ValidatedTimetableRoute> = linkedMapOf(),
            val selections: MutableSet<ValidatedStationSelectionKey> = linkedSetOf()
        ) {
            fun merge(other: ProofSink) {
                this.errors += other.errors
                this.selections += other.selections
                other.retained.forEach { (key, evidence) ->
                    val previous = this.retained.putIfAbsent(key, evidence)
                    if (previous == null) {
                        // Legacy route arrays retain each association, including shared suffix geometry
                        // from different origins. Cached copies of the same key are published only once.
                        this.routes.add(evidence.route)
                        this.offsets.add(evidence.stoppingOffsetMeters)
                    } else if (previous != evidence) {
                        this.errors += "$prefix route key has conflicting geometry or stopping constraints: $key"
                    }
                }
            }
        }

        val allProofs = ProofSink(errors, routes, routeStoppingOffsets, retainedRoutes, retainedSelections)
        var proofWork = 0
        var budgetFailure: String? = null
        fun chargeProofWork(sink: ProofSink, count: Int = 1): Boolean {
            require(count >= 0)
            if (budgetFailure == null && count <= 4096 - proofWork) {
                proofWork += count
                return true
            }
            val message = budgetFailure ?: "$prefix finite continuation proof exceeds 4096 work items"
            budgetFailure = message
            if (message !in errors) errors += message
            if (message !in sink.errors) sink.errors += message
            return false
        }
        val servicePrefix = prefix
        fun stationDoorSide(destinationId: String?, override: DoorSide? = null): DoorSide =
            override ?: (destinationId?.let { world.simpleAtoWorldData.destinations[it]?.role }
                as? DestinationRole.StationBerthRole)?.doorSide ?: DoorSide.NONE
        fun stationProgramResult(program: StationStopPrograms): DoorProof {
            val actions = when (program.role) {
                StationStopRole.ORIGIN -> program.departurePreparation
                StationStopRole.STOP -> program.arrival + program.departurePreparation
                StationStopRole.TERMINAL -> program.arrival
            }
            fun doorActions(values: List<StationProgramAction>): List<StationProgramAction> = values.flatMap { action ->
                when (action) {
                    StationProgramAction.OpenDoors, StationProgramAction.CloseDoors -> listOf(action)
                    is StationProgramAction.Async -> doorActions(action.steps)
                    else -> emptyList()
                }
            }
            val physical = doorActions(actions)
            if (physical.isEmpty()) return DoorProof.UNKNOWN
            if (actions.any { it is StationProgramAction.Async && doorActions(it.steps).isNotEmpty() }) {
                return when {
                    physical.all { it == StationProgramAction.OpenDoors } -> DoorProof.OPEN
                    physical.all { it == StationProgramAction.CloseDoors } -> DoorProof.SECURED
                    else -> DoorProof.UNKNOWN
                }
            }
            return if (physical.last() == StationProgramAction.OpenDoors) DoorProof.OPEN else DoorProof.SECURED
        }
        fun requireSecured(
            doors: DoorProof,
            address: TimetableStepAddress,
            sink: ProofSink,
            operation: String
        ): DoorProof {
            if (doors != DoorProof.OPEN) return doors
            sink.errors += "$servicePrefix $address 车门状态冲突：前序停站或动作已明确打开车门，$operation；" +
                "请在此步骤前显式添加 CLOSE_DOORS"
            return DoorProof.UNKNOWN
        }
        fun process(step: TimetableStep, address: TimetableStepAddress, input: State, sink: ProofSink): State {
            val errors = sink.errors
            val routes = sink.routes
            val routeStoppingOffsets = sink.offsets
            val retainedRoutes = sink.retained
            val index = address.topLevelIndex
            val prefix = "$servicePrefix $address"
            var currentWayPointId = input.currentWayPointId
            var requiredStartDirections = input.requiredStartDirections
            var previousAtTick = input.previousAtTick
            var pendingStationCall = input.pendingStationCall
            var pendingDestinationOptions = input.pendingDestinationOptions
            var dynamicOriginCandidates = input.dynamicOriginCandidates
            var takeActionCount = input.takeActionCount
            var takeFromDepotSelector = input.takeFromDepotSelector
            var hasMovement = input.hasMovement
            var hasFormationOrigin = input.hasFormationOrigin
            var doors = input.doors
            when (step) {
                is TimetableStep.Check -> {
                    if (takeActionCount > 0) {
                        errors.add("$prefix step#$index check 不得覆盖动态取车的起点")
                    }
                    dynamicOriginCandidates = null
                    if (!wayPointExists(step.wayPointId)) {
                        errors.add("$prefix step#$index check 路径点不存在: ${step.wayPointId}")
                    } else {
                        currentWayPointId = step.wayPointId
                    }
                }
                is TimetableStep.At -> {
                    var movementStoppingOffset = pendingDestinationOptions?.second?.stoppingOffsetMetersBefore ?: 0.0
                    if (step.tick < 0) errors.add("$prefix step#$index tick 不能为负: ${step.tick}")
                    if (step.tick >= periodTicks) {
                        errors.add("$prefix step#$index tick 超出周期: ${step.tick} >= $periodTicks")
                    }
                    previousAtTick?.let {
                        if (step.tick < it) errors.add("$prefix step#$index tick 早于前一个 at: ${step.tick} < $it")
                    }
                    previousAtTick = step.tick
                    when (step.action) {
                        is TimetableAction.BeginStationStop, is TimetableAction.BeginOperationalTrip, is TimetableAction.SetDisplay, is TimetableAction.ResetDisplay -> {
                            if (currentWayPointId == null && takeActionCount == 0 && !hasFormationOrigin && !(service.trainSelector is TrainSelector.TrainId && service.steps.any { it is TimetableStep.Check })) errors += "$prefix display action requires an acquired or checked train"
                            if (pendingStationCall != null || pendingDestinationOptions != null) errors += "$prefix display action cannot interrupt destination options"
                        }
                        is TimetableAction.SelectStop -> error("SelectStop requires candidate continuation validation")
                        is TimetableAction.TakeFromDepot -> {
                            doors = DoorProof.UNKNOWN
                            takeActionCount++
                            takeFromDepotSelector = step.action.selector
                            validateTakeAction(
                                prefix, index, "takeFromDepot", takeActionCount,
                                service, pendingStationCall, pendingDestinationOptions, errors
                            )
                        }
                        is TimetableAction.TakeFromFormationOutput -> {
                            doors = DoorProof.UNKNOWN
                            hasFormationOrigin = true
                            takeActionCount++
                            validateTakeAction(prefix, index, "takeFromFormationOutput", takeActionCount,
                                service, pendingStationCall, pendingDestinationOptions, errors)
                        }
                        is TimetableAction.Couple, is TimetableAction.Split -> {
                            doors = requireSecured(doors, address, sink, "当前编组作业要求车门已经关闭")
                            doors = DoorProof.UNKNOWN
                            if (currentWayPointId == null && takeActionCount == 0 && !hasFormationOrigin)
                                errors += "$prefix step#$index formation action requires an acquired or checked train"
                            hasFormationOrigin = true
                            takeFromDepotSelector = null
                            if (pendingStationCall != null || pendingDestinationOptions != null)
                                errors += "$prefix step#$index formation action cannot interrupt pending destination navigation"
                        }
                        is TimetableAction.TakeFromWaypoint -> {
                            doors = DoorProof.UNKNOWN
                            takeActionCount++
                            validateTakeAction(
                                prefix, index, "takeFromWaypoint", takeActionCount,
                                service, pendingStationCall, pendingDestinationOptions, errors
                            )
                        }
                        is TimetableAction.StoreAtDepot -> {
                            doors = requireSecured(doors, address, sink, "当前存车行车动作要求车门已经关闭")
                            if (service.activation is TimetableServiceActivation.Conditional) {
                                errors.add("$prefix step#$index storeAtDepot 不得用于运行时条件激活的 service")
                            }
                            if (takeActionCount != 1 && !(takeActionCount == 0 && hasFormationOrigin)) {
                                errors.add(
                                    "$prefix step#$index storeAtDepot 前必须有且仅有一次取车"
                                )
                            }
                            if (service.steps.drop(index + 1).any { (it as? TimetableStep.At)?.action !is TimetableAction.TaskEnd }) {
                                errors.add("$prefix step#$index storeAtDepot 必须是 service 的最后一步")
                            }
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 后必须完成导航，不能直接存车")
                            }
                        }
                        is TimetableAction.DestinationOptions -> {
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 不能重叠")
                            }
                            pendingDestinationOptions = index to step.action
                        }
                        is TimetableAction.CallStation -> {
                            if (pendingStationCall != null) {
                                errors.add(
                                    "$prefix step#$index 车站目的地前还有未配对的车站操作: " +
                                        "step#${pendingStationCall.first}"
                                )
                            }
                            pendingStationCall = index to step.action
                        }
                        is TimetableAction.GoTo,
                        is TimetableAction.GoVia -> {
                            doors = requireSecured(doors, address, sink, "当前行车动作要求车门已经关闭")
                            hasMovement = true
                            if (currentWayPointId == null && takeActionCount == 0 && !hasFormationOrigin) {
                                errors.add(
                                    "$prefix step#$index destination 缺少初始 check、takeFromDepot 或 takeFromWaypoint"
                                )
                            }
                            val movementTargets = when (val movement = step.action) {
                                is TimetableAction.GoTo -> listOf(movement.wayPointId)
                                is TimetableAction.GoVia -> movement.wayPointIds
                            }
                            pendingDestinationOptions?.let { (optionsIndex, options) ->
                                if (movementTargets.lastOrNull() != options.destinationId) {
                                    errors.add(
                                        "$prefix step#$optionsIndex destination options 目标 " +
                                            "${options.destinationId} 与导航终点 ${movementTargets.lastOrNull()} 不一致"
                                    )
                                }
                            }
                        }
                        TimetableAction.Halt -> {
                            if (!hasMovement && !standaloneSimulation) errors.add("$prefix step#$index halt 前必须至少有一个 destination")
                            if (service.steps.drop(index + 1).any { (it as? TimetableStep.At)?.action !is TimetableAction.TaskEnd }) {
                                errors.add("$prefix step#$index halt 必须是 service 的最后一步")
                            }
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 后必须先完成导航")
                            }
                        }
                        is TimetableAction.OpenDoors, TimetableAction.CloseDoors -> {
                            val open = step.action as? TimetableAction.OpenDoors
                            doors = when {
                                open == null -> DoorProof.SECURED
                                open.doorSideOverride != null || doors == DoorProof.OPEN -> DoorProof.OPEN
                                else -> DoorProof.UNKNOWN
                            }
                            if (currentWayPointId == null && takeActionCount == 0 && !hasFormationOrigin)
                                errors += "$prefix step#$index door action requires an acquired or checked train"
                            if (pendingStationCall != null || pendingDestinationOptions != null)
                                errors += "$prefix step#$index door action cannot interrupt pending destination navigation"
                        }
                        is TimetableAction.TaskStart, is TimetableAction.TaskEnd -> Unit
                        TimetableAction.WaitUntil -> {
                            if (currentWayPointId == null && takeActionCount == 0 && !hasFormationOrigin) {
                                errors.add("$prefix step#$index waitUntil 前必须先通过 check/take 确定列车位置")
                            }
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 后必须先完成导航")
                            }
                        }
                        TimetableAction.Reverse -> {
                            if (!hasMovement && takeActionCount == 0 && currentWayPointId == null && !hasFormationOrigin) {
                                errors.add(
                                    "$prefix step#$index reverse 前必须先通过 check/take 确定列车位置"
                                )
                            }
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 后必须先完成导航")
                            }
                        }
                        is TimetableAction.Stop -> {
                            if (!hasMovement && takeActionCount != 1 && !hasFormationOrigin) {
                                errors.add("$prefix step#$index stop 前必须完成取车或 destination")
                            }
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 后必须直接跟随导航")
                            }
                        }
                        is TimetableAction.Extension -> {
                            if (!hasMovement && takeActionCount != 1 && !hasFormationOrigin) {
                                errors.add("$prefix step#$index 扩展动作前必须完成取车或 destination")
                            }
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 后必须直接跟随导航")
                            }
                            validateExtensionAction(prefix, address, step.action, errors)
                        }
                        is TimetableAction.Conditional -> {
                            if (currentWayPointId == null && takeActionCount == 0 && !hasFormationOrigin && !(service.trainSelector is TrainSelector.TrainId && service.steps.any { it is TimetableStep.Check }))
                                errors += "$prefix conditional requires an acquired or checked train"
                            world.simpleAtoWorldData.timetableExtensions.validateCondition(step.action.condition,
                                TimetableConditionScope.RUNTIME_BRANCH).forEach { errors += "$prefix condition: $it" }
                        }
                    }
                    val movement = step.action is TimetableAction.GoTo || step.action is TimetableAction.GoVia
                    val origins = if (movement || step.action is TimetableAction.StoreAtDepot)
                        dynamicOriginCandidates ?: listOf(currentWayPointId) else listOf(currentWayPointId)
                    var successor = currentWayPointId
                    val arrivalDirections = linkedSetOf<ViewDirection?>()
                    for ((originIndex, origin) in origins.withIndex()) {
                        if (budgetFailure != null) { chargeProofWork(sink, 0); break }
                        val directionalMovement = movement || step.action is TimetableAction.StoreAtDepot
                        val inputs = if (directionalMovement) requiredStartDirections.flatMap { direction ->
                            val effective = direction ?: origin?.let(::destinationDirection)
                            if (effective == null) ViewDirection.entries else listOf(effective)
                        }.distinct() else listOf(requiredStartDirections.firstOrNull())
                        val rejected = ArrayList<String>()
                        var accepted = false
                        for (inputDirection in inputs) {
                            val routeWork = when (val action = step.action) {
                                is TimetableAction.GoVia -> action.wayPointIds.size
                                is TimetableAction.GoTo, is TimetableAction.StoreAtDepot -> 1
                                else -> 0
                            }
                            if (!chargeProofWork(sink, routeWork)) break
                            val candidateRoutes = ArrayList<ExpandedRoute>()
                            val candidateOffsets = ArrayList<Double?>()
                            val candidateErrors = ArrayList<String>()
                            var explicitTerminalStationCall = false
                            val next = validateAction(prefix, index, origin, step.action, candidateErrors, candidateRoutes,
                                candidateOffsets, takeFromDepotSelector, inputDirection, movementStoppingOffset)
                            if (movement && pendingStationCall != null && candidateRoutes.isNotEmpty()) {
                                val (callIndex, call) = pendingStationCall!!
                                val offset = validateStationCallPrefix(prefix, callIndex, origin, call,
                                    candidateRoutes, inputDirection, candidateErrors)
                                if (offset != null) {
                                    candidateOffsets[candidateOffsets.lastIndex] = offset
                                    explicitTerminalStationCall = true
                                }
                            }
                            if (candidateErrors.isNotEmpty()) {
                                rejected += candidateErrors
                                // A known predecessor direction is a real possible state, not an optional search direction.
                                if (inputDirection != null && inputDirection in requiredStartDirections) errors += candidateErrors
                                continue
                            }
                            accepted = true
                            if (originIndex == 0) successor = next
                            if (movement) candidateRoutes.lastOrNull()?.let {
                                arrivalDirections += it.segments.last().railViewRef.direction
                            }
                            candidateRoutes.forEachIndexed { legIndex, route ->
                                val key = ValidatedTimetableRouteKey(service.id, index, requireNotNull(origin),
                                    if (step.action is TimetableAction.StoreAtDepot) 0 else legIndex,
                                    route.toWayPointId, route.segments.first().railViewRef.direction, address, candidateOffsets[legIndex])
                                val terminalStation = candidateOffsets[legIndex] != null &&
                                    (world.simpleAtoWorldData.destinations[route.toWayPointId]?.role is DestinationRole.StationBerthRole ||
                                        explicitTerminalStationCall && legIndex == candidateRoutes.lastIndex)
                                val evidence = ValidatedTimetableRoute(route, candidateOffsets[legIndex], world.topologyState, terminalStation)
                                val previous = retainedRoutes.putIfAbsent(key, evidence)
                                if (previous != null && previous != evidence)
                                    errors += "$prefix route key has conflicting geometry or stopping constraints: $key"
                            }
                            routes += candidateRoutes
                            routeStoppingOffsets += candidateOffsets
                        }
                        if (!accepted) errors += rejected.distinct()
                    }
                    currentWayPointId = successor
                    val destinationOptions = pendingDestinationOptions?.second
                    val stationCall = pendingStationCall?.second
                    val stationInvocation = when {
                        movement && destinationOptions != null -> destinationOptions.stopPrograms?.let {
                            it to stationDoorSide(destinationOptions.destinationId, destinationOptions.doorSide)
                        }
                        movement && stationCall != null -> stationCall.stopPrograms?.let { programs ->
                            programs to stationDoorSide(OperationalDestinationIds.stationBerth(
                                StationId(stationCall.stationId), stationCall.berthId), stationCall.doorSideOverride)
                        }
                        step.action is TimetableAction.BeginStationStop -> step.action.stopPrograms.let { programs ->
                            programs to stationDoorSide(programs.destinationId ?: currentWayPointId)
                        }
                        else -> null
                    }
                    val stationProgram = stationInvocation?.first
                    if (movement) { pendingStationCall = null; pendingDestinationOptions = null }
                    requiredStartDirections = when (step.action) {
                        TimetableAction.Reverse -> requiredStartDirections.map { direction ->
                            (direction ?: currentWayPointId?.let(::destinationDirection))?.opposite()
                        }.toSet()
                        is TimetableAction.GoTo,
                        is TimetableAction.GoVia -> arrivalDirections.ifEmpty { setOf(null) }
                        is TimetableAction.TakeFromDepot,
                        is TimetableAction.TakeFromFormationOutput,
                        is TimetableAction.Couple,
                        is TimetableAction.Split -> setOf(null)
                        is TimetableAction.TakeFromWaypoint -> setOf(initialSimulationDirection)
                        else -> requiredStartDirections
                    }
                    if (stationProgram != null && stationProgram.reverseParity() != 0) {
                        requiredStartDirections = requiredStartDirections.map { direction -> (direction ?: currentWayPointId?.let(::destinationDirection))?.opposite() }.toSet()
                    }
                    if (stationInvocation != null) {
                        val (program, fallbackSide) = stationInvocation
                        if ((program.doorSide ?: fallbackSide) == DoorSide.NONE) {
                            doors = requireSecured(doors, address, sink,
                                "当前停站使用 DoorSide.NONE，不会执行关门作业，不能清除已打开的车门状态")
                            doors = DoorProof.SECURED
                        } else doors = stationProgramResult(program)
                    } else if (movement && (input.pendingDestinationOptions != null || input.pendingStationCall != null)) {
                        doors = DoorProof.UNKNOWN
                    }
                    when (val action = step.action) {
                        is TimetableAction.TakeFromDepot -> {
                            dynamicOriginCandidates = depotValidationOrigins(action.selector)
                                .map { it.point.destinationId }
                                .filter(::wayPointExists)
                        }
                        is TimetableAction.TakeFromWaypoint -> {
                            dynamicOriginCandidates = action.wayPointIds.filter(::wayPointExists)
                        }
                        is TimetableAction.TakeFromFormationOutput,
                        is TimetableAction.Couple,
                        is TimetableAction.Split -> dynamicOriginCandidates = null
                        is TimetableAction.GoTo,
                        is TimetableAction.GoVia -> dynamicOriginCandidates = null
                        else -> Unit
                    }
                }
            }
            return State(currentWayPointId, requiredStartDirections, previousAtTick, pendingStationCall,
                pendingDestinationOptions, dynamicOriginCandidates, takeActionCount, takeFromDepotSelector,
                hasMovement, hasFormationOrigin, doors)
        }
        fun validateOutcome(outcome: State, sink: ProofSink) {
            outcome.pendingStationCall?.let { (index, _) ->
                sink.errors += "$prefix step#$index 车站操作缺少 destination 导航"
            }
            outcome.pendingDestinationOptions?.let { (index, _) ->
                sink.errors += "$prefix step#$index destination options 缺少导航"
            }
            if (service.trainSelector == TrainSelector.AnyTrain && outcome.takeActionCount == 0)
                sink.errors += "$prefix AnyTrain service 必须以 takeFromDepot 或 takeFromWaypoint 开始"
            if (!outcome.hasMovement && !standaloneSimulation && service.steps.none { it is TimetableStep.At &&
                    (it.action is TimetableAction.Couple || it.action is TimetableAction.Split ||
                        outcome.hasFormationOrigin && it.action is TimetableAction.StoreAtDepot) })
                sink.errors += "$prefix service 至少需要一个 destination"
        }
        data class ContinuationKey(val address: TimetableStepAddress?, val state: State, val depth: Int)
        data class ContinuationResult(val outcomes: List<State>, val proof: ProofSink)
        val continuations = hashMapOf<ContinuationKey, ContinuationResult>()
        fun walk(initialCursor: TimetableProgramCursor, initialState: State, destination: ProofSink, depth: Int): List<State> {
            if (budgetFailure != null) { chargeProofWork(destination, 0); return emptyList() }
            val key = ContinuationKey(initialCursor.address, initialState, depth)
            continuations[key]?.let { cached -> destination.merge(cached.proof); return cached.outcomes }
            val sink = ProofSink()
            fun compute(): List<State> {
            if (depth > 32) {
                sink.errors += "$prefix continuation proof depth exceeds 32"
                return emptyList()
            }
            var cursor = initialCursor
            var state = initialState
            while (!cursor.isComplete) {
                val address = checkNotNull(cursor.address)
                if (!chargeProofWork(sink)) return emptyList()
                val step = checkNotNull(cursor.currentStep())
                val action = (step as? TimetableStep.At)?.action
                if (action is TimetableAction.Conditional) {
                    val next = process(step, address, state, sink)
                    return (walk(cursor.enter(TimetableBranchArm.THEN), next, sink, depth + 1) +
                        walk(cursor.enter(TimetableBranchArm.ELSE), next, sink, depth + 1)).distinct()
                }
                if (action is TimetableAction.SelectStop) {
                    val tick = (step as TimetableStep.At).tick
                    if (state.pendingStationCall != null || state.pendingDestinationOptions != null) {
                        sink.errors += "$prefix $address selected stop cannot interrupt pending destination navigation"
                        return emptyList()
                    }
                    val candidateInput = state.copy(doors = requireSecured(state.doors, address, sink,
                        "当前选站行车动作要求车门已经关闭"))
                    val accepted = arrayListOf<State>()
                    val rejected = arrayListOf<String>()
                    for (candidate in action.selection.candidates.sortedWith(compareBy({ it.priority }, { it.berthId }))) {
                        if (!chargeProofWork(sink)) return emptyList()
                        val proof = ProofSink()
                        val target = OperationalDestinationIds.stationBerth(action.selection.stationId, candidate.berthId)
                        val call = TimetableAction.CallStation(action.selection.stationId.value, candidate.berthId,
                            action.minimumDwellTicks, action.stoppingOffsetMetersBefore, action.doorSideOverride,
                            action.stopPrograms)
                        val pending = process(TimetableStep.At(tick, call), address, candidateInput, proof)
                        val arrived = process(TimetableStep.At(tick, TimetableAction.GoTo(target)), address, pending, proof)
                        val outcomes = if (proof.errors.isEmpty()) walk(cursor.advance(), arrived, proof, depth + 1) else emptyList()
                        if (proof.errors.isEmpty()) {
                            (candidateInput.dynamicOriginCandidates ?: listOf(candidateInput.currentWayPointId)).forEach { origin ->
                                proof.selections += ValidatedStationSelectionKey(service.id, address, origin, candidate.berthId)
                            }
                            sink.merge(proof)
                            accepted += outcomes
                        } else rejected += "${candidate.berthId}: ${proof.errors.distinct().joinToString("; ")}"
                    }
                    if (accepted.isEmpty()) sink.errors += "$prefix $address selected stop has no eligible incoming and onward candidate: ${rejected.joinToString(" | ")}"
                    return accepted.distinct()
                }
                state = process(step, address, state, sink)
                cursor = cursor.advance()
            }
            validateOutcome(state, sink)
            return listOf(state)
            }
            val outcomes = compute()
            // These immutable continuation inputs permit reusing evidence, never re-evaluating a runtime condition.
            if (budgetFailure == null) continuations[key] = ContinuationResult(outcomes, sink)
            destination.merge(sink)
            return outcomes
        }
        walk(TimetableProgramCursor.start(TimetableServiceTraversal(service)),
            State(initialWayPoint(service.trainSelector, prefix, errors)), allProofs, 0)
    }

    private fun validateTakeAction(
        prefix: String,
        stepIndex: Int,
        actionName: String,
        takeActionCount: Int,
        service: TimetableService,
        pendingStationCall: Pair<Int, TimetableAction.CallStation>?,
        pendingDestinationOptions: Pair<Int, TimetableAction.DestinationOptions>?,
        errors: MutableList<String>
    ) {
        if (takeActionCount > 1) {
            errors.add("$prefix step#$stepIndex 只能声明一次取车动作")
        }
        if (stepIndex != 0) {
            errors.add("$prefix step#$stepIndex $actionName 必须是 service 的第一步")
        }
        if (service.trainSelector != TrainSelector.AnyTrain) {
            errors.add("$prefix step#$stepIndex $actionName 不得与 trainSelector 同时使用")
        }
        if (pendingStationCall != null || pendingDestinationOptions != null) {
            errors.add("$prefix step#$stepIndex destination options 后不得执行 $actionName")
        }
    }

    private fun initialWayPoint(
        selector: TrainSelector,
        prefix: String,
        errors: MutableList<String>
    ): String? {
        return when (selector) {
            TrainSelector.AnyTrain -> null
            is TrainSelector.TrainId -> {
                if (selector.trainId.isBlank()) errors.add("$prefix trainId 不能为空")
                null
            }
        }
    }

    private fun validateExtensionAction(
        prefix: String,
        address: TimetableStepAddress,
        action: TimetableAction,
        errors: MutableList<String>
    ) {
        when (action) {
            is TimetableAction.OpenDoors, TimetableAction.CloseDoors, is TimetableAction.BeginOperationalTrip, is TimetableAction.SetDisplay, is TimetableAction.ResetDisplay -> Unit
            is TimetableAction.Extension -> {
                val call = action.call
                val provider = world.simpleAtoWorldData.timetableExtensions.action(call.typeId, call.version)
                if (provider == null) {
                    errors.add("$prefix $address action provider is not registered: ${call.typeId}@${call.version}")
                } else if (action.trainInfo.isEmpty()) {
                    runCatching { provider.validate(call.parameters) }
                        .getOrElse { listOf("provider validation failed: ${it.message}") }
                        .forEach { errors.add("$prefix $address action ${call.typeId}: $it") }
                }
            }
            is TimetableAction.Conditional -> {
                world.simpleAtoWorldData.timetableExtensions.validateCondition(
                    action.condition,
                    TimetableConditionScope.RUNTIME_BRANCH
                )
                    .forEach { errors.add("$prefix $address condition: $it") }
                action.thenSteps.forEachIndexed { index, step -> validateExtensionAction(prefix, address.child(TimetableBranchArm.THEN, index), step.action, errors) }
                action.elseSteps.forEachIndexed { index, step -> validateExtensionAction(prefix, address.child(TimetableBranchArm.ELSE, index), step.action, errors) }
            }
            TimetableAction.WaitUntil -> Unit
            is TimetableAction.Stop -> if (action.ticks <= 0) errors.add("$prefix $address wait duration must be positive")
            else -> errors.add("$prefix $address conditional branches currently support stationary extension and wait actions")
        }
    }

    private fun validateAction(
        prefix: String,
        stepIndex: Int,
        currentWayPointId: String?,
        action: TimetableAction,
        errors: MutableList<String>,
        routes: MutableList<ExpandedRoute>,
        routeStoppingOffsets: MutableList<Double?>,
        depotSelector: DepotTrainSelector?,
        requiredStartDirection: ViewDirection?,
        movementStoppingOffset: Double
    ): String? {
        return when (action) {
            is TimetableAction.SelectStop -> error("SelectStop requires candidate continuation validation")
            // Physical output state must be resolved after the event; no old origin/route is reused.
            is TimetableAction.TakeFromFormationOutput,
            is TimetableAction.Couple,
            is TimetableAction.Split -> null
            is TimetableAction.TakeFromDepot -> {
                val points = world.simpleAtoWorldData.depots.stablingPoints(action.selector.depotId)
                val compatiblePoints = depotValidationOrigins(action.selector)
                if (points.isEmpty()) {
                    errors.add(
                        "$prefix step#$stepIndex Depot 不存在或没有 StablingPoint: " +
                            action.selector.depotId
                    )
                    currentWayPointId
                } else {
                    points.forEach { point ->
                        if (!wayPointExists(point.point.destinationId)) {
                            errors.add(
                                "$prefix step#$stepIndex StablingPoint Destination 不存在: " +
                                    point.point.destinationId
                            )
                        }
                    }
                    compatiblePoints.firstOrNull()?.point?.destinationId
                        ?.takeIf(::wayPointExists)
                }
            }
            is TimetableAction.TakeFromWaypoint -> {
                action.wayPointIds.forEach { wayPointId ->
                    if (!wayPointExists(wayPointId)) {
                        errors.add("$prefix step#$stepIndex takeFromWaypoint 路径点不存在: $wayPointId")
                    }
                }
                action.wayPointIds.firstOrNull(::wayPointExists) ?: currentWayPointId
            }
            is TimetableAction.StoreAtDepot -> {
                val allPoints = world.simpleAtoWorldData.depots.stablingPoints(action.depotId)
                val points = allPoints.filter {
                    action.stablingPointIds.isEmpty() ||
                        it.point.ref.pointId in action.stablingPointIds
                }
                val routePoints = depotSelector?.let {
                    val selector = it
                    val origins = depotValidationOrigins(selector).map { point -> point.point.destinationId }.toSet()
                    val candidates = world.allTrains.filter { train ->
                        val frame = train.publishedFrame ?: return@filter false
                        selector.matches(world.simpleAtoWorldData.candidateFacts(train), train.cars.size) &&
                            origins.any { id -> world.wayPoints[id]?.let { origin ->
                                frame.head.railId == origin.railId &&
                                    abs(frame.head.arcLengthInAbMeters - origin.arcLength) <= 0.5
                            } == true }
                    }
                    allPoints.filter { point -> candidates.any { train ->
                        val facts = world.simpleAtoWorldData.candidateFacts(train)
                        (point.point.usableLengthMeters == null || train.cars.sumOf { car -> car.length } <= point.point.usableLengthMeters) &&
                            (point.point.allowedTags.isEmpty() || facts?.tags?.any(point.point.allowedTags::contains) == true)
                    } }
                }?.filter {
                    it.point.ref.depotId == action.depotId &&
                        (action.stablingPointIds.isEmpty() ||
                            it.point.ref.pointId in action.stablingPointIds)
                }
                // Prove route coverage for current physical candidates. Runtime rechecks their
                // current classification and exact physical storage availability.
                val candidatePoints = routePoints?.takeIf { it.isNotEmpty() } ?: points
                if (allPoints.isEmpty()) {
                    errors.add("$prefix step#$stepIndex Depot 不存在或没有 StablingPoint: ${action.depotId}")
                    currentWayPointId
                } else if (points.isEmpty()) {
                    errors.add(
                        "$prefix step#$stepIndex 没有匹配的 StablingPoint: " +
                            action.stablingPointIds.sorted()
                    )
                    currentWayPointId
                } else {
                    val known = allPoints.map { it.point.ref.pointId }.toSet()
                    (action.stablingPointIds - known).forEach { missing ->
                        errors.add("$prefix step#$stepIndex StablingPoint 不存在: ${action.depotId}/$missing")
                    }
                    if (candidatePoints.isEmpty()) {
                        errors.add("$prefix step#$stepIndex 目标 Depot 没有兼容的 StablingPoint: ${action.depotId} (requested=${action.stablingPointIds.sorted()})")
                    }
                    candidatePoints.forEach { point ->
                        validateGoTo(
                            prefix,
                            stepIndex,
                            currentWayPointId,
                            point.point.destinationId,
                            errors,
                            routes,
                            routeStoppingOffsets,
                            requiredStartDirection = requiredStartDirection
                        )
                    }
                    candidatePoints.firstOrNull()?.point?.destinationId
                        ?.takeIf(::wayPointExists) ?: currentWayPointId
                }
            }
            is TimetableAction.TaskStart,
            is TimetableAction.TaskEnd,
            TimetableAction.WaitUntil,
            TimetableAction.Halt,
            TimetableAction.Reverse,
            is TimetableAction.Extension,
            is TimetableAction.BeginOperationalTrip,
            is TimetableAction.SetDisplay,
            is TimetableAction.OpenDoors, TimetableAction.CloseDoors,
            is TimetableAction.ResetDisplay,
            is TimetableAction.Conditional -> currentWayPointId
            is TimetableAction.Stop -> {
                if (action.ticks <= 0) errors.add("$prefix step#$stepIndex stop ticks 必须 > 0: ${action.ticks}")
                currentWayPointId
            }
            is TimetableAction.GoTo -> {
                validateGoTo(
                    prefix,
                    stepIndex,
                    currentWayPointId,
                    action.wayPointId,
                    errors,
                    routes,
                    routeStoppingOffsets,
                    requiredStartDirection,
                    movementStoppingOffset
                )
                action.wayPointId.takeIf { wayPointExists(it) }
            }
            is TimetableAction.GoVia -> {
                if (action.wayPointIds.isEmpty()) {
                    errors.add("$prefix step#$stepIndex destination 至少需要一个路径点")
                    currentWayPointId
                } else if (currentWayPointId == null) {
                    // A formation output has no nominal first origin. Its entire aggregate route
                    // is proved by the structural validator; do not fabricate a suffix route here.
                    action.wayPointIds.forEach { target ->
                        if (!wayPointExists(target)) errors += "$prefix step#$stepIndex destination 路径点不存在: $target"
                    }
                    action.wayPointIds.lastOrNull()?.takeIf(::wayPointExists)
                } else {
                    var cursor = currentWayPointId
                    var startDirection = requiredStartDirection
                    action.wayPointIds.forEachIndexed { targetIndex, target ->
                        validateGoTo(
                            prefix,
                            stepIndex,
                            cursor,
                            target,
                            errors,
                            routes,
                            routeStoppingOffsets,
                            startDirection,
                            if (targetIndex == action.wayPointIds.lastIndex) movementStoppingOffset else null
                        )
                        startDirection = routes.lastOrNull()?.takeIf { it.toWayPointId == target }
                            ?.segments?.last()?.railViewRef?.direction
                        if (wayPointExists(target)) cursor = target
                    }
                    cursor
                }
            }
            is TimetableAction.BeginStationStop -> {
                if (action.stopPrograms.role != StationStopRole.ORIGIN) errors += "$prefix origin stop requires ORIGIN role"
                currentWayPointId
            }
            is TimetableAction.DestinationOptions -> currentWayPointId
            is TimetableAction.CallStation -> {
                val stationId = runCatching { StationId(action.stationId) }.getOrNull()
                val berthRef = runCatching { action.berthRef }.getOrNull()
                val station = stationId?.let { world.simpleAtoWorldData.stations[it] }
                when {
                    stationId == null -> errors.add("$prefix step#$stepIndex station id 非法")
                    station == null -> errors.add(
                        "$prefix step#$stepIndex station 不存在: ${action.stationId}"
                    )
                    berthRef == null || berthRef !in station.berthRefs ||
                        world.simpleAtoWorldData.stations.berth(berthRef) == null -> errors.add(
                        "$prefix step#$stepIndex berth 不存在: ${action.stationId}/${action.berthId}"
                    )
                }
                currentWayPointId
            }
        }
    }

    private fun validateStationCallPrefix(
        prefix: String,
        stepIndex: Int,
        fromWayPointId: String?,
        callAction: TimetableAction.CallStation,
        movementRoutes: List<ExpandedRoute>,
        requiredStartDirection: ViewDirection?,
        errors: MutableList<String>
    ): Double? {
        val origin = fromWayPointId ?: return null
        val berthRef = runCatching { callAction.berthRef }.getOrNull() ?: return null
        val call = StationCall("validation-$stepIndex", 0L, berthRef.stationId, berthRef,
            plannedArrivalTick = null, dwellPlan = DwellPlan(callAction.minimumDwellTicks ?: 0L),
            stoppingOffsetMetersBeforeOverride = callAction.stoppingOffsetMetersBefore,
            doorSideOverride = callAction.doorSideOverride)
        val prefixRoute = stationCallRoutePrefix(world, origin, call, movementRoutes)
        if (prefixRoute == null) {
            errors.add("$prefix step#$stepIndex 车站 destination berth $berthRef 不在 destination 经路上")
            return null
        }
        val resolved = StationCallResolver(world).resolve(StationCallRequest("validation", WayPointRef(origin), call),
            requiredStartDirection = requiredStartDirection,
            retainedRoute = prefixRoute, retainedTopology = world.topologyState)
        if (resolved is StationCallResolutionResult.Failure) {
            errors.add("$prefix step#$stepIndex 车站 destination 无法解析: ${resolved.message}")
            return null
        }
        resolved as StationCallResolutionResult.Success
        val cumulative = movementRoutes.sumOf { it.totalLength }
        return resolved.resolved.stoppingOffsetMetersBefore.takeIf {
            abs(cumulative - prefixRoute.totalLength) <= 1.0e-6
        }
    }

    private fun validateGoTo(
        prefix: String,
        stepIndex: Int,
        fromWayPointId: String?,
        toWayPointId: String,
        errors: MutableList<String>,
        routes: MutableList<ExpandedRoute>,
        routeStoppingOffsets: MutableList<Double?>,
        requiredStartDirection: ViewDirection? = null,
        stoppingOffset: Double? = 0.0,
        pruneNonTargetWaypoints: Boolean = true
    ) {
        if (!wayPointExists(toWayPointId)) {
            errors.add("$prefix step#$stepIndex destination 路径点不存在: $toWayPointId")
            return
        }
        if (fromWayPointId == null) return
        when (val result = routePlanner.buildRoute(
            fromWayPointId,
            toWayPointId,
            requiredGoalDirection = destinationDirection(toWayPointId),
            requiredStartDirection = requiredStartDirection
                ?: destinationDirection(fromWayPointId),
            requireUniquePath = true,
            pruneNonTargetWaypoints = pruneNonTargetWaypoints
        )) {
            is RouteBuildResult.Success -> {
                routes.add(result.route)
                routeStoppingOffsets.add(stoppingOffset)
            }
            is RouteBuildResult.Failure -> {
                val startFacing = requiredStartDirection ?: destinationDirection(fromWayPointId)
                val goalFacing = destinationDirection(toWayPointId)
                errors.add("$prefix step#$stepIndex 经路不可达: $fromWayPointId -> $toWayPointId (${result.reason}; startFacing=${startFacing?.name ?: "ANY"}, goalFacing=${goalFacing?.name ?: "ANY"})")
            }
        }
    }

    /** Acquisition candidates use actual physical placement and the host's detached facts. */
    private fun depotValidationOrigins(selector: DepotTrainSelector): List<work.art1st.javarailways.simpleato.api.depot.StablingPointSnapshot> =
        world.simpleAtoWorldData.depots.stablingPoints(selector.depotId).filter { point ->
            val waypoint = world.wayPoints[point.point.destinationId] ?: return@filter false
            world.allTrains.any { train ->
                val frame = train.publishedFrame ?: return@any false
                frame.head.railId == waypoint.railId &&
                    abs(frame.head.arcLengthInAbMeters - waypoint.arcLength) <= 0.5 &&
                    selector.matches(world.simpleAtoWorldData.candidateFacts(train), train.cars.size)
            }
        }.sortedBy { it.point.ref.pointId }

    private fun wayPointExists(id: String): Boolean = world.wayPoints[id] != null

    private fun destinationDirection(id: String): ViewDirection? =
        world.simpleAtoWorldData.destinations[id]?.approachDirection
}

private fun ViewDirection.opposite(): ViewDirection = when (this) {
    ViewDirection.AB -> ViewDirection.BA
    ViewDirection.BA -> ViewDirection.AB
}
