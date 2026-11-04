import {formationPlannedEnd} from './formationAuthoring';
import {resolveDutyTimedOperation,measuredOperationDuration,operationDuration} from './timedOperations';
import {normalizeAuthoringDocument,type AuthoringDutyElementV1,type AuthoringDutyV1,type TimetableAuthoringDocumentV1} from './authoring';
import type {StationProgramDefinition,StationProgramLiteral,StationProgramRecord,TimetableSimulationResult} from './types';

export interface DutyTimelineTask {
 id:string;type:'RUN'|'PROGRAM'|'EVENT'|'FORMATION';label:string;startTick:number;endTick?:number;
 durationMode:'RUN'|'POINT'|'MANUAL'|'SIMULATED'|'PROGRAM';measured?:boolean;overlaps:string[];
}
export function defaultDutyProgramArguments(parameters:StationProgramDefinition['parameters']):Record<string,StationProgramLiteral> {
 return Object.fromEntries(parameters.map((parameter)=>[parameter.id,parameter.type==='JSON_OBJECT'?{}:parameter.type==='ID_LIST'?[]:parameter.type==='DURATION'?1:parameter.type==='TICK'||parameter.type==='NUMBER'?0:'']));
}
/** Run occupancy includes effective terminal arrival, dwell and optional terminal departure. */
export function dutyTaskStart(document:TimetableAuthoringDocumentV1,element:AuthoringDutyElementV1):number {
 if(element.type==='FORMATION')return document.formationEvents?.find((event)=>event.id===element.eventId)?.tick??0;
 return element.type==='RUN'?document.runs.find((run)=>run.id===element.runId)?.calls[0]?.departureTick??0:resolveDutyTimedOperation(document,element)?.tick??0;
}
/** Stable ties preserve the player's chosen order at the same scheduled tick. */
export function sortDutyTasks(document:TimetableAuthoringDocumentV1,duty:AuthoringDutyV1):AuthoringDutyElementV1[] {
 return duty.elements.map((element,index)=>({element,index})).sort((a,b)=>dutyTaskStart(document,a.element)-dutyTaskStart(document,b.element)||a.index-b.index).map(({element})=>element);
}
/** Apply time ordering in the same document transaction as a run or program edit. */
export function orderDutyTimeline(document:TimetableAuthoringDocumentV1):TimetableAuthoringDocumentV1 {
 let changed=false;
 const duties=document.duties.map((duty)=>{
  const elements=sortDutyTasks(document,duty);
  if(elements.every((element,index)=>element===duty.elements[index]))return duty;
  changed=true;return {...duty,elements};
 });
 return changed?{...document,duties}:document;
}
/** Measurements belong to the caller's current simulation input; no result is persisted here. */
export function projectDutyTimeline(document:TimetableAuthoringDocumentV1,duty:AuthoringDutyV1,simulation?:TimetableSimulationResult,programs:readonly StationProgramRecord[]=[]):DutyTimelineTask[] {
 document=normalizeAuthoringDocument(document);
 const tasks:DutyTimelineTask[]=duty.elements.map((element)=>{
  const startTick=dutyTaskStart(document,element);
  if(element.type==='RUN'){
   const run=document.runs.find((candidate)=>candidate.id===element.runId),terminal=run?.calls.at(-1);
   const arrival=document.arrivalOverrides.find((override)=>override.runId===run?.id&&override.callId===terminal?.id)?.tick??terminal?.arrivalTick;
   // Temporary limitation: terminal dwell is a departure gate, not RUN task occupancy.
   // The run ends at terminal arrival; programs may execute during the dwell window.
   const endTick=arrival??terminal?.departureTick??undefined;
   return {id:element.id,type:'RUN',label:element.runId,startTick,endTick,durationMode:'RUN',overlaps:[]};
  }
  if(element.type==='FORMATION'){
   const event=document.formationEvents?.find((event)=>event.id===element.eventId);
   const duration=measuredOperationDuration(simulation,duty.id,element.id);
   return {id:element.id,type:'FORMATION',label:event?.kind??element.eventId,startTick,endTick:event?formationPlannedEnd(event,duration):undefined,durationMode:event?.duration?.type??'SIMULATED',measured:event?.duration?.type!=='MANUAL'&&duration!==undefined||undefined,overlaps:[]};
  }
  const operation=resolveDutyTimedOperation(document,element);
  const mode=operation?.duration?.type??'POINT';
  const measurement=measuredOperationDuration(simulation,duty.id,element.id);
  const measured=mode==='SIMULATED'&&measurement!==undefined;
  const duration=operation?operationDuration(operation,measurement,programs):undefined;
  return {id:element.id,type:element.type,label:operation?.label??operation?.program?.programId??(element.type==='EVENT'?element.eventId:element.id),startTick,endTick:duration===undefined?undefined:startTick+duration,durationMode:mode,measured:measured||undefined,overlaps:[]};
 });
 for(let i=0;i<tasks.length;i++)for(let j=i+1;j<tasks.length;j++){
  const a=tasks[i],b=tasks[j];
  // Unknown spans and zero-duration points make no fabricated occupancy claim.
  if(a.endTick===undefined||b.endTick===undefined||a.endTick<=a.startTick||b.endTick<=b.startTick)continue;
  if(a.startTick<b.endTick&&b.startTick<a.endTick){a.overlaps.push(b.id);b.overlaps.push(a.id);}
 }
 return tasks;
}
