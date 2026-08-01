package work.art1st.javarailways.basic.timetable.simulation

import work.art1st.javarailways.basic.timetable.Timetable
import work.art1st.javarailways.basic.timetable.TimetableAction
import work.art1st.javarailways.basic.timetable.TimetableService
import work.art1st.javarailways.basic.timetable.TimetableStep
import work.art1st.javarailways.basic.timetable.TrainSelector

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
enum class TimetableSimulationDelayCause { PREVIOUS_ACTION, INSUFFICIENT_RUNNING_TIME }

data class TimetableSimulationEvent(
    val serviceId: String,
    val stepIndex: Int,
    val type: TimetableSimulationEventType,
    val plannedTick: Long,
    val actualTick: Long,
    val delayTicks: Long,
    val location: String? = null,
    val cause: TimetableSimulationDelayCause? = null
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

/**
 * Deterministic service-layer simulation over an already compiled Timetable.
 *
 * This first-stage engine models vehicle continuity and delay propagation. Infrastructure resource
 * contention is added by adapters in later stages; the engine deliberately uses a virtual event
 * timeline and never changes the 20 Hz logical tick duration.
 */
class TimetableSimulationEngine(
    private val travelTimeEstimator: TimetableTravelTimeEstimator,
    private val operationTimeEstimator: TimetableOperationTimeEstimator = TimetableOperationTimeEstimator.ZERO
) {
    fun simulate(request: TimetableSimulationRequest): TimetableSimulationReport {
        val selectorKeys = request.timetable.services.map(::selectorKey).toSet()
        val issues = selectorKeys.filterNot(request.profileAssignments::containsKey).map { key ->
            TimetableSimulationIssue(null, "missing simulation vehicle for selector ${key.value}")
        }.toMutableList()
        if (issues.isNotEmpty()) return TimetableSimulationReport(emptyList(), issues, selectorKeys)

        val events = request.timetable.services.flatMap { service ->
            simulateService(service, request.profileAssignments.getValue(selectorKey(service)), issues)
        }.sortedWith(compareBy(TimetableSimulationEvent::actualTick, TimetableSimulationEvent::serviceId,
            TimetableSimulationEvent::stepIndex, TimetableSimulationEvent::type))
        return TimetableSimulationReport(events, issues, selectorKeys)
    }

    private fun simulateService(
        service: TimetableService,
        profileId: TimetableSimulationProfileId,
        issues: MutableList<TimetableSimulationIssue>
    ): List<TimetableSimulationEvent> {
        val events = mutableListOf<TimetableSimulationEvent>()
        var availableTick = 0L
        var currentLocation: String? = null
        service.steps.forEachIndexed { index, step ->
            when (step) {
                is TimetableStep.Check -> currentLocation = step.wayPointId
                is TimetableStep.At -> {
                    val actual = maxOf(step.tick, availableTick)
                    val delay = actual - step.tick
                    val cause = when {
                        delay == 0L -> null
                        step.action is TimetableAction.GoTo || step.action is TimetableAction.GoVia ->
                            TimetableSimulationDelayCause.INSUFFICIENT_RUNNING_TIME
                        else -> TimetableSimulationDelayCause.PREVIOUS_ACTION
                    }
                    when (val action = step.action) {
                        is TimetableAction.TakeFromWaypoint -> {
                            currentLocation = action.wayPointIds.firstOrNull()
                            events += event(service, index, TimetableSimulationEventType.ACQUIRE, step.tick, actual,
                                delay, currentLocation, cause)
                            availableTick = actual
                        }
                        is TimetableAction.TakeFromDepot -> {
                            events += event(service, index, TimetableSimulationEventType.ACQUIRE, step.tick, actual,
                                delay, action.selector.depotId.value, cause)
                            availableTick = actual
                        }
                        is TimetableAction.GoTo,
                        is TimetableAction.GoVia -> {
                            val targets = when (action) {
                                is TimetableAction.GoTo -> listOf(action.wayPointId)
                                is TimetableAction.GoVia -> action.wayPointIds
                            }
                            events += event(service, index, TimetableSimulationEventType.DEPARTURE, step.tick,
                                actual, delay, currentLocation, cause)
                            val duration = travelTimeEstimator.estimateTicks(
                                TimetableMovementEstimateRequest(service.id, index, profileId, currentLocation, targets)
                            )
                            if (duration < 0L) {
                                issues += TimetableSimulationIssue(service.id,
                                    "negative travel estimate at step#$index: $duration")
                            } else {
                                availableTick = Math.addExact(actual, duration)
                                currentLocation = targets.lastOrNull()
                                events += event(service, index, TimetableSimulationEventType.ARRIVAL, step.tick,
                                    availableTick, delay, currentLocation, cause)
                            }
                        }
                        is TimetableAction.Stop -> {
                            events += event(service, index, TimetableSimulationEventType.ACTION, step.tick, actual,
                                delay, currentLocation, cause)
                            availableTick = Math.addExact(actual, action.ticks)
                        }
                        is TimetableAction.DestinationOptions,
                        is TimetableAction.CallStation -> Unit
                        else -> {
                            events += event(service, index, TimetableSimulationEventType.ACTION, step.tick, actual,
                                delay, currentLocation, cause)
                            val duration = operationTimeEstimator.estimateTicks(
                                service.id, index, profileId, action
                            )
                            if (duration < 0L) issues += TimetableSimulationIssue(service.id,
                                "negative operation estimate at step#$index: $duration")
                            else availableTick = Math.addExact(actual, duration)
                        }
                    }
                }
            }
        }
        return events
    }

    private fun event(
        service: TimetableService,
        stepIndex: Int,
        type: TimetableSimulationEventType,
        plannedTick: Long,
        actualTick: Long,
        delayTicks: Long,
        location: String?,
        cause: TimetableSimulationDelayCause?
    ) = TimetableSimulationEvent(
        service.id, stepIndex, type, plannedTick, actualTick, delayTicks, location, cause
    )

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
