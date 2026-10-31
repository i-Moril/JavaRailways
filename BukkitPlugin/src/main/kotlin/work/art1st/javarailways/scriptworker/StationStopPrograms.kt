package work.art1st.javarailways.scriptworker

import kotlinx.serialization.json.*

/** Compile planned station definitions into a stationary instruction tree, never runtime JS. */
internal fun compileStationStopPrograms(target: String, options: JsonObject): SavedStationStopPrograms? {
    val record = options["stationProgram"] as? JsonObject ?: return null
    require(record.keys == setOf("arrival", "departurePreparation", "preparationLeadTicks")) { "stationProgram requires arrival, departurePreparation and preparationLeadTicks" }
    val stationId = options.getValue("stationId").jsonPrimitive.content
    val callId = options.getValue("callId").jsonPrimitive.content
    val role = options.getValue("callRole").jsonPrimitive.content
    require(role in setOf("ORIGIN", "STOP", "TERMINAL")) { "invalid callRole" }
    require(stationId.isNotBlank() && callId.isNotBlank()) { "stationId and callId are required" }
    val lead = record.getValue("preparationLeadTicks").jsonPrimitive.long
    val departure = options["departureTick"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
    require(lead in 0..9_007_199_254_740_991L && (departure == null || departure in 0..9_007_199_254_740_991L)) { "invalid station preparation/departure tick" }
    if (role != "TERMINAL") require(departure != null) { "origin and stopping calls require departureTick" }
    val context = (options["plannedContext"] as? JsonObject).orEmpty()
    val sourceIds = mutableSetOf<String>()
    var sourceNodes = 0
    fun validateSource(raw: JsonElement, depth: Int) {
        require(depth <= 16) { "station program depth exceeds16" }
        for (node in raw.jsonArray) {
            require(++sourceNodes <= 512) { "station source exceeds512 steps" }
            val step = node.jsonObject
            val id = step.getValue("id").jsonPrimitive.content
            require(sourceIds.add(id)) { "duplicate station source step id: $id" }
            val type = step.getValue("type").jsonPrimitive.content
            val fields = when (type) {
                "OPEN_DOORS", "CLOSE_DOORS", "REVERSE", "END" -> setOf("id", "type")
                "WAIT_UNTIL" -> setOf("id", "type", "tick")
                "WAIT_DURATION" -> setOf("id", "type", "durationTicks")
                "SET_DISPLAY" -> setOf("id", "type", "values") + (if (step.containsKey("removeKeys")) setOf("removeKeys") else emptySet())
                "RESET_DISPLAY" -> setOf("id", "type")
                "EXTENSION" -> setOf("id", "type", "typeId", "version", "parameters")
                "BRANCH" -> setOf("id", "type", "typeId", "version", "parameters", "thenSteps", "elseSteps")
                "ASYNC" -> setOf("id", "type", "taskId", "steps")
                "AWAIT" -> setOf("id", "type", "taskId")
                else -> error("station action is not allowed: $type")
            }
            require(step.keys == fields) { "invalid station step fields: $type" }
            // Validate every source branch before selecting it, including expressions not executed.
            val expressions = when (type) {
                "WAIT_UNTIL" -> listOf("tick")
                "WAIT_DURATION" -> listOf("durationTicks")
                "SET_DISPLAY" -> listOf("values") + if (step.containsKey("removeKeys")) listOf("removeKeys") else emptyList()
                "EXTENSION", "BRANCH" -> listOf("parameters")
                else -> emptyList()
            }
            for (name in expressions) {
                try { ProgramExpressions.validateShape(step.getValue(name), (type == "SET_DISPLAY" && name == "values") || type == "EXTENSION") }
                catch (failure: RuntimeException) { throw IllegalArgumentException("step:$id: ${failure.message}", failure) }
                if (step.getValue(name).jsonObject.keys != setOf("trainInfo")) {
                    val sink = when (name) { "tick" -> "TICK"; "durationTicks" -> "DURATION"; "removeKeys" -> "ID_LIST"; else -> "JSON_OBJECT" }
                    try {
                        val resolved = ProgramExpressions.evaluate(Json.decodeFromJsonElement<StationProgramValue>(step.getValue(name)), sink)
                        if (name == "durationTicks") require(resolved.jsonPrimitive.long > 0) { "wait duration must be positive" }
                    }
                    catch (failure: RuntimeException) { throw IllegalArgumentException("step:$id: ${failure.message}", failure) }
                }
            }
            if (type == "BRANCH") {
                val parameters = step.getValue("parameters").jsonObject
                evaluatePlannedCondition(step.getValue("typeId").jsonPrimitive.content, step.getValue("version").jsonPrimitive.int,
                    ProgramExpressions.evaluate(Json.decodeFromJsonElement<StationProgramValue>(parameters), "JSON_OBJECT").jsonObject, context)
                validateSource(step.getValue("thenSteps"), depth + 1); validateSource(step.getValue("elseSteps"), depth + 1)
            }
            if (type == "ASYNC") validateSource(step.getValue("steps"), depth + 1)
        }
    }
    // Entry-local step IDs may repeat across the two independently edited roots.
    validateSource(record.getValue("arrival").jsonObject.getValue("steps"), 0)
    sourceIds.clear()
    validateSource(record.getValue("departurePreparation").jsonObject.getValue("steps"), 0)
    val taskIds = mutableSetOf<String>()
    var count = 0
    fun compile(raw: JsonElement, nested: Boolean, available: MutableSet<String>, ancestors: Set<String>, depth: Int): List<SavedStationProgramAction> {
        require(depth <= 16) { "station task depth exceeds16" }
        val steps = raw as? JsonArray ?: error("station steps must be an array")
        val result = mutableListOf<SavedStationProgramAction>()
        fun expression(step: JsonObject, name: String): JsonElement {
            val raw = step.getValue(name).jsonObject
            if (raw.keys == setOf("trainInfo") && name in setOf("values", "parameters")) return JsonObject(emptyMap())
            val expected = when (name) {
                "tick" -> "TICK"; "durationTicks" -> "DURATION"; "removeKeys" -> "ID_LIST"
                else -> "JSON_OBJECT"
            }
            return try { ProgramExpressions.evaluate(Json.decodeFromJsonElement<StationProgramValue>(raw), expected) }
            catch (failure: RuntimeException) { throw IllegalArgumentException("step:${step["id"]?.jsonPrimitive?.content}: ${failure.message}", failure) }
        }
        for ((index, value) in steps.withIndex()) {
            require(++count <= 512) { "station definition exceeds512 steps" }
            val step = value.jsonObject
            val id = step.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "invalid station step id" }
            val type = step.getValue("type").jsonPrimitive.content
            val action = when (type) {
                "OPEN_DOORS", "CLOSE_DOORS", "REVERSE", "END" -> {
                    require(step.keys == setOf("id", "type")) { "unknown $type station field" }
                    if (type == "END") require(!nested && index == steps.lastIndex) { "END must terminate the main station entry" }
                    SavedStationProgramAction(SavedStationProgramActionType.valueOf(type))
                }
                "WAIT_UNTIL" -> SavedStationProgramAction(SavedStationProgramActionType.WAIT_UNTIL, tick = expression(step,"tick").jsonPrimitive.long.also { require(it >= 0) })
                "WAIT_DURATION" -> SavedStationProgramAction(SavedStationProgramActionType.WAIT_DURATION, durationTicks = expression(step,"durationTicks").jsonPrimitive.long.also { require(it > 0) })
                "SET_DISPLAY" -> SavedStationProgramAction(SavedStationProgramActionType.SET_DISPLAY,
                    values = expression(step,"values").jsonObject.also(::validateDisplayValues),
                    removeKeys = step["removeKeys"]?.let { expression(step,"removeKeys").jsonArray.map { it.jsonPrimitive.content } }.orEmpty(),
                    trainInfo = step.getValue("values").jsonObject["trainInfo"]?.let(::parseTrainInfo).orEmpty())
                "RESET_DISPLAY" -> SavedStationProgramAction(SavedStationProgramActionType.RESET_DISPLAY)
                "EXTENSION" -> SavedStationProgramAction(SavedStationProgramActionType.EXTENSION,
                    extensionCall = SavedTimetableExtensionCallV1(step.getValue("typeId").jsonPrimitive.content,
                        step.getValue("version").jsonPrimitive.int.also { require(it > 0) }, expression(step,"parameters").jsonObject.also(::validateProgramJsonObject)).also { require(it.typeId.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) },
                    trainInfo = step.getValue("parameters").jsonObject["trainInfo"]?.let(::parseTrainInfo).orEmpty())
                "ASYNC" -> {
                    val taskId = step.getValue("taskId").jsonPrimitive.content
                    require(taskId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) && taskIds.add(taskId)) { "invalid or duplicate station task: $taskId" }
                    val children = compile(step.getValue("steps"), true, available.toMutableSet(), ancestors + taskId, depth + 1)
                    available += taskId
                    SavedStationProgramAction(SavedStationProgramActionType.ASYNC, taskId = taskId, steps = children)
                }
                "AWAIT" -> {
                    val taskId = step.getValue("taskId").jsonPrimitive.content
                    require(taskId in available && taskId !in ancestors) { "cannot await unknown, forward or ancestor station task: $taskId" }
                    SavedStationProgramAction(SavedStationProgramActionType.AWAIT, taskId = taskId)
                }
                "BRANCH" -> {
                    val chosen = evaluatePlannedCondition(step.getValue("typeId").jsonPrimitive.content,
                        step.getValue("version").jsonPrimitive.int, expression(step,"parameters").jsonObject, context)
                    result += compile(step.getValue(if (chosen) "thenSteps" else "elseSteps"), nested, available, ancestors, depth + 1)
                    continue
                }
                else -> error("station action is not allowed: $type")
            }
            require(result.lastOrNull()?.type != SavedStationProgramActionType.END) { "unreachable instruction after END" }
            result += action
        }
        return result.toList()
    }
    val available = mutableSetOf<String>()
    fun entry(name: String): List<SavedStationProgramAction> {
        val definition = record.getValue(name).jsonObject
        require(definition.keys == setOf("parameters", "steps") && definition.getValue("parameters").jsonArray.isEmpty()) { "shared station programs cannot declare parameters" }
        return compile(definition.getValue("steps"), false, available, emptySet(), 0).also { actions ->
            require(actions.lastOrNull()?.type == SavedStationProgramActionType.END && actions.dropLast(1).none { it.type == SavedStationProgramActionType.END }) { "$name must end exactly once with END" }
        }
    }
    val arrival = entry("arrival")
    if (role == "ORIGIN") available.clear()
    val preparation = entry("departurePreparation")
    val doorSide = options["doorSide"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.uppercase()?.also { require(it in setOf("NONE", "LEFT", "RIGHT", "BOTH")) }
    val dwell = options["minimumDwellTicks"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long?.also { require(it >= 0L) }
    return SavedStationStopPrograms(stationId, callId, role, arrival, preparation, lead, departure, target, doorSide, dwell)
}

internal fun evaluatePlannedCondition(typeId: String, version: Int, parameters: JsonObject, context: Map<String, JsonElement>): Boolean {
    val key = mapOf("javarailways:plan_run_type" to "runType", "javarailways:plan_line_direction" to "lineDirection",
        "javarailways:plan_station_id" to "stationId", "javarailways:plan_run_id" to "runId", "javarailways:plan_line_id" to "lineId",
        "javarailways:plan_call_status" to "callStatus", "javarailways:plan_duty_id" to "dutyId")[typeId]
        ?: error("condition is not editable-plan deterministic: $typeId")
    require(version == 1 && parameters.keys == setOf("values")) { "planned condition requires version1 and values" }
    val values = parameters.getValue("values") as? JsonArray ?: error("planned condition values must be an array")
    require(values.isNotEmpty() && values.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() }) { "planned condition values must be nonblank strings" }
    val actual = context[key]?.jsonPrimitive?.content ?: error("planned condition context is missing: $key")
    return values.any { it.jsonPrimitive.content == actual }
}
