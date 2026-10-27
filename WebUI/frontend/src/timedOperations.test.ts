import {describe,it,expect} from 'vitest';
import {emptyAuthoringDocument,generateTimetableSource,type TimetableAuthoringDocumentV1} from './authoring';
import {projectTimetableOperations,resolveDutyTimedOperation,validateTimedOperation} from './timedOperations';
import {projectDutyTimeline} from './dutyTimeline';
import {parseAuthoringRecovery} from './authoringRecovery';
import {referencedTimetableEventIds,saveTimetableEvent,timetableEventUnchanged} from './timetableEvents';
import {resolveProgramSourceTarget} from './programNavigation';
import type {TimetableSimulationResult} from './types';
const fixture=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),id:'test',events:[{id:'1',tick:100,program:{programId:'p',arguments:{}},duration:{type:'SIMULATED'}}],boundPrograms:[{programId:'p',revision:1,contentIdentity:`sha256:${'a'.repeat(64)}`,definition:{parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{value:5}}]}}],duties:[{id:'D',selector:{type:'TRAIN_NAME',name:'one'},initialPosition:'a',elements:[{id:'ref1',type:'EVENT',eventId:'1'}]},{id:'E',selector:{type:'TRAIN_NAME',name:'two'},initialPosition:'a',elements:[{id:'ref2',type:'EVENT',eventId:'1'}]}]});
const simulation=(end2=107):TimetableSimulationResult=>({status:'COMPLETED',simulatedTicks:200,runs:[],tasks:[{serviceId:'D',taskId:'ref1',startTick:102,endTick:107},{serviceId:'E',taskId:'ref2',startTick:102,endTick:end2}]});
describe('unified timed operation ownership',()=>{
 it('executes each reference in its own duty and uses reference identities for source navigation',()=>{
  const doc=fixture(),generated=generateTimetableSource(doc);expect(generated.issues).toEqual([]);
  expect(generated.source.match(/const program0 =/g)).toHaveLength(1);
  for(const [duty,id] of [['D','ref1'],['E','ref2']]){
   expect(generated.source).toContain(`scoped.task("${id}", 100`);
   expect(resolveProgramSourceTarget(doc,`duty:${duty}/element:${id}/call:${id}/program:p/step:wait`,'input')).toMatchObject({dutyId:duty,elementId:id,callId:id,stepId:'wait'});
  }
  doc.duties=[];expect(generateTimetableSource(doc).source).not.toContain('.program(');
 });
 it('allows matching execution IDs in different duties because task identity is service scoped',()=>{
  const doc=fixture();doc.duties[1].elements[0].id='ref1';
  const generated=generateTimetableSource(doc);expect(generated.issues).toEqual([]);
  expect(generated.source.match(/scoped.task\("ref1", 100/g)).toHaveLength(2);
  const measured:TimetableSimulationResult={status:'COMPLETED',simulatedTicks:200,runs:[],tasks:[{serviceId:'D',taskId:'ref1',startTick:100,endTick:105},{serviceId:'E',taskId:'ref1',startTick:100,endTick:107}]};
  expect(projectDutyTimeline(doc,doc.duties[0],measured)[0].endTick).toBe(105);expect(projectDutyTimeline(doc,doc.duties[1],measured)[0].endTick).toBe(107);
  expect(projectTimetableOperations(doc,measured)[0].endTick).toBeUndefined();
 });
 it('reports missing references and references to pure markers without dropping their identities',()=>{
  const doc=fixture();doc.events=[];expect(generateTimetableSource(doc).issues.some((issue)=>issue.message.includes('Missing program operation'))).toBe(true);
  expect(referencedTimetableEventIds(doc)).toEqual(['1']);expect(resolveDutyTimedOperation(doc,doc.duties[0].elements[0])).toBeUndefined();
  doc.events=[{id:'1',tick:100}];expect(generateTimetableSource(doc).issues.some((issue)=>issue.message.includes('Missing program operation'))).toBe(true);
 });
 it('keeps display edits outside execution identity and invalidates resolved timing/arguments/program changes',()=>{
  const doc=fixture(),before=generateTimetableSource(doc);
  Object.assign(doc.events![0],{stationId:'a',label:'hello',duration:{type:'MANUAL',ticks:20}});
  expect(generateTimetableSource(doc).documentHashInput).toBe(before.documentHashInput);expect(generateTimetableSource(doc).source).toBe(before.source);
  doc.events![0].tick++;expect(generateTimetableSource(doc).documentHashInput).not.toBe(before.documentHashInput);
  doc.events![0].tick--;doc.events![0].program!.arguments={x:'new'};expect(generateTimetableSource(doc).documentHashInput).not.toBe(before.documentHashInput);
 });
 it('projects a graph simulated span only when every reference has the same completed duration',()=>{
  const doc=fixture();expect(projectTimetableOperations(doc,simulation())[0]).toMatchObject({owner:'GRAPH',startTick:100,endTick:105,measured:true});
  for(const result of [undefined,{...simulation(),status:'FAILED' as const},simulation(108),{...simulation(),tasks:simulation().tasks!.slice(0,1)},{...simulation(),tasks:[...simulation().tasks!,simulation().tasks![0]]}])expect(projectTimetableOperations(doc,result)[0].endTick).toBeUndefined();
  expect(projectDutyTimeline(doc,doc.duties[0],simulation(108))[0].endTick).toBe(105);expect(projectDutyTimeline(doc,doc.duties[1],simulation(108))[0].endTick).toBe(106);
  doc.duties=[];expect(projectTimetableOperations(doc,simulation())[0].endTick).toBeUndefined();
 });
 it('shares duration validation and stale editing guards for the whole operation',()=>{
  const doc=fixture(),original=doc.events![0];
  expect(saveTimetableEvent([], {...original,id:'',stationId:undefined},24000,[])[0].stationId).toBeUndefined();
  for(const change of [{duration:{type:'MANUAL' as const,ticks:2}},{program:{programId:'other',arguments:{}}}])expect(timetableEventUnchanged([{...original,...change}],original)).toBe(false);
  for(const ticks of [-1,1.5,24000])expect(validateTimedOperation({...original,duration:{type:'MANUAL',ticks}},24000).length).toBeGreaterThan(0);
 });
 it('recovers graph ownership and duty references without copying or executing unreferenced operations',()=>{
  const document=fixture(),state={version:1,owner:'a',world:'w',documentId:'doc',formalScriptName:'a.js',persistedFormalScriptName:'',savedDocumentJson:'{}',document};
  const restored=parseAuthoringRecovery(JSON.stringify(state),'a','w');expect(restored?.document).toEqual(document);
  document.duties[0].elements=[];expect(document.events).toHaveLength(1);
 });
});
