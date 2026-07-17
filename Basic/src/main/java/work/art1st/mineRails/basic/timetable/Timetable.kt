package work.art1st.mineRails.basic.timetable

import work.art1st.mineRails.basic.api.depot.DepotId
import work.art1st.mineRails.basic.api.depot.DepotTrainSelector
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.ato.mission.WayPointRef
import work.art1st.mineRails.basic.api.station.DwellPlan
import work.art1st.mineRails.basic.api.station.StationBerthRef
import work.art1st.mineRails.basic.api.station.StationCall
import work.art1st.mineRails.basic.api.station.StationCallRequest
import work.art1st.mineRails.basic.api.station.StationCallResolutionResult
import work.art1st.mineRails.basic.api.station.StationCallResolver
import work.art1st.mineRails.basic.api.station.StationId
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.route.ExpandedRoute
import work.art1st.mineRails.basic.route.RouteBuildResult
import work.art1st.mineRails.basic.route.RoutePlanner
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import kotlin.math.abs

/** 自动时刻表的内部 Kotlin 数据模型。JS loader 后续只负责生成这些数据。 */
data class Timetable(
    val id: String,
    val periodDays: Long = 1L,
    val services: List<TimetableService> = emptyList()
) {
    init {
        require(periodDays > 0L) { "Timetable periodDays must be positive" }
    }

    val periodTicks: Long
        get() = Math.multiplyExact(periodDays, TICKS_PER_GAME_DAY)

    companion object {
        const val TICKS_PER_GAME_DAY = 24_000L
    }
}

data class TimetableService(
    val id: String,
    val trainSelector: TrainSelector = TrainSelector.AnyTrain,
    val steps: List<TimetableStep> = emptyList()
)

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
    data class CallStation(
        val stationId: String,
        val berthId: String,
        val minimumDwellTicks: Long? = null,
        val plannedDepartureTick: Long? = null,
        val stoppingOffsetMetersBefore: Double? = null,
        val doorSideOverride: DoorSide? = null
    ) : TimetableAction() {
        init {
            require(stationId.isNotBlank() && berthId.isNotBlank())
            require(minimumDwellTicks == null || minimumDwellTicks >= 0L)
            require(plannedDepartureTick == null || plannedDepartureTick >= 0L)
            require(stoppingOffsetMetersBefore == null ||
                stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
        }

        val berthRef: StationBerthRef
            get() = StationBerthRef(StationId(stationId), berthId)
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

        val serviceIds = mutableSetOf<String>()
        for (service in timetable.services) {
            validateService(timetable.id, periodTicks, service, errors, routes)
            if (!serviceIds.add(service.id)) errors.add("重复 service id: ${service.id}")
        }
        return TimetableValidationResult(errors, routes)
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

        var currentWayPointId: String? = initialWayPoint(service.trainSelector, prefix, errors)
        var previousAtTick: Long? = null
        var pendingStationCall: Pair<Int, TimetableAction.CallStation>? = null
        var dynamicOriginCandidates: List<String>? = null
        var takeFromDepotCount = 0
        var takeFromDepotId: DepotId? = null
        var takeFromDepotSelector: DepotTrainSelector? = null
        var hasMovement = false

        service.steps.forEachIndexed { index, step ->
            when (step) {
                is TimetableStep.Check -> {
                    if (takeFromDepotCount > 0) {
                        errors.add("$prefix step#$index check 不得覆盖动态配车的 StablingPoint 起点")
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
                            takeFromDepotId = step.action.selector.depotId
                            takeFromDepotSelector = step.action.selector
                            if (takeFromDepotCount > 1) {
                                errors.add("$prefix step#$index 只能声明一次 takeFromDepot")
                            }
                            if (index != 0) {
                                errors.add("$prefix step#$index takeFromDepot 必须是 service 的第一步")
                            }
                            if (step.tick != 0L) {
                                errors.add("$prefix step#$index takeFromDepot 当前必须声明为 at(0)")
                            }
                            if (service.trainSelector != TrainSelector.AnyTrain) {
                                errors.add("$prefix step#$index takeFromDepot 不得与 trainSelector 同时使用")
                            }
                            if (pendingStationCall != null) {
                                errors.add("$prefix step#$index station call 后不得 takeFromDepot")
                            }
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
                            if (pendingStationCall != null) {
                                errors.add("$prefix step#$index station call 后必须先 goto，不能直接存车")
                            }
                        }
                        is TimetableAction.CallStation -> {
                            if (step.action.plannedDepartureTick != null) {
                                if (step.action.plannedDepartureTick < step.tick) {
                                    errors.add(
                                        "$prefix step#$index planned departure 早于 planned arrival"
                                    )
                                }
                                if (step.action.plannedDepartureTick >= periodTicks) {
                                    errors.add(
                                        "$prefix step#$index plannedDepartureTick 超出周期"
                                    )
                                }
                            }
                            if (pendingStationCall != null) {
                                errors.add(
                                    "$prefix step#$index station call 前还有未配对 call: " +
                                        "step#${pendingStationCall.first}"
                                )
                            }
                            pendingStationCall = index to step.action
                        }
                        is TimetableAction.GoTo,
                        is TimetableAction.GoVia -> {
                            hasMovement = true
                            if (currentWayPointId == null && takeFromDepotCount == 0) {
                                errors.add(
                                    "$prefix step#$index movement 缺少初始 check 或 takeFromDepot"
                                )
                            }
                            pendingStationCall?.let { (callIndex, call) ->
                                if (call.plannedDepartureTick != null &&
                                    call.plannedDepartureTick != step.tick
                                ) {
                                    errors.add(
                                        "$prefix step#$callIndex plannedDepartureTick 必须等于 " +
                                            "配对 goto tick ${step.tick}"
                                    )
                                }
                                (dynamicOriginCandidates ?: listOfNotNull(currentWayPointId))
                                    .forEach { origin ->
                                        validateStationCallPrefix(
                                            prefix,
                                            callIndex,
                                            origin,
                                            call,
                                            step.action,
                                            errors
                                        )
                                    }
                            }
                            val firstTarget = when (val movement = step.action) {
                                is TimetableAction.GoTo -> movement.wayPointId
                                is TimetableAction.GoVia -> movement.wayPointIds.firstOrNull()
                            }
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
                        }
                        is TimetableAction.Stop -> {
                            if (!hasMovement) {
                                errors.add("$prefix step#$index 首次 movement 前不得 stop")
                            }
                            if (pendingStationCall != null) {
                                errors.add("$prefix step#$index station call 后必须直接跟随 goto")
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
                        takeFromDepotSelector
                    )
                    when (val action = step.action) {
                        is TimetableAction.TakeFromDepot -> {
                            dynamicOriginCandidates = world.basicWorldData.depots
                                .compatibleStablingPoints(action.selector)
                                .map { it.point.destinationId }
                                .filter(::wayPointExists)
                        }
                        is TimetableAction.GoTo,
                        is TimetableAction.GoVia -> dynamicOriginCandidates = null
                        else -> Unit
                    }
                }
            }
        }
        pendingStationCall?.let { (index, _) ->
            errors.add("$prefix step#$index station call 缺少后续 goto")
        }
        if (service.trainSelector == TrainSelector.AnyTrain && takeFromDepotCount == 0) {
            errors.add("$prefix AnyTrain service 必须以 takeFromDepot 开始")
        }
        if (!hasMovement) {
            errors.add("$prefix service 至少需要一个 GoTo 或 GoVia movement")
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

    private fun validateAction(
        prefix: String,
        stepIndex: Int,
        currentWayPointId: String?,
        action: TimetableAction,
        errors: MutableList<String>,
        routes: MutableList<ExpandedRoute>,
        depotSelector: DepotTrainSelector?
    ): String? {
        return when (action) {
            is TimetableAction.TakeFromDepot -> {
                val points = world.basicWorldData.depots.stablingPoints(action.selector.depotId)
                val compatiblePoints = world.basicWorldData.depots
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
            is TimetableAction.StoreAtDepot -> {
                val allPoints = world.basicWorldData.depots.stablingPoints(action.depotId)
                val points = allPoints.filter {
                    action.stablingPointIds.isEmpty() ||
                        it.point.ref.pointId in action.stablingPointIds
                }
                val routePoints = depotSelector?.let {
                    world.basicWorldData.depots.compatibleStablingPoints(it)
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
            is TimetableAction.Stop -> {
                if (action.ticks <= 0) errors.add("$prefix step#$stepIndex stop ticks 必须 > 0: ${action.ticks}")
                currentWayPointId
            }
            is TimetableAction.GoTo -> {
                validateGoTo(prefix, stepIndex, currentWayPointId, action.wayPointId, errors, routes)
                action.wayPointId.takeIf { wayPointExists(it) }
            }
            is TimetableAction.GoVia -> {
                if (action.wayPointIds.isEmpty()) {
                    errors.add("$prefix step#$stepIndex goVia 至少需要一个路径点")
                    currentWayPointId
                } else {
                    var cursor = currentWayPointId
                    action.wayPointIds.forEach { target ->
                        validateGoTo(prefix, stepIndex, cursor, target, errors, routes)
                        if (wayPointExists(target)) cursor = target
                    }
                    cursor
                }
            }
            is TimetableAction.CallStation -> {
                val stationId = runCatching { StationId(action.stationId) }.getOrNull()
                val berthRef = runCatching { action.berthRef }.getOrNull()
                val station = stationId?.let { world.basicWorldData.stations[it] }
                when {
                    stationId == null -> errors.add("$prefix step#$stepIndex station id 非法")
                    station == null -> errors.add(
                        "$prefix step#$stepIndex station 不存在: ${action.stationId}"
                    )
                    berthRef == null || berthRef !in station.berthRefs ||
                        world.basicWorldData.stations.berth(berthRef) == null -> errors.add(
                        "$prefix step#$stepIndex berth 不存在: ${action.stationId}/${action.berthId}"
                    )
                }
                if (action.plannedDepartureTick != null &&
                    action.plannedDepartureTick < 0L
                ) errors.add("$prefix step#$stepIndex plannedDepartureTick 不能为负")
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
            )
        )) {
            is StationCallResolutionResult.Success -> result
            is StationCallResolutionResult.Failure -> {
                errors.add("$prefix step#$stepIndex station call 无法解析: ${result.message}")
                return
            }
        }
        val berth = world.basicWorldData.stations.berth(berthRef) ?: return
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
        var cumulative = 0.0
        val candidates = ArrayList<Double>()
        for (target in targets) {
            val route = when (val result = routePlanner.buildRoute(cursor, target)) {
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
        }
        val stationDistance = resolved.resolved.plannedRoute.expandedRoute.totalLength
        if (candidates.none { abs(it - stationDistance) <= 0.5 }) {
            errors.add(
                "$prefix step#$stepIndex berth $berthRef 不在后续 goto 的经路前缀上 " +
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
        routes: MutableList<ExpandedRoute>
    ) {
        if (!wayPointExists(toWayPointId)) {
            errors.add("$prefix step#$stepIndex goto 路径点不存在: $toWayPointId")
            return
        }
        if (fromWayPointId == null) return
        when (val result = routePlanner.buildRoute(fromWayPointId, toWayPointId)) {
            is RouteBuildResult.Success -> routes.add(result.route)
            is RouteBuildResult.Failure -> errors.add("$prefix step#$stepIndex 经路不可达: $fromWayPointId -> $toWayPointId (${result.reason})")
        }
    }

    private fun wayPointExists(id: String): Boolean = world.basicWorldData.wayPoints[id] != null
}
