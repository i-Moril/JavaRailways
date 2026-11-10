package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
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
                t.runSegment({runId:'main',lineId:'A',metadata:{notice:'airport service',custom:[true,null]},diagramLineId:'plot',lineDirection:'UP',calls:[
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
        assertEquals("outbound", restored.operationalTrips.single().id)
        assertEquals(TimetableAction.SetDisplay::class,
            (restored.services.single().steps[1] as work.art1st.javarailways.simpleato.timetable.TimetableStep.At).action::class)
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
