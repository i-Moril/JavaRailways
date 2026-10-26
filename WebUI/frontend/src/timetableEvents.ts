import type {GraphLayout} from './graph';
import type {TimetableEventPoint} from './types';

export interface GraphEventContext {
  stationId:string;
  tick:number;
  eventId?:string;
  anchor:{x:number;y:number};
}

/** Uses the rendered SVG bounds, so scrolling, panning and axis zoom stay aligned. */
export function graphEventAtPointer(
  layout:Pick<GraphLayout,'width'|'height'|'left'|'right'|'lanes'|'xToTick'>,
  bounds:{left:number;top:number;width:number;height:number},
  pointer:{x:number;y:number},
  stationIds:ReadonlySet<string>,
  periodTicks:number,
):Omit<GraphEventContext,'anchor'>|null {
  if(bounds.width<=0||bounds.height<=0||!Number.isSafeInteger(periodTicks)||periodTicks<=0)return null;
  const x=(pointer.x-bounds.left)*layout.width/bounds.width;
  const y=(pointer.y-bounds.top)*layout.height/bounds.height;
  if(!Number.isFinite(x)||!Number.isFinite(y)||x<layout.left||x>layout.width-layout.right||y<42||y>layout.height)return null;
  const lanes=layout.lanes.filter((lane)=>stationIds.has(lane.id));
  if(!lanes.length)return null;
  const lane=lanes.reduce((nearest,candidate)=>Math.abs(candidate.y-y)<Math.abs(nearest.y-y)?candidate:nearest);
  const tick=Math.max(0,Math.min(periodTicks-1,Math.round(layout.xToTick(x))));
  return Number.isSafeInteger(tick)?{stationId:lane.id,tick}:null;
}

/** Visible IDs remain simple and stable; deletion does not renumber existing references. */
export function nextTimetableEventId(events:readonly Pick<TimetableEventPoint,'id'>[]):string {
  const used=new Set(events.map((event)=>event.id));
  let next=1;
  while(used.has(String(next)))next++;
  return String(next);
}

/** Context menus must not delete an event replaced while the editor was open. */
export function deleteTimetableEvent(events:TimetableEventPoint[],original:TimetableEventPoint):TimetableEventPoint[] {
 const current=events.find((event)=>event.id===original.id);
 if(!current||current.stationId!==original.stationId||current.tick!==original.tick||current.label!==original.label)throw new Error('Event changed');
 return events.filter((event)=>event.id!==original.id);
}
