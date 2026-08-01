package work.art1st.javarailways.basic.timetable.simulation

import work.art1st.javarailways.basic.api.depot.DepotTrainSelector
import work.art1st.javarailways.basic.timetable.Timetable
import work.art1st.javarailways.basic.timetable.TimetableAction
import work.art1st.javarailways.basic.timetable.TimetableService
import work.art1st.javarailways.basic.timetable.TimetableStep
import work.art1st.javarailways.basic.timetable.TrainSelector
import java.util.PriorityQueue

/** Structural selector identity used when WebUI asks for one simulation profile per selector. */
sealed interface TimetableSimulationSelectorKey {
    val value: String

    data class NamedTrain(val trainId: String) : TimetableSimulationSelectorKey {
        override val value: String get() = "name:$trainId"
    }

    data class Depot(val selector: DepotTrainSelector) : TimetableSimulationSelectorKey {
        override val value: String get() = buildString {
            append("depot:").append(selector.depotId.value)
            if (selector.requiredTags.isNotEmpty()) {
                append(";tags=").append(selector.requiredTags.sorted().joinToString(","))
            }
            selector.minimumCars?.let { append(";minimumCars=").append(it) }
            selector.maximumCars?.let { append(";maximumCars=").append(it) }
            selector.minimumSpeedMetersPerSecond?.let { append(";minimumSpeed=").append(it) }
            if (selector.requiredCapabilities.isNotEmpty()) {
                append(";capabilities=").append(selector.requiredCapabilities.sorted().joinToString(","))
            }
        }
    }

    data class Waypoints(val wayPointIds: List<String>) : TimetableSimulationSelectorKey {
        init { require(wayPointIds.isNotEmpty()) }
        override val value: String get() = "waypoints:${wayPointIds.joinToString(",")}"
    }

    data object Any : TimetableSimulationSelectorKey { override val value: String = "any" }
}

@JvmInline
value class TimetableSimulationProfileId(val value: String)

data class TimetableSimulationRequest(
    val timetable: Timetable,
    val profileAssignments: Map<TimetableSimulationSelectorKey, TimetableSimulationProfileId>
)

enum class TimetableSimulationEventType { ACQUIRE, DEPARTURE, ARRIVAL, ACTION }
enum class TimetableSimulationDelayCause { PREVIOUS_ACTION, INSUFFICIENT_RUNNING_TIME, RESOURCE_CONFLICT }

data class TimetableSimulationEvent(
    val serviceId: String,
    val stepIndex: Int,
    val type: TimetableSimulationEventType,
    val plannedTick: Long,
    val actualTick: Long,
    val delayTicks: Long,
    val location: String? = null,
    val cause: TimetableSimulationDelayCause? = null,
    val blockingResource: String? = null
)

data class TimetableSimulationIssue(
    val serviceId: String?,
    val message: String
)

data class TimetableSimulationReport(
    val events: List<TimetableSimulationEvent>,
    val issues: List<TimetableSimulationIssue>,
    val selectorKeys: Set<TimetableSimulationSelectorKey>
) {
    val isSuccess: Boolean get() = issues.isEmpty()
    val maximumDelayTicks: Long get() = events.maxOfOrNull(TimetableSimulationEvent::delayTicks) ?: 0L
}

data class TimetableMovementEstimateRequest(
    val serviceId: String,
    val stepIndex: Int,
    val profileId: TimetableSimulationProfileId,
    val origin: String?,
    val targets: List<String>
)

/**
 * Supplies detailed physical timing without making Basic depend on Bukkit entities or control config classes.
 * Implementations must be deterministic for the same immutable infrastructure/profile snapshot.
 */
fun interface TimetableTravelTimeEstimator {
    fun estimateTicks(request: TimetableMovementEstimateRequest): Long
}

/** Duration of non-movement service operations such as reverse or custom actions. */
fun interface TimetableOperationTimeEstimator {
    /** Returns null when this action has no registered simulation implementation. */
    fun estimateTicks(
        serviceId: String,
        stepIndex: Int,
        profileId: TimetableSimulationProfileId,
        action: TimetableAction
    ): Long?

    companion object {
        val UNSUPPORTED = TimetableOperationTimeEstimator { _, _, _, _ -> null }
        val ZERO = TimetableOperationTimeEstimator { _, _, _, _ -> 0L }
    }
}

/** Resolves the actual vehicle location selected by a dynamic acquisition action. */
fun interface TimetableSimulationOriginResolver {
    fun resolveOrigin(
        serviceId: String,
        stepIndex: Int,
        profileId: TimetableSimulationProfileId,
        action: TimetableAction
    ): String?

    companion object { val NONE = TimetableSimulationOriginResolver { _, _, _, _ -> null } }
}

/** A resource interval relative to an action's actual start tick. End is exclusive. */
data class TimetableSimulationResourceUsage(
    val resourceId: String,
    val startOffsetTicks: Long = 0L,
    val endOffsetTicks: Long
) {
    init {
        require(resourceId.isNotBlank())
        require(startOffsetTicks >= 0L)
        require(endOffsetTicks > startOffsetTicks)
    }
}

/**
 * Projects compiled actions onto infrastructure resources. A later adapter can use route sections,
 * station berths, reversing sidings, and depot roads from an immutable world snapshot.
 */
interface TimetableSimulationResourceModel {
    fun movementResources(
        request: TimetableMovementEstimateRequest,
        durationTicks: Long
    ): List<TimetableSimulationResourceUsage> = emptyList()

    fun actionResources(
        serviceId: String,
        stepIndex: Int,
        profileId: TimetableSimulationProfileId,
        action: TimetableAction,
        durationTicks: Long
    ): List<TimetableSimulationResourceUsage> = emptyList()

    companion object { val NONE = object : TimetableSimulationResourceModel {} }
}

/**
 * Deterministic service-layer simulation over an already compiled Timetable.
 *
 * Services advance through one shared virtual event queue. Resource windows are reserved in event
 * order, so an earlier competing movement wins independently of service declaration order. The
 * engine never changes the 20 Hz logical tick duration.
 */
class TimetableSimulationEngine(
    private val travelTimeEstimator: TimetableTravelTimeEstimator,
    private val operationTimeEstimator: TimetableOperationTimeEstimator = TimetableOperationTimeEstimator.UNSUPPORTED,
    private val resourceModel: TimetableSimulationResourceModel = TimetableSimulationResourceModel.NONE,
    private val originResolver: TimetableSimulationOriginResolver = TimetableSimulationOriginResolver.NONE
) {
    fun simulate(request: TimetableSimulationRequest): TimetableSimulationReport {
        val selectorKeys = request.timetable.services.map(::selectorKey).toSet()
        val issues = selectorKeys.filterNot(request.profileAssignments::containsKey).map { key ->
            TimetableSimulationIssue(null, "missing simulation vehicle for selector ${key.value}")
        }.toMutableList()
        if (issues.isNotEmpty()) return TimetableSimulationReport(emptyList(), issues, selectorKeys)

        val states = request.timetable.services.map { service ->
            ServiceState(service, request.profileAssignments.getValue(selectorKey(service)))
                .also(ServiceState::advanceChecks)
        }
        val queue = PriorityQueue(compareBy<ServiceState>(ServiceState::candidateTick)
            .thenBy { it.service.id }.thenBy(ServiceState::stepIndex))
        queue += states.filter(ServiceState::hasNext)
        val calendars = mutableMapOf<String, MutableList<ReservedWindow>>()
        val events = mutableListOf<TimetableSimulationEvent>()

        while (queue.isNotEmpty()) {
            val state = queue.remove()
            val step = state.currentStep() ?: continue
            scheduleStep(state, step, request.timetable.periodTicks, calendars, events, issues)
            state.stepIndex++
            state.advanceChecks()
            if (!state.failed && state.hasNext()) queue += state
        }

        return TimetableSimulationReport(
            events.sortedWith(compareBy(TimetableSimulationEvent::actualTick,
                TimetableSimulationEvent::serviceId, TimetableSimulationEvent::stepIndex,
                TimetableSimulationEvent::type)),
            issues,
            selectorKeys
        )
    }

    private fun scheduleStep(
        state: ServiceState,
        step: TimetableStep.At,
        periodTicks: Long,
        calendars: MutableMap<String, MutableList<ReservedWindow>>,
        events: MutableList<TimetableSimulationEvent>,
        issues: MutableList<TimetableSimulationIssue>
    ) {
        val service = state.service
        val index = state.stepIndex
        val baseStart = maxOf(step.tick, state.availableTick)
        when (val action = step.action) {
            is TimetableAction.DestinationOptions -> {
                state.pendingMinimumDwellTicks = action.minimumDwellTicks ?: 0L
                return
            }
            is TimetableAction.CallStation -> {
                state.pendingMinimumDwellTicks = action.minimumDwellTicks ?: 0L
                return
            }
            is TimetableAction.GoTo,
            is TimetableAction.GoVia -> {
                val targets = when (action) {
                    is TimetableAction.GoTo -> listOf(action.wayPointId)
                    is TimetableAction.GoVia -> action.wayPointIds
                }
                val estimateRequest = TimetableMovementEstimateRequest(
                    service.id, index, state.profileId, state.currentLocation, targets
                )
                val duration = travelTimeEstimator.estimateTicks(estimateRequest)
                if (duration < 0L) {
                    fail(state, issues, "negative travel estimate at step#$index: $duration")
                    return
                }
                val allocation = allocate(baseStart, resourceModel.movementResources(estimateRequest, duration), calendars)
                val delay = allocation.startTick - step.tick
                val cause = when {
                    allocation.blockingResource != null -> TimetableSimulationDelayCause.RESOURCE_CONFLICT
                    allocation.startTick > step.tick && state.availableTick > step.tick ->
                        TimetableSimulationDelayCause.INSUFFICIENT_RUNNING_TIME
                    else -> null
                }
                events += event(state, TimetableSimulationEventType.DEPARTURE, step.tick,
                    allocation.startTick, delay, state.currentLocation, cause, allocation.blockingResource)
                val arrivalTick = Math.addExact(allocation.startTick, duration)
                state.currentLocation = targets.lastOrNull()
                events += event(state, TimetableSimulationEventType.ARRIVAL, step.tick,
                    arrivalTick, delay, state.currentLocation, cause, allocation.blockingResource)
                state.availableTick = Math.addExact(arrivalTick, state.pendingMinimumDwellTicks)
                state.pendingMinimumDwellTicks = 0L
                checkPeriod(state, periodTicks, state.availableTick, issues)
            }
            is TimetableAction.TakeFromWaypoint,
            is TimetableAction.TakeFromDepot -> {
                val resolvedOrigin = originResolver.resolveOrigin(service.id, index, state.profileId, action)
                if (resolvedOrigin.isNullOrBlank()) {
                    fail(state, issues, "could not resolve acquisition origin at step#$index")
                    return
                }
                val allocation = allocateAction(state, step, baseStart, 0L, calendars)
                state.currentLocation = resolvedOrigin
                addActionEvent(events, state, step, allocation, TimetableSimulationEventType.ACQUIRE,
                    resolvedOrigin)
                state.availableTick = allocation.startTick
                checkPeriod(state, periodTicks, allocation.startTick, issues)
            }
            is TimetableAction.Stop -> {
                val allocation = allocateAction(state, step, baseStart, action.ticks, calendars)
                addActionEvent(events, state, step, allocation, TimetableSimulationEventType.ACTION,
                    state.currentLocation)
                state.availableTick = Math.addExact(allocation.startTick, action.ticks)
                checkPeriod(state, periodTicks, state.availableTick, issues)
            }
            else -> {
                val duration = operationTimeEstimator.estimateTicks(
                    service.id, index, state.profileId, action
                )
                if (duration == null) {
                    fail(state, issues, "unsupported simulation action at step#$index: ${action::class.simpleName}")
                    return
                }
                if (duration < 0L) {
                    fail(state, issues, "negative operation estimate at step#$index: $duration")
                    return
                }
                val allocation = allocateAction(state, step, baseStart, duration, calendars)
                addActionEvent(events, state, step, allocation, TimetableSimulationEventType.ACTION,
                    state.currentLocation)
                state.availableTick = Math.addExact(allocation.startTick, duration)
                checkPeriod(state, periodTicks, state.availableTick, issues)
            }
        }
    }

    private fun checkPeriod(
        state: ServiceState,
        periodTicks: Long,
        actualTick: Long,
        issues: MutableList<TimetableSimulationIssue>
    ) {
        if (actualTick >= periodTicks) {
            fail(state, issues, "service crosses timetable period at tick $actualTick (period $periodTicks)")
        }
    }

    private fun fail(
        state: ServiceState,
        issues: MutableList<TimetableSimulationIssue>,
        message: String
    ) {
        state.failed = true
        issues += TimetableSimulationIssue(state.service.id, message)
    }

    private fun allocateAction(
        state: ServiceState,
        step: TimetableStep.At,
        baseStart: Long,
        duration: Long,
        calendars: MutableMap<String, MutableList<ReservedWindow>>
    ) = allocate(baseStart, resourceModel.actionResources(
        state.service.id, state.stepIndex, state.profileId, step.action, duration
    ), calendars)

    private fun addActionEvent(
        events: MutableList<TimetableSimulationEvent>,
        state: ServiceState,
        step: TimetableStep.At,
        allocation: Allocation,
        type: TimetableSimulationEventType,
        location: String?
    ) {
        val delay = allocation.startTick - step.tick
        events += event(state, type, step.tick, allocation.startTick, delay, location,
            delayCause(step.tick, state.availableTick, allocation), allocation.blockingResource)
    }

    private fun delayCause(planned: Long, available: Long, allocation: Allocation) = when {
        allocation.blockingResource != null -> TimetableSimulationDelayCause.RESOURCE_CONFLICT
        allocation.startTick > planned && available > planned -> TimetableSimulationDelayCause.PREVIOUS_ACTION
        else -> null
    }

    private fun allocate(
        baseStart: Long,
        usages: List<TimetableSimulationResourceUsage>,
        calendars: MutableMap<String, MutableList<ReservedWindow>>
    ): Allocation {
        var start = baseStart
        var blockingResource: String? = null
        while (true) {
            var nextStart = start
            for (usage in usages) {
                val requestedStart = Math.addExact(start, usage.startOffsetTicks)
                val requestedEnd = Math.addExact(start, usage.endOffsetTicks)
                val conflict = calendars[usage.resourceId].orEmpty()
                    .filter { it.startTick < requestedEnd && requestedStart < it.endTick }
                    .minByOrNull(ReservedWindow::endTick)
                if (conflict != null) {
                    val candidate = Math.subtractExact(conflict.endTick, usage.startOffsetTicks)
                    if (candidate > nextStart) {
                        nextStart = candidate
                        blockingResource = usage.resourceId
                    }
                }
            }
            if (nextStart == start) break
            start = nextStart
        }
        usages.forEach { usage ->
            calendars.getOrPut(usage.resourceId, ::mutableListOf) += ReservedWindow(
                Math.addExact(start, usage.startOffsetTicks),
                Math.addExact(start, usage.endOffsetTicks)
            )
        }
        return Allocation(start, blockingResource)
    }

    private fun event(
        state: ServiceState,
        type: TimetableSimulationEventType,
        plannedTick: Long,
        actualTick: Long,
        delayTicks: Long,
        location: String?,
        cause: TimetableSimulationDelayCause?,
        blockingResource: String?
    ) = TimetableSimulationEvent(
        state.service.id, state.stepIndex, type, plannedTick, actualTick, delayTicks,
        location, cause, blockingResource
    )

    private data class Allocation(val startTick: Long, val blockingResource: String?)
    private data class ReservedWindow(val startTick: Long, val endTick: Long)
    private data class ServiceState(
        val service: TimetableService,
        val profileId: TimetableSimulationProfileId,
        var stepIndex: Int = 0,
        var availableTick: Long = 0,
        var currentLocation: String? = null,
        var pendingMinimumDwellTicks: Long = 0,
        var failed: Boolean = false
    ) {
        fun advanceChecks() {
            while (stepIndex < service.steps.size && service.steps[stepIndex] is TimetableStep.Check) {
                currentLocation = (service.steps[stepIndex] as TimetableStep.Check).wayPointId
                stepIndex++
            }
        }
        fun currentStep(): TimetableStep.At? = service.steps.getOrNull(stepIndex) as? TimetableStep.At
        fun hasNext(): Boolean = currentStep() != null
        fun candidateTick(): Long = currentStep()?.let { maxOf(it.tick, availableTick) } ?: Long.MAX_VALUE
    }

    companion object {
        fun selectorKey(service: TimetableService): TimetableSimulationSelectorKey {
            val selector = service.trainSelector
            if (selector is TrainSelector.TrainId) {
                return TimetableSimulationSelectorKey.NamedTrain(selector.trainId)
            }
            val acquisition = service.steps.asSequence().filterIsInstance<TimetableStep.At>()
                .map(TimetableStep.At::action).firstOrNull {
                    it is TimetableAction.TakeFromDepot || it is TimetableAction.TakeFromWaypoint
                }
            return when (acquisition) {
                is TimetableAction.TakeFromDepot -> TimetableSimulationSelectorKey.Depot(acquisition.selector)
                is TimetableAction.TakeFromWaypoint -> TimetableSimulationSelectorKey.Waypoints(
                    acquisition.wayPointIds.toList()
                )
                else -> TimetableSimulationSelectorKey.Any
            }
        }
    }
}
