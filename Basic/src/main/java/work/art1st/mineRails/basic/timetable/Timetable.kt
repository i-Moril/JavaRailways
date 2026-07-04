package work.art1st.mineRails.basic.timetable

import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.route.ExpandedRoute
import work.art1st.mineRails.basic.route.RouteBuildResult
import work.art1st.mineRails.basic.route.RoutePlanner
import work.art1st.mineRails.core.api.physics.World

/** 自动时刻表的内部 Kotlin 数据模型。JS loader 后续只负责生成这些数据。 */
data class Timetable(
    val id: String,
    val periodTicks: Long? = null,
    val services: List<TimetableService> = emptyList()
)

data class TimetableService(
    val id: String,
    val trainSelector: TrainSelector = TrainSelector.AnyTrain,
    val steps: List<TimetableStep> = emptyList()
)

sealed class TrainSelector {
    data object AnyTrain : TrainSelector()
    data class TrainId(val trainId: String) : TrainSelector()
    /** 从若干路径点附近选择一列车；第一版仅作为验证/调度提示。 */
    data class WayPointPool(val wayPointIds: List<String>) : TrainSelector()
}

sealed class TimetableStep {
    data class At(val tick: Long, val action: TimetableAction) : TimetableStep()
    data class Check(val wayPointId: String) : TimetableStep()
}

sealed class TimetableAction {
    data class GoTo(val wayPointId: String) : TimetableAction()
    data class GoVia(val wayPointIds: List<String>) : TimetableAction()
    data class Stop(val ticks: Long) : TimetableAction()
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
        timetable.periodTicks?.let { if (it <= 0) errors.add("时刻表周期必须 > 0: $it") }

        val serviceIds = mutableSetOf<String>()
        for (service in timetable.services) {
            validateService(timetable.id, service, errors, routes)
            if (!serviceIds.add(service.id)) errors.add("重复 service id: ${service.id}")
        }
        return TimetableValidationResult(errors, routes)
    }

    private fun validateService(
        timetableId: String,
        service: TimetableService,
        errors: MutableList<String>,
        routes: MutableList<ExpandedRoute>
    ) {
        val prefix = "[$timetableId/${service.id}]"
        if (service.id.isBlank()) errors.add("$prefix service id 不能为空")

        var currentWayPointId: String? = initialWayPoint(service.trainSelector, prefix, errors)
        var previousAtTick: Long? = null

        service.steps.forEachIndexed { index, step ->
            when (step) {
                is TimetableStep.Check -> {
                    if (!wayPointExists(step.wayPointId)) {
                        errors.add("$prefix step#$index check 路径点不存在: ${step.wayPointId}")
                    } else {
                        currentWayPointId = step.wayPointId
                    }
                }
                is TimetableStep.At -> {
                    if (step.tick < 0) errors.add("$prefix step#$index tick 不能为负: ${step.tick}")
                    previousAtTick?.let {
                        if (step.tick < it) errors.add("$prefix step#$index tick 早于前一个 at: ${step.tick} < $it")
                    }
                    previousAtTick = step.tick
                    currentWayPointId = validateAction(prefix, index, currentWayPointId, step.action, errors, routes)
                }
            }
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
            is TrainSelector.WayPointPool -> {
                if (selector.wayPointIds.isEmpty()) errors.add("$prefix 路径点车库列表不能为空")
                selector.wayPointIds.forEach { id ->
                    if (!wayPointExists(id)) errors.add("$prefix 车库路径点不存在: $id")
                }
                selector.wayPointIds.firstOrNull()
            }
        }
    }

    private fun validateAction(
        prefix: String,
        stepIndex: Int,
        currentWayPointId: String?,
        action: TimetableAction,
        errors: MutableList<String>,
        routes: MutableList<ExpandedRoute>
    ): String? {
        return when (action) {
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
