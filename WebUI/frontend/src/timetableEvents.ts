import type {TimetableEventPoint,StationProgramStep} from './types';
import type {TimetableAuthoringDocumentV1} from './authoring';

export interface GraphEventContext {
  event:TimetableEventPoint;
  anchor:{x:number;y:number};
}

export function timetableEventUnchanged(events:readonly TimetableEventPoint[],original:TimetableEventPoint):boolean {
  const current=events.find((event)=>event.id===original.id);
  return !!current&&current.stationId===original.stationId&&current.tick===original.tick&&current.label===original.label;
}

/** Validate before a single document/history transaction, preserving all references. */
export function saveTimetableEvent(events:TimetableEventPoint[],draft:TimetableEventPoint,periodTicks:number,stationIds:readonly string[],original?:TimetableEventPoint,reservedIds:readonly string[]=[]):TimetableEventPoint[] {
  if(original&&!timetableEventUnchanged(events,original))throw new Error('Event changed');
  if(!stationIds.includes(draft.stationId)||!Number.isSafeInteger(draft.tick)||draft.tick<0||draft.tick>=periodTicks)throw new Error('Invalid event');
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

/** A dangling wait keeps its identity: adding a point must not silently rebind it. */
export function referencedTimetableEventIds(document:Pick<TimetableAuthoringDocumentV1,'stationPrograms'|'boundPrograms'>):string[] {
  const ids=new Set<string>();
  const visit=(steps:StationProgramStep[])=>{for(const step of steps){
    if(step.type==='WAIT_EVENT')ids.add(step.eventId);
    else if(step.type==='ASYNC')visit(step.steps);
    else if(step.type==='BRANCH'){visit(step.thenSteps);visit(step.elseSteps);}
  }};
  for(const program of document.stationPrograms??[]){visit(program.arrival.steps);visit(program.departurePreparation.steps);}
  for(const program of document.boundPrograms??[])visit(program.definition.steps);
  return [...ids];
}
