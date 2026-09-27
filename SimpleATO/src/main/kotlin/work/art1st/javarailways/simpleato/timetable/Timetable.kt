package work.art1st.javarailways.simpleato.timetable

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
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.route.ExpandedRoute
import work.art1st.javarailways.basic.route.RouteBuildResult
import work.art1st.javarailways.basic.route.RoutePlanner
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 自动时刻表的内部 Kotlin 数据模型。JS loader 后续只负责生成这些数据。 */
data class Timetable(
    val id: String,
    val periodDays: Long = 1L,
    val services: List<TimetableService> = emptyList(),
    val initialPositions: List<TimetableInitialPosition> = emptyList(),
    val lineSpeedLimits: List<TimetableLineSpeedLimit> = emptyList()
) {
    init {
        require(periodDays > 0L) { "Timetable periodDays must be positive" }
        require(lineSpeedLimits.distinctBy(TimetableLineSpeedLimit::lineId).size == lineSpeedLimits.size) {
            "Timetable contains duplicate line speed limits"
        }
    }

    val periodTicks: Long
        get() = Math.multiplyExact(periodDays, TICKS_PER_GAME_DAY)

    companion object {
        const val TICKS_PER_GAME_DAY = 24_000L
    }
}

class TimetableStore {
    private val timetables = ConcurrentHashMap<String, Timetable>()

    fun add(timetable: Timetable) {
        require(timetable.id.isNotBlank()) { "timetable id must not be blank" }
        timetables[timetable.id] = timetable
    }

    fun remove(id: String): Timetable? = timetables.remove(id)

    operator fun get(id: String): Timetable? = timetables[id]

    fun all(): List<Timetable> = timetables.values.sortedBy { it.id }
}

data class TimetableLineSpeedLimit(
    val lineId: LineId,
    val maximumSpeedMetersPerSecond: Double
) {
    init {
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0.0)
    }
}

data class TimetableService(
    val id: String,
    val trainSelector: TrainSelector = TrainSelector.AnyTrain,
    val steps: List<TimetableStep> = emptyList(),
    val activation: TimetableServiceActivation = TimetableServiceActivation.Scheduled,
    val editorRows: List<TimetableServiceEditorRow>? = null,
    /** Actual Basic infrastructure line; never substitute presentation-only diagramLineId. */
    val lineId: LineId? = null
)

class LineMaximumSpeedResolver(private val timetable: Timetable) {
    fun resolve(service: TimetableService): Double? {
        val lineId = service.lineId ?: return null
        return timetable.lineSpeedLimits.singleOrNull { it.lineId == lineId }
            ?.maximumSpeedMetersPerSecond
    }

    companion object {
        fun resolve(timetable: Timetable, service: TimetableService): Double? =
            LineMaximumSpeedResolver(timetable).resolve(service)
    }
}

enum class TimetableServiceRowType { STATION, SPECIAL_DESTINATION, DEPOT_OPERATION, ACTION_ONLY }
enum class TimetableServiceStopStatus { ORIGIN, STOP, PASS, TERMINAL, DISABLED }
enum class TimetableServiceDepotOperation { TAKE_FROM_DEPOT, STORE_AT_DEPOT }

/** Authoring metadata persisted with the runtime timetable; generated steps remain execution-authoritative. */
data class TimetableServiceEditorRow(
    val id: String,
    val type: TimetableServiceRowType,
    val status: TimetableServiceStopStatus? = null,
    val stationId: String? = null,
    val berthId: String? = null,
    val destinationId: String? = null,
    val tick: Long? = null,
    val departureTick: Long? = null,
    val depotOperation: TimetableServiceDepotOperation? = null,
    val depotId: String? = null,
    val actions: List<TimetableAction> = emptyList()
) {
    init {
        require(id.isNotBlank())
        require(tick == null || tick >= 0L)
        require(departureTick == null || departureTick >= 0L)
    }
}

sealed class TrainSelector {
    data object AnyTrain : TrainSelector()
    data class TrainId(val trainId: String) : TrainSelector()
}

sealed class TimetableStep {
    data class At(val tick: Long, val action: TimetableAction) : TimetableStep()
    data class Check(val wayPointId: String) : TimetableStep()
}

sealed class TimetableAction {
    /** Select and reserve one compatible AVAILABLE train from its home Depot. */
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
    data class Extension(val call: TimetableExtensionCall) : TimetableAction()

    /**
     * Evaluate once when this action is reached and execute the selected extension-action branch.
     *
     * Core movement and safety actions are deliberately excluded from branches in the first
     * version. This keeps route validation deterministic while still supporting node-specific
     * plugin operations. Nested conditional and extension actions are accepted.
     */
    data class Conditional(
        val condition: TimetableCondition,
        val thenActions: List<TimetableAction>,
        val elseActions: List<TimetableAction> = emptyList()
    ) : TimetableAction() {
        init {
            require(thenActions.all(TimetableAction::isRuntimeExtensionAction))
            require(elseActions.all(TimetableAction::isRuntimeExtensionAction))
        }
    }

    data object Halt : TimetableAction()
    data object Reverse : TimetableAction()
    data class DestinationOptions(
        val destinationId: String,
        val minimumDwellTicks: Long? = null,
        val stoppingOffsetMetersBefore: Double? = null,
        val doorSide: DoorSide? = null
    ) : TimetableAction() {
        init {
            require(destinationId.isNotBlank() && destinationId == destinationId.trim())
            require(minimumDwellTicks == null || minimumDwellTicks >= 0L)
            require(stoppingOffsetMetersBefore == null ||
                stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
        }
    }
    data class CallStation(
        val stationId: String,
        val berthId: String,
        val minimumDwellTicks: Long? = null,
        val stoppingOffsetMetersBefore: Double? = null,
        val doorSideOverride: DoorSide? = null
    ) : TimetableAction() {
        init {
            require(stationId.isNotBlank() && berthId.isNotBlank())
            require(minimumDwellTicks == null || minimumDwellTicks >= 0L)
            require(stoppingOffsetMetersBefore == null ||
                stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
        }

        val berthRef: StationBerthRef
            get() = StationBerthRef(StationId(stationId), berthId)
    }

    private fun isRuntimeExtensionAction(): Boolean = when (this) {
        is Extension -> true
        is Conditional -> thenActions.all(TimetableAction::isRuntimeExtensionAction) &&
            elseActions.all(TimetableAction::isRuntimeExtensionAction)
        else -> false
    }
}

data class TimetableValidationResult(
    val errors: List<String>,
    val routes: List<ExpandedRoute>
) {
    val isSuccess: Boolean get() = errors.isEmpty()
}

class TimetableValidator(private val world: World) {
    private val routePlanner = RoutePlanner(world)

    fun validate(timetable: Timetable): TimetableValidationResult {
        val errors = mutableListOf<String>()
        val routes = mutableListOf<ExpandedRoute>()

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
        val serviceIds = mutableSetOf<String>()
        for (service in timetable.services) {
            validateServiceLine(timetable, service, errors)
            validateService(timetable.id, periodTicks, service, errors, routes)
            if (!serviceIds.add(service.id)) errors.add("重复 service id: ${service.id}")
        }
        return TimetableValidationResult(errors, routes)
    }

    private fun validateServiceLine(
        timetable: Timetable,
        service: TimetableService,
        errors: MutableList<String>
    ) {
        val lineId = service.lineId ?: return
        val prefix = "[${timetable.id}/${service.id}]"
        if (world.simpleAtoWorldData.lines[lineId] == null) {
            errors.add("$prefix 引用不存在的 LineId: ${lineId.value}")
        }
        if (LineMaximumSpeedResolver.resolve(timetable, service) == null) {
            errors.add("$prefix 未配置线路最高速度: ${lineId.value}")
        }
    }

    private fun validateService(
        timetableId: String,
        periodTicks: Long,
        service: TimetableService,
        errors: MutableList<String>,
        routes: MutableList<ExpandedRoute>
    ) {
        val prefix = "[$timetableId/${service.id}]"
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

        var currentWayPointId: String? = initialWayPoint(service.trainSelector, prefix, errors)
        var requiredStartDirection: ViewDirection? = null
        var previousAtTick: Long? = null
        var pendingStationCall: Pair<Int, TimetableAction.CallStation>? = null
        var pendingDestinationOptions: Pair<Int, TimetableAction.DestinationOptions>? = null
        var dynamicOriginCandidates: List<String>? = null
        var takeFromDepotCount = 0
        var takeActionCount = 0
        var takeFromDepotId: DepotId? = null
        var takeFromDepotSelector: DepotTrainSelector? = null
        var hasMovement = false

        service.steps.forEachIndexed { index, step ->
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
                    if (step.tick < 0) errors.add("$prefix step#$index tick 不能为负: ${step.tick}")
                    if (step.tick >= periodTicks) {
                        errors.add("$prefix step#$index tick 超出周期: ${step.tick} >= $periodTicks")
                    }
                    previousAtTick?.let {
                        if (step.tick < it) errors.add("$prefix step#$index tick 早于前一个 at: ${step.tick} < $it")
                    }
                    previousAtTick = step.tick
                    when (step.action) {
                        is TimetableAction.TakeFromDepot -> {
                            takeFromDepotCount++
                            takeActionCount++
                            takeFromDepotId = step.action.selector.depotId
                            takeFromDepotSelector = step.action.selector
                            validateTakeAction(
                                prefix, index, "takeFromDepot", takeActionCount,
                                service, pendingStationCall, pendingDestinationOptions, errors
                            )
                        }
                        is TimetableAction.TakeFromWaypoint -> {
                            takeActionCount++
                            validateTakeAction(
                                prefix, index, "takeFromWaypoint", takeActionCount,
                                service, pendingStationCall, pendingDestinationOptions, errors
                            )
                        }
                        is TimetableAction.StoreAtDepot -> {
                            if (takeFromDepotCount != 1 || takeFromDepotId != step.action.depotId) {
                                errors.add(
                                    "$prefix step#$index storeAtDepot 必须返回 takeFromDepot 的 home Depot"
                                )
                            }
                            if (index != service.steps.lastIndex) {
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
                            hasMovement = true
                            if (currentWayPointId == null && takeActionCount == 0) {
                                errors.add(
                                    "$prefix step#$index destination 缺少初始 check、takeFromDepot 或 takeFromWaypoint"
                                )
                            }
                            pendingStationCall?.let { (callIndex, call) ->
                                (dynamicOriginCandidates ?: listOfNotNull(currentWayPointId))
                                    .forEach { origin ->
                                        validateStationCallPrefix(
                                            prefix,
                                            callIndex,
                                            origin,
                                            call,
                                            step.action,
                                            requiredStartDirection,
                                            errors
                                        )
                                    }
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
                            val firstTarget = movementTargets.firstOrNull()
                            if (firstTarget != null) {
                                dynamicOriginCandidates.orEmpty().drop(1).forEach { origin ->
                                    validateGoTo(
                                        prefix,
                                        index,
                                        origin,
                                        firstTarget,
                                        errors,
                                        routes
                                    )
                                }
                            }
                            pendingStationCall = null
                            pendingDestinationOptions = null
                        }
                        TimetableAction.Halt -> {
                            if (!hasMovement) errors.add("$prefix step#$index halt 前必须至少有一个 destination")
                            if (index != service.steps.lastIndex) {
                                errors.add("$prefix step#$index halt 必须是 service 的最后一步")
                            }
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 后必须先完成导航")
                            }
                        }
                        TimetableAction.Reverse -> {
                            if (!hasMovement && takeActionCount == 0 && currentWayPointId == null) {
                                errors.add(
                                    "$prefix step#$index reverse 前必须先通过 check/take 确定列车位置"
                                )
                            }
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 后必须先完成导航")
                            }
                        }
                        is TimetableAction.Stop -> {
                            if (!hasMovement) {
                                errors.add("$prefix step#$index 首次 destination 前不得 stop")
                            }
                            if (pendingStationCall != null || pendingDestinationOptions != null) {
                                errors.add("$prefix step#$index destination options 后必须直接跟随导航")
                            }
                        }
                        is TimetableAction.Extension -> {
                            if (!hasMovement) {
                                errors.add("$prefix step#$index 首次 destination 前不得执行扩展动作")
                            }
                            validateExtensionAction(prefix, index, step.action, errors)
                        }
                        is TimetableAction.Conditional -> {
                            if (!hasMovement) {
                                errors.add("$prefix step#$index 首次 destination 前不得执行条件分支")
                            }
                            world.simpleAtoWorldData.timetableExtensions
                                .validateCondition(
                                    step.action.condition,
                                    TimetableConditionScope.RUNTIME_BRANCH
                                )
                                .forEach { errors.add("$prefix step#$index condition: $it") }
                            step.action.thenActions.forEach {
                                validateExtensionAction(prefix, index, it, errors)
                            }
                            step.action.elseActions.forEach {
                                validateExtensionAction(prefix, index, it, errors)
                            }
                        }
                    }
                    currentWayPointId = validateAction(
                        prefix,
                        index,
                        currentWayPointId,
                        step.action,
                        errors,
                        routes,
                        takeFromDepotSelector,
                        requiredStartDirection
                    )
                    requiredStartDirection = when (step.action) {
                        TimetableAction.Reverse -> {
                            val direction = requiredStartDirection
                                ?: currentWayPointId?.let {
                                    world.simpleAtoWorldData.destinations[it]?.approachDirection
                                }
                            direction?.opposite()
                        }
                        is TimetableAction.GoTo,
                        is TimetableAction.GoVia,
                        is TimetableAction.TakeFromDepot,
                        is TimetableAction.TakeFromWaypoint -> null
                        else -> requiredStartDirection
                    }
                    when (val action = step.action) {
                        is TimetableAction.TakeFromDepot -> {
                            dynamicOriginCandidates = world.simpleAtoWorldData.depots
                                .compatibleStablingPoints(action.selector)
                                .map { it.point.destinationId }
                                .filter(::wayPointExists)
                        }
                        is TimetableAction.TakeFromWaypoint -> {
                            dynamicOriginCandidates = action.wayPointIds.filter(::wayPointExists)
                        }
                        is TimetableAction.GoTo,
                        is TimetableAction.GoVia -> dynamicOriginCandidates = null
                        else -> Unit
                    }
                }
            }
        }
        pendingStationCall?.let { (index, _) ->
            errors.add("$prefix step#$index 车站操作缺少 destination 导航")
        }
        pendingDestinationOptions?.let { (index, _) ->
            errors.add("$prefix step#$index destination options 缺少导航")
        }
        if (service.trainSelector == TrainSelector.AnyTrain && takeActionCount == 0) {
            errors.add("$prefix AnyTrain service 必须以 takeFromDepot 或 takeFromWaypoint 开始")
        }
        if (!hasMovement) {
            errors.add("$prefix service 至少需要一个 destination")
        }
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
        stepIndex: Int,
        action: TimetableAction,
        errors: MutableList<String>
    ) {
        when (action) {
            is TimetableAction.Extension -> {
                val call = action.call
                val provider = world.simpleAtoWorldData.timetableExtensions.action(call.typeId, call.version)
                if (provider == null) {
                    errors.add("$prefix step#$stepIndex action provider is not registered: ${call.typeId}@${call.version}")
                } else {
                    runCatching { provider.validate(call.parameters) }
                        .getOrElse { listOf("provider validation failed: ${it.message}") }
                        .forEach { errors.add("$prefix step#$stepIndex action ${call.typeId}: $it") }
                }
            }
            is TimetableAction.Conditional -> {
                world.simpleAtoWorldData.timetableExtensions.validateCondition(
                    action.condition,
                    TimetableConditionScope.RUNTIME_BRANCH
                )
                    .forEach { errors.add("$prefix step#$stepIndex condition: $it") }
                action.thenActions.forEach { validateExtensionAction(prefix, stepIndex, it, errors) }
                action.elseActions.forEach { validateExtensionAction(prefix, stepIndex, it, errors) }
            }
            else -> errors.add("$prefix step#$stepIndex conditional branches only support extension actions")
        }
    }

    private fun validateAction(
        prefix: String,
        stepIndex: Int,
        currentWayPointId: String?,
        action: TimetableAction,
        errors: MutableList<String>,
        routes: MutableList<ExpandedRoute>,
        depotSelector: DepotTrainSelector?,
        requiredStartDirection: ViewDirection?
    ): String? {
        return when (action) {
            is TimetableAction.TakeFromDepot -> {
                val points = world.simpleAtoWorldData.depots.stablingPoints(action.selector.depotId)
                val compatiblePoints = world.simpleAtoWorldData.depots
                    .compatibleStablingPoints(action.selector)
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
                    world.simpleAtoWorldData.depots.compatibleStablingPoints(it)
                }.orEmpty().filter {
                    it.point.ref.depotId == action.depotId &&
                        (action.stablingPointIds.isEmpty() ||
                            it.point.ref.pointId in action.stablingPointIds)
                }
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
                    routePoints.forEach { point ->
                        validateGoTo(
                            prefix,
                            stepIndex,
                            currentWayPointId,
                            point.point.destinationId,
                            errors,
                            routes
                        )
                    }
                    routePoints.firstOrNull()?.point?.destinationId
                        ?.takeIf(::wayPointExists) ?: currentWayPointId
                }
            }
            TimetableAction.Halt,
            TimetableAction.Reverse,
            is TimetableAction.Extension,
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
                    requiredStartDirection
                )
                action.wayPointId.takeIf { wayPointExists(it) }
            }
            is TimetableAction.GoVia -> {
                if (action.wayPointIds.isEmpty()) {
                    errors.add("$prefix step#$stepIndex destination 至少需要一个路径点")
                    currentWayPointId
                } else {
                    var cursor = currentWayPointId
                    var startDirection = requiredStartDirection
                    action.wayPointIds.forEach { target ->
                        validateGoTo(
                            prefix,
                            stepIndex,
                            cursor,
                            target,
                            errors,
                            routes,
                            startDirection
                        )
                        startDirection = null
                        if (wayPointExists(target)) cursor = target
                    }
                    cursor
                }
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
        movementAction: TimetableAction,
        requiredStartDirection: ViewDirection?,
        errors: MutableList<String>
    ) {
        val origin = fromWayPointId ?: return
        val berthRef = runCatching { callAction.berthRef }.getOrNull() ?: return
        val resolved = when (val result = StationCallResolver(world).resolve(
            StationCallRequest(
                serviceRunId = "validation",
                origin = WayPointRef(origin),
                call = StationCall(
                    callId = "validation-$stepIndex",
                    missionRevision = 0L,
                    stationId = berthRef.stationId,
                    berthRef = berthRef,
                    plannedArrivalTick = null,
                    dwellPlan = DwellPlan(callAction.minimumDwellTicks ?: 0L),
                    stoppingOffsetMetersBeforeOverride = callAction.stoppingOffsetMetersBefore,
                    doorSideOverride = callAction.doorSideOverride
                )
            ),
            requiredStartDirection = requiredStartDirection
        )) {
            is StationCallResolutionResult.Success -> result
            is StationCallResolutionResult.Failure -> {
                errors.add("$prefix step#$stepIndex 车站 destination 无法解析: ${result.message}")
                return
            }
        }
        val berth = world.simpleAtoWorldData.stations.berth(berthRef) ?: return
        val arrivalDirection = resolved.resolved.plannedRoute.expandedRoute
            .segments.last().railViewRef.direction
        val berthArcInView = when (arrivalDirection) {
            ViewDirection.AB -> berth.location.arcLengthIn(ViewDirection.AB)
            ViewDirection.BA -> berth.location.arcLengthIn(ViewDirection.BA)
        }
        val targets = when (movementAction) {
            is TimetableAction.GoTo -> listOf(movementAction.wayPointId)
            is TimetableAction.GoVia -> movementAction.wayPointIds
            else -> return
        }
        var cursor = origin
        var startDirection = requiredStartDirection
        var cumulative = 0.0
        val candidates = ArrayList<Double>()
        for (target in targets) {
            val route = when (val result = routePlanner.buildRoute(
                cursor,
                target,
                requiredGoalDirection = destinationDirection(target),
                requiredStartDirection = startDirection ?: destinationDirection(cursor)
            )) {
                is RouteBuildResult.Success -> result.route
                is RouteBuildResult.Failure -> return
            }
            var segmentStart = cumulative
            route.segments.forEach { segment ->
                if (segment.railViewRef.railId == berth.location.rail.id &&
                    segment.railViewRef.direction == arrivalDirection &&
                    berthArcInView >= segment.startArcLength - 1.0e-6 &&
                    berthArcInView <= segment.endArcLength + 1.0e-6
                ) {
                    candidates += segmentStart +
                        (berthArcInView.coerceIn(segment.startArcLength, segment.endArcLength) -
                            segment.startArcLength)
                }
                segmentStart += segment.length
            }
            cumulative += route.totalLength
            cursor = target
            startDirection = route.segments.last().railViewRef.direction
        }
        val stationDistance = resolved.resolved.plannedRoute.expandedRoute.totalLength
        if (candidates.none { abs(it - stationDistance) <= 0.5 }) {
            errors.add(
                "$prefix step#$stepIndex berth $berthRef 不在 destination 经路上 " +
                    "(stationDistance=$stationDistance, candidates=$candidates)"
            )
        }
    }

    private fun validateGoTo(
        prefix: String,
        stepIndex: Int,
        fromWayPointId: String?,
        toWayPointId: String,
        errors: MutableList<String>,
        routes: MutableList<ExpandedRoute>,
        requiredStartDirection: ViewDirection? = null
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
                ?: destinationDirection(fromWayPointId)
        )) {
            is RouteBuildResult.Success -> routes.add(result.route)
            is RouteBuildResult.Failure -> errors.add("$prefix step#$stepIndex 经路不可达: $fromWayPointId -> $toWayPointId (${result.reason})")
        }
    }

    private fun wayPointExists(id: String): Boolean = world.basicWorldData.wayPoints[id] != null

    private fun destinationDirection(id: String): ViewDirection? =
        world.simpleAtoWorldData.destinations[id]?.approachDirection
}

private fun ViewDirection.opposite(): ViewDirection = when (this) {
    ViewDirection.AB -> ViewDirection.BA
    ViewDirection.BA -> ViewDirection.AB
}
