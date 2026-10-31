package work.art1st.javarailways.scriptworker

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StationProgramScriptTest {
    private fun compile(body: String): SavedTimetableV1 = Json.decodeFromString(executeScript("function main(timetable) { timetable.setPeriodDays(1); $body }", "program-test"))
    private val definition = """{parameters:[{id:'target',type:'DESTINATION'}],steps:[
      {id:'until',type:'WAIT_UNTIL',tick:{value:100}},
      {id:'reverse',type:'REVERSE'},
      {id:'move',type:'MOVE',destinationId:{parameterId:'target'},viaIds:{value:['via']}},
      {id:'wait',type:'WAIT_DURATION',durationTicks:{value:20}},
      {id:'store',type:'STORE_AT_DEPOT',depotId:{value:'other-depot'},stablingPointIds:{value:[]}}
    ]}"""

    @Test fun `task boundaries retain empty calls and selected program source provenance`() {
        val result = compile("""
          timetable.service('s','train',t=>{t.check('A');t.origin('duty:s/element:1', scoped=>{
            scoped.task('1',20, task=>task.program('p',{parameters:[],steps:[
              {id:'w',type:'WAIT_UNTIL',tick:{value:40}},
              {id:'d',type:'WAIT_DURATION',durationTicks:{value:7}}]},{}));
          });t.task('2',50,empty=>{});});
        """)
        val steps = result.services.single().steps.drop(1)
        assertEquals(listOf(SavedTimetableActionTypeV1.TASK_START, SavedTimetableActionTypeV1.WAIT_UNTIL,
            SavedTimetableActionTypeV1.STOP, SavedTimetableActionTypeV1.TASK_END,
            SavedTimetableActionTypeV1.TASK_START, SavedTimetableActionTypeV1.TASK_END), steps.map { it.action!!.type })
        assertEquals(listOf(20L,40L,40L,40L,50L,50L), steps.map { it.tick })
        assertEquals("1", steps.first().action!!.taskId)
        assertTrue(result.compilationTrace.any { it.origin == "duty:s/element:1/program:p/step:d" })
        assertThrows(Throwable::class.java) { compile("timetable.service('s','train',t=>{t.task('x',10,c=>{});t.task('x',20,c=>{});});") }
        assertThrows(Throwable::class.java) { compile("timetable.service('s','train',t=>t.task('x',10,c=>c.task('y',10,d=>{})));") }
        assertThrows(Throwable::class.java) { compile("timetable.service('s','train',t=>{t.task('x',30,c=>c.waitUntil(50));t.task('y',40,c=>{});});") }
    }

    @Test fun `display program compiles parameterized slots and values into native timed actions`() {
        val result = compile("""
          timetable.service('s','train',t=>{t.check('A');t.at(20).program('display',{
            parameters:[{id:'units',type:'UNIT_SLOTS'},{id:'text',type:'JSON_OBJECT'}],steps:[
              {id:'set',type:'SET_DISPLAY',unitSlots:{parameterId:'units'},values:{parameterId:'text'},removeKeys:{value:['old']}},
              {id:'reset',type:'RESET_DISPLAY',unitSlots:{value:[2]}}]}, {units:[1,2],text:{destination:'E'}});});
        """)
        assertEquals(listOf(1,2), result.services.single().steps[1].action!!.unitSlots)
        assertEquals(listOf("old"), result.services.single().steps[1].action!!.removeDisplayKeys)
        assertEquals(SavedTimetableActionTypeV1.RESET_DISPLAY, result.services.single().steps.last().action!!.type)
        for (slots in listOf("[]","[0]","[1,1]","['1']")) assertThrows(Throwable::class.java) {
            compile("""timetable.service('s','train',t=>{t.check('A');t.program('p',{parameters:[],steps:[
              {id:'bad',type:'RESET_DISPLAY',unitSlots:{value:$slots}}]},{});});""")
        }
    }

    @Test fun `planned program branches fold and retain selected source addresses`() {
        val result = compile("""
          timetable.service('s','train',t=>{t.check('start');t.at(10).program('branch',{
            parameters:[],steps:[{id:'choose',type:'BRANCH',typeId:'javarailways:plan_duty_id',version:1,parameters:{value:{values:['s']}},
              thenSteps:[{id:'later',type:'WAIT_UNTIL',tick:{value:80}},{id:'yes',type:'EXTENSION',typeId:'demo:action',version:1,parameters:{value:{}}}],
              elseSteps:[{id:'earlier',type:'WAIT_UNTIL',tick:{value:40}}]},
              {id:'after',type:'EXTENSION',typeId:'demo:action',version:1,parameters:{value:{}}}]},{},{dutyId:'s'});});
        """)
        val steps = result.services.single().steps
        assertEquals(listOf(80L,80L,80L), steps.drop(1).map { it.tick })
        assertTrue(steps.none { it.action?.type == SavedTimetableActionTypeV1.CONDITIONAL })
        val child = result.compilationTrace.single { it.origin.endsWith("step:yes") }
        assertEquals(2, child.stepIndex)
        assertTrue(child.branchPath.isEmpty())
        for (fixed in listOf("{id:'fixed',type:'STORE_AT_DEPOT',depotId:{value:'depot'},stablingPointIds:{value:[]}}")) {
            assertThrows(Throwable::class.java) { compile("""
              timetable.service('s','train',t=>{t.check('start');t.program('p',{parameters:[],steps:[
                {id:'branch',type:'BRANCH',typeId:'demo:condition',version:1,parameters:{value:{}},thenSteps:[$fixed],elseSteps:[]}]},{});});
            """) }
        }
    }

    @Test fun `visual station selection emits one timed action with ordered candidates and source trace`() {
        val result = compile("""
          timetable.service('s','train',t=>{t.check('start');t.at(20).program('platform',{
            parameters:[{id:'station',type:'STATION'},{id:'berths',type:'ID_LIST'}],steps:[
              {id:'choose',type:'SELECT_STOP',stationId:{parameterId:'station'},berthIds:{parameterId:'berths'},selectionPolicy:'AVAILABLE_PRIORITY'}]},
            {station:'central',berths:['long','short']});});
        """)
        val step = result.services.single().steps.last()
        assertEquals(2, result.services.single().steps.size)
        assertEquals(20L, step.tick)
        assertEquals(SavedTimetableActionTypeV1.SELECT_STOP, step.action!!.type)
        assertEquals("central", step.action.stationId)
        assertEquals(listOf(SavedStationStopCandidateV1("long", 0), SavedStationStopCandidateV1("short", 1)), step.action.berthCandidates)
        assertEquals(SavedStationStopSelectionPolicyV1.AVAILABLE_PRIORITY, step.action.selectionPolicy)
        assertTrue(result.compilationTrace.single().origin.endsWith("program:platform/step:choose"))
        for (ids in listOf("[]", "['same','same']")) {
            assertThrows(Throwable::class.java) { compile("""
              timetable.service('s','train',t=>{t.check('start');t.program('p',{parameters:[],steps:[
                {id:'choose',type:'SELECT_STOP',stationId:{value:'central'},berthIds:{value:$ids},selectionPolicy:'FIXED_PREFERENCE'}]},{});});
            """) }
        }
    }

    @Test fun `formation operations are not reusable program instructions or parameter types`() {
        for (type in listOf("SPLIT", "COUPLE")) {
            assertThrows(Throwable::class.java) { compile("""
              timetable.service('s','train',t=>t.program('p',{
                parameters:[],steps:[{id:'op',type:'$type',eventId:{value:'event'}}]},{}));
            """) }
        }
        assertThrows(Throwable::class.java) { compile("""
          timetable.service('s','train',t=>t.program('p',{
            parameters:[{id:'event',type:'FORMATION_EVENT'}],steps:[]},{event:'event'}));
        """) }
    }

    @Test fun `embedded sequential program expands exact actions and traces each stable step`() {
        val compiled = compile("""
          const definition = $definition;
          timetable.service('duty', timetable.takeFromDepot('home', 10), t => {
            t.origin('duty:duty/element:call/call:c1', s => s.at(10).program('turnback', definition, {target:'end'}));
          });
        """)
        val actions = compiled.services.single().steps.mapNotNull { it.action }
        assertEquals(listOf(SavedTimetableActionTypeV1.TAKE_FROM_DEPOT, SavedTimetableActionTypeV1.WAIT_UNTIL,
            SavedTimetableActionTypeV1.REVERSE, SavedTimetableActionTypeV1.DESTINATION_OPTIONS,
            SavedTimetableActionTypeV1.DESTINATION, SavedTimetableActionTypeV1.STOP, SavedTimetableActionTypeV1.STORE_AT_DEPOT), actions.map { it.type })
        assertEquals(listOf("via", "end"), actions[4].wayPointIds)
        assertEquals(20L, actions[5].stopTicks)
        assertEquals("other-depot", actions[6].depotId)
        assertEquals(listOf(10L,100L,100L,100L,100L,100L,100L), compiled.services.single().steps.map { it.tick })
        assertEquals("duty:duty/element:call/call:c1/program:turnback/step:move", compiled.compilationTrace.first { it.stepIndex == 4 }.origin)
        assertEquals(6, compiled.compilationTrace.size)
    }

    @Test fun `program refuses missing acquisition wrong parameter type and unsupported structure`() {
        assertThrows(Throwable::class.java) { compile("timetable.service('s','train', t => t.program('p',$definition,{target:'end'}));") }
        assertThrows(Throwable::class.java) { compile("timetable.service('s','train', t => {t.check('start');t.program('p',$definition,{target:12});});") }
        val mistyped = definition.replace("type:'DESTINATION'", "type:'NUMBER'")
        assertThrows(Throwable::class.java) { compile("timetable.service('s','train', t => {t.check('start');t.program('p',$mistyped,{target:'end'});});") }
        for (bad in listOf("{parameters:[],steps:[{id:'x',type:'ACQUIRE'}]}", "{parameters:[],steps:[],unknown:true}", "{parameters:[],steps:[{id:'x',type:'WAIT_DURATION',durationTicks:{value:0}}]}")) {
            assertThrows(Throwable::class.java) { compile("timetable.service('s','train', t => {t.check('start');t.program('p',$bad,{});});") }
        }
    }

    @Test fun `extension object bindings preserve nested literals and existing snapshot action contract`() {
        val compiled = compile("""
          const d={parameters:[{id:'payload',type:'JSON_OBJECT'}],steps:[{id:'action',type:'EXTENSION',typeId:'demo:action',version:2,parameters:{parameterId:'payload'}}]};
          timetable.service('s','train',t=>{t.check('start');t.origin('duty:s/element:e/call:c',s=>s.at(5).program('p',d,{payload:{enabled:true,nested:[null,0.25,{parameterId:'literal'}]}}));});
        """)
        val action=compiled.services.single().steps.last().action!!
        assertEquals(SavedTimetableActionTypeV1.EXTENSION, action.type)
        assertEquals("demo:action", action.extensionCall?.typeId)
        assertEquals(2, action.extensionCall?.version)
        assertEquals("{\"enabled\":true,\"nested\":[null,0.25,{\"parameterId\":\"literal\"}]}", action.extensionCall?.parameters.toString())
        val restored=work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec.decode(kotlinx.serialization.json.Json.encodeToString(SavedTimetableV1.serializer(),compiled)).restore()
        val extension=(restored.services.single().steps.last() as work.art1st.javarailways.simpleato.timetable.TimetableStep.At).action as work.art1st.javarailways.simpleato.timetable.TimetableAction.Extension
        assertEquals(action.extensionCall?.parameters, extension.call.parameters)
        assertEquals("duty:s/element:e/call:c/program:p/step:action", compiled.compilationTrace.single().origin)
        val registry=work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionRegistry()
        assertNull(registry.action(extension.call.typeId,extension.call.version))
    }

    @Test fun `JSON parsed payload preserves special object keys instead of JavaScript prototype syntax`() {
        val result=compile("""
          const d=JSON.parse('{"parameters":[],"steps":[{"id":"action","type":"EXTENSION","typeId":"demo:action","version":1,"parameters":{"value":{"__proto__":{"marker":true},"constructor":1}}}]}');
          timetable.service('s','train',t=>{t.check('start');t.program('p',d,{});});
        """)
        assertEquals("{\"__proto__\":{\"marker\":true},\"constructor\":1}",result.services.single().steps.last().action?.extensionCall?.parameters.toString())
    }

    @Test fun `extension JSON object restrictions reject unsafe integers nonobjects and resource excess`() {
        fun action(payload:String)="timetable.service('s','train',t=>{t.check('start');t.program('p',{parameters:[],steps:[{id:'action',type:'EXTENSION',typeId:'demo:action',version:1,parameters:{value:$payload}}]},{});});"
        assertDoesNotThrow { compile(action("{}")) }
        for (bad in listOf("[]", "null", "{n:Infinity}", "{n:9007199254740992}", "{items:Array(4095).fill(null)}", "{s:'x'.repeat(8193)}")) {
            assertThrows(Throwable::class.java) { compile(action(bad)) }
        }
    }

    @Test fun `failed visual step errors retain current call program and step identity`() {
        val failure=assertThrows(Throwable::class.java) { compile("timetable.service('s','train',t=>{t.check('start');t.origin('duty:s/element:e/call:c',s=>s.program('p',{parameters:[],steps:[{id:'bad',type:'WAIT_DURATION',durationTicks:{value:0}}]},{}));});") }
        assertTrue(failure.message.orEmpty().contains("duty:s/element:e/call:c/program:p/step:bad"),failure.message)
    }

    @Test fun `nested visual failure points to failing child instead of enclosing branch`() {
        val failure = assertThrows(Throwable::class.java) { compile("""
          timetable.service('s','train',t=>{t.check('start');
            t.origin('duty:s/element:e/call:c',s=>s.program('p',{parameters:[],steps:[
              {id:'parent',type:'BRANCH',typeId:'javarailways:plan_duty_id',version:1,parameters:{value:{values:['s']}},
                thenSteps:[{id:'child',type:'WAIT_DURATION',durationTicks:{value:0}}],elseSteps:[]}
            ]},{},{dutyId:'s'}));});
        """) }
        assertTrue(failure.message.orEmpty().contains("duty:s/element:e/call:c/program:p/step:child"), failure.message)
        assertFalse(failure.message.orEmpty().contains("program:p/step:parent"), failure.message)
    }

    @Test fun `timed program builder keeps its captured tick and rejects nullable extra expression keys`() {
        val result = compile("timetable.service('s','train',t=>{t.check('start');const earlier=t.at(5);t.at(20);earlier.program('p',{parameters:[],steps:[{id:'reverse',type:'REVERSE'}]},{});});")
        assertEquals(5L, result.services.single().steps.last().tick)
        assertThrows(Throwable::class.java) { compile("timetable.service('s','train',t=>{t.check('start');t.program('p',{parameters:[],steps:[{id:'wait',type:'WAIT_UNTIL',tick:{value:5,parameterId:null}}]},{});});") }
    }

    @Test fun `direct absolute wait emits real gate and bad expansion appends no partial actions`() {
        val compiled = compile("timetable.service('s','train', t => {t.check('start');t.waitUntil(100);t.reverse();});")
        assertEquals(SavedTimetableActionTypeV1.WAIT_UNTIL, compiled.services.single().steps[1].action?.type)
        val caught = compile("""
          timetable.service('s','train', t => {
            t.check('start');
            try {t.program('p',{parameters:[],steps:[{id:'one',type:'REVERSE'},{id:'bad',type:'WAIT_DURATION',durationTicks:{value:0}}]},{});}catch(error){}
            t.at(200).destination('end');
          });
        """)
        assertFalse(caught.services.single().steps.any { it.action?.type == SavedTimetableActionTypeV1.REVERSE })
        val elapsed = compile("timetable.service('s','train', t => {t.check('start');t.at(100).program('p',{parameters:[],steps:[{id:'x',type:'WAIT_UNTIL',tick:{value:50}},{id:'r',type:'REVERSE'}]},{});});")
        assertEquals(listOf(100L,100L), elapsed.services.single().steps.drop(1).map { it.tick })
    }
}
