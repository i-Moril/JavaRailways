package work.art1st.javarailways.scriptworker

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec
import work.art1st.javarailways.simpleato.timetable.FormationEventValidator
import work.art1st.javarailways.simpleato.timetable.TimetableFormationEvent
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableStep

class FormationEventScriptTest {
    private fun compile(body: String): SavedTimetableV1 = Json.decodeFromString(executeScript(
        "function main(timetable) { timetable.setPeriodDays(1); $body }", "fixed-events"))
    private fun restore(compiled: SavedTimetableV1) = TimetableSnapshotCodec.decode(
        Json.encodeToString(SavedTimetableV1.serializer(), compiled)).restore()
    private val split = """{kind:'SPLIT',coordinatorDutyId:'source',partition:{from:'HEAD',firstUnitCount:1},outputs:[
        {id:'head',group:'FIRST',disposition:{type:'DUTY',dutyId:'source'}},
        {id:'tail',group:'REST',disposition:{type:'DUTY',dutyId:'branch'}}]}"""
    private val duties = """
        timetable.service('source',timetable.takeFromWaypoint('origin',0),t=>{
          t.origin('duty:source/element:split',s=>s.at(100).split('divide'));
          t.at(200).destination('end');
        });
        timetable.service('branch',timetable.takeFromFormationOutput('divide','tail',100),t=>t.at(200).destination('branch-end'));
    """

    @Test fun `real JS fixed split emits typed catalog acquisition and origin trace`() {
        val compiled = compile("$duties timetable.formationEvent('divide',$split);")
        assertEquals(1, compiled.formationEvents.size)
        assertEquals(SavedFormationPartitionFrom.HEAD, compiled.formationEvents.single().partition?.from)
        val runtime = restore(compiled)
        assertTrue(FormationEventValidator.validate(runtime).isEmpty())
        assertEquals(1, (runtime.formationEvents.single() as TimetableFormationEvent.Split).firstUnitCount)
        val acquire = (runtime.services[1].steps.first() as TimetableStep.At).action as TimetableAction.TakeFromFormationOutput
        assertEquals("divide", acquire.eventId)
        assertEquals("tail", acquire.outputId)
        assertEquals("duty:source/element:split", compiled.compilationTrace.single().origin)
    }

    @Test fun `duty formation task uses native split and persists task boundary and handoff`() {
        val compiled = compile("""
          timetable.formationEvent('divide',$split);
          timetable.service('source',timetable.takeFromWaypoint('origin',0),t=>{
            t.origin('duty:source/element:split',s=>s.task('split',100,task=>task.split('divide')));
            t.at(200).destination('end');
          });
          timetable.service('branch',timetable.takeFromFormationOutput('divide','tail',100),t=>t.at(200).destination('branch-end'));
        """)
        val actions = compiled.services.first().steps.drop(1).take(3)
        assertEquals(listOf(SavedTimetableActionTypeV1.TASK_START, SavedTimetableActionTypeV1.SPLIT,
            SavedTimetableActionTypeV1.TASK_END), actions.map { it.action!!.type })
        assertTrue(actions.all { it.tick == 100L })
        assertEquals("divide", actions[1].action!!.eventId)
        assertTrue(FormationEventValidator.validate(restore(compiled)).isEmpty())
        assertTrue(compiled.compilationTrace.all { it.origin == "duty:source/element:split" })
    }

    @Test fun `couple declaration supports two reciprocal duties and explicit hold`() {
        val compiled = compile("""
          timetable.formationEvent('join',{kind:'COUPLE',coordinatorDutyId:'a',approachingDutyId:'a',stationaryDutyId:'b',
            stationaryEnd:'TAIL',rendezvous:{approachDestinationId:'approach',stationaryDestinationId:'standing'},
            maximumApproachSpeedMetersPerSecond:0.5,output:{id:'combined',disposition:{type:'HOLD'}}});
          timetable.service('a',timetable.takeFromWaypoint('approach',0),t=>t.task('join',100,task=>task.couple('join')));
          timetable.service('b',timetable.takeFromWaypoint('standing',0),t=>t.task('join',100,task=>task.couple('join')));
        """)
        assertTrue(FormationEventValidator.validate(restore(compiled)).isEmpty())
        assertEquals(SavedFormationEventKind.COUPLE, compiled.formationEvents.single().kind)
    }

    @Test fun `strict declarations reject unsupported partition fields unknown fields and duplicate ownership`() {
        for (bad in listOf(split.replace("from:'HEAD'", "from:'TAIL'"),
            split.replace("firstUnitCount:1", "firstUnitCount:0"),
            split.replace("dutyId:'branch'", "dutyId:'source'"),
            split.replace("group:'REST'", "group:'FIRST'"),
            split.replace("kind:'SPLIT'", "kind:'SPLIT',physicalTrainId:'secret'"))) {
            assertThrows(Throwable::class.java) { compile("timetable.formationEvent('divide',$bad);") }
        }
        assertThrows(Throwable::class.java) { compile("timetable.formationEvent('divide',$split);timetable.formationEvent('divide',$split);") }
    }

    @Test fun `structural event refs are typed static strings and cannot be runtime branch actions`() {
        assertThrows(Throwable::class.java) { compile("timetable.service('s','train',t=>t.at(5).split({eventId:'divide'}));") }
        assertThrows(Throwable::class.java) { compile("timetable.takeFromFormationOutput('divide',123,5);") }
        assertThrows(Throwable::class.java) { compile("timetable.service('s','train',t=>t.at(5).whenCondition(timetable.predicate('x:y',{}),r=>r.split('divide')));") }
        assertThrows(Throwable::class.java) { compile("timetable.takeFromFormationOutput('divide','tail',-1);") }
    }

    @Test fun `cross layer reciprocal validation rejects missing output and misplaced acquisition`() {
        val wrong = compile("timetable.formationEvent('divide',$split);" + duties.replace("'divide','tail'", "'divide','missing'"))
        assertFalse(FormationEventValidator.validate(restore(wrong)).isEmpty())
        val missing = compile("timetable.formationEvent('divide',$split);" + duties.substringBefore("timetable.service('branch'"))
        assertFalse(FormationEventValidator.validate(restore(missing)).isEmpty())
    }
}
