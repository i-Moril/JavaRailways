import type {AuthoringDutyElementV1,TimetableAuthoringDocumentV1} from './authoring';
import type {StationProgramCall,StationProgramRecord,TimedOperation,TimetableSimulationResult} from './types';

export function resolveDutyTimedOperation(document:TimetableAuthoringDocumentV1,element:AuthoringDutyElementV1):TimedOperation|undefined {
 return element.type==='PROGRAM'?element:element.type==='EVENT'?document.events?.find((event)=>event.id===element.eventId):undefined;
}
export function timedOperationCall(operation:TimedOperation,executionId:string):StationProgramCall {
 if(!operation.program)throw new Error('Operation has no program');
 return {id:executionId,tick:operation.tick,...operation.program};
}
export function validateTimedOperation(operation:TimedOperation,periodTicks:number,programs:readonly StationProgramRecord[]=[]):string[] {
 const errors:string[]=[];
 if(!Number.isSafeInteger(operation.tick)||operation.tick<0||operation.tick>=periodTicks)errors.push('Operation tick must be inside the timetable period');
 if(operation.stationId!==undefined&&!operation.stationId.trim())errors.push('Operation station must be non-blank');
 if(operation.duration?.type==='MANUAL'){
  if(!Number.isSafeInteger(operation.duration.ticks)||operation.duration.ticks<0)errors.push('Program duration must be a non-negative integer');
  else if(operation.tick+operation.duration.ticks>=periodTicks)errors.push('Program duration crosses the timetable period');
 }else if(operation.duration&&!['SIMULATED','PROGRAM'].includes(operation.duration.type))errors.push('Unknown program duration type');
 if(operation.duration?.type==='PROGRAM'){
  if(!operation.program)errors.push('Program duration requires a program');
  const ticks=operationDuration(operation,undefined,programs);
  if(ticks!==undefined&&(!Number.isSafeInteger(operation.tick+ticks)||operation.tick+ticks>=periodTicks))errors.push('Program duration crosses the timetable period');
 }
 return errors;
}
/** Only a complete, uniquely identified execution establishes a duration. */
export function measuredOperationDuration(simulation:TimetableSimulationResult|undefined,dutyId:string,elementId:string):number|undefined {
 if(simulation?.status!=='COMPLETED')return undefined;
 const matches=simulation.tasks?.filter((task)=>task.serviceId===dutyId&&task.taskId===elementId)??[];
 if(matches.length!==1)return undefined;
 const task=matches[0];
 return task.endTick!==undefined&&Number.isSafeInteger(task.startTick)&&Number.isSafeInteger(task.endTick)&&task.endTick>=task.startTick?task.endTick-task.startTick:undefined;
}
export function operationDuration(operation:TimedOperation,measured?:number,programs:readonly StationProgramRecord[]=[]):number|undefined {
 if(!operation.duration)return 0;
 if(operation.duration.type==='MANUAL')return operation.duration.ticks;
 if(operation.duration.type==='PROGRAM')return programs.find(program=>program.id===operation.program?.programId)?.estimatedDurationTicks;
 return measured;
}
export interface ProjectedTimedOperation {operation:TimedOperation;owner:'GRAPH'|'DUTY';dutyId?:string;elementId?:string;startTick:number;endTick?:number;measured?:boolean}
export function projectTimetableOperations(document:TimetableAuthoringDocumentV1,simulation?:TimetableSimulationResult,programs:readonly StationProgramRecord[]=[]):ProjectedTimedOperation[] {
 const graph=(document.events??[]).map((operation):ProjectedTimedOperation=>{
  const references=document.duties.flatMap((duty)=>duty.elements.filter((element)=>element.type==='EVENT'&&element.eventId===operation.id).map((element)=>({dutyId:duty.id,elementId:element.id})));
  const durations=references.map(({dutyId,elementId})=>measuredOperationDuration(simulation,dutyId,elementId));
  const measured=durations.length>0&&durations.every((duration)=>duration!==undefined&&duration===durations[0])?durations[0]:undefined;
  const duration=operationDuration(operation,measured,programs);
  return {operation,owner:'GRAPH',startTick:operation.tick,endTick:duration===undefined?undefined:operation.tick+duration,measured:operation.duration?.type==='SIMULATED'&&measured!==undefined||undefined};
 });
 const duties=document.duties.flatMap((duty)=>duty.elements.filter((element)=>element.type==='PROGRAM').map((operation):ProjectedTimedOperation=>{
  const measured=measuredOperationDuration(simulation,duty.id,operation.id),duration=operationDuration(operation,measured,programs);
  return {operation,owner:'DUTY',dutyId:duty.id,elementId:operation.id,startTick:operation.tick,endTick:duration===undefined?undefined:operation.tick+duration,measured:operation.duration?.type==='SIMULATED'&&measured!==undefined||undefined};
 }));
 return [...graph,...duties];
}
