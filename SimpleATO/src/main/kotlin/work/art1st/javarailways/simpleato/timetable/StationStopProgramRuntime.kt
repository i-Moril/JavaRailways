package work.art1st.javarailways.simpleato.timetable

/** A real host operation, not a queued-success approximation. */
internal interface StationProgramTicket {
    fun poll(): StationProgramTicketResult
    /** Null only when no operation remains pending. */
    fun cancel(): String?
}

internal sealed interface StationProgramTicketResult {
    data object Pending : StationProgramTicketResult
    data object Succeeded : StationProgramTicketResult
    data class Failed(val reason: String) : StationProgramTicketResult
    data class Cancelled(val reason: String) : StationProgramTicketResult
}

internal fun interface StationProgramActionHost {
    /** A null ticket means this synchronous action has completed successfully. */
    fun start(action: StationProgramAction, taskId: String, instruction: Int): StationProgramTicket?
}

/**
 * Bounded, world-owner task state for exactly one actual stop. It owns neither movement nor a
 * second Dispatcher. Each spawned branch owns its own program counter, wait and operation ticket.
 */
internal class StationStopProgramRuntime(
    val definition: StationStopPrograms,
    private val stoppedAtDispatcherTick: Long,
    private val minimumDwellTicks: Long,
    private val doorless: Boolean,
    private val operationTimeoutTicks: Long,
    private val host: StationProgramActionHost
) {
    private class Task(val id: String, val steps: List<StationProgramAction>, val main: Boolean) {
        var index = 0
        var completed = false
        val children = mutableListOf<Task>()
        var waitUntilDispatcherTick = 0L
        var ticket: StationProgramTicket? = null
        var startedAtDispatcherTick = 0L
        var activeAction: StationProgramAction? = null
    }

    private val tasks = linkedMapOf<String, Task>()
    private val symbols = linkedMapOf<String, Task>()
    private var physicalOperationOwner: String? = null
    private var preparationStarted = false
    private var dwellDeadline = Math.addExact(stoppedAtDispatcherTick, minimumDwellTicks)
    private var lastTick = stoppedAtDispatcherTick
    var doorsSecured: Boolean = doorless
        private set
    var failure: Throwable? = null
        private set
    val arrivalMainComplete: Boolean
        get() = definition.role == StationStopRole.ORIGIN || tasks[ARRIVAL]?.completed == true
    val allTasksComplete: Boolean
        get() = failure == null && arrivalMainComplete &&
            (definition.role == StationStopRole.TERMINAL || preparationStarted) && tasks.values.all { it.completed }

    init {
        require(minimumDwellTicks >= 0 && operationTimeoutTicks > 0)
        if (definition.role != StationStopRole.ORIGIN) tasks[ARRIVAL] = Task(ARRIVAL, definition.arrival, true)
    }

    fun finalGateSatisfied(elapsedTimetableTick: Long, dispatcherTick: Long): Boolean =
        allTasksComplete && dispatcherTick >= dwellDeadline &&
            (definition.plannedDepartureTick?.let { elapsedTimetableTick >= it } ?: true) &&
            (definition.role == StationStopRole.TERMINAL || doorsSecured)

    fun tick(elapsedTimetableTick: Long, dispatcherTick: Long) {
        if (failure != null) return
        require(dispatcherTick >= lastTick)
        lastTick = dispatcherTick
        var executingTask: Task? = null
        try {
            fun startPreparationIfEligible(): Task? {
                if (!preparationStarted && definition.role != StationStopRole.TERMINAL && arrivalMainComplete &&
                    elapsedTimetableTick >= preparationDueTick()) {
                    preparationStarted = true
                    return Task(PREPARATION, definition.departurePreparation, true).also { tasks[PREPARATION] = it }
                }
                return null
            }
            startPreparationIfEligible()
            var budget = MAX_TRANSITIONS_PER_TICK
            val pending = java.util.ArrayDeque(tasks.values.toList())
            while (pending.isNotEmpty()) {
                val task = pending.removeFirst()
                executingTask = task
                while (!task.completed && budget-- > 0) {
                    if (dispatcherTick < task.waitUntilDispatcherTick) break
                    if (task.ticket != null) {
                        if (!observeTicket(task, dispatcherTick)) break
                        continue
                    }
                    val action = task.steps.getOrNull(task.index)
                    if (action == null) {
                        check(!task.main) { "station entry ${task.id} must terminate with END" }
                        task.completed = true
                        break
                    }
                    when (action) {
                        StationProgramAction.End -> {
                            check(task.main && task.index == task.steps.lastIndex) { "END must terminate a main station entry" }
                            task.completed = true
                        }
                        is StationProgramAction.Async -> {
                            check(tasks.size < MAX_TASKS) { "station program exceeds $MAX_TASKS tasks" }
                            check(action.taskId !in symbols && action.taskId !in setOf(ARRIVAL, PREPARATION)) { "duplicate station task ${action.taskId}" }
                            val child = Task(action.taskId, action.steps, false)
                            symbols[action.taskId] = child
                            task.children += child
                            tasks[action.taskId] = child
                            pending.addLast(child)
                            task.index++
                        }
                        is StationProgramAction.Await -> {
                            val joined = checkNotNull(symbols[action.taskId]) { "unknown or not yet started station task ${action.taskId}" }
                            check(joined !== task) { "station task cannot await itself" }
                            if (!taskTreeComplete(joined)) break
                            task.index++
                        }
                        is StationProgramAction.WaitUntil -> {
                            if (elapsedTimetableTick < action.tick) break
                            task.index++
                        }
                        is StationProgramAction.WaitDuration -> {
                            require(action.ticks > 0)
                            task.waitUntilDispatcherTick = Math.addExact(dispatcherTick, action.ticks)
                            task.index++
                            break
                        }
                        else -> {
                            val physical = action == StationProgramAction.OpenDoors || action == StationProgramAction.CloseDoors || action == StationProgramAction.Reverse
                            if (physical) {
                                check(physicalOperationOwner == null || physicalOperationOwner == task.id) {
                                    "conflicting station physical operations in ${physicalOperationOwner} and ${task.id}"
                                }
                                physicalOperationOwner = task.id
                            }
                            task.activeAction = action
                            task.startedAtDispatcherTick = dispatcherTick
                            task.ticket = if (doorless && (action == StationProgramAction.OpenDoors || action == StationProgramAction.CloseDoors)) null
                                else host.start(action, task.id, task.index)
                            if (task.ticket == null) {
                                completedAction(task, dispatcherTick)
                                task.activeAction = null
                                task.index++
                            } else if (!observeTicket(task, dispatcherTick)) break
                        }
                    }
                }
                startPreparationIfEligible()?.let(pending::addLast)
            }
        } catch (problem: Throwable) {
            val addressed = IllegalStateException("station task ${executingTask?.id ?: "entry"} instruction ${executingTask?.index ?: 0}: ${problem.message}", problem)
            failure = addressed
            cancelOutstanding()?.let { addressed.addSuppressed(IllegalStateException(it)) }
        }
    }

    private fun observeTicket(task: Task, dispatcherTick: Long): Boolean {
        when (val result = checkNotNull(task.ticket).poll()) {
            StationProgramTicketResult.Pending -> {
                check(dispatcherTick - task.startedAtDispatcherTick < operationTimeoutTicks) {
                    "station task ${task.id} operation timed out"
                }
                return false
            }
            is StationProgramTicketResult.Failed -> error("station task ${task.id}: ${result.reason}")
            is StationProgramTicketResult.Cancelled -> error("station task ${task.id} cancelled: ${result.reason}")
            StationProgramTicketResult.Succeeded -> {
                completedAction(task, dispatcherTick)
                task.ticket = null
                task.activeAction = null
                task.index++
                return true
            }
        }
    }

    private fun taskTreeComplete(task: Task): Boolean = task.completed && task.children.all(::taskTreeComplete)

    private fun preparationDueTick(): Long {
        val departure = requireNotNull(definition.plannedDepartureTick) { "origin/intermediate stop needs a planned departure tick" }
        return (departure - definition.preparationLeadTicks).coerceAtLeast(0L)
    }

    private fun completedAction(task: Task, dispatcherTick: Long) {
        when (task.activeAction) {
            StationProgramAction.OpenDoors -> {
                doorsSecured = doorless
                dwellDeadline = maxOf(dwellDeadline, Math.addExact(dispatcherTick, minimumDwellTicks))
            }
            StationProgramAction.CloseDoors -> doorsSecured = true
            StationProgramAction.Reverse -> Unit // A direction change does not open confirmed closed doors.
            else -> Unit
        }
        if (physicalOperationOwner == task.id) physicalOperationOwner = null
    }

    fun cancelOutstanding(): String? {
        val errors = mutableListOf<String>()
        tasks.values.forEach { task ->
            task.ticket?.let { ticket ->
                try {
                    ticket.cancel()?.let { errors += "${task.id}: $it" }
                } catch (failure: Throwable) { errors += "${task.id}: ${failure.message ?: failure.javaClass.name}" }
            }
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    companion object {
        private const val ARRIVAL = "@arrival"
        private const val PREPARATION = "@preparation"
        private const val MAX_TASKS = 514
        private const val MAX_TRANSITIONS_PER_TICK = 1024
    }
}
