package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.*
import work.art1st.javarailways.scriptworker.ProgramExpressions
import work.art1st.javarailways.scriptworker.StationProgramValue
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.persistence.SavedTimetableV1
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationResult
import java.util.concurrent.*

internal const val PROGRAM_SIMULATION_SERVICE = "function-simulation"
internal const val PROGRAM_SIMULATION_TASK = "function-call"
private const val MAX_SIMULATION_TICK = (9_007_199_254_740_991L / 24_000L) * 24_000L - 1L

internal data class ProgramSimulationWayPoint(
    val id: String,
    val railId: String,
    val direction: ViewDirection,
    val directionSource: String
) {
    fun json(): JsonObject = buildJsonObject {
        put("id", id); put("railId", railId)
        put("direction", direction.name); put("directionSource", directionSource)
    }
}

/** Called only on the world's capture owner; no geometry-based or AB direction fallback. */
internal fun resolveProgramSimulationWayPoint(world: World, id: String): ProgramSimulationWayPoint {
    val point = world.basicWorldData.wayPoints[id]
        ?: throw IllegalArgumentException("simulation waypoint not found: $id")
    val rail = world.getRail(point.railId)
        ?: throw IllegalArgumentException("simulation waypoint rail not found: $id")
    require(point.arcLength <= rail.curve.totalArcLength) { "simulation waypoint is outside its rail: $id" }
    val own = world.simpleAtoWorldData.destinations[id]?.approachDirection
    val direction = own ?: world.basicWorldData.operatingDirections[point.railId]
        ?: throw IllegalArgumentException("simulation waypoint has no configured direction: $id")
    return ProgramSimulationWayPoint(id, point.railId.toString(), direction,
        if (own != null) "WAYPOINT" else "RAIL")
}

internal data class ProgramSimulationRequest(
    val programId: String,
    val definition: JsonObject,
    val arguments: JsonObject,
    val startTick: Long,
    val profileType: SimulationProfileType,
    val profileId: String,
    val wayPointId: String,
    val reverseTrain: Boolean,
    val plannedContext: JsonObject,
    val maximumDurationTicks: Long,
    val executionTimeoutMillis: Long
) {
    val maximumSimulationTicks: Long get() = startTick + maximumDurationTicks

    // The dispatcher needs a period, but a one-call measurement has no repeating timetable.
    // Cover absolute waits as well as the budget: a wait beyond the budget must reach the
    // simulation limit rather than fail compilation for falling outside an artificial day.
    val periodDays: Long get() {
        var latestTick = maximumSimulationTicks
        fun visit(steps: JsonArray) {
            steps.forEach { raw ->
                val step = raw.jsonObject
                when (step["type"]?.jsonPrimitive?.content) {
                    "WAIT_UNTIL" -> {
                        val parameterTypes = definition.getValue("parameters").jsonArray.associate {
                            it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("type").jsonPrimitive.content
                        }
                        ProgramExpressions.validateShape(step.getValue("tick"))
                        val expression = Json.decodeFromJsonElement<StationProgramValue>(step.getValue("tick"))
                        val tick = ProgramExpressions.evaluate(expression, "TICK", parameterTypes, arguments).jsonPrimitive.long
                        run {
                            require(tick in 0L..MAX_SIMULATION_TICK) { "wait tick is outside the safe simulation range" }
                            latestTick = maxOf(latestTick, tick)
                        }
                    }
                    "BRANCH" -> { visit(step["thenSteps"]!!.jsonArray); visit(step["elseSteps"]!!.jsonArray) }
                }
            }
        }
        visit(definition["steps"]!!.jsonArray)
        return latestTick / 24_000L + 1L
    }

    /** JSON.parse keeps keys such as __proto__ as data; nothing supplied is executable source. */
    fun source(): String {
        val payload = buildJsonObject {
            put("programId", programId); put("definition", definition); put("arguments", arguments)
            put("plannedContext", plannedContext); put("wayPointId", wayPointId)
        }
        return """
            function main(timetable) {
              const input = JSON.parse(${JsonPrimitive(payload.toString())});
              timetable.setPeriodDays($periodDays);
              timetable.service('$PROGRAM_SIMULATION_SERVICE', timetable.takeFromWaypoint([input.wayPointId], $startTick), service => {
                ${if (reverseTrain) "service.reverse();" else ""}
                service.task('$PROGRAM_SIMULATION_TASK', $startTick, task => task.program(input.programId, input.definition, input.arguments, input.plannedContext));
              });
            }
        """.trimIndent()
    }

    companion object {
        fun parse(body: String): ProgramSimulationRequest {
            val root = Json.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalArgumentException("program simulation request must be an object")
            fields(root, setOf("programId", "definition", "arguments", "startTick", "profile", "initialPosition", "plannedContext", "maximumDurationTicks", "executionTimeoutMillis"), "program simulation request")
            val programId = text(root["programId"], "programId")
            require(programId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "invalid programId" }
            val definition = obj(root["definition"], "definition")
            var stepCount = 0
            fun checkLibrarySteps(steps: JsonElement?, depth: Int = 0) {
                require(depth <= 16) { "program exceeds branch depth16" }
                require(steps is JsonArray) { "program steps must be an array" }
                steps.forEach { raw ->
                    require(++stepCount <= 256) { "program exceeds256 total steps" }
                    val step = obj(raw, "program step")
                    val type = text(step["type"], "program step.type")
                    require(type !in setOf("SELECT_STOP", "OPEN_DOORS", "CLOSE_DOORS", "END", "ASYNC", "AWAIT", "WAIT_EVENT")) {
                        "action must be resolved or is unavailable in library programs: $type"
                    }
                    if (type == "BRANCH") { checkLibrarySteps(step["thenSteps"], depth + 1); checkLibrarySteps(step["elseSteps"], depth + 1) }
                }
            }
            checkLibrarySteps(definition["steps"])
            val arguments = obj(root["arguments"], "arguments")
            val startTick = integer(root["startTick"], "startTick", 0L, MAX_SIMULATION_TICK - 1L)
            val maximumDuration = integer(root["maximumDurationTicks"], "maximumDurationTicks", 1L, MAX_SIMULATION_TICK - startTick)
            val profile = obj(root["profile"], "profile")
            fields(profile, setOf("type", "id"), "profile")
            val profileType = SimulationProfileType.valueOf(text(profile["type"], "profile.type"))
            val position = obj(root["initialPosition"], "initialPosition")
            fields(position, setOf("wayPointId", "reverseTrain"), "initialPosition")
            val context = root["plannedContext"]?.let { obj(it, "plannedContext") } ?: JsonObject(emptyMap())
            fields(context, setOf("runType", "lineDirection", "stationId", "runId", "lineId", "callStatus", "dutyId"), "plannedContext")
            context.forEach { (key, value) -> text(value, "plannedContext.$key") }
            return ProgramSimulationRequest(programId, definition, arguments, startTick,
                profileType, text(profile["id"], "profile.id"), text(position["wayPointId"], "initialPosition.wayPointId"),
                (position["reverseTrain"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: throw IllegalArgumentException("initialPosition.reverseTrain must be an explicit boolean"), context, maximumDuration, simulationExecutionTimeoutMillis(root))
        }

        private fun fields(value: JsonObject, allowed: Set<String>, label: String) {
            require(value.keys.all { it in allowed }) { "$label contains unknown fields" }
        }
        private fun obj(value: JsonElement?, label: String) = value as? JsonObject
            ?: throw IllegalArgumentException("$label must be an object")
        private fun text(value: JsonElement?, label: String): String =
            (value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= 128 }?.content
                ?: throw IllegalArgumentException("$label must be a nonblank string of at most128 characters")
        private fun integer(value: JsonElement?, label: String, minimum: Long, maximum: Long): Long =
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in minimum..maximum }
                ?: throw IllegalArgumentException("$label must be an integer between $minimum and $maximum")
    }
}

/** Compiled actions, rather than unselected source branches, determine fixed-unit requirements. */
internal fun programSimulationNeedsRegisteredUnits(saved: SavedTimetableV1): Boolean {
    fun needs(steps: List<work.art1st.javarailways.simpleato.persistence.SavedTimetableStepV1>): Boolean = steps.any { step ->
        val action = step.action
        action != null && (action.type in setOf(
            work.art1st.javarailways.simpleato.persistence.SavedTimetableActionTypeV1.SET_DISPLAY,
            work.art1st.javarailways.simpleato.persistence.SavedTimetableActionTypeV1.RESET_DISPLAY
        ) || action.trainInfo.values.any { it.name == "UNIT_COUNT" } || needs(action.thenSteps) || needs(action.elseSteps))
    }
    return saved.services.any { needs(it.steps) }
}

internal fun programSimulationResultJson(
    result: IsolatedTimetableSimulationResult,
    saved: SavedTimetableV1,
    point: ProgramSimulationWayPoint,
    reverseTrain: Boolean
): JsonObject = buildJsonObject {
    val run = result.runs.singleOrNull { it.id.serviceId == PROGRAM_SIMULATION_SERVICE }
    val timing = run?.taskTimings?.singleOrNull { it.taskId == PROGRAM_SIMULATION_TASK }
    val endTick = timing?.endTick
    val complete = result is IsolatedTimetableSimulationResult.Completed && timing != null && endTick != null && endTick >= timing.startTick
    put("status", if (complete) "COMPLETED" else if (result is IsolatedTimetableSimulationResult.PeriodEnded || result is IsolatedTimetableSimulationResult.DelayExceeded) "LIMIT_REACHED" else "FAILED")
    put("simulatedTicks", result.simulatedTicks)
    timing?.let { put("startTick", it.startTick) }
    if (complete) { put("endTick", endTick!!); put("durationTicks", endTick - timing!!.startTick) }
    else {
        put("reasonCode", when (result) {
            is IsolatedTimetableSimulationResult.PeriodEnded, is IsolatedTimetableSimulationResult.DelayExceeded -> "SIMULATION_LIMIT_REACHED"
            is IsolatedTimetableSimulationResult.Failed -> "SIMULATION_FAILED"
            else -> "SIMULATION_TIMING_UNAVAILABLE"
        })
        put("reason", when (result) {
            is IsolatedTimetableSimulationResult.Failed -> result.reason
            is IsolatedTimetableSimulationResult.PeriodEnded -> if (timing == null)
                "initial preparation did not complete within the simulation limit"
                else "function did not complete within the simulation limit"
            is IsolatedTimetableSimulationResult.DelayExceeded -> "function simulation exceeded its time limit"
            else -> "function completed without an unambiguous task timing"
        })
        val failed = (result as? IsolatedTimetableSimulationResult.Failed)?.failedRun
        val index = failed?.activeStepIndex ?: failed?.nextStepIndex
        if (failed != null && index != null) {
            val path = if (failed.activeStepIndex != null) failed.activeStepBranchPath else failed.nextStepBranchPath
            saved.compilationTrace.singleOrNull { trace -> trace.serviceId == PROGRAM_SIMULATION_SERVICE && trace.stepIndex == index &&
                trace.branchPath.map { it.arm.name to it.index } == path.map { it.arm.name to it.index }
            }?.origin?.substringAfterLast("/step:", "")?.takeIf { it.isNotBlank() }?.let { put("failedProgramStepId", it) }
        }
    }
    put("initialPosition", buildJsonObject {
        put("wayPointId", point.id); put("direction", point.direction.name)
        put("directionSource", point.directionSource); put("reverseTrain", reverseTrain)
    })
}

internal fun <T> runBoundedProgramSimulation(
    executor: ExecutorService,
    timeoutMillis: Long,
    action: () -> T
): T {
    val future = try { executor.submit(Callable(action)) }
    catch (_: RejectedExecutionException) { throw HttpFailure(429, "program simulation queue is full; retry later", "SIMULATION_QUEUE_FULL") }
    return try { future.get(timeoutMillis, TimeUnit.MILLISECONDS) }
    catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
    catch (_: TimeoutException) { future.cancel(true); throw HttpFailure(504, "program simulation timed out", "SIMULATION_TIMEOUT") }
    catch (interrupted: InterruptedException) { future.cancel(true); Thread.currentThread().interrupt(); throw interrupted }
}

/** Shared wall-clock request budget; independent of the simulated tick horizon. */
internal fun simulationExecutionTimeoutMillis(root: JsonObject): Long {
    return (root["executionTimeoutMillis"] as? JsonPrimitive)?.takeUnless { it.isString }
        ?.longOrNull?.takeIf { it in 1L..3_600_000L }
        ?: throw IllegalArgumentException("executionTimeoutMillis must be an integer between 1 and 3600000")
}
