package work.art1st.javarailways.basic.timetable.simulation

import work.art1st.javarailways.basic.timetable.Timetable
import work.art1st.javarailways.basic.timetable.TimetableAction
import work.art1st.javarailways.basic.timetable.TimetableService
import work.art1st.javarailways.basic.timetable.TimetableStep
import work.art1st.javarailways.basic.timetable.TrainSelector
import java.util.PriorityQueue

/** Stable key shown by WebUI when asking the user to map each selector to a simulation vehicle. */
@JvmInline
value class TimetableSimulationSelectorKey(val value: String)

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
    fun estimateTicks(
        serviceId: String,
        stepIndex: Int,
        profileId: TimetableSimulationProfileId,
        action: TimetableAction
    ): Long

    companion object {
        val ZERO = TimetableOperationTimeEstimator { _, _, _, _ -> 0L }
    }
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
    private val operationTimeEstimator: TimetableOperationTimeEstimator = TimetableOperationTimeEstimator.ZERO,
    private val resourceModel: TimetableSimulationResourceModel = TimetableSimulationResourceModel.NONE
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
            scheduleStep(state, step, calendars, events, issues)
            state.stepIndex++
            state.advanceChecks()
            if (state.hasNext()) queue += state
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
        calendars: MutableMap<String, MutableList<ReservedWindow>>,
        events: MutableList<TimetableSimulationEvent>,
        issues: MutableList<TimetableSimulationIssue>
    ) {
        val service = state.service
        val index = state.stepIndex
        val baseStart = maxOf(step.tick, state.availableTick)
        when (val action = step.action) {
            is TimetableAction.DestinationOptions,
            is TimetableAction.CallStation -> return
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
                    issues += TimetableSimulationIssue(service.id,
                        "negative travel estimate at step#$index: $duration")
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
                state.availableTick = Math.addExact(allocation.startTick, duration)
                state.currentLocation = targets.lastOrNull()
                events += event(state, TimetableSimulationEventType.ARRIVAL, step.tick,
                    state.availableTick, delay, state.currentLocation, cause, allocation.blockingResource)
            }
            is TimetableAction.TakeFromWaypoint -> {
                val allocation = allocateAction(state, step, baseStart, 0L, calendars)
                state.currentLocation = action.wayPointIds.firstOrNull()
                addActionEvent(events, state, step, allocation, TimetableSimulationEventType.ACQUIRE,
                    state.currentLocation)
                state.availableTick = allocation.startTick
            }
            is TimetableAction.TakeFromDepot -> {
                val allocation = allocateAction(state, step, baseStart, 0L, calendars)
                addActionEvent(events, state, step, allocation, TimetableSimulationEventType.ACQUIRE,
                    action.selector.depotId.value)
                state.availableTick = allocation.startTick
            }
            is TimetableAction.Stop -> {
                val allocation = allocateAction(state, step, baseStart, action.ticks, calendars)
                addActionEvent(events, state, step, allocation, TimetableSimulationEventType.ACTION,
                    state.currentLocation)
                state.availableTick = Math.addExact(allocation.startTick, action.ticks)
            }
            else -> {
                val duration = operationTimeEstimator.estimateTicks(
                    service.id, index, state.profileId, action
                )
                if (duration < 0L) {
                    issues += TimetableSimulationIssue(service.id,
                        "negative operation estimate at step#$index: $duration")
                    return
                }
                val allocation = allocateAction(state, step, baseStart, duration, calendars)
                addActionEvent(events, state, step, allocation, TimetableSimulationEventType.ACTION,
                    state.currentLocation)
                state.availableTick = Math.addExact(allocation.startTick, duration)
            }
        }
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
        var currentLocation: String? = null
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
                return TimetableSimulationSelectorKey("name:${selector.trainId}")
            }
            val acquisition = service.steps.asSequence().filterIsInstance<TimetableStep.At>()
                .map(TimetableStep.At::action).firstOrNull {
                    it is TimetableAction.TakeFromDepot || it is TimetableAction.TakeFromWaypoint
                }
            return when (acquisition) {
                is TimetableAction.TakeFromDepot -> TimetableSimulationSelectorKey(buildString {
                    append("depot:").append(acquisition.selector.depotId.value)
                    if (acquisition.selector.requiredTags.isNotEmpty()) {
                        append(";tags=").append(acquisition.selector.requiredTags.sorted().joinToString(","))
                    }
                })
                is TimetableAction.TakeFromWaypoint -> TimetableSimulationSelectorKey(
                    "waypoints:${acquisition.wayPointIds.joinToString(",")}"
                )
                else -> TimetableSimulationSelectorKey("any")
            }
        }
    }
}
