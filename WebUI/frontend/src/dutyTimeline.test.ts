import {describe,it,expect} from 'vitest';
import {emptyAuthoringDocument,generateTimetableSource,type TimetableAuthoringDocumentV1} from './authoring';
import {defaultDutyProgramArguments,projectDutyTimeline,sortDutyTasks} from './dutyTimeline';
import type {StationProgramRecord,TimetableSimulationResult} from './types';
const programs:StationProgramRecord[]=[{formatVersion:1,id:'1',name:'P',definition:{parameters:[],steps:[]}}];
const fixture=():TimetableAuthoringDocumentV1=>({...emptyAuthoringDocument(),id:'test',minimumDwellTicks:10,runs:[{id:'R',type:'PASSENGER',lineDirection:'UP',calls:[{id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:100},{id:'b',stationId:'b',targetId:'b',status:'TERMINAL',arrivalTick:200,departureTick:220}]}],duties:[{id:'D',selector:{type:'TRAIN_NAME',name:'train'},elements:[{id:'r',type:'RUN',runId:'R'},{id:'p',type:'PROGRAM',tick:230,program:{programId:'1',arguments:{}}}]}]});
describe('duty timeline projection',()=>{
 it('uses effective terminal arrival, dwell and departure without mutating the document',()=>{
  const doc=fixture();doc.arrivalOverrides=[{runId:'R',callId:'b',tick:215}];const before=JSON.stringify(doc);
  expect(projectDutyTimeline(doc,doc.duties[0])[0]).toMatchObject({startTick:100,endTick:225});expect(JSON.stringify(doc)).toBe(before);
 });
 it('ends a deadhead run at terminal arrival instead of its later departure tick',()=>{
  const doc=fixture();doc.runs[0]={...doc.runs[0],type:'DEADHEAD'};doc.minimumDwellTicks=400;
  expect(projectDutyTimeline(doc,doc.duties[0])[0]).toMatchObject({startTick:100,endTick:200});
 });
 it('distinguishes point, manual and unknown simulated duration',()=>{
  const doc=fixture(),duty=doc.duties[0],element=duty.elements[1];if(element.type!=='PROGRAM')throw Error();
  expect(projectDutyTimeline(doc,duty)[1]).toMatchObject({endTick:230,durationMode:'POINT'});
  element.duration={type:'MANUAL',ticks:50};expect(projectDutyTimeline(doc,duty)[1].endTick).toBe(280);
  element.duration={type:'SIMULATED'};expect(projectDutyTimeline(doc,duty)[1].endTick).toBeUndefined();
 });
 it('matches completed measurements by service and element, preserves zero durations and rejects missing or failed results',()=>{
  const doc=fixture(),duty=doc.duties[0],element=duty.elements[1];if(element.type!=='PROGRAM')throw Error();element.duration={type:'SIMULATED'};
  const result:TimetableSimulationResult={status:'COMPLETED',simulatedTicks:400,runs:[],tasks:[{serviceId:'D',taskId:'p',startTick:250,endTick:280}]};
  expect(projectDutyTimeline(doc,duty,result)[1]).toMatchObject({startTick:230,endTick:260,measured:true});
  expect(projectDutyTimeline(doc,duty,{...result,status:'FAILED'})[1].endTick).toBeUndefined();
  for(const task of [{serviceId:'D',taskId:'p',startTick:250},{serviceId:'other',taskId:'p',startTick:250,endTick:280}])expect(projectDutyTimeline(doc,duty,{...result,tasks:[task]})[1].endTick).toBeUndefined();
  expect(projectDutyTimeline(doc,duty,{...result,tasks:[{serviceId:'D',taskId:'p',startTick:250,endTick:250}]})[1]).toMatchObject({endTick:230,measured:true});
 });
 it('marks occupancy overlaps, sorts starts and preserves ties',()=>{
  const doc=fixture(),duty=doc.duties[0],element=duty.elements[1];if(element.type!=='PROGRAM')throw Error();element.tick=150;element.duration={type:'MANUAL',ticks:20};
  expect(projectDutyTimeline(doc,duty).map((task)=>task.overlaps)).toEqual([['p'],['r']]);
  duty.elements.reverse();expect(sortDutyTasks(doc,duty).map((e)=>e.id)).toEqual(['r','p']);element.tick=100;expect(sortDutyTasks(doc,duty).map((e)=>e.id)).toEqual(['p','r']);
 });
 it('validates current planning duration boundaries',()=>{
  const doc=fixture(),element=doc.duties[0].elements[1];if(element.type!=='PROGRAM')throw Error();doc.runs=[];doc.duties[0].initialPosition='a';doc.duties[0].elements=[element];
  for(const ticks of [-1,1.5,Number.MAX_SAFE_INTEGER]){element.duration={type:'MANUAL',ticks};expect(generateTimetableSource(doc,[],programs).issues.some((issue)=>issue.message.includes('duration'))).toBe(true);}
  element.duration={type:'MANUAL',ticks:0};expect(generateTimetableSource(doc,[],programs).issues).toEqual([]);
 });
 it('excludes planning duration from generated source and runtime identity',()=>{
  const doc=fixture(),element=doc.duties[0].elements[1];if(element.type!=='PROGRAM')throw Error();
  doc.runs=[];doc.duties[0].initialPosition='a';doc.duties[0].elements=[element];
  const first=generateTimetableSource(doc,[],programs);expect(first.issues).toEqual([]);element.duration={type:'MANUAL',ticks:100};const second=generateTimetableSource(doc,[],programs);
  expect(first.source).toBe(second.source);expect(first.documentHashInput).toBe(second.documentHashInput);expect(first.source).toContain('scoped.task("p", 230, taskScope => taskScope.program');
 });
});

import {orderDutyTimeline} from './dutyTimeline';
it('orders tasks after run time edits in one transaction and preserves equal-time order',()=>{
 const d:TimetableAuthoringDocumentV1={formatVersion:1,id:'t',periodDays:1,diagramLines:[],arrivalOverrides:[],runs:[{id:'r',type:'PASSENGER',lineDirection:'UP',calls:[{id:'a',stationId:'a',targetId:'a',status:'ORIGIN',departureTick:50}]}],duties:[{id:'d',selector:{type:'TRAIN_NAME',name:'t'},elements:[{id:'p',type:'PROGRAM',tick:30,program:{programId:'1',arguments:{}}},{id:'r',type:'RUN',runId:'r'}]}]};
 expect(orderDutyTimeline(d)).toBe(d);
 const edited={...d,runs:[{...d.runs[0],calls:[{...d.runs[0].calls[0],departureTick:10}]}]};
 const ordered=orderDutyTimeline(edited);expect(ordered.duties[0].elements.map(e=>e.id)).toEqual(['r','p']);expect(edited.duties[0].elements.map(e=>e.id)).toEqual(['p','r']);
 const tied={...edited,runs:[{...edited.runs[0],calls:[{...edited.runs[0].calls[0],departureTick:30}]}]};expect(orderDutyTimeline(tied)).toBe(tied);
});

it('initializes numeric program and simulation arguments with valid numeric defaults',()=>{
 expect(defaultDutyProgramArguments([{id:'number',type:'NUMBER'},{id:'duration',type:'DURATION'},{id:'tick',type:'TICK'},{id:'text',type:'STRING'}])).toEqual({number:0,duration:1,tick:0,text:''});
});
it('uses the document minimum for all run endpoints and defaults missing minimum to zero',()=>{
 const doc=fixture();doc.runs[0].calls[1].departureTick=null;
 doc.minimumDwellTicks=40;
 expect(projectDutyTimeline(doc,doc.duties[0])[0].endTick).toBe(240);
 doc.minimumDwellTicks=80;
 expect(projectDutyTimeline(doc,doc.duties[0])[0].endTick).toBe(280);
 delete doc.minimumDwellTicks;
 expect(projectDutyTimeline(doc,doc.duties[0])[0].endTick).toBe(200);
});
