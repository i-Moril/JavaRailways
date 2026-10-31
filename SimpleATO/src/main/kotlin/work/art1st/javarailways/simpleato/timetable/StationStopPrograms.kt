package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall

/** Planned role, never inferred from the actual train or a simulation reference vehicle. */
enum class StationStopRole { ORIGIN, STOP, TERMINAL }

/** Independent stationary instruction tree; no movement or rolling-stock acquisition is representable. */
sealed interface StationProgramAction {
    data object OpenDoors : StationProgramAction
    data object CloseDoors : StationProgramAction
    data object End : StationProgramAction
    data object Reverse : StationProgramAction
    data class WaitUntil(val tick: Long) : StationProgramAction { init { require(tick >= 0L) } }
    data class WaitDuration(val ticks: Long) : StationProgramAction { init { require(ticks > 0L) } }
    data class Await(val taskId: String) : StationProgramAction { init { require(taskId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "invalid station task symbol" } } }
    class Async(val taskId: String, steps: List<StationProgramAction>) : StationProgramAction {
        val steps: List<StationProgramAction> = java.util.List.copyOf(steps)
        init { require(taskId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "invalid station task symbol" } }
        override fun equals(other: Any?) = other is Async && taskId == other.taskId && steps == other.steps
        override fun hashCode() = java.util.Objects.hash(taskId, steps)
    }
    class SetDisplay(values: JsonObject, removeKeys: List<String> = emptyList(), trainInfo: Map<String, TrainInfoField> = emptyMap()) : StationProgramAction {
        val values = immutableDisplayValues(values)
        val trainInfo = immutableTrainInfo(trainInfo)
        init { require(trainInfo.isEmpty() || values.isEmpty()) { "trainInfo replaces literal values" } }
        val removeKeys: List<String> = java.util.List.copyOf(removeKeys)
        init { require(removeKeys.distinct().size == removeKeys.size && removeKeys.all { it.isNotBlank() }) }
        override fun equals(other: Any?) = other is SetDisplay && values == other.values && removeKeys == other.removeKeys && trainInfo == other.trainInfo
        override fun hashCode() = java.util.Objects.hash(values, removeKeys, trainInfo)
    }
    data object ResetDisplay : StationProgramAction
    class Extension(val call: TimetableExtensionCall, trainInfo: Map<String, TrainInfoField> = emptyMap()) : StationProgramAction {
        val trainInfo = immutableTrainInfo(trainInfo)
        init { require(trainInfo.isEmpty() || call.parameters.isEmpty()) { "trainInfo replaces literal parameters" } }
        override fun equals(other: Any?) = other is Extension && call == other.call && trainInfo == other.trainInfo
        override fun hashCode() = java.util.Objects.hash(call, trainInfo)
    }
}

/** Frozen compiled program for one concrete stop occurrence. Runtime state never lives on this value. */
class StationStopPrograms(
    val stationId: String,
    val callId: String,
    val role: StationStopRole,
    arrival: List<StationProgramAction>,
    departurePreparation: List<StationProgramAction>,
    val preparationLead: StationPreparationLead,
    val plannedDepartureTick: Long?,
    val destinationId: String? = null,
    val doorSide: DoorSide? = null,
    val minimumDwellTicks: Long? = null
) {
    val arrival: List<StationProgramAction> = java.util.List.copyOf(arrival)
    val departurePreparation: List<StationProgramAction> = java.util.List.copyOf(departurePreparation)
    override fun equals(other: Any?) = other is StationStopPrograms && stationId == other.stationId && callId == other.callId && role == other.role && arrival == other.arrival && departurePreparation == other.departurePreparation && preparationLead == other.preparationLead && plannedDepartureTick == other.plannedDepartureTick && destinationId == other.destinationId && doorSide == other.doorSide && minimumDwellTicks == other.minimumDwellTicks
    override fun hashCode() = java.util.Objects.hash(stationId, callId, role, arrival, departurePreparation, preparationLead, plannedDepartureTick, destinationId, doorSide, minimumDwellTicks)
    init {
        require(stationId.isNotBlank() && callId.isNotBlank())
        require(minimumDwellTicks == null || minimumDwellTicks >= 0L)
        require(plannedDepartureTick == null || plannedDepartureTick >= 0L)
        require(destinationId == null || destinationId.isNotBlank())
        require(role == StationStopRole.TERMINAL || plannedDepartureTick != null) { "nonterminal stop requires a planned departure tick" }
        validateStationEntry(arrival, "arrival")
        validateStationEntry(departurePreparation, "departure preparation")
        val declaredTasks = linkedMapOf<String, StationProgramAction.Async>()
        fun declarations(actions: List<StationProgramAction>, depth: Int) {
            require(depth <= 16) { "station task depth exceeds16" }
            for (action in actions) if (action is StationProgramAction.Async) {
                require(declaredTasks.put(action.taskId, action) == null) { "duplicate station task: ${action.taskId}" }
                declarations(action.steps, depth + 1)
            }
        }
        declarations(arrival, 0); declarations(departurePreparation, 0)
        // Only already launched lexical tasks may be awaited. An async child cannot await its own
        // ancestor: that would make the implicit descendant join cyclic.
        fun references(actions: List<StationProgramAction>, available: Set<String>, ancestors: Set<String>): Set<String> {
            val launched = available.toMutableSet()
            for (action in actions) when (action) {
                is StationProgramAction.Async -> {
                    references(action.steps, launched, ancestors + action.taskId)
                    launched += action.taskId
                }
                is StationProgramAction.Await -> require(action.taskId in launched && action.taskId !in ancestors) {
                    "station task must be launched before waiting and cannot await an ancestor: ${action.taskId}"
                }
                else -> Unit
            }
            return launched
        }
        val arrivalTasks = references(arrival, emptySet(), emptySet())
        references(departurePreparation, if (role == StationStopRole.ORIGIN) emptySet() else arrivalTasks, emptySet())
    }
}

private fun validateStationEntry(actions: List<StationProgramAction>, name: String) {
    require(actions.isNotEmpty() && actions.last() == StationProgramAction.End) { "$name program must end with END" }
    require(actions.dropLast(1).none { it == StationProgramAction.End }) { "$name END must be the final instruction" }
    var count = 0
    fun visit(steps: List<StationProgramAction>, nested: Boolean, depth: Int) {
        require(depth <= 16) { "station task depth exceeds16" }
        for (step in steps) {
            require(++count <= 256) { "station entry exceeds256 steps" }
            if (nested) require(step != StationProgramAction.End) { "END cannot appear inside an asynchronous task" }
            if (step is StationProgramAction.Async) visit(step.steps, true, depth + 1)
        }
    }
    visit(actions, false, 0)
}

/** All instructions are statically selected; departure joins every child before onward routing. */
internal fun StationStopPrograms.reverseParity(): Int {
    fun count(actions: List<StationProgramAction>): Int = actions.sumOf { action ->
        when (action) { StationProgramAction.Reverse -> 1; is StationProgramAction.Async -> count(action.steps); else -> 0 }
    }
    return ((if (role == StationStopRole.ORIGIN) 0 else count(arrival)) + (if (role == StationStopRole.TERMINAL) 0 else count(departurePreparation))) % 2
}
