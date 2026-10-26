package work.art1st.javarailways.scriptworker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Visual authoring DTOs are expanded at compile time, never evaluated as runtime JavaScript. */
@Serializable internal data class StationProgramParameter(val id: String, val type: String)
@Serializable internal data class StationProgramValue(val value: JsonElement? = null, val parameterId: String? = null)
@Serializable internal data class StationProgramDefinition(val parameters: List<StationProgramParameter>, val steps: List<StationProgramStep>)
@Serializable internal sealed class StationProgramStep {
    abstract val id: String
    @Serializable @SerialName("SET_DISPLAY") data class SetDisplay(override val id: String, val unitSlots: StationProgramValue,
        val values: StationProgramValue, val removeKeys: StationProgramValue = StationProgramValue(value = JsonArray(emptyList()))) : StationProgramStep()
    @Serializable @SerialName("RESET_DISPLAY") data class ResetDisplay(override val id: String, val unitSlots: StationProgramValue) : StationProgramStep()
    @Serializable @SerialName("WAIT_UNTIL") data class WaitUntil(override val id: String, val tick: StationProgramValue) : StationProgramStep()
    @Serializable @SerialName("WAIT_DURATION") data class WaitDuration(override val id: String, val durationTicks: StationProgramValue) : StationProgramStep()
    @Serializable @SerialName("EXTENSION") data class Extension(override val id: String, val typeId: String, val version: Int, val parameters: StationProgramValue) : StationProgramStep()
    @Serializable @SerialName("BRANCH") data class Branch(override val id: String, val typeId: String, val version: Int,
        val parameters: StationProgramValue, val thenSteps: List<StationProgramStep>, val elseSteps: List<StationProgramStep>) : StationProgramStep()
    @Serializable @SerialName("COUPLE") data class Couple(override val id: String, val eventId: StationProgramValue) : StationProgramStep()
    @Serializable @SerialName("SPLIT") data class Split(override val id: String, val eventId: StationProgramValue) : StationProgramStep()
    @Serializable @SerialName("REVERSE") data class Reverse(override val id: String) : StationProgramStep()
    @Serializable @SerialName("MOVE") data class Move(override val id: String, val destinationId: StationProgramValue, val viaIds: StationProgramValue) : StationProgramStep()
    @Serializable @SerialName("SELECT_STOP") data class SelectStop(override val id: String, val stationId: StationProgramValue, val berthIds: StationProgramValue, val selectionPolicy: SavedStationStopSelectionPolicyV1) : StationProgramStep()
    @Serializable @SerialName("STORE_AT_DEPOT") data class Store(override val id: String, val depotId: StationProgramValue, val stablingPointIds: StationProgramValue) : StationProgramStep()
}

private class ProgramStepFailure(stepId: String, cause: RuntimeException) :
    IllegalArgumentException("step:$stepId: ${cause.message}", cause)

internal data class ExpandedProgramStepOrigin(val stepIndex: Int, val branchPath: List<SavedTimetableBranchPathEntryV1>, val programStepId: String)
internal data class ExpandedStationProgram(val steps: List<SavedTimetableStepV1>, val origins: List<ExpandedProgramStepOrigin>)

internal fun expandStationProgram(definitionJson: JsonElement, argumentsJson: JsonElement, initialTick: Long, plannedContext: Map<String, JsonElement> = emptyMap()): ExpandedStationProgram {
    require(initialTick in 0L..9_007_199_254_740_991L) { "program start tick must be a nonnegative safe integer" }
    fun expressionShape(value: JsonElement) {
        val fields = value as? JsonObject ?: error("program value must be an object")
        require(fields.keys == setOf("value") || fields.keys == setOf("parameterId")) { "program value requires exactly value or parameterId" }
    }
    var count = 0
    val stepIds = mutableSetOf<String>()
    fun checkSteps(steps: JsonArray, depth: Int) {
      require(depth <= 16) { "program exceeds branch depth16" }
      steps.forEach { raw ->
        require(++count <= 256) { "program exceeds256 total steps" }
        val step = raw.jsonObject
        val id = step.getValue("id").jsonPrimitive.content
        requireProgramId(id)
        require(stepIds.add(id)) { "duplicate program step id: $id" }
        if (depth > 0) require(step["type"]?.jsonPrimitive?.content !in setOf("COUPLE", "SPLIT", "STORE_AT_DEPOT")) {
            "step:$id: fixed operations cannot be conditional"
        }
        when (step["type"]?.jsonPrimitive?.content) {
            "SET_DISPLAY" -> { expressionShape(step.getValue("unitSlots")); expressionShape(step.getValue("values")); step["removeKeys"]?.let(::expressionShape) }
            "RESET_DISPLAY" -> expressionShape(step.getValue("unitSlots"))
            "COUPLE", "SPLIT" -> expressionShape(step.getValue("eventId"))
            "BRANCH" -> {
                expressionShape(step.getValue("parameters"))
                checkSteps(step.getValue("thenSteps").jsonArray, depth + 1)
                checkSteps(step.getValue("elseSteps").jsonArray, depth + 1)
            }
            "EXTENSION" -> expressionShape(step.getValue("parameters"))
            "WAIT_UNTIL" -> expressionShape(step.getValue("tick"))
            "WAIT_DURATION" -> expressionShape(step.getValue("durationTicks"))
            "MOVE" -> { expressionShape(step.getValue("destinationId")); expressionShape(step.getValue("viaIds")) }
            "SELECT_STOP" -> { expressionShape(step.getValue("stationId")); expressionShape(step.getValue("berthIds")) }
            "STORE_AT_DEPOT" -> { expressionShape(step.getValue("depotId")); expressionShape(step.getValue("stablingPointIds")) }
        }
    }
    }
    checkSteps(definitionJson.jsonObject.getValue("steps").jsonArray, 0)
    val definition = Json.decodeFromJsonElement<StationProgramDefinition>(definitionJson)
    val arguments = argumentsJson as? JsonObject ?: error("program arguments must be an object")
    require(definition.parameters.size <= 64 && definition.steps.size <= 256) { "program exceeds parameter/step limits" }
    val parameters = definition.parameters.associate { parameter ->
        requireProgramId(parameter.id)
        require(parameter.type in setOf("TICK", "DURATION", "DESTINATION", "STATION", "DEPOT", "FORMATION_EVENT", "STRING", "ID_LIST", "JSON_OBJECT", "UNIT_SLOTS")) { "unknown parameter type: ${parameter.type}" }
        parameter.id to parameter.type
    }
    require(parameters.size == definition.parameters.size) { "duplicate program parameter id" }
    require(arguments.keys == parameters.keys) { "program arguments must match declared parameters" }
    parameters.forEach { (id, type) -> validateProgramLiteral(arguments.getValue(id), type) }
    require(definition.steps.map { it.id }.distinct().size == definition.steps.size) { "duplicate program step id" }
    fun value(expression: StationProgramValue, type: String): JsonElement {
        require((expression.value != null) != (expression.parameterId != null)) { "program value requires exactly value or parameterId" }
        val literal = expression.parameterId?.let { id ->
            require(parameters[id] == type) { "unknown or incompatible parameter: $id" }
            arguments.getValue(id)
        } ?: requireNotNull(expression.value)
        validateProgramLiteral(literal, type)
        return literal
    }
    fun expand(steps: List<StationProgramStep>, startTick: Long): Pair<ExpandedStationProgram, Long> {
    var tick = startTick
    val result = mutableListOf<SavedTimetableStepV1>()
    val origins = mutableListOf<ExpandedProgramStepOrigin>()
    fun append(id: String, action: SavedTimetableActionV1) {
        origins += ExpandedProgramStepOrigin(result.size, emptyList(), id)
        result += SavedTimetableStepV1(SavedTimetableStepTypeV1.AT, tick, action = action)
    }
    for (step in steps) {
      try {
        requireProgramId(step.id)
        when (step) {
            is StationProgramStep.SetDisplay -> {
                val slots = value(step.unitSlots, "UNIT_SLOTS").jsonArray.map { it.jsonPrimitive.int }
                val keys = value(step.removeKeys, "ID_LIST").jsonArray.map { it.jsonPrimitive.content }
                require(keys.distinct().size == keys.size) { "display removal keys must be distinct" }
                append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.SET_DISPLAY, unitSlots = slots,
                    displayValues = value(step.values, "JSON_OBJECT").jsonObject.also(::validateDisplayValues), removeDisplayKeys = keys))
            }
            is StationProgramStep.ResetDisplay -> append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.RESET_DISPLAY,
                unitSlots = value(step.unitSlots, "UNIT_SLOTS").jsonArray.map { it.jsonPrimitive.int }))
            is StationProgramStep.WaitUntil -> {
                val next = value(step.tick, "TICK").jsonPrimitive.long
                tick = maxOf(tick, next)
                append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.WAIT_UNTIL))
            }
            is StationProgramStep.WaitDuration -> {
                val duration = value(step.durationTicks, "DURATION").jsonPrimitive.long
                require(duration > 0) { "wait duration must be positive" }
                append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.STOP, stopTicks = duration))
            }
            is StationProgramStep.Extension -> {
                require(step.typeId.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")) && step.typeId.length <= 256) { "extension typeId must be namespaced" }
                require(step.version > 0) { "extension version must be positive" }
                append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.EXTENSION,
                    extensionCall = SavedTimetableExtensionCallV1(step.typeId, step.version, value(step.parameters, "JSON_OBJECT").jsonObject)))
            }
            is StationProgramStep.Branch -> {
                val chosen = evaluatePlannedCondition(step.typeId, step.version, value(step.parameters, "JSON_OBJECT").jsonObject, plannedContext)
                val (expanded, nextTick) = expand(if (chosen) step.thenSteps else step.elseSteps, tick)
                val offset = result.size
                result += expanded.steps
                origins += expanded.origins.map { it.copy(stepIndex = it.stepIndex + offset) }
                tick = nextTick
            }
            is StationProgramStep.Couple -> append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.COUPLE,
                eventId = value(step.eventId, "FORMATION_EVENT").jsonPrimitive.content))
            is StationProgramStep.Split -> append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.SPLIT,
                eventId = value(step.eventId, "FORMATION_EVENT").jsonPrimitive.content))
            is StationProgramStep.Reverse -> append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.REVERSE))
            is StationProgramStep.Move -> {
                val destination = value(step.destinationId, "DESTINATION").jsonPrimitive.content
                val via = value(step.viaIds, "ID_LIST").jsonArray.map { it.jsonPrimitive.content }
                append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.DESTINATION_OPTIONS, wayPointIds = listOf(destination)))
                append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.DESTINATION, wayPointIds = via + destination))
            }
            is StationProgramStep.SelectStop -> {
                val ids = value(step.berthIds, "ID_LIST").jsonArray.map { it.jsonPrimitive.content }
                require(ids.size in 1..64 && ids.distinct().size == ids.size) { "station selection requires distinct nonempty berth IDs (at most64)" }
                append(step.id, SavedTimetableActionV1(SavedTimetableActionTypeV1.SELECT_STOP,
                    stationId = value(step.stationId, "STATION").jsonPrimitive.content,
                    berthCandidates = ids.mapIndexed { index, id -> SavedStationStopCandidateV1(id, index) },
                    selectionPolicy = step.selectionPolicy))
            }
            is StationProgramStep.Store -> append(step.id, SavedTimetableActionV1(
                SavedTimetableActionTypeV1.STORE_AT_DEPOT,
                depotId = value(step.depotId, "DEPOT").jsonPrimitive.content,
                stablingPointIds = value(step.stablingPointIds, "ID_LIST").jsonArray.map { it.jsonPrimitive.content }.toSet()
            ))
        }
      } catch (failure: RuntimeException) {
        throw if (failure is ProgramStepFailure) failure else ProgramStepFailure(step.id, failure)
      }
    }
    return ExpandedStationProgram(result, origins) to tick
    }
    return expand(definition.steps, initialTick).first
}

private fun requireProgramId(id: String) { require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "invalid program parameter/step id: $id" } }
private fun validateProgramLiteral(value: JsonElement, type: String) {
    when (type) {
        "TICK", "DURATION" -> {
            val primitive = value as? JsonPrimitive
            val number = primitive?.takeUnless { it.isString }?.longOrNull
            require(number != null && number in 0L..9_007_199_254_740_991L) { "$type must be a nonnegative safe integer" }
        }
        "UNIT_SLOTS" -> {
            require(value is JsonArray) { "UNIT_SLOTS must be an array" }
            requireUnitSlots(value.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: error("UNIT_SLOTS must be integers") })
        }
        "JSON_OBJECT" -> validateProgramJsonObject(value)
        "ID_LIST" -> {
            require(value is JsonArray && value.size <= 256) { "ID_LIST must be an array with at most256 entries" }
            value.forEach { validateProgramLiteral(it, "STRING") }
        }
        else -> require(value is JsonPrimitive && value.isString && value.content.isNotBlank() && value.content.length <= 256) { "$type must be a nonblank string of at most256 characters" }
    }
}

internal fun validateProgramJsonObject(value: JsonElement) {
    require(value is JsonObject) { "JSON_OBJECT must be a JSON object" }
    var nodes = 0
    fun walk(node: JsonElement, depth: Int) {
        require(depth <= 16 && ++nodes <= 4096) { "JSON_OBJECT exceeds depth16 or4096 values" }
        when (node) {
            is JsonObject -> { require(node.keys.all { it.length <= 8192 }) { "JSON object key exceeds8192 characters" }; node.values.forEach { walk(it, depth + 1) } }
            is JsonArray -> node.forEach { walk(it, depth + 1) }
            is JsonPrimitive -> when {
                node.isString -> require(node.content.length <= 8192) { "JSON string exceeds8192 characters" }
                node == JsonNull || node.booleanOrNull != null -> Unit
                else -> {
                    val number = node.doubleOrNull
                    require(number != null && number.isFinite() && !(kotlin.math.floor(number) == number && kotlin.math.abs(number) > 9_007_199_254_740_991.0)) { "JSON number must be finite and integral values must be safe integers" }
                }
            }
        }
    }
    walk(value, 0)
}

internal fun validateDisplayValues(value: JsonObject) {
    validateProgramJsonObject(value)
    require(value.size <= 128 && value.keys.all { it.isNotBlank() } && value.toString().length <= 65536) {
        "display values require at most128 nonblank keys and64 KiB"
    }
}

/** Arrival definitions have no call arguments and cannot alter the active movement/formation. */
internal fun validateArrivalProgram(raw: JsonElement) {
    val definition = raw.jsonObject
    require(definition.getValue("parameters").jsonArray.isEmpty()) { "arrival program cannot declare parameters" }
    fun checkSteps(steps: JsonArray, depth: Int) {
        require(depth <= 16) { "arrival program exceeds branch depth16" }
        steps.forEach { value ->
            val step = value.jsonObject
            require(step.getValue("type").jsonPrimitive.content in setOf("SET_DISPLAY", "RESET_DISPLAY", "WAIT_DURATION", "WAIT_UNTIL", "EXTENSION", "BRANCH")) {
                "step:${step["id"]?.jsonPrimitive?.content}: action is not allowed in arrival program"
            }
            if (step["type"]?.jsonPrimitive?.content == "BRANCH") {
                checkSteps(step.getValue("thenSteps").jsonArray, depth + 1)
                checkSteps(step.getValue("elseSteps").jsonArray, depth + 1)
            }
        }
    }
    checkSteps(definition.getValue("steps").jsonArray, 0)
}
