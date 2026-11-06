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
    stoppedAtDispatcherTick: Long,
    minimumDwellTicks: Long,
    private val doorless: Boolean,
    private val operationTimeoutTicks: Long,
    private val host: StationProgramActionHost,
    private val queryDoorCloseDuration: () -> java.util.concurrent.CompletableFuture<Long> = {
        java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("door duration gateway is not configured"))
    }
) {
    private class Task(val id: String, val steps: List<StationProgramAction>, val main: Boolean) {
        var index = 0
        var completed = false
        val children = mutableListOf<Task>()
        var waitUntilDispatcherTick = 0L
        var ticket: StationProgramTicket? = null
        var startedAtDispatcherTick = 0L
        var activeAction: StationProgramAction? = null
        var lastWorkTick: Long? = null
        var observedTicketCompletionTick: Long? = null
    }

    private val tasks = linkedMapOf<String, Task>()
    private val symbols = linkedMapOf<String, Task>()
    private var physicalOperationOwner: String? = null
    private var preparationStarted = false
    private var preparationLeadTicks: Long? = null
    private var durationQuery: java.util.concurrent.CompletableFuture<Long>? = null
    private var durationQueryStartedAt = 0L
    private val dwellDeadline = Math.addExact(stoppedAtDispatcherTick,
        if (definition.role == StationStopRole.STOP) minimumDwellTicks else 0L)
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
    /** Real work completion, independent of the later interpreter pass that consumes END/join. */
    val allTasksCompletedAtDispatcherTick: Long?
        get() = if (allTasksComplete) tasks.values.maxOf { checkNotNull(it.lastWorkTick) } else null

    /** Measurement-only observation after effect commits. Never advances a task or a movement gate. */
    fun recordCommittedTicketCompletions(dispatcherTick: Long) {
        require(dispatcherTick >= lastTick)
        if (failure != null) return
        tasks.values.forEach { task ->
            if (task.observedTicketCompletionTick == null && task.ticket?.poll() == StationProgramTicketResult.Succeeded) {
                task.observedTicketCompletionTick = dispatcherTick
            }
        }
    }

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
            resolvePreparationLead(dispatcherTick)
            fun startPreparationIfEligible(): Task? {
                if (!preparationStarted && definition.role != StationStopRole.TERMINAL && arrivalMainComplete &&
                    preparationLeadTicks != null && elapsedTimetableTick >= preparationDueTick() &&
                    dispatcherTick >= dwellDeadline - checkNotNull(preparationLeadTicks)) {
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
                if (task.lastWorkTick == null) task.lastWorkTick = dispatcherTick
                while (!task.completed && budget-- > 0) {
                    if (dispatcherTick < task.waitUntilDispatcherTick) break
                    if (task.waitUntilDispatcherTick != 0L) {
                        task.lastWorkTick = dispatcherTick
                        task.waitUntilDispatcherTick = 0L
                    }
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
                            task.lastWorkTick = dispatcherTick
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
                            task.lastWorkTick = maxOf(checkNotNull(task.lastWorkTick), taskTreeCompletionTick(joined))
                            task.index++
                        }
                        is StationProgramAction.WaitUntil -> {
                            if (elapsedTimetableTick < action.tick) break
                            task.lastWorkTick = dispatcherTick
                            task.index++
                        }
                        is StationProgramAction.WaitDuration -> {
                            require(action.ticks > 0)
                            task.lastWorkTick = dispatcherTick
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
                            task.lastWorkTick = dispatcherTick
                            task.observedTicketCompletionTick = null
                            task.ticket = if (doorless && (action == StationProgramAction.OpenDoors || action == StationProgramAction.CloseDoors)) null
                                else host.start(action, task.id, task.index)
                            if (task.ticket == null) {
                                completedAction(task)
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
                val completedAt = task.observedTicketCompletionTick ?: dispatcherTick
                check(completedAt in task.startedAtDispatcherTick..dispatcherTick) { "invalid station operation completion time" }
                task.lastWorkTick = maxOf(checkNotNull(task.lastWorkTick), completedAt)
                completedAction(task)
                task.ticket = null
                task.observedTicketCompletionTick = null
                task.activeAction = null
                task.index++
                return true
            }
        }
    }

    private fun taskTreeComplete(task: Task): Boolean = task.completed && task.children.all(::taskTreeComplete)

    private fun taskTreeCompletionTick(task: Task): Long =
        maxOf(checkNotNull(task.lastWorkTick), task.children.maxOfOrNull(::taskTreeCompletionTick) ?: 0L)

    private fun resolvePreparationLead(dispatcherTick: Long) {
        if (definition.role == StationStopRole.TERMINAL || preparationLeadTicks != null) return
        if (doorless || !definition.preparationLead.requiresDoorCloseDuration) {
            preparationLeadTicks = definition.preparationLead.evaluate(0)
            return
        }
        val future = durationQuery ?: queryDoorCloseDuration().also {
            durationQuery = it
            durationQueryStartedAt = dispatcherTick
        }
        if (!future.isDone) {
            check(dispatcherTick - durationQueryStartedAt < operationTimeoutTicks) { "door duration query timed out" }
            return
        }
        preparationLeadTicks = definition.preparationLead.evaluate(future.join())
        durationQuery = null
    }

    private fun preparationDueTick(): Long {
        val departure = requireNotNull(definition.plannedDepartureTick) { "origin/intermediate stop needs a planned departure tick" }
        return (departure - checkNotNull(preparationLeadTicks)).coerceAtLeast(0L)
    }

    private fun completedAction(task: Task) {
        when (task.activeAction) {
            StationProgramAction.OpenDoors -> doorsSecured = doorless
            StationProgramAction.CloseDoors -> doorsSecured = true
            StationProgramAction.Reverse -> Unit // A direction change does not open confirmed closed doors.
            else -> Unit
        }
        if (physicalOperationOwner == task.id) physicalOperationOwner = null
    }

    fun cancelOutstanding(): String? {
        val errors = mutableListOf<String>()
        durationQuery?.let { future ->
            future.cancel(true)
            if (!future.isDone) errors += "door duration query cancellation was not confirmed"
        }
        durationQuery = null
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
