package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.timetable.TimetableServiceActivation
import work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.scriptworker.executeScript

class ScriptWorkerCompatibilityTest {
    @Test
    fun `worker segmented trips and native display actions decode through authoritative codec`() {
        val output = executeScript("""
            function main(timetable) {
              timetable.setPeriodDays(1);
              timetable.operationalTrip('outbound',{defaults:{destination:'E'},segments:[{serviceId:'s',runId:'main',unitSlots:[1]}]});
              timetable.service('s','train',t=>{
                t.check('A');
                t.runSegment({isInService:true,runId:'main',lineId:'A',metadata:{notice:'airport service',custom:[true,null]},diagramLineId:'plot',lineDirection:'UP',calls:[
                  {id:'a',stationId:'A',destinationId:'A@1',status:'ORIGIN',departureTick:10},
                  {id:'c',stationId:'C',destinationId:'C@1',status:'TERMINAL',arrivalTick:20}]},r=>{
                    r.at(10).setDisplay([1],{kind:'rapid'},['old']);r.at(20).destination('C@1');r.resetDisplay([1]);
                });
              });
            }
        """.trimIndent(), "through")
        val saved = TimetableSnapshotCodec.decode(output)
        val restored = saved.restore()
        assertEquals("main", restored.services.single().runSegments.single().runId)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("airport service"), restored.services.single().runSegments.single().metadata["notice"])
        assertEquals(1, restored.services.single().runSegments.single().startStepIndex)
        assertEquals(listOf("outbound", "default-run/1:s/4:main"), restored.operationalTrips.map { it.id })
        assertEquals(true, restored.operationalTrips.last().segments.single().unassignedUnits)
        assertEquals(TimetableAction.SetDisplay::class,
            (restored.services.single().steps[1] as work.art1st.javarailways.simpleato.timetable.TimetableStep.At).action::class)
    }

    @Test
    fun `worker defaults and collision ids survive authoritative normalization capture and copy`() {
        val output = executeScript("""
            function main(timetable) {
              timetable.setPeriodDays(1);
              timetable.operationalTrip('default-run/1:s/4:main',{defaults:{destination:'Authored'},segments:[{serviceId:'s',runId:'main',unitSlots:[1]}]});
              timetable.service('s','train',t=>{
                t.check('A'); t.at(5).setDisplay([],{kind:'rapid'});
                t.runSegment({isInService:true,runId:'main',lineId:'A'},r=>r.at(10).destination('C'));
                t.runSegment({isInService:false,runId:'branch',lineId:'B'},r=>r.at(20).destination('E'));
              });
            }
        """.trimIndent(), "defaults")
        val saved = TimetableSnapshotCodec.decode(output)
        val restored = saved.restore()
        val captured = work.art1st.javarailways.simpleato.persistence.SavedTimetableV1.capture(restored)
        assertEquals(listOf(true, false), saved.services.single().runSegments.map { it.isInService })
        assertEquals(listOf(true, false), restored.services.single().runSegments.map { it.isInService })
        assertEquals(saved.services.single().runSegments, captured.services.single().runSegments)
        assertEquals(listOf("default-run/1:s/4:main", "default-run/1:s/4:main#1", "default-run/1:s/6:branch"),
            saved.operationalTrips.map { it.id })
        assertEquals(saved.operationalTrips, captured.operationalTrips)
        assertEquals(saved.operationalTrips, work.art1st.javarailways.simpleato.persistence.SavedTimetableV1.capture(restored.copy()).operationalTrips)
        val begins = restored.services.single().steps.filterIsInstance<work.art1st.javarailways.simpleato.timetable.TimetableStep.At>()
            .map { it.action }.filterIsInstance<TimetableAction.BeginOperationalTrip>()
        assertEquals(listOf("default-run/1:s/4:main", "default-run/1:s/4:main#1"), begins.map { it.tripId })
        assertEquals(listOf(listOf(1), emptyList<Int>()), begins.map { it.unitSlots })
        assertEquals(4, restored.services.single().runSegments.first().startStepIndex)
    }

    @Test
    fun `worker JSON restores through authoritative Basic snapshot codec`() {
        val output = executeScript("""
            function main(timetable) {
              timetable.setPeriodDays(1)
              const demand = timetable.predicate('demo:demand', { minimum: 1 })
              timetable.service('s1', 'train-1', {
                diagramLineId: 'metro',
                activation: { type: 'conditional', condition: demand, falseBehavior: 'SKIP_CYCLE' }
              }, t => {
                t.check('origin')
                t.at(10).destination('central@one', { minimumDwellTicks: 40, doorSide: 'LEFT' })
                t.at(20).whenCondition(demand, a => a.action('demo:ring', { count: 2 }))
              })
            }
        """.trimIndent(), "demo")

        val saved = TimetableSnapshotCodec.decode(output)
        val restored = saved.restore()

        assertEquals("metro", saved.services.single().diagramLineId)
        assertEquals(
            TimetableServiceActivation.Conditional::class,
            restored.services.single().activation::class
        )
        assertEquals(
            TimetableAction.Conditional::class,
            (restored.services.single().steps.last() as work.art1st.javarailways.simpleato.timetable.TimetableStep.At).action::class
        )
    }
}
