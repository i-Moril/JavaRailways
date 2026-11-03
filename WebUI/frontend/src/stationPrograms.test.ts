import {afterEach,describe,expect,it,vi} from 'vitest';
import {api} from './api';
import {parseProgramJsonDraft} from './ProgramJsonInput';
import {findProgramStep,stationProgramSchedule,programJsonObjectError,validateStationProgram,validateStationProgramCall} from './stationPrograms';
import {emptyAuthoringDocument,generateTimetableSource as generateWithLines,validateAuthoringDocument as validateWithLines,type TimetableAuthoringDocumentV1} from './authoring';
import {parseAuthoringRecovery} from './authoringRecovery';
import type {StationProgramDefinition,StationProgramRecord} from './types';
const infrastructureLines=[{"id": "line", "displayName": "line", "upStationIds": ["a", "b"], "downStationIds": ["b", "a"], "maximumSpeedMetersPerSecond": 20}];
const generateTimetableSource=(document:TimetableAuthoringDocumentV1,programs:StationProgramRecord[]=[])=>generateWithLines(document,infrastructureLines,programs);
const validateAuthoringDocument=(document:TimetableAuthoringDocumentV1,programs:StationProgramRecord[]=[])=>validateWithLines(document,infrastructureLines,programs);
const definition:StationProgramDefinition={parameters:[{id:'target',type:'STRING'}],steps:[{id:'reverse',type:'REVERSE'},{id:'move',type:'MOVE',destinationId:{parameterId:'target'},viaIds:{value:[]}}]};
const record=(estimatedDurationTicks?:number):StationProgramRecord=>({formatVersion:1,id:'turnback',name:'Turnback',definition:JSON.parse(JSON.stringify(definition)),...(estimatedDurationTicks===undefined?{}:{estimatedDurationTicks})});
const call={id:'call-1',programId:'turnback',tick:900,arguments:{target:'b@1'}};
const document=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),diagramLines:[{id:'line',upStationIds:['a','b'],downStationIds:['b','a']}],runs:[
  {id:'up',type:'PASSENGER',lineId:'line',diagramLineId:'line',lineDirection:'UP',calls:[{id:'a',stationId:'a',targetId:'a@1',status:'ORIGIN',departureTick:100},{id:'b',stationId:'b',targetId:'b@1',status:'TERMINAL',arrivalTick:800}]},
  {id:'down',type:'PASSENGER',lineId:'line',diagramLineId:'line',lineDirection:'DOWN',calls:[{id:'c',stationId:'b',targetId:'b@1',status:'ORIGIN',departureTick:1000},{id:'d',stationId:'a',targetId:'a@1',status:'TERMINAL',arrivalTick:1800}]},
],duties:[{id:'duty',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'up',type:'RUN',runId:'up'},{id:'program',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:call.arguments}},{id:'down',type:'RUN',runId:'down'}]}]});
afterEach(()=>vi.unstubAllGlobals());
describe('timetable-owned station programs',()=>{
  it('keeps ordered dynamic stop candidates typed and rejects empty or duplicate choices',()=>{
    const selection:StationProgramDefinition={parameters:[{id:'station',type:'STRING'},{id:'berths',type:'ID_LIST'}],steps:[{id:'stop',type:'SELECT_STOP',stationId:{parameterId:'station'},berthIds:{parameterId:'berths'},selectionPolicy:'AVAILABLE_PRIORITY'}]};
    expect(validateStationProgram(selection,{station:'central',berths:['long','short']})).toEqual([]);
    for(const berths of [[],['long','long']])expect(validateStationProgram(selection,{station:'central',berths}).join(' ')).toContain('distinct nonempty');
    expect(validateStationProgram({...selection,parameters:[{id:'station',type:'NUMBER'},{id:'berths',type:'ID_LIST'}]}).join(' ')).toContain('incompatible');
  });
  it('validates both timed branch arms and rejects conditional fixed obligations',()=>{
    const branch:StationProgramDefinition={parameters:[],steps:[{id:'choose',type:'BRANCH',typeId:'javarailways:plan_run_type',version:1,parameters:{value:{values:['PASSENGER']}},thenSteps:[{id:'late',type:'WAIT_UNTIL',tick:{value:100}},{id:'dwell',type:'WAIT_DURATION',durationTicks:{value:20}}],elseSteps:[{id:'early',type:'WAIT_UNTIL',tick:{value:50}}]}]};
    expect(validateStationProgram(branch)).toEqual([]);
    const dynamic=structuredClone(branch);if(dynamic.steps[0].type==='BRANCH')dynamic.steps[0].typeId='javarailways:train_model';expect(validateStationProgram(dynamic)).toContain('Conditions must use planned timetable context');
    expect(stationProgramSchedule(branch.steps,{},10,24000).earliest).toBe(120);
    expect(findProgramStep(branch.steps,'dwell')?.type).toBe('WAIT_DURATION');
    expect(validateStationProgramCall({definition:branch},{...call,tick:10,arguments:{}},110).join(' ')).toContain('crosses');
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
  it('emits one embedded definition for repeated local calls with quoted arguments and stable attribution',()=>{
    const value=document(),programs=[record()];value.duties[0].elements.splice(2,0,{id:'again',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:call.arguments}});
    const source=generateTimetableSource(value,programs).source;
    expect(validateAuthoringDocument(value,programs).some((issue)=>issue.message.includes('Duplicate program call ID'))).toBe(false);
    expect(source.match(/const program0 =/g)).toHaveLength(1);expect(source.match(/\.program\("turnback", program0/g)).toHaveLength(2);
    expect(source).toContain('duty:duty/element:program/call:program');expect(source).toContain('duty:duty/element:again/call:again');
    value.duties[0].elements.splice(2,0,{id:'program',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:call.arguments}});expect(validateAuthoringDocument(value,programs).some((issue)=>issue.message.includes('Duplicate element ID'))).toBe(true);value.duties[0].elements.splice(2,1);
    value.duties[0].elements[1]={id:'program',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:{target:'x"; injected() //'}}};
    expect(generateTimetableSource(value,programs).source).toContain(`JSON.parse(${JSON.stringify(JSON.stringify({target:'x"; injected() //'}))})`);
  });
  it('retains known run location through nonmoving and consecutive programs',()=>{
    const value=document(),programs=[record()];value.duties[0].elements[1]={id:'program',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:{target:'b@1'}}};
    value.duties[0].elements.splice(2,0,{id:'again',type:'PROGRAM',tick:call.tick,program:{programId:call.programId,arguments:{target:'b@1'}}});
    expect(validateAuthoringDocument(value,programs)).toEqual([]);
    value.runs[1].calls[0].targetId='wrong-origin';expect(validateAuthoringDocument(value,programs).some((issue)=>issue.message.includes('Program location discontinuity'))).toBe(true);
  });
  it('rejects mismatched typed arguments and invalid waits without mutating inputs',()=>{
    const program=record(),before=JSON.stringify(program);
    expect(validateStationProgramCall(program,{...call,arguments:{target:12}},24000).length).toBeGreaterThan(0);
    expect(validateStationProgram({...definition,parameters:[{id:'target',type:'NUMBER'}]}).join(' ')).toContain('incompatible');
    const waiting={...program,definition:{parameters:[],steps:[{id:'wait',type:'WAIT_DURATION' as const,durationTicks:{value:10}},{id:'until',type:'WAIT_UNTIL' as const,tick:{value:24000}}]}};
    expect(validateStationProgramCall(waiting,{...call,arguments:{}},24000).join(' ')).toContain('schedule');
    expect(validateStationProgram({parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{value:0}}]}).length).toBeGreaterThan(0);
    expect(JSON.stringify(program)).toBe(before);
  });
  it('rejects malformed recovery without discarding current empty documents',()=>{
    const recovery={version:1,owner:'Alice',world:'world',documentId:'',formalScriptName:'a.js',persistedFormalScriptName:'',savedDocumentJson:'{}',document:emptyAuthoringDocument()};
    expect(parseAuthoringRecovery(JSON.stringify(recovery),'Alice','world')).toBeDefined();
    expect(validateStationProgram({parameters:[],steps:[{id:'bad',type:'WAIT_UNTIL'}]} as unknown as StationProgramDefinition).length).toBeGreaterThan(0);
  });
  it('binds an entire extension JSON object without recursive parameter substitution',()=>{
    const extension:StationProgramDefinition={parameters:[{id:'payload',type:'JSON_OBJECT'}],steps:[{id:'action',type:'EXTENSION',typeId:'demo:action',version:2,parameters:{parameterId:'payload'}}]};
    const nested={enabled:true,list:[1,0.25,null,{parameterId:'literal-not-a-reference'}],text:'";throw new Error()'};
    expect(validateStationProgram(extension,{payload:nested})).toEqual([]);
    expect(parseProgramJsonDraft(JSON.stringify(nested)).value).toEqual(nested);
    const special=parseProgramJsonDraft('{"__proto__":{"marker":true},"constructor":1}').value!;expect(Object.hasOwn(special,'__proto__')).toBe(true);expect(Object.getOwnPropertyDescriptor(special,'__proto__')?.value).toEqual({marker:true});
    expect(validateStationProgram(extension,{payload:'not-an-object'}).length).toBeGreaterThan(0);
    expect(validateStationProgram({...extension,steps:[{id:'action',type:'EXTENSION',typeId:'not-namespaced',version:0,parameters:{value:{}}}]}).length).toBeGreaterThan(0);
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
  it('uses encoded authenticated transport for timetable program routes',async()=>{
    const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify(record()),{status:200}));vi.stubGlobal('fetch',fetch);
    await api.createTimetableProgram('world/a','doc/1','turnback','Turnback',definition);
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/worlds/world%2Fa/editor-documents/doc%2F1/programs');expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({id:'turnback',name:'Turnback',definition});
    await api.saveTimetableProgram('world/a','doc/1','program/a','Changed',definition);expect(fetch.mock.calls[1][0]).toContain('program%2Fa');expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({name:'Changed',definition});
    await api.deleteTimetableProgram('world/a','doc/1','program/a');expect(fetch.mock.calls[2][0]).toBe('/api/v1/worlds/world%2Fa/editor-documents/doc%2F1/programs/program%2Fa');expect(fetch.mock.calls[2][1].method).toBe('DELETE');
  });
});
