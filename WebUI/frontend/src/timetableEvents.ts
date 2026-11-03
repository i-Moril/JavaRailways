import {adoptStationProgram} from './stationPrograms';
import {validateTimedOperation} from './timedOperations';
import type {BoundStationProgram,StationProgramRecord,TimetableEventPoint,StationProgramStep} from './types';
import type {TimetableAuthoringDocumentV1} from './authoring';

export interface GraphEventContext {
  event:TimetableEventPoint;
  anchor:{x:number;y:number};
}

export function timetableEventUnchanged(events:readonly TimetableEventPoint[],original:TimetableEventPoint):boolean {
  const current=events.find((event)=>event.id===original.id);
  return !!current&&JSON.stringify(current)===JSON.stringify(original);
}

/** Preview and save use the same exact binding, including the first library selection. */
export function withTimetableEventProgram(document:TimetableAuthoringDocumentV1,operation:TimetableEventPoint,library:readonly StationProgramRecord[]):TimetableAuthoringDocumentV1 {
 const id=operation.program?.programId;
 if(!id||document.boundPrograms?.some(binding=>binding.programId===id))return document;
 const record=library.find(record=>record.id===id);
 if(!record)throw new Error('Missing bound program');
 return adoptStationProgram(document,record);
}

/** Validate before a single document/history transaction, preserving all references. */
export function saveTimetableEvent(events:TimetableEventPoint[],draft:TimetableEventPoint,periodTicks:number,stationIds:readonly string[],original?:TimetableEventPoint,reservedIds:readonly string[]=[],bindings:readonly BoundStationProgram[]=[]):TimetableEventPoint[] {
  if(original&&!timetableEventUnchanged(events,original))throw new Error('Event changed');
  if(draft.stationId!==undefined&&!stationIds.includes(draft.stationId)||validateTimedOperation(draft,periodTicks,bindings).length>0)throw new Error('Invalid event');
  const event={...draft,id:original?.id??nextTimetableEventId(events,reservedIds)};
  return original?events.map((item)=>item.id===original.id?event:item):[...events,event];
}

/** Visible IDs remain simple and stable; deletion does not renumber existing references. */
export function nextTimetableEventId(events:readonly Pick<TimetableEventPoint,'id'>[],reservedIds:readonly string[]=[]):string {
  const used=new Set([...events.map((event)=>event.id),...reservedIds]);
  let next=1;
  while(used.has(String(next)))next++;
  return String(next);
}

/** Context menus must not delete an event replaced while the editor was open. */
export function deleteTimetableEvent(events:TimetableEventPoint[],original:TimetableEventPoint):TimetableEventPoint[] {
 if(!timetableEventUnchanged(events,original))throw new Error('Event changed');
 return events.filter((event)=>event.id!==original.id);
}

/** Copy one timed operation into fresh identities, shifting the whole batch cumulatively. */
export function copyTimetableEvents(
  events:TimetableEventPoint[],sourceId:string,offsetTicks:number,count:number,periodTicks:number,
  reservedIds:readonly string[]=[],bindings:readonly BoundStationProgram[]=[],
):TimetableEventPoint[] {
  if(!Number.isSafeInteger(offsetTicks))throw new Error('Copy offset must be an integer');
  if(!Number.isSafeInteger(count)||count<1||count>100)throw new Error('Copy count must be an integer from 1 to 100');
  const source=events.find((event)=>event.id===sourceId);
  if(!source)throw new Error('Unknown timed event');
  const next=[...events];
  for(let index=1;index<=count;index++){
    const tick=source.tick+offsetTicks*index;
    if(!Number.isSafeInteger(tick)||tick<0||tick>=periodTicks)throw new Error('Copied event is outside the timetable period');
    const event={...structuredClone(source),id:nextTimetableEventId(next,reservedIds),tick};
    if(validateTimedOperation(event,periodTicks,bindings).length>0)throw new Error('Invalid copied event');
    next.push(event);
  }
  return next;
}

/** A dangling wait keeps its identity: adding a point must not silently rebind it. */
export function referencedTimetableEventIds(document:Pick<TimetableAuthoringDocumentV1,'stationPrograms'|'boundPrograms'> & Partial<Pick<TimetableAuthoringDocumentV1,'duties'>>):string[] {
  const ids=new Set<string>();
  const visit=(steps:StationProgramStep[])=>{for(const step of steps){
    if(step.type==='WAIT_EVENT')ids.add(step.eventId);
    else if(step.type==='ASYNC')visit(step.steps);
    else if(step.type==='BRANCH'){visit(step.thenSteps);visit(step.elseSteps);}
  }};
  for(const program of document.stationPrograms??[]){visit(program.arrival.steps);visit(program.departurePreparation.steps);}
  for(const program of document.boundPrograms??[])visit(program.definition.steps);
  for(const duty of document.duties??[])for(const element of duty.elements)if(element.type==='EVENT')ids.add(element.eventId);
  return [...ids];
}
