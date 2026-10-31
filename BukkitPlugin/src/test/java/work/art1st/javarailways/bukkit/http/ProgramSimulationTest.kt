package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationResult
import java.nio.file.Path
import java.util.concurrent.*

class ProgramSimulationTest {
    @TempDir lateinit var directory: Path
    private fun request(extra: JsonObject.() -> JsonObject = { this }): ProgramSimulationRequest {
        val root = Json.parseToJsonElement("""
            {"programId":"p","definition":{"parameters":[],"steps":[]},"arguments":{},
             "startTick":100,"executionTimeoutMillis":60000,"maximumDurationTicks":2000,
             "profile":{"type":"TRAIN_TEMPLATE","id":"train"},
             "initialPosition":{"wayPointId":"point","reverseTrain":false}}
        """).jsonObject
        return ProgramSimulationRequest.parse(extra(root).toString())
    }

    @Test fun `requires explicit reverse choice and safe bounded integer horizon`() {
        assertEquals(2100L, request().maximumSimulationTicks)
        for (field in listOf("programId", "definition", "arguments", "startTick", "maximumDurationTicks", "executionTimeoutMillis", "profile", "initialPosition")) {
            assertThrows(IllegalArgumentException::class.java) { request { JsonObject(this - field) } }
        }
        for ((field, value) in listOf("startTick" to JsonPrimitive("100"), "startTick" to JsonPrimitive(-1),
            "startTick" to JsonPrimitive(Long.MAX_VALUE), "maximumDurationTicks" to JsonPrimitive(Long.MAX_VALUE), "maximumDurationTicks" to JsonPrimitive(0))) {
            assertThrows(IllegalArgumentException::class.java) { request { JsonObject(this + (field to value)) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            request { JsonObject(this + ("initialPosition" to buildJsonObject { put("wayPointId", "point") })) }
        }
        for (position in listOf(
            """{"wayPointId":"point","reverseTrain":"true"}""",
            """{"wayPointId":"point","reverseTrain":null}""",
            """{"wayPointId":"point","facing":"ALONG"}""",
            """{"wayPointId":"point","reverseTrain":true,"facing":"OPPOSITE"}"""
        )) assertThrows(IllegalArgumentException::class.java) {
            request { JsonObject(this + ("initialPosition" to Json.parseToJsonElement(position))) }
        }
    }

    @Test fun `internal horizon covers multiple days and absolute waits without restricting the advance budget`() {
        val input = request {
            JsonObject(this + mapOf("startTick" to JsonPrimitive(23_900L), "maximumDurationTicks" to JsonPrimitive(48_000L),
                "definition" to Json.parseToJsonElement("""
                    {"parameters":[{"id":"when","type":"TICK"}],"steps":[
                      {"id":"wait","type":"WAIT_UNTIL","tick":{"parameterId":"when"}}]}
                """), "arguments" to buildJsonObject { put("when", 120_000L) }))
        }
        assertEquals(71_900L, input.maximumSimulationTicks)
        assertEquals(6L, input.periodDays)
        TimetableScriptSandbox(directory).use { sandbox ->
            val saved = sandbox.compileSource(input.source(), "function-simulation")
            assertEquals(6L, saved.periodDays)
            assertTrue(saved.services.single().steps.any { it.tick == 120_000L })
        }
        val edge = (9_007_199_254_740_991L / 24_000L) * 24_000L - 1L
        val boundary = request { JsonObject(this + mapOf("startTick" to JsonPrimitive(edge - 1), "maximumDurationTicks" to JsonPrimitive(1))) }
        assertTrue(boundary.periodDays * 24_000L <= 9_007_199_254_740_991L)
    }

    @Test fun `expression absolute wait extends simulation period with the same compiled value`() {
        val input = request {
            JsonObject(this + mapOf("definition" to Json.parseToJsonElement("""
                {"parameters":[{"id":"days","type":"NUMBER"}],"steps":[{"id":"wait","type":"WAIT_UNTIL",
                  "tick":{"operation":{"type":"MULTIPLY","operands":[{"parameterId":"days"},{"value":24000}]}}}]}
            """), "arguments" to buildJsonObject { put("days", 5) }))
        }
        assertEquals(6L, input.periodDays)
        TimetableScriptSandbox(directory).use { sandbox ->
            val compiled = sandbox.compileSource(input.source(), "function-simulation")
            assertEquals(6L, compiled.periodDays)
            assertTrue(compiled.services.single().steps.any { it.tick == 120_000L })
        }
    }

    @Test fun `execution deadline is an explicit safe wall clock budget independent of ticks`() {
        for (value in listOf(JsonPrimitive(0), JsonPrimitive(3_600_001), JsonPrimitive(1.5), JsonPrimitive("1000"), JsonNull)) {
            assertThrows(IllegalArgumentException::class.java) { request { JsonObject(this + ("executionTimeoutMillis" to value)) } }
        }
        for (value in listOf(1L, 1_001L, 3_600_000L)) {
            assertEquals(value, request { JsonObject(this + ("executionTimeoutMillis" to JsonPrimitive(value))) }.executionTimeoutMillis)
        }
    }

    @Test fun `wrapper compiles real acquisition and task boundaries with original point and data safe JSON`() {
        val request = request {
            JsonObject(this + mapOf("definition" to Json.parseToJsonElement("""
                {"parameters":[{"id":"data","type":"JSON_OBJECT"}],"steps":[
                  {"id":"action","type":"EXTENSION","typeId":"demo:action","version":1,"parameters":{"parameterId":"data"}},
                  {"id":"wait","type":"WAIT_DURATION","durationTicks":{"value":1300}}]}
            """), "arguments" to Json.parseToJsonElement("""{"data":{"__proto__":{"marker":true},"text":"');throw Error('bad');("}}""")))
        }
        TimetableScriptSandbox(directory).use { sandbox ->
            val saved = sandbox.compileSource(request.source(), "function-simulation")
            val steps = saved.services.single().steps
            assertEquals("function-simulation", saved.services.single().id)
            assertEquals(listOf("point"), steps.first().action!!.wayPointIds)
            assertEquals("TAKE_FROM_WAYPOINT", steps.first().action!!.type.name)
            assertEquals("TASK_START", steps[1].action!!.type.name)
            assertEquals("TASK_END", steps.last().action!!.type.name)
            assertEquals("function-call", steps.last().action!!.taskId)
            assertEquals(1300L, steps[3].action!!.stopTicks)
            assertEquals(true, steps[2].action!!.extensionCall!!.parameters["__proto__"]!!.jsonObject["marker"]!!.jsonPrimitive.boolean)
            assertEquals("program:p/step:wait", saved.compilationTrace.single { it.stepIndex == 3 }.origin)
            assertFalse(programSimulationNeedsRegisteredUnits(saved))
        }
    }

    @Test fun `optional reversal is a real action before measured function and never changes acquisition point`() {
        TimetableScriptSandbox(directory).use { sandbox ->
            for (reverse in listOf(false, true)) {
                val input = request {
                    JsonObject(this + ("initialPosition" to buildJsonObject {
                        put("wayPointId", "point"); put("reverseTrain", reverse)
                    }))
                }
                val saved = sandbox.compileSource(input.source(), "function-simulation")
                val actions = saved.services.single().steps.map { it.action!! }
                assertEquals(listOf("TAKE_FROM_WAYPOINT") + (if (reverse) listOf("REVERSE") else emptyList()) +
                    listOf("TASK_START", "TASK_END"), actions.map { it.type.name })
                assertEquals(listOf("point"), actions.first().wayPointIds)
                assertEquals(100L, saved.services.single().steps.first().tick)
                assertEquals(100L, saved.services.single().steps.last().tick)
            }
        }
    }

    @Test fun `registered units are required by actual compiled actions and never inferred from literal JSON`() {
        TimetableScriptSandbox(directory).use { sandbox ->
            fun compiled(steps: String, context: String = "{}") = sandbox.compileSource(request {
                JsonObject(this + mapOf("definition" to Json.parseToJsonElement("""{"parameters":[],"steps":$steps}"""),
                    "plannedContext" to Json.parseToJsonElement(context)))
            }.source(), "function-simulation")
            assertTrue(programSimulationNeedsRegisteredUnits(compiled("""[{"id":"r","type":"RESET_DISPLAY"}]""")))
            assertFalse(programSimulationNeedsRegisteredUnits(compiled("""[{"id":"e","type":"EXTENSION","typeId":"demo:action","version":1,"parameters":{"trainInfo":{"name":"NAME"}}}]""")))
            assertFalse(programSimulationNeedsRegisteredUnits(compiled("""[{"id":"e","type":"EXTENSION","typeId":"demo:action","version":1,"parameters":{"value":{"type":"SET_DISPLAY","trainInfo":{"name":"NAME"}}}}]""")))
            assertFalse(programSimulationNeedsRegisteredUnits(compiled("""[{"id":"b","type":"BRANCH","typeId":"javarailways:plan_duty_id","version":1,"parameters":{"value":{"values":["other"]}},"thenSteps":[{"id":"r","type":"RESET_DISPLAY"}],"elseSteps":[]}]""", """{"dutyId":"actual"}""")))
        }
    }

    @Test fun `task duration excludes scheduled idle and failures never return completed duration`() {
        TimetableScriptSandbox(directory).use { sandbox ->
            val saved = sandbox.compileSource(request().source(), "function-simulation")
            val point = ProgramSimulationWayPoint("point", "rail", ViewDirection.AB, "WAYPOINT")
            fun run(timings: List<TimetableTaskTiming>) = TimetableServiceRunSnapshot(
                TimetableServiceRunId("function-simulation", PROGRAM_SIMULATION_SERVICE), "train", 1L,
                TimetableServiceRunState.COMPLETED, false, taskTimings = timings)
            val complete = programSimulationResultJson(IsolatedTimetableSimulationResult.Completed(1501L,
                listOf(run(listOf(TimetableTaskTiming(PROGRAM_SIMULATION_TASK, 100L, 1500L))))), saved, point, true)
            assertEquals(1400L, complete["durationTicks"]!!.jsonPrimitive.long)
            assertTrue(complete["initialPosition"]!!.jsonObject["reverseTrain"]!!.jsonPrimitive.boolean)
            val preparing = programSimulationResultJson(IsolatedTimetableSimulationResult.PeriodEnded(2100L,
                listOf(run(emptyList()))), saved, point, true)
            assertTrue(preparing["reason"]!!.jsonPrimitive.content.contains("preparation"))
            assertEquals("SIMULATION_LIMIT_REACHED", preparing["reasonCode"]!!.jsonPrimitive.content)
            assertFalse(preparing.containsKey("startTick"))
            for (result in listOf(
                IsolatedTimetableSimulationResult.PeriodEnded(2100L, listOf(run(listOf(TimetableTaskTiming(PROGRAM_SIMULATION_TASK, 100L))))),
                IsolatedTimetableSimulationResult.Failed(300L, "actual failure", runs = listOf(run(listOf(TimetableTaskTiming(PROGRAM_SIMULATION_TASK, 100L, 200L))))),
                IsolatedTimetableSimulationResult.Completed(300L, listOf(run(emptyList())))
            )) {
                val json = programSimulationResultJson(result, saved, point, false)
                assertFalse(json.containsKey("durationTicks")); assertFalse(json.containsKey("endTick"))
                assertNotEquals("COMPLETED", json["status"]!!.jsonPrimitive.content)
                val expectedReasonCode = when (result) {
                    is IsolatedTimetableSimulationResult.Failed -> "SIMULATION_FAILED"
                    is IsolatedTimetableSimulationResult.PeriodEnded -> "SIMULATION_LIMIT_REACHED"
                    else -> "SIMULATION_TIMING_UNAVAILABLE"
                }
                assertEquals(expectedReasonCode, json["reasonCode"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun `shared executor capacity and wall deadline remain bounded and cancel work`() {
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit { occupied.countDown(); release.await() }
        assertTrue(occupied.await(5, TimeUnit.SECONDS))
        executor.submit { release.await() }
        try {
            val full = assertThrows(HttpFailure::class.java) { runBoundedProgramSimulation(executor, 1000L) { error("cannot start") } }
            assertEquals(429, full.status)
            assertEquals("SIMULATION_QUEUE_FULL", full.code)
        } finally { release.countDown(); executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        val worker = Executors.newSingleThreadExecutor()
        val cancelled = CountDownLatch(1)
        try {
            val timeout = assertThrows(HttpFailure::class.java) {
                runBoundedProgramSimulation(worker, 50L) {
                    try { CountDownLatch(1).await() } finally { cancelled.countDown() }
                }
            }
            assertEquals(504, timeout.status)
            assertEquals("SIMULATION_TIMEOUT", timeout.code)
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
