package work.art1st.javarailways.scriptworker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefaultOperationalTripScriptTest {
    private fun run(body: String) = Json.decodeFromString<SavedTimetableV1>(
        executeScript("function main(timetable) { timetable.setPeriodDays(1); $body }", "default-trips"))

    @Test fun `an ordinary run has its own default trip without authored metadata`() {
        val result = run("timetable.service('s','train',t=>{t.check('A');t.runSegment({isInService:true,runId:'main',lineId:'line'},r=>r.at(10).destination('C'));});")
        val trip = result.operationalTrips.single()
        assertEquals("default-run/1:s/4:main", trip.id)
        assertTrue(trip.defaults.isEmpty())
        assertEquals(SavedTimetableOperationalTripSegmentV1("s", "main", emptyList(), true), trip.segments.single())
        assertEquals(1, result.services.single().runSegments.single().startStepIndex)
    }

    @Test fun `consecutive runs remain independent and explicit cross run trip retains authored slots`() {
        val result = run("""
            timetable.operationalTrip('through',{defaults:{destination:'E'},segments:[
              {serviceId:'s',runId:'main',unitSlots:[2]},{serviceId:'s',runId:'branch',unitSlots:[1]}]});
            timetable.service('s','train',t=>{
              t.check('A');
              t.runSegment({isInService:true,runId:'main',lineId:'A'},r=>r.at(10).destination('C'));
              t.at(20).reverse();
              t.runSegment({isInService:true,runId:'branch',lineId:'B'},r=>r.at(30).destination('E'));
            });
        """)
        assertEquals(listOf("through", "default-run/1:s/4:main", "default-run/1:s/6:branch"), result.operationalTrips.map { it.id })
        assertEquals(listOf(listOf(2), listOf(1)), result.operationalTrips.first().segments.map { it.unitSlots })
        assertEquals(JsonPrimitive("E"), result.operationalTrips.first().defaults["destination"])
        assertEquals(listOf("main", "branch"), result.operationalTrips.drop(1).map { it.segments.single().runId })
        assertTrue(result.operationalTrips.drop(1).all { it.defaults.isEmpty() && it.segments.single().unassignedUnits })
    }

    @Test fun `a station program before a plain run begins the same default trip after composition changes`() {
        val result = run("""
            timetable.service('s','train',t=>{
              t.check('A'); t.at(5).reverse();
              t.origin('station-display',p=>p.at(5).setDisplay([],{destination:'C'}));
              t.runSegment({isInService:true,runId:'main',lineId:'line'},r=>r.at(10).destination('C'));
            });
        """)
        val service = result.services.single()
        val trip = result.operationalTrips.single()
        val begin = service.steps[2].action!!
        assertEquals(SavedTimetableActionTypeV1.REVERSE, service.steps[1].action!!.type)
        assertEquals(SavedTimetableActionTypeV1.BEGIN_OPERATIONAL_TRIP, begin.type)
        assertEquals(trip.id, begin.operationalTripId)
        assertEquals(trip.segments.single().runId, begin.runId)
        assertTrue(begin.unitSlots.isEmpty())
        assertEquals(SavedTimetableActionTypeV1.SET_DISPLAY, service.steps[3].action!!.type)
        assertEquals(4, service.runSegments.single().startStepIndex)
        assertEquals(listOf(3, 2), result.compilationTrace.map { it.stepIndex })
    }

    @Test fun `normalization is deterministic idempotent and regenerates after deleting a run`() {
        val result = run("""
            timetable.operationalTrip('default-run/1:s/4:main',{segments:[{serviceId:'s',runId:'main',unitSlots:[1]}]});
            timetable.operationalTrip('default-run/1:s/4:main#1',{segments:[{serviceId:'s',runId:'main',unitSlots:[2]}]});
            timetable.service('s','train',t=>{
              t.runSegment({isInService:true,runId:'main',lineId:'A'},r=>r.at(10).destination('C'));
              t.runSegment({isInService:true,runId:'branch',lineId:'B'},r=>r.at(20).destination('E'));
            });
        """)
        assertEquals(listOf("default-run/1:s/4:main", "default-run/1:s/4:main#1",
            "default-run/1:s/4:main#2", "default-run/1:s/6:branch"), result.operationalTrips.map { it.id })
        assertEquals(result.operationalTrips, normalizeOperationalTrips(result.services, result.operationalTrips))
        val service = result.services.single()
        val kept = service.copy(runSegments = service.runSegments.filter { it.runId == "main" })
        val regenerated = normalizeOperationalTrips(listOf(kept), result.operationalTrips)
        assertEquals(result.operationalTrips.take(3), regenerated)
    }

    @Test fun `malformed generated entries fail rather than disappearing during normalization`() {
        assertThrows(IllegalArgumentException::class.java) {
            SavedTimetableOperationalTripSegmentV1("s", "r", listOf(1), true)
        }
        val dynamic = SavedTimetableOperationalTripSegmentV1("s", "r", emptyList(), true)
        assertThrows(IllegalArgumentException::class.java) {
            SavedTimetableOperationalTripV1("bad", JsonObject(mapOf("destination" to JsonPrimitive("C"))), listOf(dynamic))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedTimetableOperationalTripV1("bad", segments = listOf(dynamic, dynamic))
        }
    }

    @Test fun `public begin and explicit operational trip still require nonempty slots`() {
        for (body in listOf(
            "timetable.service('s','train',t=>{t.beginOperationalTrip('trip','main',[]);t.runSegment({isInService:true,runId:'main',lineId:'A'},r=>r.destination('C'));});",
            "timetable.operationalTrip('trip',{segments:[{serviceId:'s',runId:'main',unitSlots:[]}]});",
            "timetable.operationalTrip('trip',{segments:[{serviceId:'s',runId:'main',unitSlots:[],unassignedUnits:true}]});")) {
            assertThrows(Throwable::class.java) { run(body) }
        }
    }
}
