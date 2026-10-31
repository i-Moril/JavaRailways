package work.art1st.javarailways.scriptworker

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrainInfoCompilerTest {
    private val bindings = """{"name":"NAME","cars":"CAR_COUNT","direction":"DIRECTION"}"""
    private fun definition(steps: String) = Json.parseToJsonElement("""{"parameters":[],"steps":[$steps]}""")
    private fun expand(steps: String) = expandStationProgram(definition(steps), JsonObject(emptyMap()), 20,
        mapOf("dutyId" to JsonPrimitive("d")))
    private fun extension(expression: String) = """{"id":"action","type":"EXTENSION","typeId":"demo:action","version":1,"parameters":$expression}"""
    private fun display(expression: String) = """{"id":"display","type":"SET_DISPLAY","values":$expression}"""
    private fun station(steps: String) = compileStationStopPrograms("A", Json.parseToJsonElement("""
      {"stationId":"s","callId":"c","callRole":"STOP","departureTick":100,"plannedContext":{"dutyId":"d"},
       "stationProgram":{"preparationLeadTicks":10,"arrival":{"parameters":[],"steps":[$steps,{"id":"end","type":"END"}]},
       "departurePreparation":{"parameters":[],"steps":[${extension("""{"trainInfo":$bindings}""")},{"id":"end","type":"END"}]}}}
    """).jsonObject)!!

    @Test fun `explicit source survives worker wire and preserves native action trace`() {
        val result = expand(display("""{"trainInfo":$bindings}""") + "," + extension("""{"trainInfo":$bindings}"""))
        val expected = parseTrainInfo(Json.parseToJsonElement(bindings))
        assertEquals(listOf("display", "action"), result.origins.map { it.programStepId })
        result.steps.forEach { step ->
            assertEquals(20L, step.tick)
            assertEquals(expected, step.action!!.trainInfo)
            assertTrue(step.action.displayValues.isEmpty())
            assertEquals(step, Json.decodeFromString<SavedTimetableStepV1>(Json.encodeToString(SavedTimetableStepV1.serializer(), step)))
        }
        assertTrue(result.steps.last().action!!.extensionCall!!.parameters.isEmpty())
        val stop = station(display("""{"trainInfo":$bindings}""") + "," + extension("""{"trainInfo":$bindings}"""))
        assertEquals(expected, stop.arrival.first().trainInfo)
        assertEquals(expected, stop.arrival[1].trainInfo)
        assertEquals(expected, stop.departurePreparation.first().trainInfo)
        assertEquals(stop, Json.decodeFromString<SavedStationStopPrograms>(Json.encodeToString(SavedStationStopPrograms.serializer(), stop)))
    }

    @Test fun `real JS compilation and runtime restore capture preserve bindings and origins`() {
        val source = """
            function main(timetable) {
              timetable.setPeriodDays(1);
              timetable.service('d', 'train', t => {
                t.check('A');
                t.task('1', 20, task => task.program('info', ${definition(display("""{"trainInfo":$bindings}""") + "," + extension("""{"trainInfo":$bindings}"""))}, {}));
              });
            }
        """
        val compiled = Json.decodeFromString<SavedTimetableV1>(executeScript(source, "train-info"))
        assertTrue(compiled.compilationTrace.any { it.origin.endsWith("program:info/step:display") })
        assertTrue(compiled.compilationTrace.any { it.origin.endsWith("program:info/step:action") })
        val saved = work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec.decode(
            Json.encodeToString(SavedTimetableV1.serializer(), compiled))
        val captured = work.art1st.javarailways.simpleato.persistence.SavedTimetableV1.capture(saved.restore())
        val expected = bindings.let(Json::parseToJsonElement).jsonObject.mapValues { it.value.jsonPrimitive.content }
        for (action in captured.services.single().steps.mapNotNull { it.action }.filter { it.trainInfo.isNotEmpty() }) {
            assertEquals(expected, action.trainInfo.mapValues { it.value.name })
        }
        assertEquals(2, captured.services.single().steps.count { it.action?.trainInfo?.isNotEmpty() == true })
    }

    @Test fun `literal objects and JSON library arguments never become sources`() {
        val literal = """{"trainInfo":{"name":"NAME"}}"""
        assertEquals(Json.parseToJsonElement(literal), expand(extension("""{"value":$literal}""")).steps.single().action!!.extensionCall!!.parameters)
        assertTrue(expand(extension("""{"value":$literal}""")).steps.single().action!!.trainInfo.isEmpty())
        val parameterized = Json.parseToJsonElement("""{"parameters":[{"id":"p","type":"JSON_OBJECT"}],"steps":[${extension("""{"parameterId":"p"}""")}]}""")
        val result = expandStationProgram(parameterized, Json.parseToJsonElement("""{"p":$literal}"""), 0)
        assertEquals(Json.parseToJsonElement(literal), result.steps.single().action!!.extensionCall!!.parameters)
        assertTrue(result.steps.single().action!!.trainInfo.isEmpty())
        assertEquals(Json.parseToJsonElement(literal), station(extension("""{"value":$literal}""")).arrival.first().extensionCall!!.parameters)
    }

    @Test fun `malformed bindings and illegal contexts fail even in unselected branches`() {
        val invalid = listOf("{}", "[]", "null", "{\"x\":\"MODEL\"}", "{\"x\":1}", "{\" \":\"NAME\"}",
            "{\"${"x".repeat(257)}\":\"NAME\"}", (1..65).joinToString(",", "{", "}") { "\"k$it\":\"NAME\"" })
        for (bindings in invalid) {
            val step = extension("""{"trainInfo":$bindings}""")
            assertThrows(RuntimeException::class.java) { expand(step) }
            assertThrows(RuntimeException::class.java) { station(step) }
            val branch = """{"id":"branch","type":"BRANCH","typeId":"javarailways:plan_duty_id","version":1,"parameters":{"value":{"values":["d"]}},"thenSteps":[],"elseSteps":[$step]}"""
            assertTrue(assertThrows(RuntimeException::class.java) { expand(branch) }.message!!.contains("step:action"))
            assertTrue(assertThrows(RuntimeException::class.java) { station(branch) }.message!!.contains("step:action"))
        }
        for (step in listOf(
            """{"id":"bad","type":"WAIT_DURATION","durationTicks":{"trainInfo":$bindings}}""",
            """{"id":"bad","type":"BRANCH","typeId":"javarailways:plan_duty_id","version":1,"parameters":{"trainInfo":$bindings},"thenSteps":[],"elseSteps":[]}"""
        )) {
            assertThrows(RuntimeException::class.java) { expand(step) }
            assertThrows(RuntimeException::class.java) { station(step) }
        }
    }
}
