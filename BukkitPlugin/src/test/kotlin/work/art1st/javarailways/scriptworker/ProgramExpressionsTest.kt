package work.art1st.javarailways.scriptworker

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProgramExpressionsTest {
    private fun expr(raw: String) = Json.decodeFromString<StationProgramValue>(raw)
    private fun evaluate(raw: String, type: String = "NUMBER") = ProgramExpressions.evaluate(expr(raw), type)
    private fun op(type: String, vararg operands: String) = """{"operation":{"type":"$type","operands":[${operands.joinToString(",")}]}}"""
    private fun literal(value: Any) = buildJsonObject { put("value", when(value) { is String -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> error("test input") }) }.toString()

    @Test fun `numeric operations conversions and javascript rounding are deterministic`() {
        val cases = listOf("ADD" to 9.0, "SUBTRACT" to 5.0, "MULTIPLY" to 14.0, "DIVIDE" to 3.5, "MODULO" to 1.0, "MIN" to 2.0, "MAX" to 7.0)
        cases.forEach { (type, result) -> assertEquals(result, evaluate(op(type, literal(7), literal(2))).jsonPrimitive.double) }
        assertEquals(-1L, evaluate(op("ROUND", literal(-1.5))).jsonPrimitive.long)
        assertEquals(-2L, evaluate(op("FLOOR", literal(-1.1))).jsonPrimitive.long)
        assertEquals(-1L, evaluate(op("CEIL", literal(-1.1))).jsonPrimitive.long)
        assertEquals(12L, evaluate(op("TO_NUMBER", literal("\u00A0\uFEFF12\uFEFF"))).jsonPrimitive.long)
        assertThrows(RuntimeException::class.java) { evaluate(op("TO_NUMBER", literal("\u001C12"))) }
        assertEquals(0.0000001, evaluate(op("TO_NUMBER", literal(" +1e-7 "))).jsonPrimitive.double)
        assertEquals("0.0000001", evaluate(op("TO_STRING", literal(0.0000001)), "STRING").jsonPrimitive.content)
        assertEquals("0", evaluate(op("TO_STRING", literal(-0.0)), "STRING").jsonPrimitive.content)
        for ((number, text) in listOf(0.1 to "0.1", 0.30000000000000004 to "0.30000000000000004",
            Double.MIN_VALUE to ("0." + "0".repeat(323) + "5"), 1e-323 to ("0." + "0".repeat(322) + "1"))) {
            assertEquals(text, evaluate(op("TO_STRING", literal(number)), "STRING").jsonPrimitive.content)
        }
    }

    @Test fun `text uses utf16 indexing literal replacements and permits blank text`() {
        assertEquals(4L, evaluate(op("TEXT_LENGTH", literal("a😀b"))).jsonPrimitive.long)
        assertEquals("😀", evaluate(op("SUBSTRING", literal("a😀b"), literal(1), literal(3)), "STRING").jsonPrimitive.content)
        assertEquals("x\$x\$", evaluate(op("REPLACE", literal("ab.ab."), literal("ab."), literal("x$")), "STRING").jsonPrimitive.content)
        assertEquals(" ", evaluate(op("CONCAT", literal(""), literal(" ")), "STRING").jsonPrimitive.content)
    }

    @Test fun `planned field comparisons compose into boolean conditions`() {
        val context = mapOf("callStatus" to JsonPrimitive("TERMINAL"), "lineDirection" to JsonPrimitive("UP"))
        fun evaluate(raw: String, type: String) = ProgramExpressions.evaluate(expr(raw), type, context = context)

        assertEquals("TERMINAL", evaluate(op("PLAN_CALL_STATUS"), "STRING").jsonPrimitive.content)
        assertTrue(evaluate(op("EQUALS", op("PLAN_CALL_STATUS"), literal("TERMINAL")), "BOOLEAN").jsonPrimitive.boolean)
        assertFalse(evaluate(op("EQUALS", op("PLAN_CALL_STATUS"), literal("STOP")), "BOOLEAN").jsonPrimitive.boolean)
        assertTrue(evaluate(op("AND",
            op("EQUALS", op("PLAN_CALL_STATUS"), literal("TERMINAL")),
            op("NOT_EQUALS", op("PLAN_LINE_DIRECTION"), literal("DOWN"))), "BOOLEAN").jsonPrimitive.boolean)
        assertTrue(evaluate(op("NOT", op("EQUALS", op("PLAN_LINE_DIRECTION"), literal("DOWN"))), "BOOLEAN").jsonPrimitive.boolean)
        assertFalse(evaluate(op("OR",
            op("EQUALS", op("PLAN_LINE_DIRECTION"), literal("DOWN")),
            op("EQUALS", op("PLAN_CALL_STATUS"), literal("STOP"))), "BOOLEAN").jsonPrimitive.boolean)

        assertThrows(RuntimeException::class.java) { ProgramExpressions.evaluate(expr(op("PLAN_CALL_STATUS")), "STRING") }
        assertThrows(RuntimeException::class.java) { evaluate(op("EQUALS", literal("a"), literal("b")), "STRING") }
    }

    @Test fun `bound numeric and text parameters compose into display object and waits`() {
        val definition = Json.parseToJsonElement("""{"parameters":[{"id":"seconds","type":"NUMBER"},{"id":"station","type":"STRING"}],"steps":[
            {"id":"display","type":"SET_DISPLAY","values":{"object":{"destination":${op("CONCAT", literal("Next: "), """{"parameterId":"station"}""")},"count":${op("ADD", literal(1), literal(2))}}}},
            {"id":"wait","type":"WAIT_DURATION","durationTicks":${op("MULTIPLY", """{"parameterId":"seconds"}""", literal(20))}}
        ]}""")
        val expanded = expandStationProgram(definition, buildJsonObject { put("seconds", 1.5); put("station", "central") }, 0)
        assertEquals("Next: central", expanded.steps[0].action!!.displayValues.getValue("destination").jsonPrimitive.content)
        assertEquals(3L, expanded.steps[0].action!!.displayValues.getValue("count").jsonPrimitive.long)
        assertEquals(30L, expanded.steps[1].action!!.stopTicks)
    }

    @Test fun `numeric parameters retain numeric sink validation and strings allow empty values`() {
        assertEquals(20L, ProgramExpressions.evaluate(expr("""{"parameterId":"n"}"""), "DURATION", mapOf("n" to "NUMBER"), buildJsonObject { put("n",20.0) }).jsonPrimitive.long)
        assertEquals("central@1", ProgramExpressions.evaluate(expr("""{"parameterId":"name"}"""), "STRING", mapOf("name" to "STRING"), buildJsonObject { put("name","central@1") }).jsonPrimitive.content)
        assertEquals(0L, evaluate(literal(0), "DURATION").jsonPrimitive.long)
        assertEquals("", ProgramExpressions.evaluate(expr("""{"parameterId":"name"}"""), "STRING", mapOf("name" to "STRING"), buildJsonObject { put("name","") }).jsonPrimitive.content)
    }

    @Test fun `zero duration parameter is valid for arithmetic but not a final wait even in unselected branches`() {
        fun definition(expression: String, branched: Boolean = false): JsonElement {
            val wait = """{"id":"wait","type":"WAIT_DURATION","durationTicks":$expression}"""
            val step = if (branched) """{"id":"branch","type":"BRANCH","condition":{"operation":{"type":"EQUALS","operands":[{"operation":{"type":"PLAN_DUTY_ID","operands":[]}},{"value":"duty"}]}},"thenSteps":[],"elseSteps":[$wait]}""" else wait
            return Json.parseToJsonElement("""{"parameters":[{"id":"d","type":"DURATION"}],"steps":[$step]}""")
        }
        val args = buildJsonObject { put("d", 0) }
        val reference = """{"parameterId":"d"}"""
        val result = expandStationProgram(definition(op("ADD", reference, literal(1))), args, 0)
        assertEquals(1L, result.steps.single().action!!.stopTicks)
        for (branched in listOf(false, true)) {
            val failure = assertThrows(RuntimeException::class.java) {
                expandStationProgram(definition(reference, branched), args, 0, mapOf("dutyId" to JsonPrimitive("duty")))
            }
            assertTrue(failure.message!!.contains("step:wait"))
        }
    }

    @Test fun `pure errors in unselected branches are rejected with nested source identity`() {
        val definition = Json.parseToJsonElement("""{"parameters":[],"steps":[{"id":"branch","type":"BRANCH",
          "condition":{"operation":{"type":"EQUALS","operands":[{"operation":{"type":"PLAN_DUTY_ID","operands":[]}},{"value":"duty"}]}},
          "thenSteps":[],"elseSteps":[{"id":"bad","type":"WAIT_DURATION","durationTicks":${op("DIVIDE",literal(1),literal(0))}}]}]}""")
        val failure = assertThrows(RuntimeException::class.java) { expandStationProgram(definition, JsonObject(emptyMap()), 0, mapOf("dutyId" to JsonPrimitive("duty"))) }
        assertTrue(failure.message!!.contains("step:bad"))
    }

    @Test fun `shared station program compiles nested wait and display expressions`() {
        val options = Json.parseToJsonElement("""{"stationId":"s","callId":"c","callRole":"STOP","departureTick":100,"stationProgram":{
          "preparationLead":{"value":10},"arrival":{"parameters":[],"steps":[
            {"id":"wait","type":"WAIT_DURATION","durationTicks":${op("MULTIPLY", literal(2), literal(20))}},
            {"id":"display","type":"SET_DISPLAY","values":{"object":{"text":${op("CONCAT",literal("A"),literal("B"))}}}},
            {"id":"end","type":"END"}]},"departurePreparation":{"parameters":[],"steps":[{"id":"end","type":"END"}]}}}""").jsonObject
        val result = compileStationStopPrograms("s@1", options)!!
        assertEquals(40L, result.arrival[0].durationTicks)
        assertEquals("AB", result.arrival[1].values.getValue("text").jsonPrimitive.content)
        val invalidWait = Json.parseToJsonElement("""{"id":"branch","type":"BRANCH",
          "condition":{"operation":{"type":"EQUALS","operands":[{"operation":{"type":"PLAN_DUTY_ID","operands":[]}},{"value":"duty"}]}},"thenSteps":[],
          "elseSteps":[{"id":"invalidWait","type":"WAIT_DURATION","durationTicks":{"value":0}}]}""")
        val invalid = JsonObject(options + ("plannedContext" to buildJsonObject { put("dutyId", "duty") }) +
            ("stationProgram" to JsonObject(options.getValue("stationProgram").jsonObject +
                ("arrival" to buildJsonObject { put("parameters", JsonArray(emptyList())); put("steps", JsonArray(listOf(invalidWait, Json.parseToJsonElement("""{"id":"end","type":"END"}""")))) }))))
        val failure = assertThrows(RuntimeException::class.java) { compileStationStopPrograms("s@1", invalid) }
        assertTrue(failure.message!!.contains("step:invalidWait"))
    }

    @Test fun `one string parameter composes into destination station depot and display fields`() {
        val reference = """{"parameterId":"id"}"""
        val destination = op("CONCAT", reference, literal("@1"))
        val definition = Json.parseToJsonElement("""{"parameters":[{"id":"id","type":"STRING"}],"steps":[
          {"id":"display","type":"SET_DISPLAY","values":{"object":{"text":$reference}}},
          {"id":"move","type":"MOVE","destinationId":$destination,"viaIds":{"value":[]}},
          {"id":"select","type":"SELECT_STOP","stationId":$reference,"berthIds":{"value":["berth"]},"selectionPolicy":"FIXED_PREFERENCE"},
          {"id":"store","type":"STORE_AT_DEPOT","depotId":$reference,"stablingPointIds":{"value":[]}}
        ]}""")
        val actions = expandStationProgram(definition, buildJsonObject { put("id", "central") }, 0).steps.map { it.action!! }
        assertEquals(listOf(SavedTimetableActionTypeV1.SET_DISPLAY, SavedTimetableActionTypeV1.DESTINATION,
            SavedTimetableActionTypeV1.SELECT_STOP, SavedTimetableActionTypeV1.STORE_AT_DEPOT), actions.map { it.type })
        assertEquals("central", actions[0].displayValues.getValue("text").jsonPrimitive.content)
        assertEquals(listOf("central@1"), actions[1].wayPointIds)
        assertEquals("central", actions[2].stationId)
        assertEquals(listOf(SavedStationStopCandidateV1("berth", 0)), actions[2].berthCandidates)
        assertEquals(SavedStationStopSelectionPolicyV1.FIXED_PREFERENCE, actions[2].selectionPolicy)
        assertEquals("central", actions[3].depotId)
        assertTrue(actions[3].stablingPointIds.isEmpty())
        for (text in listOf("", " ", "x".repeat(8192))) {
            assertEquals(text, ProgramExpressions.evaluate(expr(reference), "STRING", mapOf("id" to "STRING"), buildJsonObject { put("id", text) }).jsonPrimitive.content)
        }
    }

    @Test fun `string identifiers are checked at each action including unselected branch sinks`() {
        val operations = listOf(
            """{"id":"bad","type":"MOVE","destinationId":{"parameterId":"id"},"viaIds":{"value":[]}}""",
            """{"id":"bad","type":"SELECT_STOP","stationId":{"parameterId":"id"},"berthIds":{"value":["berth"]},"selectionPolicy":"FIXED_PREFERENCE"}""",
            """{"id":"bad","type":"STORE_AT_DEPOT","depotId":{"parameterId":"id"},"stablingPointIds":{"value":[]}}""")
        for (step in operations) for (text in listOf("", " ", "x".repeat(257))) {
            for (branched in if (step.contains("STORE_AT_DEPOT")) listOf(false) else listOf(false, true)) {
                val body = if (!branched) step else """{"id":"parent","type":"BRANCH","condition":{"operation":{"type":"EQUALS","operands":[{"operation":{"type":"PLAN_DUTY_ID","operands":[]}},{"value":"duty"}]}},"thenSteps":[],"elseSteps":[$step]}"""
                val definition = Json.parseToJsonElement("""{"parameters":[{"id":"id","type":"STRING"}],"steps":[$body]}""")
                val failure = assertThrows(RuntimeException::class.java) {
                    expandStationProgram(definition, buildJsonObject { put("id", text) }, 0, mapOf("dutyId" to JsonPrimitive("duty")))
                }
                assertTrue(failure.message.orEmpty().contains("step:bad"), failure.message)
            }
        }
        for (text in listOf("", "x".repeat(257))) assertThrows(RuntimeException::class.java) {
            validateProgramLiteral(JsonArray(listOf(JsonPrimitive(text))), "ID_LIST")
        }
        assertDoesNotThrow { validateProgramLiteral(JsonArray(listOf(JsonPrimitive("x".repeat(256)))), "ID_LIST") }
    }

    @Test fun `invalid numbers shapes arity budgets and types fail before execution`() {
        val bad = listOf(op("DIVIDE", literal(1), literal(0)), op("MODULO", literal(1), literal(0)),
            op("MULTIPLY", literal(9_007_199_254_740_991L), literal(2)), op("ADD", literal(1)),
            op("TO_NUMBER", literal("0xff")), op("TO_NUMBER", literal("")), op("TO_NUMBER", literal("1e999")),
            op("ADD", literal("2"), literal(1)), """{"value":1,"parameterId":"x"}""")
        bad.forEach { raw -> assertThrows(RuntimeException::class.java, { evaluate(raw) }, raw) }
        assertThrows(RuntimeException::class.java) { evaluate(op("SUBSTRING", literal("ab"), literal(2), literal(1)), "STRING") }
        assertThrows(RuntimeException::class.java) { evaluate(op("REPLACE", literal("ab"), literal(""), literal("x")), "STRING") }
        assertThrows(RuntimeException::class.java) { evaluate(op("CONCAT",literal("a".repeat(8192)),literal("b")), "STRING") }
        assertThrows(RuntimeException::class.java) { evaluate(op("ADD", literal(0.5), literal(1)), "DURATION") }
        assertEquals("", evaluate(op("CONCAT", literal(""), literal("")), "STRING").jsonPrimitive.content)
        assertThrows(RuntimeException::class.java) { evaluate("""{"object":{"x":{"trainInfo":{"name":"MODEL"}}}}""", "JSON_OBJECT") }
        var nested = literal(1)
        repeat(17) { nested = op("FLOOR", nested) }
        assertThrows(RuntimeException::class.java) { evaluate(nested) }
    }
}
