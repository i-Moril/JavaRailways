import {describe,it,expect} from 'vitest';
import {emptyAuthoringDocument,generateTimetableSource,type TimetableAuthoringDocumentV1} from './authoring';
import {operationDuration,projectTimetableOperations,resolveDutyTimedOperation,validateTimedOperation} from './timedOperations';
import {projectDutyTimeline} from './dutyTimeline';
import {parseAuthoringRecovery} from './authoringRecovery';
import {referencedTimetableEventIds,saveTimetableEvent,timetableEventUnchanged} from './timetableEvents';
import {resolveProgramSourceTarget} from './programNavigation';
import type {StationProgramRecord,TimetableSimulationResult} from './types';
const programs=():StationProgramRecord[]=>[{formatVersion:1,id:'p',name:'p',definition:{parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{value:5}}]}}];
const fixture=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),id:'test',initialPositions:[{trainName:'one',wayPointId:'a'},{trainName:'two',wayPointId:'b'}],events:[{id:'1',tick:100,program:{programId:'p',arguments:{}},duration:{type:'SIMULATED'}}],duties:[{id:'D',selector:{type:'TRAIN_NAME',name:'one'},elements:[{id:'ref1',type:'EVENT',eventId:'1'}]},{id:'E',selector:{type:'TRAIN_NAME',name:'two'},elements:[{id:'ref2',type:'EVENT',eventId:'1'}]}]});
const simulation=(end2=107):TimetableSimulationResult=>({status:'COMPLETED',simulatedTicks:200,runs:[],tasks:[{serviceId:'D',taskId:'ref1',startTick:102,endTick:107},{serviceId:'E',taskId:'ref2',startTick:102,endTick:end2}]});
describe('timetable-owned timed operations',()=>{
 it('uses local program estimates in both projections without changing execution source or hash',()=>{
  const doc=fixture(),local=programs();local[0].estimatedDurationTicks=30;doc.events![0].duration={type:'PROGRAM'};
  const before=generateTimetableSource(doc,[],local);
  expect(projectDutyTimeline(doc,doc.duties[0],undefined,local)[0]).toMatchObject({endTick:130,durationMode:'PROGRAM'});
  expect(projectTimetableOperations(doc,undefined,local)[0].endTick).toBe(130);
  local[0].estimatedDurationTicks=50;expect(projectTimetableOperations(doc,undefined,local)[0].endTick).toBe(150);
  const after=generateTimetableSource(doc,[],local);expect(after.source).toBe(before.source);expect(after.documentHashInput).toBe(before.documentHashInput);
  local[0].estimatedDurationTicks=undefined;expect(projectTimetableOperations(doc,undefined,local)[0].endTick).toBeUndefined();
  local[0].estimatedDurationTicks=0;expect(projectTimetableOperations(doc,undefined,local)[0].endTick).toBe(100);
 });
 it('validates program durations against the timetable period',()=>{
  const local=programs();local[0].estimatedDurationTicks=24000;
  const operation={id:'',tick:23980,duration:{type:'PROGRAM' as const},program:{programId:'p',arguments:{}}};
  expect(validateTimedOperation(operation,24000,local)).toContain('Program duration crosses the timetable period');
  expect(()=>saveTimetableEvent([],operation,24000,[],undefined,[],local)).toThrow('Invalid event');
  local[0].estimatedDurationTicks=0;
  expect(saveTimetableEvent([],{...operation,tick:23900},24000,[],undefined,[],local)[0].duration).toEqual({type:'PROGRAM'});
  expect(validateTimedOperation({id:'marker',tick:1,duration:{type:'PROGRAM'}},24000,local)).toContain('Program duration requires a program');
 });
 it('executes each reference in its own duty and resolves local program steps',()=>{
  const doc=fixture(),local=programs(),generated=generateTimetableSource(doc,[],local);expect(generated.issues).toEqual([]);
  expect(generated.source.match(/const program0 =/g)).toHaveLength(1);
  for(const [duty,id] of [['D','ref1'],['E','ref2']]){
   expect(generated.source).toContain(`scoped.task("${id}", 100`);
   expect(resolveProgramSourceTarget(doc,`duty:${duty}/element:${id}/call:${id}/program:p/step:wait`,'input',undefined,local)).toMatchObject({dutyId:duty,elementId:id,callId:id,stepId:'wait'});
  }
  doc.duties=[];expect(generateTimetableSource(doc,[],local).source).not.toContain('.program(');
 });
 it('shares completed measurement rules between duty and graph projections',()=>{
  const doc=fixture(),local=programs();
  expect(projectTimetableOperations(doc,simulation(),local)[0]).toMatchObject({owner:'GRAPH',startTick:100,endTick:105,measured:true});
  for(const result of [undefined,{...simulation(),status:'FAILED' as const},simulation(108),{...simulation(),tasks:simulation().tasks!.slice(0,1)},{...simulation(),tasks:[...simulation().tasks!,simulation().tasks![0]]}])expect(projectTimetableOperations(doc,result,local)[0].endTick).toBeUndefined();
  expect(projectDutyTimeline(doc,doc.duties[0],simulation(108),local)[0].endTick).toBe(105);expect(projectDutyTimeline(doc,doc.duties[1],simulation(108),local)[0].endTick).toBe(106);
  doc.duties=[];expect(projectTimetableOperations(doc,simulation(),local)[0].endTick).toBeUndefined();
  expect(operationDuration({...doc.events![0],duration:undefined},undefined,local)).toBe(0);
  expect(operationDuration({...doc.events![0],duration:{type:'MANUAL',ticks:0}},7,local)).toBe(0);
  expect(operationDuration({...doc.events![0],duration:{type:'SIMULATED'}},undefined,local)).toBeUndefined();
  expect(operationDuration({...doc.events![0],duration:{type:'SIMULATED'}},7,local)).toBe(7);
 });
 it('reports missing references and preserves event identity for unreferenced operations',()=>{
  const doc=fixture(),local=programs();doc.events=[];expect(generateTimetableSource(doc,[],local).issues.some((issue)=>issue.message.includes('Missing program operation'))).toBe(true);
  expect(referencedTimetableEventIds(doc,local)).toEqual(['1']);expect(resolveDutyTimedOperation(doc,doc.duties[0].elements[0])).toBeUndefined();
  doc.events=[{id:'1',tick:100}];expect(generateTimetableSource(doc,[],local).issues.some((issue)=>issue.message.includes('Missing program operation'))).toBe(true);
 });
 it('keeps display edits outside execution identity and invalidates timing/arguments',()=>{
  const doc=fixture(),local=programs(),before=generateTimetableSource(doc,[],local);
  Object.assign(doc.events![0],{stationId:'a',label:'hello',duration:{type:'MANUAL',ticks:20}});
  expect(generateTimetableSource(doc,[],local).documentHashInput).toBe(before.documentHashInput);expect(generateTimetableSource(doc,[],local).source).toBe(before.source);
  doc.events![0].tick++;expect(generateTimetableSource(doc,[],local).documentHashInput).not.toBe(before.documentHashInput);
  doc.events![0].tick--;doc.events![0].program!.arguments={x:'new'};expect(generateTimetableSource(doc,[],local).documentHashInput).not.toBe(before.documentHashInput);
 });
 it('shares stale editing guards for the whole operation and recovers ownership',()=>{
  const doc=fixture(),local=programs(),original=doc.events![0];
  for(const change of [{duration:{type:'MANUAL' as const,ticks:2}},{program:{programId:'other',arguments:{}}}])expect(timetableEventUnchanged([{...original,...change}],original)).toBe(false);
  for(const ticks of [-1,1.5,24000])expect(validateTimedOperation({...original,duration:{type:'MANUAL',ticks}},24000,local).length).toBeGreaterThan(0);
  local[0].estimatedDurationTicks=30;doc.events![0].duration={type:'PROGRAM'};doc.duties[0].elements.push({id:'own',type:'PROGRAM',tick:300,program:{programId:'p',arguments:{}},duration:{type:'PROGRAM'}});const state={version:1,owner:'a',world:'w',documentId:'doc',formalScriptName:'a.js',persistedFormalScriptName:'',savedDocumentJson:'{}',document:doc};
  const restored=parseAuthoringRecovery(JSON.stringify(state),'a','w');expect(restored?.document).toEqual(doc);
 });
});
