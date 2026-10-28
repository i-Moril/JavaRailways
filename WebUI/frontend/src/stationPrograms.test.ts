import {afterEach,describe,expect,it,vi} from 'vitest';
import {api} from './api';
import {parseProgramJsonDraft} from './ProgramJsonInput';
import {findProgramStep,stationProgramSchedule,adoptStationProgram,programJsonObjectError,validateStationProgram,validateStationProgramCall} from './stationPrograms';
import {emptyAuthoringDocument,generateTimetableSource as generateWithLines,validateAuthoringDocument as validateWithLines,type TimetableAuthoringDocumentV1} from './authoring';
import {createDocumentHistory,updateDocumentHistory,undoDocumentHistory,redoDocumentHistory} from './documentHistory';
import {parseAuthoringRecovery} from './authoringRecovery';
import type {BoundStationProgram,StationProgramDefinition,StationProgramRecord} from './types';
const infrastructureLines=[{"id": "line", "displayName": "line", "upStationIds": ["a", "b"], "downStationIds": ["b", "a"], "maximumSpeedMetersPerSecond": 20}];
const generateTimetableSource=(document:TimetableAuthoringDocumentV1)=>generateWithLines(document,infrastructureLines);
const validateAuthoringDocument=(document:TimetableAuthoringDocumentV1)=>validateWithLines(document,infrastructureLines);
const definition:StationProgramDefinition={parameters:[{id:'target',type:'DESTINATION'}],steps:[{id:'reverse',type:'REVERSE'},{id:'move',type:'MOVE',destinationId:{parameterId:'target'},viaIds:{value:[]}}]};
const record=(revision=1):StationProgramRecord=>({formatVersion:1,id:'turnback',name:'Turnback',revision,contentIdentity:`sha256:${String(revision).repeat(64)}`,definition:JSON.parse(JSON.stringify(definition))});
const snapshot:BoundStationProgram={programId:'turnback',revision:1,contentIdentity:`sha256:${'1'.repeat(64)}`,definition};
const call={id:'call-1',programId:'turnback',tick:900,arguments:{target:'b@1'}};
const document=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),boundPrograms:[snapshot],diagramLines:[{id:'line',upStationIds:['a','b'],downStationIds:['b','a']}],runs:[
  {id:'up',type:'PASSENGER',lineId:'line',diagramLineId:'line',lineDirection:'UP',calls:[{id:'a',stationId:'a',targetId:'a@1',status:'ORIGIN',departureTick:100},{id:'b',stationId:'b',targetId:'b@1',status:'TERMINAL',arrivalTick:800}]},
  {id:'down',type:'PASSENGER',lineId:'line',diagramLineId:'line',lineDirection:'DOWN',calls:[{id:'c',stationId:'b',targetId:'b@1',status:'ORIGIN',departureTick:1000},{id:'d',stationId:'a',targetId:'a@1',status:'TERMINAL',arrivalTick:1800}]},
],duties:[{id:'duty',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'up',type:'RUN',runId:'up'},{id:'program',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:call.arguments}},{id:'down',type:'RUN',runId:'down'}]}]});
afterEach(()=>vi.unstubAllGlobals());
describe('shared station program bindings',()=>{
  it('keeps ordered dynamic stop candidates typed and rejects empty or duplicate bound choices',()=>{
    const selection:StationProgramDefinition={parameters:[{id:'station',type:'STATION'},{id:'berths',type:'ID_LIST'}],steps:[{id:'stop',type:'SELECT_STOP',stationId:{parameterId:'station'},berthIds:{parameterId:'berths'},selectionPolicy:'AVAILABLE_PRIORITY'}]};
    expect(validateStationProgram(selection,{station:'central',berths:['long','short']})).toEqual([]);
    for(const berths of [[],['long','long']])expect(validateStationProgram(selection,{station:'central',berths}).join(' ')).toContain('distinct nonempty');
    expect(validateStationProgram({...selection,parameters:[{id:'station',type:'DESTINATION'},{id:'berths',type:'ID_LIST'}]}).join(' ')).toContain('incompatible');
    const doc=document();doc.boundPrograms=[{...snapshot,definition:selection}];doc.duties[0].elements[1]={id:'program',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:{station:'central',berths:['long','short']}}};
    const before=JSON.stringify(doc);expect(generateTimetableSource(doc).source).toContain('AVAILABLE_PRIORITY');expect(JSON.stringify(doc)).toBe(before);
  });
  it('validates both timed branch arms and rejects conditional fixed obligations',()=>{
    const branch:StationProgramDefinition={parameters:[],steps:[{id:'choose',type:'BRANCH',typeId:'javarailways:plan_run_type',version:1,parameters:{value:{values:['PASSENGER']}},thenSteps:[{id:'late',type:'WAIT_UNTIL',tick:{value:100}},{id:'dwell',type:'WAIT_DURATION',durationTicks:{value:20}}],elseSteps:[{id:'early',type:'WAIT_UNTIL',tick:{value:50}}]}]};
    expect(validateStationProgram(branch)).toEqual([]);
    const dynamic=structuredClone(branch);if(dynamic.steps[0].type==='BRANCH')dynamic.steps[0].typeId='javarailways:train_model';expect(validateStationProgram(dynamic)).toContain('Conditions must use planned timetable context');
    expect(stationProgramSchedule(branch.steps,{},10,24000).earliest).toBe(120);
    expect(findProgramStep(branch.steps,'dwell')?.type).toBe('WAIT_DURATION');
    expect(validateStationProgramCall({...snapshot,definition:branch},{...call,tick:10,arguments:{}},110).join(' ')).toContain('crosses');
    const first=branch.steps[0];if(first.type!=='BRANCH')throw new Error('fixture');
    expect(validateStationProgram({...branch,steps:[{...first,elseSteps:[{id:'store',type:'STORE_AT_DEPOT',depotId:{value:'b'},stablingPointIds:{value:[]}}]}]}).join(' ')).toContain('cannot be conditional');
  });

  it('accepts only current program actions and parameter types',()=>{
    for(const type of ['COUPLE','SPLIT','UNKNOWN_ACTION']){
      const value=JSON.parse(JSON.stringify({parameters:[],steps:[{id:'action',type,eventId:{value:'event'}}]})) as StationProgramDefinition;
      expect(validateStationProgram(value)).toContain('Unsupported program step');
    }
    const value=JSON.parse('{"parameters":[{"id":"event","type":"FORMATION_EVENT"}],"steps":[]}') as StationProgramDefinition;
    expect(validateStationProgram(value)).toContain('Unknown parameter type: FORMATION_EVENT');
  });

  it('two timetables retain independent immutable snapshots until explicit one-history adoption',()=>{
    const library=record();const first=adoptStationProgram(emptyAuthoringDocument(),library);const second=adoptStationProgram({...emptyAuthoringDocument(),id:'second'},library);
    library.definition.steps.push({id:'wait',type:'WAIT_UNTIL',tick:{value:1000}});library.revision=2;library.contentIdentity=`sha256:${'2'.repeat(64)}`;
    expect(first.boundPrograms![0].definition.steps).toHaveLength(2);expect(second.boundPrograms![0].revision).toBe(1);
    const adopted=adoptStationProgram(first,library);const history=updateDocumentHistory(createDocumentHistory(first),adopted);
    expect(history.past).toHaveLength(1);expect(undoDocumentHistory(history).present).toBe(first);expect(redoDocumentHistory(undoDocumentHistory(history)).present).toBe(adopted);
    expect(second.boundPrograms![0].revision).toBe(1);expect(adoptStationProgram(adopted,library)).toBe(adopted);
  });
  it('emits one embedded definition for repeated calls with quoted arguments and stable call attribution',()=>{
    const value=document();value.duties[0].elements.splice(2,0,{id:'again',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:call.arguments}});
    const source=generateTimetableSource(value).source;
    expect(validateAuthoringDocument(value).some((issue)=>issue.message.includes('Duplicate program call ID'))).toBe(false);
    expect(source.match(/const program0 =/g)).toHaveLength(1);expect(source.match(/\.program\("turnback", program0/g)).toHaveLength(2);
    expect(source).toContain('duty:duty/element:program/call:program');expect(source).toContain('duty:duty/element:again/call:again');
    value.duties[0].elements.splice(2,0,{id:'program',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:call.arguments}});expect(validateAuthoringDocument(value).some((issue)=>issue.message.includes('Duplicate element ID'))).toBe(true);value.duties[0].elements.splice(2,1);
    const before=generateTimetableSource(value);const changed=adoptStationProgram(value,record(2));expect(generateTimetableSource(changed).documentHashInput).not.toBe(before.documentHashInput);
    value.duties[0].elements[1]={id:'program',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:{target:'x"; injected() //'}}};
    expect(generateTimetableSource(value).source).toContain(`JSON.parse(${JSON.stringify(JSON.stringify({target:'x"; injected() //'}))})`);
  });
  it('retains known run location through nonmoving and consecutive programs',()=>{
    const value=document();value.boundPrograms=[{...snapshot,definition:{parameters:[],steps:[{id:'reverse',type:'REVERSE'}]}}];
    value.duties[0].elements[1]={id:'program',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:{}}};
    value.duties[0].elements.splice(2,0,{id:'again',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:{}}});
    expect(validateAuthoringDocument(value)).toEqual([]);
    value.runs[1].calls[0].targetId='wrong-origin';expect(validateAuthoringDocument(value).some((issue)=>issue.message.includes('Program location discontinuity'))).toBe(true);
  });
  it('rejects mismatched typed arguments, invalid waits and schedules without mutating inputs',()=>{
    const before=JSON.stringify(snapshot);expect(validateStationProgramCall(snapshot,{...call,arguments:{target:12}},24000).length).toBeGreaterThan(0);
    expect(validateStationProgram({...definition,parameters:[{id:'target',type:'STRING'}]}).join(' ')).toContain('incompatible');
    const waiting={...snapshot,definition:{parameters:[],steps:[{id:'wait',type:'WAIT_DURATION' as const,durationTicks:{value:10}},{id:'until',type:'WAIT_UNTIL' as const,tick:{value:24000}}]}};
    expect(validateStationProgramCall(waiting,{...call,arguments:{}},24000).join(' ')).toContain('schedule');
    expect(validateStationProgram({parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{value:0}}]}).length).toBeGreaterThan(0);
    expect(JSON.stringify(snapshot)).toBe(before);
  });
  it('rejects malformed new recovery instead of crashing the editor or discarding valid old default-empty documents',()=>{
    const recovery={version:1,owner:'Alice',world:'world',documentId:'',formalScriptName:'a.js',persistedFormalScriptName:'',savedDocumentJson:'{}',document:emptyAuthoringDocument()};
    expect(parseAuthoringRecovery(JSON.stringify(recovery),'Alice','world')).toBeDefined();
    expect(validateStationProgram({parameters:[],steps:[{id:'bad',type:'WAIT_UNTIL'}]} as unknown as StationProgramDefinition).length).toBeGreaterThan(0);
    expect(parseAuthoringRecovery(JSON.stringify({...recovery,document:{...recovery.document,boundPrograms:[{...snapshot,definition:{steps:null}}]}}),'Alice','world')).toBeUndefined();
  });
  it('binds an entire extension JSON object without recursive parameter substitution',()=>{
    const extension:StationProgramDefinition={parameters:[{id:'payload',type:'JSON_OBJECT'}],steps:[{id:'action',type:'EXTENSION',typeId:'demo:action',version:2,parameters:{parameterId:'payload'}}]};
    const nested={enabled:true,list:[1,0.25,null,{parameterId:'literal-not-a-reference'}],text:'";throw new Error()'};
    expect(validateStationProgram(extension,{payload:nested})).toEqual([]);
    expect(parseProgramJsonDraft(JSON.stringify(nested)).value).toEqual(nested);
    const special=parseProgramJsonDraft('{"__proto__":{"marker":true},"constructor":1}').value!;expect(Object.hasOwn(special,'__proto__')).toBe(true);expect(Object.getOwnPropertyDescriptor(special,'__proto__')?.value).toEqual({marker:true});
    expect(validateStationProgram(extension,{payload:'not-an-object'}).length).toBeGreaterThan(0);
    expect(validateStationProgram({...extension,steps:[{id:'action',type:'EXTENSION',typeId:'not-namespaced',version:0,parameters:{value:{}}}]}).length).toBeGreaterThan(0);
    expect(validateStationProgram({...extension,parameters:[],steps:[{id:'action',type:'EXTENSION',typeId:'demo:action',version:1,parameters:{value:{}}}]})).toEqual([]);
  });
  it('keeps JSON data finite and bounded and accepts an empty object rather than empty text',()=>{
    expect(parseProgramJsonDraft('{}').value).toEqual({});
    for(const text of ['', 'null','[]','1','{"n":1e309}','{"n":9007199254740992}'])expect(parseProgramJsonDraft(text).error).toBeTruthy();
    expect(programJsonObjectError({n:NaN})).toBeTruthy();expect(programJsonObjectError({n:Infinity})).toBeTruthy();
    expect(programJsonObjectError({items:Array(4094).fill(null)})).toBe('');expect(programJsonObjectError({items:Array(4095).fill(null)})).toBeTruthy();
    expect(programJsonObjectError({s:'x'.repeat(8192)})).toBe('');expect(programJsonObjectError({s:'x'.repeat(8193)})).toBeTruthy();
    let nested:unknown=null;for(let i=0;i<16;i++)nested={child:nested};expect(programJsonObjectError(nested)).toBe('');expect(programJsonObjectError({child:nested})).toBeTruthy();
    expect(parseProgramJsonDraft('{"n":0.125,"one":1.0,"zero":-0.0}').value).toEqual({n:0.125,one:1,zero:-0});
  });
  it('uses encoded authenticated transport and preserves expected revision conflicts',async()=>{
    const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify(record()),{status:200}));vi.stubGlobal('fetch',fetch);
    await api.createStationProgram('world/a','turnback','Turnback',definition);
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/worlds/world%2Fa/station-programs');expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({id:'turnback',name:'Turnback',definition});
    await api.saveStationProgram('world/a','program/a','Changed',definition,3);expect(fetch.mock.calls[1][0]).toContain('program%2Fa');expect(JSON.parse(fetch.mock.calls[1][1].body).expectedRevision).toBe(3);
    await api.deleteStationProgram('world/a','program/a',4);expect(fetch.mock.calls[2][0]).toBe('/api/v1/worlds/world%2Fa/station-programs/program%2Fa');expect(fetch.mock.calls[2][1].method).toBe('DELETE');expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({expectedRevision:4});
    fetch.mockImplementationOnce(async()=>new Response('{"error":"program revision conflict"}',{status:409}));await expect(api.saveStationProgram('world','turnback','Stale',definition,1)).rejects.toThrow('revision conflict');
  });
});
