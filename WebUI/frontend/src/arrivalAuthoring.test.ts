import {describe,it,expect} from 'vitest';
import {authoringDocumentForSave,emptyAuthoringDocument,generateTimetableSource,copyRunShifted,type TimetableAuthoringDocumentV1} from './authoring';
import {validateArrivalProgram} from './stationPrograms';
import {resolveArrivalSourceTarget} from './programNavigation';
import {parseAuthoringRecovery} from './authoringRecovery';
import type {StationProgramDefinition,SavedTimetableV1} from './types';
const program=():StationProgramDefinition=>({parameters:[],steps:[{id:'branch',type:'BRANCH',typeId:'demo:ready',version:1,parameters:{value:{}},thenSteps:[{id:'pause',type:'WAIT_DURATION',durationTicks:{value:20}}],elseSteps:[{id:'display',type:'SET_DISPLAY',unitSlots:{value:[1]},values:{value:JSON.parse('{"__proto__":{"x":1},"destination":"A"}')}}]}]});
const document=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),id:'tt',diagramLines:[{id:'L',upStationIds:['a','b'],downStationIds:['b','a']}],runs:[{id:'r',type:'PASSENGER',lineId:'L',diagramLineId:'L',lineDirection:'UP',calls:[{id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},{id:'b',stationId:'b',targetId:'b',status:'TERMINAL',arrivalTick:200,arrivalProgram:program()}]}],duties:[{id:'d',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'e',type:'RUN',runId:'r'}]}]});
const lines=[{id:'L',displayName:'Line',upStationIds:['a','b'],downStationIds:['b','a'],maximumSpeedMetersPerSecond:20}];
describe('arrival programs in authoring',()=>{
 it('saves and safely generates arrival programs with source scope, without leaking into run metadata',()=>{
  const value=document(),result=generateTimetableSource(value,lines);
  expect(result.issues).toEqual([]);expect(result.source).toContain('arrivalProgram: JSON.parse(');expect(result.source).toContain('/run:r/arrival:b');
  expect(result.source.split('\n').find((line)=>line.includes('scoped.runSegment'))).not.toContain('arrivalProgram');
  expect(authoringDocumentForSave(value).runs[0].calls[1].arrivalProgram).toEqual(program());
 });
 it('rejects recursive route changes, parameters and programs on origin/pass',()=>{
  expect(validateArrivalProgram({parameters:[{id:'p',type:'TICK'}],steps:[]})).toContain('Arrival programs cannot declare parameters');
  const invalid=program();if(invalid.steps[0].type!=='BRANCH')throw Error();invalid.steps[0].thenSteps=[{id:'reverse',type:'REVERSE'}];
  expect(validateArrivalProgram(invalid).some((error)=>error.includes('Unsupported arrival action'))).toBe(true);
  for(const status of ['ORIGIN','PASS'] as const){const value=document();value.runs[0].calls[1].status=status;expect(generateTimetableSource(value,lines).issues.some((issue)=>issue.path.endsWith('arrivalProgram'))).toBe(true);}
  expect(validateArrivalProgram({parameters:[],steps:[]})).toEqual([]);
 });
 it('copies nested arrival steps with fresh IDs and independent values',()=>{
  const original=document().runs[0],copy=copyRunShifted(original,'copy',100),p=copy.calls[1].arrivalProgram!;
  expect(p.steps[0].id).toBe('arrival-copy-1');if(p.steps[0].type!=='BRANCH')throw Error();
  expect(p.steps[0].thenSteps[0].id).toBe('arrival-copy-2');expect(p.steps[0].elseSteps[0].id).toBe('arrival-copy-3');
  expect(original.calls[1].arrivalProgram!.steps[0].id).toBe('branch');expect(p).not.toBe(original.calls[1].arrivalProgram);
 });
 it('shifts absolute arrival wait ticks while preserving relative durations',()=>{
  const run=document().runs[0];run.calls[1].arrivalProgram={parameters:[],steps:[{id:'until',type:'WAIT_UNTIL',tick:{value:220}},{id:'duration',type:'WAIT_DURATION',durationTicks:{value:20}}]};
  const copied=copyRunShifted(run,'copy with spaces',100).calls[1].arrivalProgram!;
  expect(copied.steps[0]).toMatchObject({id:'arrival-copy_with_spaces-1',tick:{value:320}});
  expect(copied.steps[1]).toMatchObject({durationTicks:{value:20}});
  expect(()=>copyRunShifted(run,'negative',-300)).toThrow();
 });
 it('restores arrival definitions but rejects malformed or forbidden recovered programs',()=>{
  const state={version:1,owner:'Alice',world:'world',documentId:'doc',formalScriptName:'tt.js',persistedFormalScriptName:'tt.js',savedDocumentJson:'old',document:document()};
  expect(parseAuthoringRecovery(JSON.stringify(state),'Alice','world')?.document.runs[0].calls[1].arrivalProgram).toEqual(program());
  state.document.runs[0].calls[1].arrivalProgram={parameters:[],steps:[{id:'reverse',type:'REVERSE'}]};
  expect(parseAuthoringRecovery(JSON.stringify(state),'Alice','world')).toBeUndefined();
 });
 it('resolves exact ARRIVAL trace paths and refuses stale input or removed step identities',()=>{
  const value=document(),origin='duty:d/element:e/run:r/arrival:b/step:pause';
  const timetable:SavedTimetableV1={id:'tt',periodDays:1,services:[],compilationTrace:[{serviceId:'d',stepIndex:3,branchPath:[{arm:'ARRIVAL',index:0},{arm:'THEN',index:0}],origin}]};
  expect(resolveArrivalSourceTarget(value,origin,'now')).toEqual({runId:'r',callId:'b',stepId:'pause'});
  expect(resolveArrivalSourceTarget(value,'[tt/d] step#3/arrival[0]/then[0] invalid','now',{input:'now',timetable})?.stepId).toBe('pause');
  expect(resolveArrivalSourceTarget(value,'[tt/d] step#3/arrival[0]/then[0] invalid','now',{input:'old',timetable})).toBeUndefined();
  expect(resolveArrivalSourceTarget(value,origin.replace('step:pause','step:missing'),'now')).toBeUndefined();
  expect(resolveArrivalSourceTarget(value,origin.replace('arrival:b/','arrival:bad/'),'now')).toBeUndefined();
 });
});
