package work.art1st.javarailways.scriptworker

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StationStopProgramsTest {
    private fun options(arrival: String, preparation: String = """[{"id":"endPrep","type":"END"}]""", role: String = "STOP"): JsonObject = Json.parseToJsonElement("""
        {"stationId":"central","callId":"call-b","callRole":"$role","departureTick":100,
         "plannedContext":{"runType":"PASSENGER","stationId":"central"},
         "stationProgram":{"preparationLeadTicks":10,"arrival":{"parameters":[],"steps":$arrival},
           "departurePreparation":{"parameters":[],"steps":$preparation}}}
    """).jsonObject

    @Test fun `generated origin and destination DSL compile matching stationary wire snapshots`() {
        val source = """
            function main(timetable) {
              timetable.setPeriodDays(1);
              const stationProgram = {arrival:{parameters:[],steps:[{id:'open',type:'OPEN_DOORS'},{id:'endArrival',type:'END'}]},
                departurePreparation:{parameters:[],steps:[{id:'close',type:'CLOSE_DOORS'},{id:'endPrep',type:'END'}]},preparationLeadTicks:10};
              timetable.service('duty','train',t => {
                t.check('A');
                t.beginStationStop('A',{stationId:'s1',callId:'origin',callRole:'ORIGIN',departureTick:100,stationProgram,plannedContext:{dutyId:'duty'}});
                t.at(100).destination('B',{stationId:'s2',callId:'terminal',callRole:'TERMINAL',stationProgram,plannedContext:{dutyId:'duty'}});
              });
            }
        """
        val compiled = Json.decodeFromString<SavedTimetableV1>(executeScript(source, "stop-test"))
        val actions = compiled.services.single().steps.mapNotNull { it.action }
        assertEquals(SavedTimetableActionTypeV1.BEGIN_STATION_STOP, actions.first().type)
        assertEquals("ORIGIN", actions.first().stopPrograms!!.role)
        assertEquals("TERMINAL", actions[1].stopPrograms!!.role)
        val restored = work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec.decode(
            Json.encodeToString(SavedTimetableV1.serializer(), compiled)).restore()
        val origin = (restored.services.single().steps[1] as work.art1st.javarailways.simpleato.timetable.TimetableStep.At).action
            as work.art1st.javarailways.simpleato.timetable.TimetableAction.BeginStationStop
        assertEquals(100L, origin.stopPrograms.plannedDepartureTick)
        assertEquals(work.art1st.javarailways.simpleato.timetable.StationProgramAction.CloseDoors, origin.stopPrograms.departurePreparation.first())
    }

    @Test fun `serial origin preparation with early due time does not regress main schedule`() {
        val source = """
            function main(timetable) {
              timetable.setPeriodDays(1);
              const stationProgram = {arrival:{parameters:[],steps:[{id:'ea',type:'END'}]},
                departurePreparation:{parameters:[],steps:[{id:'ep',type:'END'}]},preparationLeadTicks:150};
              timetable.service('duty','train',t => {
                t.check('A');t.at(100).destination('B');
                t.beginStationStop('B',{stationId:'s',callId:'origin2',callRole:'ORIGIN',departureTick:220,stationProgram});
                t.at(220).destination('A');
              });
            }
        """
        val compiled = Json.decodeFromString<SavedTimetableV1>(executeScript(source, "serial-origin"))
        val origin = compiled.services.single().steps.single { it.action?.type == SavedTimetableActionTypeV1.BEGIN_STATION_STOP }
        assertEquals(100L, origin.tick)
        assertEquals(220L, origin.action!!.stopPrograms!!.plannedDepartureTick)
        assertEquals(150L, origin.action!!.stopPrograms!!.preparationLeadTicks)
    }

    @Test fun `station branches fold from planned properties and retain async order`() {
        val compiled = compileStationStopPrograms("central@1", options("""[
          {"id":"choose","type":"BRANCH","typeId":"javarailways:plan_run_type","version":1,"parameters":{"value":{"values":["PASSENGER"]}},
            "thenSteps":[{"id":"open","type":"OPEN_DOORS"}],"elseSteps":[{"id":"close","type":"CLOSE_DOORS"}]},
          {"id":"launch","type":"ASYNC","taskId":"announcement","steps":[{"id":"delay","type":"WAIT_DURATION","durationTicks":{"value":5}}]},
          {"id":"end","type":"END"}]""", """[{"id":"join","type":"AWAIT","taskId":"announcement"},{"id":"endPrep","type":"END"}]"""))!!
        assertEquals("central@1", compiled.destinationId)
        assertEquals(SavedStationProgramActionType.OPEN_DOORS, compiled.arrival.first().type)
        assertEquals(5L, compiled.arrival[1].steps.single().durationTicks)
        assertEquals("announcement", compiled.departurePreparation.first().taskId)
    }

    @Test fun `dynamic and absent planned context fail at compile time`() {
        for (id in listOf("javarailways:train_model", "javarailways:plan_line_id")) assertThrows(RuntimeException::class.java) {
            compileStationStopPrograms("B", options("""[{"id":"branch","type":"BRANCH","typeId":"$id","version":1,"parameters":{"value":{"values":["x"]}},"thenSteps":[],"elseSteps":[]},{"id":"end","type":"END"}]"""))
        }
    }

    @Test fun `station wait references cannot be forward self or ancestor`() {
        for (actions in listOf(
            """[{"id":"wait","type":"AWAIT","taskId":"future"},{"id":"end","type":"END"}]""",
            """[{"id":"launch","type":"ASYNC","taskId":"self","steps":[{"id":"wait","type":"AWAIT","taskId":"self"}]},{"id":"end","type":"END"}]""",
            """[{"id":"launch","type":"ASYNC","taskId":"t","steps":[{"id":"endChild","type":"END"}]},{"id":"end","type":"END"}]"""
        )) assertThrows(RuntimeException::class.java) { compileStationStopPrograms("B", options(actions)) }
    }

    @Test fun `station entry requires explicit reachable end and rejects movement in either arm`() {
        for (actions in listOf("[]", """[{"id":"e","type":"END"},{"id":"o","type":"OPEN_DOORS"}]""",
            """[{"id":"m","type":"MOVE","destinationId":{"value":"B"},"viaIds":{"value":[]}},{"id":"e","type":"END"}]"""
        )) assertThrows(RuntimeException::class.java) { compileStationStopPrograms("B", options(actions)) }
    }

    @Test fun `origin cannot wait for unused arrival tasks`() {
        assertThrows(RuntimeException::class.java) {
            compileStationStopPrograms("B", options("""[{"id":"a","type":"ASYNC","taskId":"arrivalTask","steps":[]},{"id":"e","type":"END"}]""",
                """[{"id":"wait","type":"AWAIT","taskId":"arrivalTask"},{"id":"ep","type":"END"}]""", "ORIGIN"))
        }
    }
}
