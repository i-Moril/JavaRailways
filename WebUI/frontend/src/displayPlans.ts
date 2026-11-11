import type {TimetableAuthoringDocumentV1} from './authoring';
import type {OperationalTrip,OperationalTripSegment,ProgramJsonObject} from './types';
import {displayValuesError} from './stationPrograms';
import {isUnitSlots} from './unitSlots';

export interface DisplayPlanRun {serviceId:string;runId:string;label:string}
const sameRun=(left:Pick<OperationalTripSegment,'serviceId'|'runId'>,right:Pick<OperationalTripSegment,'serviceId'|'runId'>)=>left.serviceId===right.serviceId&&left.runId===right.runId;

/** Only actual assigned tasks with a run definition can own a display-plan leg. */
export function displayPlanRuns(document:TimetableAuthoringDocumentV1):DisplayPlanRun[]{
  const runs=new Set(document.runs.map((run)=>run.id));
  const result:DisplayPlanRun[]=[];
  for(const duty of document.duties)for(const task of duty.elements){
    if(task.type!=='RUN'||!runs.has(task.runId))continue;
    const run={serviceId:duty.id,runId:task.runId,label:`${duty.id} / ${task.runId}`};
    if(!result.some((existing)=>sameRun(existing,run)))result.push(run);
  }
  return result;
}
export function displayPlansForRun(document:TimetableAuthoringDocumentV1,serviceId:string,runId:string):OperationalTrip[]{
  return (document.operationalTrips??[]).filter((plan)=>plan.segments.some((leg)=>sameRun(leg,{serviceId,runId})));
}
export function orphanDisplayPlans(document:TimetableAuthoringDocumentV1):OperationalTrip[]{
  const runs=displayPlanRuns(document);
  return (document.operationalTrips??[]).filter((plan)=>!plan.segments.some((leg)=>runs.some((run)=>sameRun(run,leg))));
}
function requirePlan(document:TimetableAuthoringDocumentV1,planId:string):OperationalTrip {
  const plan=document.operationalTrips?.find((item)=>item.id===planId);
  if(!plan)throw new Error('Display plan no longer exists');
  return plan;
}
function requireLegIndex(plan:OperationalTrip,index:number):void {
  if(!Number.isInteger(index)||index<0||index>=plan.segments.length)throw new Error('Display plan leg no longer exists');
}
function requireAvailableLeg(document:TimetableAuthoringDocumentV1,leg:OperationalTripSegment,plan?:OperationalTrip,index?:number):void {
  if(!displayPlanRuns(document).some((run)=>sameRun(run,leg)))throw new Error('Display plan run must belong to an existing duty RUN task');
  if(!isUnitSlots(leg.unitSlots))throw new Error('Unit slots must be distinct positive integers (at most128)');
  if(plan?.segments.some((other,otherIndex)=>otherIndex!==index&&sameRun(other,leg)))throw new Error('Run already belongs to this display plan');
  for(const otherPlan of document.operationalTrips??[])for(const [otherIndex,other] of otherPlan.segments.entries()){
    if(otherPlan===plan&&otherIndex===index)continue;
    if(sameRun(other,leg)&&other.unitSlots.some((slot)=>leg.unitSlots.includes(slot)))throw new Error('A run unit slot already belongs to a display plan');
  }
}
function replacePlan(document:TimetableAuthoringDocumentV1,previous:OperationalTrip,next:OperationalTrip):TimetableAuthoringDocumentV1 {
  return {...document,operationalTrips:document.operationalTrips!.map((plan)=>plan===previous?next:plan)};
}
function copyLeg(leg:OperationalTripSegment):OperationalTripSegment{return {...leg,unitSlots:[...leg.unitSlots]};}
export function createDisplayPlan(document:TimetableAuthoringDocumentV1,leg:OperationalTripSegment):TimetableAuthoringDocumentV1 {
  requireAvailableLeg(document,leg);
  let maximum=0n;
  for(const plan of document.operationalTrips??[])if(/^\d+$/.test(plan.id)){const value=BigInt(plan.id);if(value>maximum)maximum=value;}
  const plan:OperationalTrip={id:String(maximum+1n),defaults:{},segments:[copyLeg(leg)]};
  return {...document,operationalTrips:[...(document.operationalTrips??[]),plan]};
}
export function attachDisplayPlanRun(document:TimetableAuthoringDocumentV1,planId:string,leg:OperationalTripSegment):TimetableAuthoringDocumentV1 {
  const plan=requirePlan(document,planId);requireAvailableLeg(document,leg,plan);
  return replacePlan(document,plan,{...plan,segments:[...plan.segments,copyLeg(leg)]});
}
export function updateDisplayPlanDefaults(document:TimetableAuthoringDocumentV1,planId:string,defaults:ProgramJsonObject):TimetableAuthoringDocumentV1 {
  const plan=requirePlan(document,planId),error=displayValuesError(defaults);if(error)throw new Error(error);
  return replacePlan(document,plan,{...plan,defaults:structuredClone(defaults)});
}
export function updateDisplayPlanLeg(document:TimetableAuthoringDocumentV1,planId:string,index:number,leg:OperationalTripSegment):TimetableAuthoringDocumentV1 {
  const plan=requirePlan(document,planId);requireLegIndex(plan,index);requireAvailableLeg(document,leg,plan,index);
  return replacePlan(document,plan,{...plan,segments:plan.segments.map((previous,i)=>i===index?copyLeg(leg):previous)});
}
export function moveDisplayPlanLeg(document:TimetableAuthoringDocumentV1,planId:string,index:number,delta:-1|1):TimetableAuthoringDocumentV1 {
  const plan=requirePlan(document,planId);requireLegIndex(plan,index);
  if(delta!==-1&&delta!==1)throw new Error('Display plan leg can move one position at a time');
  const destination=index+delta;if(destination<0||destination>=plan.segments.length)return document;
  const segments=[...plan.segments];[segments[index],segments[destination]]=[segments[destination],segments[index]];
  return replacePlan(document,plan,{...plan,segments});
}
export function removeDisplayPlanLeg(document:TimetableAuthoringDocumentV1,planId:string,index:number):TimetableAuthoringDocumentV1 {
  const plan=requirePlan(document,planId);requireLegIndex(plan,index);
  return plan.segments.length===1?removeDisplayPlan(document,planId):replacePlan(document,plan,{...plan,segments:plan.segments.filter((_,i)=>i!==index)});
}
export function removeDisplayPlan(document:TimetableAuthoringDocumentV1,planId:string):TimetableAuthoringDocumentV1 {
  const plan=requirePlan(document,planId);
  return {...document,operationalTrips:document.operationalTrips!.filter((item)=>item!==plan)};
}
