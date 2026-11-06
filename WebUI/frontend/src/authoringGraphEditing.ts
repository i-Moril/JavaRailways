import { authoringMinimumDwellForCall,type TimetableAuthoringDocumentV1 } from './authoring';
import type { GraphPoint } from './graph';

export interface GraphSuffixShift {
  document:TimetableAuthoringDocumentV1;
  delta:number;
  affectedEvents:number;
  callId:string;
  kind:'ARRIVAL'|'DEPARTURE';
  anchorTick:number;
  targetTick:number;
}

function graphPointCall(document:TimetableAuthoringDocumentV1,runId:string,point:Pick<GraphPoint,'kind'|'stepIndex'>){
  const run=document.runs.find((item)=>item.id===runId);
  if(!run)throw new Error('Unknown run');
  const arrival=point.kind==='ARRIVAL';
  if((!arrival&&point.kind!=='DEPARTURE')||!Number.isInteger(point.stepIndex)||point.stepIndex<(arrival?1:2)||point.stepIndex%2!==(arrival?1:0))throw new Error('Unknown graph point');
  const leg=arrival?(point.stepIndex-1)/2:(point.stepIndex-2)/2;
  const index=leg+(arrival?1:0),call=run.calls[index];
  if(!call||leg>=run.calls.length-1)throw new Error('Unknown graph point');
  return {run,index,call,kind:arrival||call.status==='PASS'?'ARRIVAL' as const:'DEPARTURE' as const};
}

/** Only accepts point identities from authoringGraphTimetable's two-step-per-leg projection. */
export function shiftAuthoringGraphSuffix(
  document:TimetableAuthoringDocumentV1,runId:string,point:Pick<GraphPoint,'kind'|'stepIndex'>,mode:'OFFSET'|'ABSOLUTE',value:number,
):GraphSuffixShift {
  const period=document.periodDays*24_000;
  if(!Number.isSafeInteger(document.periodDays)||!Number.isSafeInteger(period)||period<=0)throw new Error('Invalid timetable period');
  if(!Number.isSafeInteger(value))throw new Error('Shift value must be a safe integer');
  if(mode!=='OFFSET'&&mode!=='ABSOLUTE')throw new Error('Unknown shift mode');
  const {run,index,call:anchor,kind}=graphPointCall(document,runId,point);
  const effectiveCalls=run.calls.map((call)=>({...call,arrivalTick:document.arrivalOverrides.find((item)=>item.runId===runId&&item.callId===call.id)?.tick??call.arrivalTick}));
  const anchorTick=kind==='ARRIVAL'?effectiveCalls[index].arrivalTick:anchor.departureTick;
  if(anchorTick==null)throw new Error('The selected event has no time');
  if(!Number.isSafeInteger(anchorTick)||anchorTick<0||anchorTick>=period)throw new Error('Anchor time must be inside the timetable period');
  const delta=mode==='OFFSET'?value:value-anchorTick;
  const targetTick=anchorTick+delta;
  if(!Number.isSafeInteger(delta)||!Number.isSafeInteger(targetTick))throw new Error('Shifted time must be a safe integer');
  const affectedArrivals=new Set<string>();
  let affectedEvents=0;
  const calls=run.calls.map((call,callIndex)=>{
    if(callIndex<index)return call;
    const shiftArrival=callIndex>index||kind==='ARRIVAL';
    const effectiveArrival=effectiveCalls[callIndex].arrivalTick;
    const next={...call};
    if(shiftArrival&&effectiveArrival!=null){
      if(!Number.isSafeInteger(effectiveArrival))throw new Error('Arrival time must be a safe integer');
      next.arrivalTick=effectiveArrival+delta;affectedArrivals.add(call.id);affectedEvents++;
    }
    if(call.departureTick!=null){
      if(!Number.isSafeInteger(call.departureTick))throw new Error('Departure time must be a safe integer');
      next.departureTick=call.departureTick+delta;affectedEvents++;
    }
    return next;
  });
  // Validate only this run's shifted suffix and its immediate chronological boundary.
  // Unrelated document issues (for example an unassigned run) cannot block a time edit.
  let previous:number|undefined;
  calls.forEach((call,callIndex)=>{
    const effectiveArrival=affectedArrivals.has(call.id)?call.arrivalTick:effectiveCalls[callIndex].arrivalTick;
    const events=[effectiveArrival,call.departureTick].filter((tick):tick is number=>tick!=null);
    if(callIndex>=index){
      if(call.status==='PASS'&&call.departureTick!=null)throw new Error('Pass rows have one passage time');
      const minimumDwell=authoringMinimumDwellForCall(document,run,call);
      if(!Number.isSafeInteger(minimumDwell)||minimumDwell<0)throw new Error('Minimum dwell must be a non-negative integer');
      if(effectiveArrival!=null&&call.departureTick!=null&&call.departureTick-effectiveArrival<minimumDwell)throw new Error('Departure violates arrival or minimum dwell');
    }
    events.forEach((tick,eventIndex)=>{
      const shifted=callIndex>index||callIndex===index&&(kind==='ARRIVAL'||effectiveArrival==null||eventIndex>0);
      if(shifted){
        if(!Number.isSafeInteger(tick)||tick<0||tick>=period)throw new Error('Shifted time must be inside the timetable period');
        if(previous!=null&&(!Number.isSafeInteger(previous)||previous<0||previous>=period))throw new Error('Previous event must be inside the timetable period');
        if(previous!=null&&tick<previous)throw new Error('Shifted events must remain in chronological order');
      }
      previous=tick;
    });
  });
  return {
    document:delta===0?document:{...document,runs:document.runs.map((item)=>item.id===runId?{...item,calls}:item),arrivalOverrides:document.arrivalOverrides.filter((item)=>item.runId!==runId||!affectedArrivals.has(item.callId))},
    delta,affectedEvents,callId:anchor.id,kind,anchorTick,targetTick,
  };
}

/** Single-node time commits from the toolbar; graph gestures never call this transaction. */
export function editAuthoringGraphTime(
  document:TimetableAuthoringDocumentV1,runId:string,point:Pick<GraphPoint,'kind'|'stepIndex'>,tick:number,
):TimetableAuthoringDocumentV1 {
  if(!Number.isSafeInteger(tick)||tick<0||tick>=document.periodDays*24_000)throw new Error('Time must be inside the timetable period');
  const {run,call,kind}=graphPointCall(document,runId,point);
  const currentArrival=document.arrivalOverrides.find((item)=>item.runId===runId&&item.callId===call.id)?.tick??call.arrivalTick;
  const editsArrival=kind==='ARRIVAL';
  const nextArrival=editsArrival?tick:currentArrival,nextDeparture=editsArrival?call.departureTick:tick;
  const minimumDwell=authoringMinimumDwellForCall(document,run,call);
  if(!Number.isSafeInteger(minimumDwell)||minimumDwell<0)throw new Error('Minimum dwell must be a non-negative integer');
  if(nextArrival!=null&&nextDeparture!=null&&nextDeparture-nextArrival<minimumDwell)throw new Error('Departure violates arrival or minimum dwell');
  if(tick===(editsArrival?currentArrival:call.departureTick))return document;
  if(editsArrival)return {...document,arrivalOverrides:[...document.arrivalOverrides.filter((item)=>item.runId!==runId||item.callId!==call.id),{runId,callId:call.id,tick}]};
  return {...document,runs:document.runs.map((item)=>item.id===runId?{...item,calls:item.calls.map((itemCall)=>itemCall.id===call.id?{...itemCall,departureTick:tick}:itemCall)}:item)};
}
