import {describe,it,expect} from 'vitest';
import {authoringDocumentForSave,emptyAuthoringDocument,generateTimetableSource,copyRunShifted,type TimetableAuthoringDocumentV1} from './authoring';
import {defaultStationStopProgram,resolveEventWaits,validateStopProgram} from './stationPlans';
import {parseAuthoringRecovery} from './authoringRecovery';
import {definitionToBlocks,blocksToDefinition} from './programBlocks';
import type {StationProgramDefinition,StationProgramRecord} from './types';
const document=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),id:'tt',diagramLines:[{id:'L',upStationIds:['a','b'],downStationIds:['b','a']}],runs:[{id:'r',type:'PASSENGER',lineId:'L',diagramLineId:'L',lineDirection:'UP',calls:[{id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},{id:'b',stationId:'b',targetId:'b',status:'TERMINAL',arrivalTick:200}]}],stationPrograms:[defaultStationStopProgram('b')],duties:[{id:'d',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'e',type:'RUN',runId:'r'}]}]});
const lines=[{id:'L',displayName:'Line',upStationIds:['a','b'],downStationIds:['b','a'],maximumSpeedMetersPerSecond:20}];
describe('shared station programs',()=>{
 it('saves station definitions once and supplies independent call role/context to compilation',()=>{
  const value=document(),result=generateTimetableSource(value,lines);
  expect(result.issues).toEqual([]);expect(result.source).toContain('beginStationStop');expect(result.source).toContain('stationProgram');expect(result.source).toContain('TERMINAL');
  expect(authoringDocumentForSave(value).stationPrograms).toEqual(value.stationPrograms);
 });
 it('allows reversal but forbids movement and requires END on each main path',()=>{
  const shared=defaultStationStopProgram('b');shared.arrival.steps.unshift({id:'reverse',type:'REVERSE'});expect(validateStopProgram(shared,[],24000)).toEqual([]);
  shared.arrival.steps.unshift({id:'move',type:'MOVE',destinationId:{value:'c'},viaIds:{value:[]}});expect(validateStopProgram(shared,[],24000).some((e)=>e.includes('Unsupported'))).toBe(true);
  shared.arrival={parameters:[],steps:[]};expect(validateStopProgram(shared,[],24000)).toContain('Every main path must end with END');
 });
 it('copies runs without cloning or shifting shared station definitions',()=>{
  const value=document(),copy=copyRunShifted(value.runs[0],'copy',100);expect(copy.calls[1].arrivalTick).toBe(300);expect(value.stationPrograms![0]).toEqual(defaultStationStopProgram('b'));
 });
 it('round trips nested nonblocking programs and rejects END inside child tasks',()=>{
  const definition:StationProgramDefinition={parameters:[],steps:[{id:'async',type:'ASYNC',taskId:'broadcast',steps:[{id:'delay',type:'WAIT_DURATION',durationTicks:{value:20}}]},{id:'wait',type:'AWAIT',taskId:'broadcast'},{id:'end',type:'END'}]};
  expect(blocksToDefinition(definitionToBlocks(definition),[],'arrival')).toEqual(definition);
  const shared=defaultStationStopProgram('b');shared.arrival=definition;expect(validateStopProgram(shared,[],24000)).toEqual([]);
  if(definition.steps[0].type==='ASYNC')definition.steps[0].steps.push({id:'bad-end',type:'END'});expect(validateStopProgram(shared,[],24000)).toContain('END is not allowed in a nonblocking task');
 });
 it('resolves event references recursively and refuses stale event ids or out-of-period offsets',()=>{
  const value:StationProgramDefinition={parameters:[],steps:[{id:'wait',type:'WAIT_EVENT',eventId:'turn',offsetTicks:-20}]};
  expect(resolveEventWaits(value,[{id:'turn',stationId:'b',tick:200}],24000).steps[0]).toEqual({id:'wait',type:'WAIT_UNTIL',tick:{value:180}});
  expect(()=>resolveEventWaits(value,[],24000)).toThrow('Unknown event');expect(()=>resolveEventWaits(value,[{id:'turn',stationId:'b',tick:0}],24000)).toThrow('outside');
 });
 it('keeps terminal optional departure and ordered via points in current documents',()=>{
  const value=document();value.runs[0].calls[1].departureTick=240;value.runs[0].calls[1].viaIds=['junction'];const result=generateTimetableSource(value,lines);expect(result.issues).toEqual([]);expect(result.source).toContain('.pass("junction")');expect(result.source).toContain('departureTick');expect(authoringDocumentForSave(value).runs[0].calls[1].departureTick).toBe(240);
 });
 it('restores shared programs while refusing forbidden recovered station actions',()=>{
  const state={version:1,owner:'Alice',world:'world',documentId:'doc',formalScriptName:'tt.js',persistedFormalScriptName:'tt.js',savedDocumentJson:'old',document:document()};
  expect(parseAuthoringRecovery(JSON.stringify(state),'Alice','world')?.document.stationPrograms).toEqual(state.document.stationPrograms);
  state.document.stationPrograms![0].arrival.steps.push({id:'move',type:'MOVE',destinationId:{value:'c'},viaIds:{value:[]}});expect(parseAuthoringRecovery(JSON.stringify(state),'Alice','world')).toBeUndefined();
 });
 it('keeps the editor alive when event references are deleted or shifted outside the period',()=>{
  for(const use of ['station','library'] as const){
   const value=document(),definition:StationProgramDefinition={parameters:[],steps:[{id:'wait-event',type:'WAIT_EVENT',eventId:'missing',offsetTicks:0}]},programs:StationProgramRecord[]=[];
   if(use==='station')value.stationPrograms![0].arrival.steps.unshift(definition.steps[0]);
   if(use==='library'){programs.push({formatVersion:1,id:'library',name:'library',definition});value.duties[0].elements.unshift({id:'library',type:'PROGRAM',tick:0,program:{programId:'library',arguments:{}}});}
   let generated:ReturnType<typeof generateTimetableSource>|undefined;
   expect(()=>{generated=generateTimetableSource(value,lines,programs);}).not.toThrow();expect(generated!.source).toBe('');expect(generated!.issues.some((issue)=>issue.message.includes('Unknown event'))).toBe(true);
  }
 });
});

it('shares task declarations between arrival and preparation but rejects duplicate symbols',()=>{
 const shared=defaultStationStopProgram('b');
 shared.arrival.steps.unshift({id:'launch',type:'ASYNC',taskId:'announcement',steps:[{id:'delay',type:'WAIT_DURATION',durationTicks:{value:20}}]});
 shared.departurePreparation.steps.unshift({id:'join',type:'AWAIT',taskId:'announcement'});
 expect(validateStopProgram(shared,[],24000)).toEqual([]);
 shared.departurePreparation.steps.unshift({id:'duplicate',type:'ASYNC',taskId:'announcement',steps:[]});
 expect(validateStopProgram(shared,[],24000)).toContain('Duplicate task: announcement');
});

it('gates following duty actions with terminal departure and effective arrival dwell',()=>{
 const value=document();value.runs[0].calls[1].departureTick=300;
 value.runs.push({id:'next',type:'PASSENGER',lineId:'L',diagramLineId:'L',lineDirection:'DOWN',calls:[{id:'next-b',stationId:'b',targetId:'b',status:'ORIGIN',departureTick:250},{id:'next-a',stationId:'a',targetId:'a',status:'TERMINAL',arrivalTick:500}]});
 value.duties[0].elements.push({id:'next-run',type:'RUN',runId:'next'});
 expect(generateTimetableSource(value,lines).issues.some((issue)=>issue.message.includes('previous'))).toBe(true);
 value.runs[1].calls[0].departureTick=300;
 expect(generateTimetableSource(value,lines).issues).toEqual([]);
 value.minimumDwellTicks=20;value.arrivalOverrides=[{runId:'r',callId:'b',tick:290}];
 expect(generateTimetableSource(value,lines).issues.some((issue)=>issue.message.includes('previous'))).toBe(true);
});
