import {projectDutyTimeline} from './dutyTimeline';
import {resolveDutyTimedOperation,timedOperationCall,validateTimedOperation} from './timedOperations';
import {defaultStationStopProgram,resolveEventWaits,validateStopProgram} from './stationPlans';
import {validateFormationAuthoring,formationPlannedEnd,type AuthoringFormationEvent} from './formationAuthoring';
import { copyStationProgram,validateArrivalProgram,stationProgramSchedule,resolveProgramValue,displayValuesError,validateStationProgram,validateStationProgramCall } from './stationPrograms';
import type { StationStopProgram,TimetableEventPoint,OperationalTrip,StationProgramRecord,TimedOperation,TimedOperationProgram,TimedOperationDuration,DiagramLineV1, LineDirection, LineSimulationResult, LineV1, SavedTimetableV1, SimulationProfile, TimetableActionV1, WorldMetadata } from './types';

export type AuthoringDoorSide = 'NONE'|'LEFT'|'RIGHT'|'BOTH';
export type AuthoringRunCallStatusV1 = 'ORIGIN'|'STOP'|'PASS'|'TERMINAL';
export interface AuthoringRunCallV1 {
  id:string;
  stationId:string;
  berthId?:string|null;
  status:AuthoringRunCallStatusV1;
  targetId:string;
  arrivalTick?:number|null;
  departureTick?:number|null;
  stoppingOffsetMetersBefore?:number|null;
  doorSide?:AuthoringDoorSide|null;
  viaIds?:string[];
}
export interface AuthoringRunV1 {
  id:string;
  color?:string;
  type:'PASSENGER'|'DEADHEAD';
  lineId?:string|null;
  diagramLineId?:string|null;
  lineDirection:LineDirection;
  originDepartureMode?:'AUTO'|'CONTINUE'|'REVERSE';
  calls:AuthoringRunCallV1[];
}
export type AuthoringDutySelectorV1 =
  | {type:'TRAIN_NAME';name:string}
  | {type:'TAKE_FROM_DEPOT';depotId:string;tick:number;tags:string[]}
  | {type:'TAKE_FROM_WAYPOINT';wayPointIds:string[];tick:number}
  | {type:'TAKE_FROM_FORMATION_OUTPUT';eventId:string;outputId:string};
export type DutyProgramDuration = TimedOperationDuration;
export type AuthoringDutyElementV1 =
  | {id:string;type:'RUN';runId:string}
  | (TimedOperation & {type:'PROGRAM';program:TimedOperationProgram})
  | {id:string;type:'EVENT';eventId:string}
  | {id:string;type:'FORMATION';eventId:string};
export interface AuthoringDutyV1 {
  id:string;
  selector:AuthoringDutySelectorV1;
  initialPosition?:string;
  elements:AuthoringDutyElementV1[];
  referenceVehicle?:{type:'WORLD_TRAIN'|'TRAIN_TEMPLATE';id:string};
}
export interface ArrivalOverrideV1 {runId:string;callId:string;tick:number}
export interface TimetableAuthoringDocumentV1 {
  formatVersion:1;
  id:string;
  formalScriptName?:string;
  periodDays:number;
  minimumDwellTicks?:number;
  runs:AuthoringRunV1[];
  duties:AuthoringDutyV1[];
  diagramLines:DiagramLineV1[];
  arrivalOverrides:ArrivalOverrideV1[];
  stationPrograms?:StationStopProgram[];
  events?:TimetableEventPoint[];
  formationEvents?:AuthoringFormationEvent[];
  operationalTrips?:OperationalTrip[];
}
/** Serialize only the current editor contract, never derived runtime line caps. */
export function authoringDocumentForSave(document:TimetableAuthoringDocumentV1):TimetableAuthoringDocumentV1 {
  const {formatVersion,id,formalScriptName,periodDays,minimumDwellTicks,runs,duties,diagramLines,arrivalOverrides,formationEvents,operationalTrips,stationPrograms,events}=document;
  return {formatVersion,id,formalScriptName,periodDays,minimumDwellTicks,runs:runs.map((run)=>({...run,calls:run.calls.map(authoringCallForSave)})),duties,diagramLines,arrivalOverrides,formationEvents,operationalTrips,stationPrograms,events};
}
/** Project only the current call contract at the persistence and generated-metadata boundaries. */
function authoringCallForSave(call:AuthoringRunCallV1):AuthoringRunCallV1 {
  const {id,stationId,berthId,status,targetId,arrivalTick,departureTick,stoppingOffsetMetersBefore,doorSide,viaIds}=call;
  return {id,stationId,berthId,status,targetId,arrivalTick,departureTick,stoppingOffsetMetersBefore,doorSide,viaIds};
}
/** One timetable-wide minimum, in ticks. A missing field has the explicit default zero. */
export function authoringMinimumDwell(document:Pick<TimetableAuthoringDocumentV1,'minimumDwellTicks'>):number {
  return document.minimumDwellTicks??0;
}
/** Minimum dwell applies to revenue stops only; deadhead movements may depart immediately. */
export function authoringMinimumDwellForRun(document:Pick<TimetableAuthoringDocumentV1,'minimumDwellTicks'>,run:Pick<AuthoringRunV1,'type'>):number {
  return run.type==='DEADHEAD'?0:authoringMinimumDwell(document);
}
export interface AuthoringIssue {path:string;message:string;severity:'error'|'warning'}
export interface GeneratedTimetableSource {
  source:string;
  issues:AuthoringIssue[];
  documentHashInput:string;
}

export function deleteRunFromDocument(
  document:TimetableAuthoringDocumentV1,runId:string,
):TimetableAuthoringDocumentV1 {
  if(!document.runs.some((run)=>run.id===runId))return document;
  return {
    ...document,
    runs:document.runs.filter((run)=>run.id!==runId),
    duties:document.duties.map((duty)=>({...duty,elements:duty.elements.filter((element)=>element.type!=='RUN'||element.runId!==runId)})),
    arrivalOverrides:document.arrivalOverrides.filter((override)=>override.runId!==runId),
    operationalTrips:document.operationalTrips?.map((trip)=>({...trip,segments:trip.segments.filter((segment)=>segment.runId!==runId)})),
  };
}

/** Renames one run and its references without changing its route, calls or timing. */
export function renameAuthoringRun(document:TimetableAuthoringDocumentV1,runId:string,nextId:string):TimetableAuthoringDocumentV1 {
  if(!document.runs.some((run)=>run.id===runId))throw new Error('Unknown run');
  if(!nextId.trim())throw new Error('Run ID is required');
  if(nextId===runId)return document;
  if(document.runs.some((run)=>run.id===nextId))throw new Error('Run ID already exists');
  return {...document,
    runs:document.runs.map((run)=>run.id===runId?{...run,id:nextId}:run),
    operationalTrips:document.operationalTrips?.map((trip)=>({...trip,segments:trip.segments.map((segment)=>segment.runId===runId?{...segment,runId:nextId}:segment)})),
    duties:document.duties.map((duty)=>duty.elements.some((element)=>element.type==='RUN'&&element.runId===runId)?{
      ...duty,elements:duty.elements.map((element)=>element.type==='RUN'&&element.runId===runId?{...element,runId:nextId}:element),
    }:duty),
    arrivalOverrides:document.arrivalOverrides.map((override)=>override.runId===runId?{...override,runId:nextId}:override),
  };
}

/** Reconciles transient selection after a rename is undone/redone using unchanged call identities. */
export function resolveAuthoringRunSelection(runs:AuthoringRunV1[],selectedId:string,previous?:AuthoringRunV1):AuthoringRunV1|undefined {
  const selected=runs.find((run)=>run.id===selectedId);
  if(selected||!previous)return selected;
  const sameScope=runs.filter((run)=>run.diagramLineId===previous.diagramLineId&&run.lineDirection===previous.lineDirection);
  const sameCalls=sameScope.filter((run)=>run.calls===previous.calls);
  if(sameCalls.length)return sameCalls.length===1?sameCalls[0]:undefined;
  const candidates=sameScope.filter((run)=>previous.calls.length>0&&run.calls.length===previous.calls.length&&run.calls.every((call,index)=>call.id===previous.calls[index].id));
  return candidates.length===1?candidates[0]:undefined;
}

/** An empty run focus is intentional; never invent a selection or recover from the previous run. */
export function retainAuthoringRunSelection(runs:AuthoringRunV1[],selectedId:string,previous?:AuthoringRunV1):AuthoringRunV1|undefined {
  if(!selectedId)return undefined;
  return resolveAuthoringRunSelection(runs,selectedId,previous);
}

/** Runtime caps are snapshots of the authoritative infrastructure line settings. */
export function authoringLineSpeedLimits(lines:LineV1[],document:TimetableAuthoringDocumentV1) {
  const usedLineIds=new Set(document.runs.map((run)=>run.lineId));
  return lines.filter((line)=>usedLineIds.has(line.id)&&line.maximumSpeedMetersPerSecond!=null).map((line)=>({lineId:line.id,maximumSpeedMetersPerSecond:line.maximumSpeedMetersPerSecond!}));
}

export function authoringResetProfile(lines:LineV1[],run:AuthoringRunV1,profile:Pick<SimulationProfile,'profileType'|'profileId'>):SimulationProfile {
  const cap=lines.find((line)=>line.id===run.lineId)?.maximumSpeedMetersPerSecond;
  if(cap==null||!Number.isFinite(cap)||cap<=0)throw new Error(`No maximum speed is configured for infrastructure line: ${run.lineId??''}`);
  return {...profile,direction:run.lineDirection,...lineSimulationRoute(run),maximumSpeedMetersPerSecond:cap};
}

/** Atomically removes a run from any previous duty and appends it to the target duty. */
export function moveRunToDuty(
  document:TimetableAuthoringDocumentV1,
  runId:string,
  targetDutyId:string,
  newElementId:string,
):TimetableAuthoringDocumentV1 {
  if(!document.runs.some((run)=>run.id===runId)||!document.duties.some((duty)=>duty.id===targetDutyId)) return document;
  const owners=document.duties.filter((duty)=>duty.elements.some((element)=>element.type==='RUN'&&element.runId===runId));
  if(owners.length===1&&owners[0].id===targetDutyId) return document;
  const existing=document.duties.flatMap((duty)=>duty.elements).find((element)=>element.type==='RUN'&&element.runId===runId);
  const assignment:AuthoringDutyElementV1=existing?.type==='RUN'?existing:{id:newElementId,type:'RUN',runId};
  return {...document,operationalTrips:document.operationalTrips?.map((trip)=>({...trip,segments:trip.segments.map((segment)=>segment.runId===runId?{...segment,serviceId:targetDutyId}:segment)})),duties:document.duties.map((duty)=>{
    const without=duty.elements.filter((element)=>element.type!=='RUN'||element.runId!==runId);
    return duty.id===targetDutyId?{...duty,elements:[...without,assignment]}:{...duty,elements:without};
  })};
}

export const emptyAuthoringDocument = ():TimetableAuthoringDocumentV1 => ({
  formatVersion:1,
  id:'new-timetable',
  periodDays:1,
  runs:[],
  duties:[],
  diagramLines:[],
  arrivalOverrides:[],
});

type RunCallIdFactory=(stationId:string,index:number)=>string;
const lineStations=(line:LineV1,direction:LineDirection):string[]=>direction==='UP'?line.upStationIds:line.downStationIds;
const stationBerths=(metadata:WorldMetadata,stationId:string)=>(metadata.stations??[]).filter((station)=>station.stationId===stationId);

function newRunCall(
  stationId:string,index:number,status:AuthoringRunCallStatusV1,metadata:WorldMetadata,idFactory:RunCallIdFactory,
):AuthoringRunCallV1 {
  const berth=stationBerths(metadata,stationId)[0];
  return {
    id:idFactory(stationId,index),stationId,berthId:berth?.berthId??null,status,
    targetId:berth?.destinationId??'',arrivalTick:null,departureTick:null,
    doorSide:null,stoppingOffsetMetersBefore:null,
  };
}

export function createRunFromLine(
  id:string,line:LineV1,direction:LineDirection,metadata:WorldMetadata,
  idFactory:RunCallIdFactory=(station,index)=>`${id}-call-${index}-${station}`,
):AuthoringRunV1 {
  const stations=lineStations(line,direction);
  const calls=stations.map((station,index)=>newRunCall(
    station,index,index===0?'ORIGIN':index===stations.length-1?'TERMINAL':'STOP',metadata,idFactory,
  ));
  return {id,type:'PASSENGER',lineId:line.id,diagramLineId:line.id,lineDirection:direction,originDepartureMode:'AUTO',calls};
}

export function resizeRunRoute(
  run:AuthoringRunV1,line:LineV1,direction:LineDirection,originStationId:string,terminalStationId:string,
  metadata:WorldMetadata,idFactory:RunCallIdFactory=(station,index)=>`${run.id}-call-${index}-${station}`,
):AuthoringRunV1 {
  const stations=lineStations(line,direction);
  const originIndex=stations.indexOf(originStationId);
  const terminalIndex=stations.indexOf(terminalStationId);
  if(originIndex<0||terminalIndex<0||terminalIndex<=originIndex) throw new Error('route endpoints must follow line direction and contain at least two stations');
  const selectedStations=stations.slice(originIndex,terminalIndex+1);
  const existingByStation=new Map(run.calls.map((call)=>[call.stationId,call]));
  const calls=selectedStations.map((station,index)=>{
    const existing=existingByStation.get(station);
    const status:AuthoringRunCallStatusV1=index===0?'ORIGIN':index===selectedStations.length-1?'TERMINAL':existing?.status==='PASS'?'PASS':'STOP';
    const call=existing?{...existing,status}:newRunCall(station,index,status,metadata,idFactory);
    if(status==='ORIGIN') return {...call,arrivalTick:null};
    if(status==='TERMINAL') return call;
    if(status==='PASS') return {...call,departureTick:null};
    return call;
  });
  return {...run,diagramLineId:line.id,lineDirection:direction,calls};
}

export function lineSimulationRoute(run:AuthoringRunV1):Pick<SimulationProfile,'originStationId'|'terminalStationId'|'stopStationIds'|'destinationIds'|'originDepartureMode'|'viaIds'> {
  return {
    originStationId:run.calls[0]?.stationId,
    terminalStationId:run.calls.at(-1)?.stationId,
    stopStationIds:run.calls.filter((call)=>call.status==='STOP'||call.status==='TERMINAL').map((call)=>call.stationId),
    destinationIds:run.calls.map((call)=>call.targetId),
    viaIds:run.calls.map((call)=>call.viaIds??[]),
    originDepartureMode:run.originDepartureMode??'AUTO',
  };
}

const secondsToTicks=(seconds:number):number=>Math.max(1,Math.ceil(seconds*20-1e-9));
export function resetRunTimings(
  run:AuthoringRunV1,simulation:LineSimulationResult,firstDepartureTick:number,fixedDwellTicks:number,
):AuthoringRunV1 {
  if(!Number.isInteger(firstDepartureTick)||firstDepartureTick<0) throw new Error('first departure tick must be a non-negative integer');
  if(!Number.isInteger(fixedDwellTicks)||fixedDwellTicks<0) throw new Error('fixed dwell ticks must be a non-negative integer');
  if(run.calls.length<2||simulation.legs.length!==run.calls.length-1) throw new Error('simulation does not match route table');
  const calls=run.calls.map((call)=>({...call}));
  calls[0]={...calls[0],status:'ORIGIN',arrivalTick:null,departureTick:firstDepartureTick};
  let cursor=firstDepartureTick;
  simulation.legs.forEach((leg,index)=>{
    const from=calls[index];
    const to=calls[index+1];
    if(leg.fromStationId!==from.stationId||leg.toStationId!==to.stationId) throw new Error('simulation station order does not match route table');
    if(!Number.isFinite(leg.travelSeconds)||leg.travelSeconds<=0) throw new Error('simulation returned an invalid interval');
    cursor+=secondsToTicks(leg.travelSeconds);
    if(index===simulation.legs.length-1) {
      calls[index+1]={...to,status:'TERMINAL',arrivalTick:cursor};
    } else if(to.status==='PASS') {
      calls[index+1]={...to,arrivalTick:cursor,departureTick:null};
    } else {
      calls[index+1]={...to,status:'STOP',arrivalTick:cursor,departureTick:cursor+fixedDwellTicks};
      cursor+=fixedDwellTicks;
    }
  });
  return {...run,calls};
}

const shiftedTick=(tick:number|null|undefined,offsetTicks:number):number|null=>tick==null?null:tick+offsetTicks;
export interface ShiftedRunPreview {run:AuthoringRunV1;anchorCallId:string;deltaTicks:number;affectedTimeCount:number}
export type RunMoveEvent='ARRIVAL'|'DEPARTURE';
export type RunMoveMode='OFFSET'|'ABSOLUTE';

/** Moves every timestamp in a run by one delta derived from a selected call timestamp. */
export function moveRunFromCall(
  run:AuthoringRunV1,anchorCallId:string,event:RunMoveEvent,mode:RunMoveMode,value:number,periodTicks:number,minimumDwellTicks=0,
):ShiftedRunPreview {
  requireRunMinimumDwell(run,minimumDwellTicks);
  const anchor=run.calls.find((call)=>call.id===anchorCallId);
  if(!anchor)throw new Error('move anchor does not exist');
  const anchorTick=event==='ARRIVAL'?anchor.arrivalTick:anchor.departureTick;
  if(anchorTick==null)throw new Error(`move anchor requires an ${event.toLowerCase()} time`);
  if(!Number.isSafeInteger(value))throw new Error('move value must be an integer');
  if(mode==='ABSOLUTE'&&value<0)throw new Error('target time must be a non-negative integer');
  const deltaTicks=mode==='OFFSET'?value:value-anchorTick;
  let affectedTimeCount=0;
  const calls=run.calls.map((call)=>{
    if(call.arrivalTick!=null)affectedTimeCount++;
    if(call.departureTick!=null)affectedTimeCount++;
    return {...call,arrivalTick:shiftedTick(call.arrivalTick,deltaTicks),departureTick:shiftedTick(call.departureTick,deltaTicks)};
  });
  if(calls.some((call)=>[call.arrivalTick,call.departureTick].some((tick)=>tick!=null&&(tick<0||tick>=periodTicks)))) {
    throw new Error('moved time is outside the timetable period');
  }
  return {run:{...run,calls},anchorCallId,deltaTicks,affectedTimeCount};
}

export function shiftRunFromDeparture(
  run:AuthoringRunV1,anchorCallId:string,targetDepartureTick:number,periodTicks:number,minimumDwellTicks=0,
):ShiftedRunPreview {
  const anchorIndex=run.calls.findIndex((call)=>call.id===anchorCallId);
  if(anchorIndex<0)throw new Error('shift departure anchor does not exist');
  const anchor=run.calls[anchorIndex];
  if(anchor.departureTick==null)throw new Error('shift anchor requires a departure time');
  if(!Number.isSafeInteger(targetDepartureTick)||targetDepartureTick<0)throw new Error('target departure must be a non-negative integer');
  const earliestDeparture=(anchor.arrivalTick??0)+(anchor.status==='PASS'?0:(run.type==='DEADHEAD'?0:minimumDwellTicks));
  if(anchor.arrivalTick!=null&&targetDepartureTick<earliestDeparture)throw new Error('target departure violates minimum dwell');
  const deltaTicks=targetDepartureTick-anchor.departureTick;
  let affectedTimeCount=1;
  const calls=run.calls.map((call,index)=>{
    if(index<anchorIndex)return call;
    if(index===anchorIndex)return {...call,departureTick:targetDepartureTick};
    if(call.arrivalTick!=null)affectedTimeCount++;
    if(call.departureTick!=null)affectedTimeCount++;
    return {...call,arrivalTick:shiftedTick(call.arrivalTick,deltaTicks),departureTick:shiftedTick(call.departureTick,deltaTicks)};
  });
  requireRunMinimumDwell({...run,calls},minimumDwellTicks);
  if(calls.some((call)=>[call.arrivalTick,call.departureTick].some((tick)=>tick!=null&&(tick<0||tick>=periodTicks)))) {
    throw new Error('shifted time is outside the timetable period');
  }
  return {run:{...run,calls},anchorCallId,deltaTicks,affectedTimeCount};
}

export function copyRunShifted(
  run:AuthoringRunV1,newId:string,offsetTicks:number,
  idFactory:RunCallIdFactory=(station,index)=>`${newId}-call-${index}-${station}`,minimumDwellTicks=0,
):AuthoringRunV1 {
  requireRunMinimumDwell(run,minimumDwellTicks);
  if(!newId.trim()) throw new Error('copied run id is required');
  if(!Number.isInteger(offsetTicks)) throw new Error('copy offset must be an integer');
  return {...run,id:newId,calls:run.calls.map((call,index)=>({
    ...call,id:idFactory(call.stationId,index),arrivalTick:shiftedTick(call.arrivalTick,offsetTicks),
    departureTick:shiftedTick(call.departureTick,offsetTicks),

  }))};
}

function requireRunMinimumDwell(run:AuthoringRunV1,minimumDwellTicks:number):void {
  if(!Number.isSafeInteger(minimumDwellTicks)||minimumDwellTicks<0)throw new Error('Minimum dwell must be a non-negative integer');
  const effective=run.type==='DEADHEAD'?0:minimumDwellTicks;
  if(run.calls.some((call)=>call.status!=='PASS'&&call.arrivalTick!=null&&call.departureTick!=null&&call.departureTick-call.arrivalTick<effective)) {
    throw new Error('Departure violates minimum dwell');
  }
}

const issue=(path:string,message:string):AuthoringIssue=>({path,message,severity:'error'});
const nonNegativeInteger=(value:number)=>Number.isSafeInteger(value)&&value>=0;
const quote=(value:string)=>JSON.stringify(value);
const origin=(dutyId:string,elementId:string,runId?:string,functionId?:string)=>
  [`duty:${dutyId}`,`element:${elementId}`,runId&&`run:${runId}`,functionId&&`function:${functionId}`].filter(Boolean).join('/');

export function validateAuthoringDocument(document:TimetableAuthoringDocumentV1,infrastructureLines:LineV1[]=[],programs:readonly StationProgramRecord[]=[]):AuthoringIssue[] {
  const programMap=new Map(programs.map((program)=>[program.id,program]));
  const issues:AuthoringIssue[]=[];
  if(document.formatVersion!==1) issues.push(issue('formatVersion','Unsupported editor document version'));
  if(!document.id.trim()) issues.push(issue('id','Timetable ID is required'));
  if(!Number.isSafeInteger(document.periodDays)||document.periodDays<1) issues.push(issue('periodDays','Period days must be a positive integer'));
  if(document.minimumDwellTicks!==undefined&&!nonNegativeInteger(document.minimumDwellTicks)) issues.push(issue('minimumDwellTicks','Minimum dwell must be a non-negative integer'));
  const periodTicks=Math.max(0,document.periodDays*24_000);
  const diagramLineIds=new Set<string>();
  document.diagramLines.forEach((line,lineIndex)=>{
    const path=`diagramLines[${lineIndex}]`;
    if(!line.id.trim()) issues.push(issue(`${path}.id`,'Diagram line ID is required'));
    else if(diagramLineIds.has(line.id)) issues.push(issue(`${path}.id`,`Duplicate diagram line ID: ${line.id}`));
    diagramLineIds.add(line.id);
    if(!line.upStationIds.length||!line.downStationIds.length) issues.push(issue(path,'Diagram line directions must not be empty'));
    if(new Set(line.upStationIds).size!==line.upStationIds.length||new Set(line.downStationIds).size!==line.downStationIds.length) issues.push(issue(path,'Diagram line directions must not contain duplicate stations'));
  });
  const lineSpeedLimitIds=new Set<string>();
  authoringLineSpeedLimits(infrastructureLines,document).forEach((limit,index)=>{
    const path=`lineSpeedLimits[${index}]`;
    if(!limit.lineId.trim()) issues.push(issue(`${path}.lineId`,'Infrastructure line ID is required'));
    else if(lineSpeedLimitIds.has(limit.lineId)) issues.push(issue(`${path}.lineId`,`Duplicate line speed limit: ${limit.lineId}`));
    lineSpeedLimitIds.add(limit.lineId);
    if(!Number.isFinite(limit.maximumSpeedMetersPerSecond)||limit.maximumSpeedMetersPerSecond<=0) issues.push(issue(`${path}.maximumSpeedMetersPerSecond`,'Line maximum speed must be finite and positive'));
  });
  const runIds=new Set<string>();
  document.runs.forEach((run,runIndex)=>{
    const path=`runs[${runIndex}]`;
    if(!run.id.trim()) issues.push(issue(`${path}.id`,'Run ID is required'));
    else if(runIds.has(run.id)) issues.push(issue(`${path}.id`,`Duplicate run ID: ${run.id}`));
    runIds.add(run.id);
    if(run.color!==undefined&&!/^#[0-9a-fA-F]{6}$/.test(run.color))issues.push(issue(`${path}.color`,'Run color must be a six-digit hexadecimal color'));
    if(run.calls.length<2) issues.push(issue(`${path}.calls`,'A run requires an origin and a destination'));
    if(!run.lineId?.trim())issues.push(issue(`${path}.lineId`,'A run requires an infrastructure line'));
    if(run.lineId&&!lineSpeedLimitIds.has(run.lineId)) issues.push(issue(`${path}.lineId`,`No maximum speed is configured for infrastructure line: ${run.lineId}`));
    if(!run.diagramLineId) issues.push(issue(`${path}.diagramLineId`,'A run requires a diagram line'));
    else if(!diagramLineIds.has(run.diagramLineId)) issues.push(issue(`${path}.diagramLineId`,`Unknown diagram line: ${run.diagramLineId}`));
    if(run.lineDirection!=='UP'&&run.lineDirection!=='DOWN') issues.push(issue(`${path}.lineDirection`,'Line direction must be UP or DOWN'));
    const diagramLine=document.diagramLines.find((line)=>line.id===run.diagramLineId);
    const expectedStations=diagramLine?(run.lineDirection==='UP'?diagramLine.upStationIds:diagramLine.downStationIds):[];
    const firstStationIndex=expectedStations.indexOf(run.calls[0]?.stationId);
    const routeStations=firstStationIndex<0?[]:expectedStations.slice(firstStationIndex,firstStationIndex+run.calls.length);
    if(diagramLine&&(routeStations.length!==run.calls.length||routeStations.some((station,index)=>station!==run.calls[index].stationId))) {
      issues.push(issue(`${path}.calls`,'Route calls must be a contiguous section of the selected line direction'));
    }
    const callIds=new Set<string>();
    run.calls.forEach((call,callIndex)=>{
      const callPath=`${path}.calls[${callIndex}]`;
      if(!call.id.trim()) issues.push(issue(`${callPath}.id`,'Call ID is required'));
      else if(callIds.has(call.id)) issues.push(issue(`${callPath}.id`,`Duplicate call ID: ${call.id}`));
      callIds.add(call.id);
      if(!call.stationId.trim()) issues.push(issue(`${callPath}.stationId`,'Call station is required'));
      if(!call.targetId.trim()) issues.push(issue(`${callPath}.targetId`,'Call target is required'));
      const expectedStatus:AuthoringRunCallStatusV1=callIndex===0?'ORIGIN':callIndex===run.calls.length-1?'TERMINAL':call.status;
      if(call.status!==expectedStatus||(callIndex>0&&callIndex<run.calls.length-1&&call.status!=='STOP'&&call.status!=='PASS')) {
        issues.push(issue(`${callPath}.status`,'Route endpoints and intermediate stop statuses are inconsistent'));
      }
      for(const [name,value] of [['arrivalTick',call.arrivalTick],['departureTick',call.departureTick]] as const) {
        if(value!=null&&(!nonNegativeInteger(value)||value>=periodTicks)) issues.push(issue(`${callPath}.${name}`,`${name} must be inside the timetable period`));
      }
      if(call.viaIds&&(!Array.isArray(call.viaIds)||call.viaIds.some((id)=>typeof id!=='string'||!id.trim())||new Set(call.viaIds).size!==call.viaIds.length))issues.push(issue(`${callPath}.viaIds`,'Invalid ordered via waypoints'));
      if(call.stoppingOffsetMetersBefore!=null&&(!Number.isFinite(call.stoppingOffsetMetersBefore)||call.stoppingOffsetMetersBefore<0)) issues.push(issue(`${callPath}.stoppingOffsetMetersBefore`,'Stopping offset must be finite and non-negative'));
      const effectiveArrival=document.arrivalOverrides.find((override)=>override.runId===run.id&&override.callId===call.id)?.tick??call.arrivalTick;
      if(effectiveArrival!=null&&call.departureTick!=null&&call.departureTick-effectiveArrival<(call.status==='PASS'?0:authoringMinimumDwellForRun(document,run))) issues.push(issue(callPath,'Departure violates arrival or minimum dwell'));
      if(call.status==='PASS'&&call.departureTick!=null) issues.push(issue(`${callPath}.departureTick`,'Pass rows cannot have a departure tick'));
      if(callIndex<run.calls.length-1&&call.status!=='PASS'&&call.departureTick==null) issues.push(issue(`${callPath}.departureTick`,'Every stopping non-terminal call requires a departure tick'));
    });
  });
  issues.push(...validateFormationAuthoring(document));
  const dutyIds=new Set<string>();
  const assignments=new Map<string,string>();
  document.duties.forEach((duty,dutyIndex)=>{
    const path=`duties[${dutyIndex}]`;
    if(!duty.id.trim()) issues.push(issue(`${path}.id`,'Duty ID is required'));
    else if(dutyIds.has(duty.id)) issues.push(issue(`${path}.id`,`Duplicate duty ID: ${duty.id}`));
    dutyIds.add(duty.id);
    if(duty.selector.type==='TRAIN_NAME'&&!duty.selector.name.trim()) issues.push(issue(`${path}.selector.name`,'Train name is required'));
    if(duty.selector.type==='TAKE_FROM_DEPOT') {
      if(!duty.selector.depotId.trim()) issues.push(issue(`${path}.selector.depotId`,'Depot ID is required'));
      if(duty.selector.tags.some((tag)=>!tag.trim())||new Set(duty.selector.tags).size!==duty.selector.tags.length) issues.push(issue(`${path}.selector.tags`,'Depot tags must be non-blank and unique'));
      if(!nonNegativeInteger(duty.selector.tick)||duty.selector.tick>=periodTicks) issues.push(issue(`${path}.selector.tick`,'Acquisition tick must be inside the timetable period'));
    }
    if(duty.selector.type==='TAKE_FROM_WAYPOINT') {
      if(!duty.selector.wayPointIds.length||duty.selector.wayPointIds.some((id)=>!id.trim())) issues.push(issue(`${path}.selector.wayPointIds`,'At least one waypoint is required'));
      if(!nonNegativeInteger(duty.selector.tick)||duty.selector.tick>=periodTicks) issues.push(issue(`${path}.selector.tick`,'Acquisition tick must be inside the timetable period'));
    }
    const plannedTasks=projectDutyTimeline(document,duty,undefined,programs);
    for(let index=0;index<plannedTasks.length;index++){
      const task=plannedTasks[index];
      if(plannedTasks.slice(0,index).some((prior)=>prior.endTick!==undefined&&prior.endTick>task.startTick))issues.push(issue(`${path}.elements[${index}]`,'Task starts before a previous planned task completes'));
    }
    let previousRun:AuthoringRunV1|undefined;
    let programDestination:string|undefined=namedDutyInitialTarget(document,duty);
    let previousElementEndTick:number|undefined;
    const elementIds=new Set<string>();
    if(duty.initialPosition!==undefined){
      if(!duty.initialPosition.trim())issues.push(issue(`${path}.initialPosition`,'Initial placement waypoint is required'));
      if(duty.selector.type!=='TRAIN_NAME')issues.push(issue(`${path}.initialPosition`,'Initial placement requires a named-train selector'));
    }
    duty.elements.forEach((element,elementIndex)=>{
      const elementPath=`${path}.elements[${elementIndex}]`;
      if(!element.id.trim()) issues.push(issue(`${elementPath}.id`,'Element ID is required'));
      else if(elementIds.has(element.id)) issues.push(issue(`${elementPath}.id`,`Duplicate element ID: ${element.id}`));
      elementIds.add(element.id);
      if(element.type==='RUN') {
        const run=document.runs.find((candidate)=>candidate.id===element.runId);
        if(!run) issues.push(issue(`${elementPath}.runId`,`Unknown run: ${element.runId}`));
        const owner=assignments.get(element.runId);
        if(owner) issues.push(issue(`${elementPath}.runId`,`Run ${element.runId} is already assigned to duty ${owner}`));
        else assignments.set(element.runId,duty.id);
        if(run&&programDestination&&run.calls[0]?.targetId!==programDestination)issues.push(issue(elementPath,`Program location discontinuity: ${programDestination} -> ${run.calls[0]?.targetId}`));
        programDestination=undefined;
        if(run&&previousRun) {
          const previousTerminal=previousRun.calls.at(-1)?.targetId;
          const nextOrigin=run.calls[0]?.targetId;
          if((previousRun.calls.at(-1)?.stoppingOffsetMetersBefore??0)!==(run.calls[0]?.stoppingOffsetMetersBefore??0))issues.push(issue(elementPath,'Run stopping offset discontinuity requires an explicit station program'));
          if(run.calls[0]?.doorSide&&run.calls[0].doorSide!=='NONE'&&run.calls[0].doorSide!==previousRun.calls.at(-1)?.doorSide)issues.push(issue(elementPath,'Connection departure must not configure a second conflicting door operation'));
          if(previousTerminal&&nextOrigin&&previousTerminal!==nextOrigin) issues.push(issue(elementPath,`Run location discontinuity: ${previousTerminal} -> ${nextOrigin}`));
          const terminal=previousRun.calls.at(-1);
          const previousArrival=document.arrivalOverrides.find((override)=>override.runId===previousRun!.id&&override.callId===terminal?.id)?.tick??terminal?.arrivalTick;
          const previousEnd=previousRun.type==='DEADHEAD'?(previousArrival??terminal?.departureTick??0):Math.max(previousArrival==null?0:previousArrival+authoringMinimumDwellForRun(document,previousRun),terminal?.departureTick??0);
          const nextStart=run.calls[0]?.departureTick;
          if(previousEnd!=null&&nextStart!=null&&nextStart<previousEnd) issues.push(issue(elementPath,`Run overlaps previous run by ${previousEnd-nextStart} ticks`));
        }
        if(run) {
          const runStart=run.calls[0]?.departureTick;
          const terminal=run.calls.at(-1);
          const arrival=document.arrivalOverrides.find((override)=>override.runId===run.id&&override.callId===terminal?.id)?.tick??terminal?.arrivalTick;
          const runEnd=run.type==='DEADHEAD'?(arrival??terminal?.departureTick??0):Math.max(arrival==null?0:arrival+authoringMinimumDwellForRun(document,run),terminal?.departureTick??0);
          if(previousElementEndTick!=null&&runStart!=null&&runStart<previousElementEndTick) issues.push(issue(elementPath,`Run starts before the previous duty element completes by ${previousElementEndTick-runStart} ticks`));
          if(runEnd!=null) previousElementEndTick=Math.max(previousElementEndTick??0,runEnd);
          previousRun=run;
        }
      } else if(element.type==='FORMATION'){
        const event=document.formationEvents?.find((event)=>event.id===element.eventId);
        if(event){
          if(duty.selector.type==='TRAIN_NAME'&&!previousRun&&!programDestination)issues.push(issue(elementPath,'Formation requires a prior run or initial placement'));
          if(previousElementEndTick!==undefined&&event.tick<previousElementEndTick)issues.push(issue(elementPath,'Formation starts before the previous duty element completes'));
          previousElementEndTick=formationPlannedEnd(event);
          programDestination=event.kind==='COUPLE'?(duty.id===event.approachingDutyId?event.rendezvous.approachDestinationId:event.rendezvous.stationaryDestinationId):programDestination??previousRun?.calls.at(-1)?.targetId;
          previousRun=undefined;
        }
      } else {
        const operation=resolveDutyTimedOperation(document,element);
        if(!operation?.program){issues.push(issue(elementPath,`Missing program operation: ${element.type==='EVENT'?element.eventId:element.id}`));return;}
        const call=timedOperationCall(operation,element.id);
        for(const message of validateTimedOperation(operation,periodTicks,programs))issues.push(issue(elementPath,message));
        const snapshot=programMap.get(call.programId);
        if(!snapshot)issues.push(issue(elementPath,`Missing bound program: ${call.programId}`));
        else {
          const callIssues=validateStationProgramCall(snapshot,call,periodTicks);
          for(const message of callIssues)issues.push(issue(elementPath,message));
          if(callIssues.length)return;
          programDestination=programDestination??previousRun?.calls.at(-1)?.targetId;
          if(previousElementEndTick!=null&&call.tick<previousElementEndTick)issues.push(issue(elementPath,'Program starts before the previous duty element completes'));
          if(duty.selector.type==='TRAIN_NAME'&&!previousRun&&!programDestination)issues.push(issue(elementPath,'Program requires a prior run or service-boundary acquisition'));
          const lower=stationProgramSchedule(snapshot.definition.steps,call.arguments,call.tick,periodTicks).earliest;
          for(const step of snapshot.definition.steps){
            if(step.type==='BRANCH'||step.type==='SELECT_STOP')programDestination=undefined;
            if(step.type==='MOVE')programDestination=String(resolveProgramValue(step.destinationId,call.arguments));
            if(step.type==='STORE_AT_DEPOT'&&(elementIndex!==duty.elements.length-1||step!==snapshot.definition.steps.at(-1)))issues.push(issue(elementPath,'Store at depot must be the final duty action'));
          }
          previousElementEndTick=lower;
          previousRun=undefined;
        }
      }
    });
  });
  issues.push(...validateOperationalTrips(document));
  const programIds=new Set<string>();
  const stationIds=new Set<string>();
  for(const [index,program] of (document.stationPrograms??[]).entries()){
    if(stationIds.has(program.stationId))issues.push(issue(`stationPrograms[${index}]`,'Duplicate station program'));
    stationIds.add(program.stationId);
    for(const message of validateStopProgram(program,document.events??[],periodTicks))issues.push(issue(`stationPrograms[${index}]`,message));
  }
  const eventIds=new Set<string>();
  for(const [index,event] of (document.events??[]).entries()){
    if(!/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(event.id)||eventIds.has(event.id)||validateTimedOperation(event,periodTicks,programs).length>0)issues.push(issue(`events[${index}]`,'Invalid or duplicate timed event'));
    eventIds.add(event.id);
    if(event.program){
      const snapshot=event.program?programMap.get(event.program.programId):undefined;
      if(!snapshot)issues.push(issue(`events[${index}]`,`Missing bound program: ${event.program.programId}`));
      else for(const message of validateStationProgramCall(snapshot,timedOperationCall(event,event.id),periodTicks))issues.push(issue(`events[${index}]`,message));
    }
  }
  for(const [index,program] of programs.entries()){
    if(programIds.has(program.id))issues.push(issue(`programs[${index}]`,'Duplicate program'));
    programIds.add(program.id);
    if(program.estimatedDurationTicks!==undefined&&(!Number.isSafeInteger(program.estimatedDurationTicks)||program.estimatedDurationTicks<0))issues.push(issue(`programs[${index}]`,'Invalid program estimated duration'));
    for(const message of validateStationProgram(program.definition))issues.push(issue(`programs[${index}]`,message));
    try{resolveEventWaits(program.definition,document.events??[],periodTicks);}catch(error){issues.push(issue(`programs[${index}]`,String(error)));}
  }
  const overrideKeys=new Set<string>();
  document.arrivalOverrides.forEach((override,overrideIndex)=>{
    const path=`arrivalOverrides[${overrideIndex}]`;
    const run=document.runs.find((candidate)=>candidate.id===override.runId);
    if(!run) issues.push(issue(`${path}.runId`,`Unknown run: ${override.runId}`));
    else if(!run.calls.some((call)=>call.id===override.callId)) issues.push(issue(`${path}.callId`,`Unknown call: ${override.callId}`));
    if(!nonNegativeInteger(override.tick)||override.tick>=periodTicks) issues.push(issue(`${path}.tick`,'Arrival override tick must be inside the timetable period'));
    const key=`${override.runId}\u0000${override.callId}`;
    if(overrideKeys.has(key)) issues.push(issue(path,`Duplicate arrival override for ${override.runId}/${override.callId}`));
    overrideKeys.add(key);
  });
  document.runs.forEach((run,runIndex)=>{if(!assignments.has(run.id))issues.push(issue(`runs[${runIndex}]`,`Run ${run.id} is not assigned to a duty`));});
  return issues;
}

const graphAction=(type:TimetableActionV1['type'],overrides:Partial<TimetableActionV1>):TimetableActionV1=>({
  type,requiredTags:[],requiredCapabilities:[],stablingPointIds:[],wayPointIds:[],thenSteps:[],elseSteps:[],...overrides,
});

/** Builds a presentation-only service per run so the graph stays live without compiling the duty document. */
export function authoringGraphTimetable(document:TimetableAuthoringDocumentV1,infrastructureLines:LineV1[]=[]):SavedTimetableV1 {
  return {
    id:document.id,periodDays:document.periodDays,diagramLines:document.diagramLines,lineSpeedLimits:authoringLineSpeedLimits(infrastructureLines,document),
    services:document.runs.map((run)=>{
      if(run.calls.length===0)return {id:run.id,lineId:run.lineId,diagramLineId:run.diagramLineId,lineDirection:run.lineDirection,selectorType:'ANY' as const,activation:{type:'SCHEDULED' as const},steps:[]};
      const fallbackLegTicks=Math.max(20,Math.floor(document.periodDays*24_000/(run.calls.length+1)));
      const periodEnd=document.periodDays*24_000-1;
      let cursor=run.calls[0].departureTick??run.calls[0].arrivalTick??0;
      const steps:SavedTimetableV1['services'][number]['steps']=[{type:'CHECK',wayPointId:run.calls[0].targetId}];
      run.calls.slice(1).forEach((call,index)=>{
        const previous=run.calls[index];
        const departureTick=(previous.status==='PASS'?previous.arrivalTick:previous.departureTick)??cursor;
        const arrivalTick=call.arrivalTick??Math.min(periodEnd,departureTick+fallbackLegTicks);
        steps.push(
          {type:'AT',tick:departureTick,action:graphAction('DESTINATION_OPTIONS',{arrivalTick})},
          {type:'AT',tick:departureTick,action:graphAction('DESTINATION',{wayPointIds:[call.targetId]})},
        );
        cursor=(call.status==='PASS'?call.arrivalTick:call.departureTick)??arrivalTick;
      });
      return {
        id:run.id,lineId:run.lineId,diagramLineId:run.diagramLineId,lineDirection:run.lineDirection,selectorType:'ANY' as const,
        activation:{type:'SCHEDULED' as const},steps,
      };
    }),
  };
}

export function authoringSelectorKey(selector:AuthoringDutySelectorV1):string {
  if(selector.type==='TAKE_FROM_FORMATION_OUTPUT')return JSON.stringify(['TAKE_FROM_FORMATION_OUTPUT',selector.eventId,selector.outputId]);
  if(selector.type==='TRAIN_NAME')return JSON.stringify(['TRAIN_NAME',selector.name]);
  if(selector.type==='TAKE_FROM_WAYPOINT')return JSON.stringify(['TAKE_FROM_WAYPOINT',selector.wayPointIds]);
  return JSON.stringify(['TAKE_FROM_DEPOT',selector.depotId,[...selector.tags].sort()]);
}

function selectorSource(selector:AuthoringDutySelectorV1,document:TimetableAuthoringDocumentV1):string {
  if(selector.type==='TAKE_FROM_FORMATION_OUTPUT') return `timetable.takeFromFormationOutput(${quote(selector.eventId)}, ${quote(selector.outputId)}, ${document.formationEvents?.find((event)=>event.id===selector.eventId)?.tick??0})`;
  if(selector.type==='TRAIN_NAME') return quote(selector.name);
  if(selector.type==='TAKE_FROM_WAYPOINT') return `timetable.takeFromWaypoint([${selector.wayPointIds.map(quote).join(', ')}], ${selector.tick})`;
  const options=selector.tags.length?`, { tags: [${[...selector.tags].sort().map(quote).join(', ')}] }`:'';
  return options
    ? `timetable.takeFromDepot(${quote(selector.depotId)}${options}, ${selector.tick})`
    : `timetable.takeFromDepot(${quote(selector.depotId)}, ${selector.tick})`;
}

function namedDutyInitialTarget(document:TimetableAuthoringDocumentV1,duty:AuthoringDutyV1):string|undefined {
  if(duty.selector.type!=='TRAIN_NAME')return undefined;
  if(duty.initialPosition)return duty.initialPosition;
  const firstRun=duty.elements.find((item)=>item.type==='RUN');
  return firstRun?.type==='RUN'?document.runs.find((run)=>run.id===firstRun.runId)?.calls[0]?.targetId:undefined;
}

function stopOptions(document:TimetableAuthoringDocumentV1,duty:AuthoringDutyV1,run:AuthoringRunV1,call:AuthoringRunCallV1):Record<string,unknown>{
 const shared=document.stationPrograms?.find((program)=>program.stationId===call.stationId)??defaultStationStopProgram(call.stationId);
 const periodTicks=document.periodDays*24000;
 return {stationId:call.stationId,callId:call.id,callRole:call.status,departureTick:call.departureTick??undefined,stationProgram:{arrival:resolveEventWaits(shared.arrival,document.events??[],periodTicks),departurePreparation:resolveEventWaits(shared.departurePreparation,document.events??[],periodTicks),preparationLead:shared.preparationLead},plannedContext:{runId:run.id,runType:run.type,lineId:run.lineId??'',lineDirection:run.lineDirection,stationId:call.stationId,callStatus:call.status,dutyId:duty.id}};
}
const runDoorSide=(run:AuthoringRunV1,call:AuthoringRunCallV1)=>call.doorSide??(run.type==='DEADHEAD'?'NONE':undefined);
function destinationOptions(document:TimetableAuthoringDocumentV1,duty:AuthoringDutyV1,run:AuthoringRunV1,call:AuthoringRunCallV1,override?:number):string {
 return `, JSON.parse(${quote(JSON.stringify({...stopOptions(document,duty,run,call),arrivalTick:override??call.arrivalTick??undefined,departureTick:run.type==='DEADHEAD'?undefined:call.departureTick??undefined,minimumDwellTicks:call.status==='PASS'?undefined:authoringMinimumDwellForRun(document,run),stoppingOffsetMetersBefore:call.stoppingOffsetMetersBefore??undefined,doorSide:runDoorSide(run,call)}))})`;
}

export function generateTimetableSource(document:TimetableAuthoringDocumentV1,infrastructureLines:LineV1[]=[],programs:readonly StationProgramRecord[]=[]):GeneratedTimetableSource {
  const orderedPrograms=[...programs].sort((a,b)=>a.id.localeCompare(b.id));
  const issues=validateAuthoringDocument(document,infrastructureLines,orderedPrograms);
  const {formalScriptName:ignoredName,...savedInput}=authoringDocumentForSave(document);
  const semanticInput={...savedInput,runs:savedInput.runs.map(({color:_,...run})=>run),formationEvents:savedInput.formationEvents?.map(({duration:_,stationId:__,...event})=>event),events:savedInput.events?.map(({id,tick})=>({id,tick})),duties:savedInput.duties.map((duty)=>({...duty,elements:duty.elements.map((element)=>{
    if(element.type==='RUN'||element.type==='FORMATION')return element;
    const operation=resolveDutyTimedOperation(document,element);
    return {id:element.id,type:element.type,...(element.type==='EVENT'?{eventId:element.eventId}:{}),tick:operation?.tick,program:operation?.program};
  })}))};
  const documentHashInput=JSON.stringify({document:semanticInput,programs:orderedPrograms.map((program)=>({id:program.id,definition:program.definition})),lineSpeedLimits:authoringLineSpeedLimits(infrastructureLines,document)});
  if(issues.some((issue)=>issue.severity==='error'))return {source:'',issues,documentHashInput};
  const lines:string[]=['// Generated by JavaRailways WebUI. Do not edit in structured mode.','function main(timetable) {',`    timetable.setPeriodDays(${document.periodDays})`];
  for(const [index,snapshot] of orderedPrograms.entries()) {
    if(document.duties.some((duty)=>duty.elements.some((element)=>resolveDutyTimedOperation(document,element)?.program?.programId===snapshot.id)))lines.push(`    const program${index} = JSON.parse(${quote(JSON.stringify(resolveEventWaits(snapshot.definition,document.events??[],document.periodDays*24000)))})`);
  }
  for(const event of document.formationEvents??[]) {
    const {id,tick:_,duration:__,stationId:___,...definition}=event;
    lines.push(`    timetable.formationEvent(${quote(id)}, JSON.parse(${quote(JSON.stringify(definition))}))`);
  }
  for(const limit of authoringLineSpeedLimits(infrastructureLines,document)) {
    lines.push(`    timetable.lineSpeedLimit(${quote(limit.lineId)}, ${limit.maximumSpeedMetersPerSecond})`);
  }
  for(const trip of document.operationalTrips??[]) {
    const {id,...options}=trip;
    lines.push(`    timetable.operationalTrip(${quote(id)}, JSON.parse(${quote(JSON.stringify(options))}))`);
  }
  for(const line of document.diagramLines) {
    lines.push('',`    timetable.diagramLine(${quote(line.id)}, {`,`        upStations: [${line.upStationIds.map(quote).join(', ')}],`,`        downStations: [${line.downStationIds.map(quote).join(', ')}]`,`    })`);
  }
  for(const duty of document.duties) {
    if(duty.initialPosition&&duty.selector.type==='TRAIN_NAME') {
      lines.push('',`    timetable.setInitialPosition(timetable.named(${quote(duty.selector.name)}), timetable.named(${quote(duty.initialPosition)}))`);
    }
  }
  for(const duty of document.duties) {
    const assignedRuns=duty.elements.flatMap((element)=>element.type==='RUN'?[document.runs.find((run)=>run.id===element.runId)].filter(Boolean) as AuthoringRunV1[]:[]);
    const diagramIds=Array.from(new Set(assignedRuns.map((run)=>run.diagramLineId).filter(Boolean)));
    const infrastructureLineIds=Array.from(new Set(assignedRuns.map((run)=>run.lineId).filter(Boolean)));
    const optionFields:string[]=[];
    if(diagramIds.length===1) optionFields.push(`diagramLineId: ${quote(diagramIds[0]!)}`);
    if(infrastructureLineIds.length===1) optionFields.push(`lineId: ${quote(infrastructureLineIds[0]!)}`);
    const serviceOptions=optionFields.length?`, { ${optionFields.join(', ')} }`:'';
    lines.push('',`    timetable.service(${quote(duty.id)}, ${selectorSource(duty.selector,document)}${serviceOptions}, t => {`);
    let emittedCheck=false;
    for(const element of duty.elements) {
      if(element.type==='RUN') {
        const run=document.runs.find((candidate)=>candidate.id===element.runId);
        if(!run) continue;
        const scoped='runScope';
        const segment={runId:run.id,lineId:run.lineId,diagramLineId:run.diagramLineId,lineDirection:run.lineDirection,calls:run.calls.map(authoringCallForSave).map(({targetId,berthId:_,stoppingOffsetMetersBefore:__,doorSide:___,...call})=>({...call,destinationId:targetId,arrivalTick:document.arrivalOverrides.find((o)=>o.runId===run.id&&o.callId===call.id)?.tick??call.arrivalTick}))};
        lines.push(`        t.origin(${quote(origin(duty.id,element.id,run.id))}, scoped => {`, `          scoped.runSegment(JSON.parse(${quote(JSON.stringify(segment))}), ${scoped} => {`);
        if(!emittedCheck&&duty.selector.type==='TRAIN_NAME'&&run.calls[0]?.targetId) {
          lines.push(`            ${scoped}.check(${quote(run.calls[0].targetId)})`);
          emittedCheck=true;
        }
        const first=run.calls[0];
        if(first?.departureTick!=null){
          const options={...stopOptions(document,duty,run,first),doorSide:runDoorSide(run,first),minimumDwellTicks:authoringMinimumDwellForRun(document,run)};
          lines.push(`            ${scoped}.beginStationStop(${quote(first.targetId)}, JSON.parse(${quote(JSON.stringify(options))}))`);
        }
        let routeAnchor=run.calls[0];
        let pendingPasses='';
        for(let index=1;index<run.calls.length;index++) {
          const call=run.calls[index];
          if(call.status==='PASS') {
            for(const via of call.viaIds??[])pendingPasses+=`.pass(${quote(via)})`;
            if(call.targetId) pendingPasses+=`.pass(${quote(call.targetId)})`;
            continue;
          }
          if(routeAnchor?.departureTick==null||!call.targetId) continue;
          const override=document.arrivalOverrides.find((item)=>item.runId===run.id&&item.callId===call.id)?.tick;
          const viaPasses=(call.viaIds??[]).map((id)=>`.pass(${quote(id)})`).join('');
          const statement=`${scoped}.at(${routeAnchor.departureTick})${pendingPasses}${viaPasses}.destination(${quote(call.targetId)}${destinationOptions(document,duty,run,call,override)})`;
          lines.push(`            ${statement}`);
          routeAnchor=call;
          pendingPasses='';
        }
        lines.push('          })','        })');
      } else if(element.type==='FORMATION'){
        const event=document.formationEvents?.find((event)=>event.id===element.eventId);
        if(event){
          lines.push(`        t.origin(${quote(origin(duty.id,element.id))}, scoped => {`);
          if(!emittedCheck&&duty.selector.type==='TRAIN_NAME'){
            const target=namedDutyInitialTarget(document,duty);
            if(target){lines.push(`            scoped.check(${quote(target)})`);emittedCheck=true;}
          }
          lines.push(`            scoped.task(${quote(element.id)}, ${event.tick}, taskScope => taskScope.${event.kind==='SPLIT'?'split':'couple'}(${quote(event.id)}))`,'        })');
        }
      } else {
        const operation=resolveDutyTimedOperation(document,element);
        if(!operation?.program)continue;
        const call=timedOperationCall(operation,element.id);
        const index=orderedPrograms.findIndex((item)=>item.id===call.programId);
        const argumentsSource=`JSON.parse(${quote(JSON.stringify(call.arguments))})`;
        if(index>=0){
          lines.push(`        t.origin(${quote(`${origin(duty.id,element.id)}/call:${call.id}`)}, scoped => {`);
          if(!emittedCheck&&duty.selector.type==='TRAIN_NAME'){
            const target=namedDutyInitialTarget(document,duty);
            if(target){lines.push(`            scoped.check(${quote(target)})`);emittedCheck=true;}
          }
          lines.push(`            scoped.task(${quote(element.id)}, ${call.tick}, taskScope => taskScope.program(${quote(call.programId)}, program${index}, ${argumentsSource}, JSON.parse(${quote(JSON.stringify({dutyId:duty.id}))})))`,'        })');
        }
      }
    }
    lines.push('    })');
  }
  lines.push('}','');
  return {source:lines.join('\n'),issues,documentHashInput};
}

export interface CompiledEditorDocument {
  document:TimetableAuthoringDocumentV1;
  generatedSource:string;
  timetable?:SavedTimetableV1;
}

/** A single transaction for the two stored halves of a shared physical station call. */
export function editRunConnection(document:TimetableAuthoringDocumentV1,fromRunId:string,toRunId:string,arrivalTick:number|null,departureTick:number|null):TimetableAuthoringDocumentV1 {
  const from=document.runs.find((r)=>r.id===fromRunId),to=document.runs.find((r)=>r.id===toRunId);
  const linked=document.duties.some((d)=>d.elements.some((e,i)=>e.type==='RUN'&&e.runId===fromRunId&&d.elements[i+1]?.type==='RUN'&&(d.elements[i+1] as {runId:string}).runId===toRunId));
  if(!from||!to||!linked||from.calls.at(-1)?.targetId!==to.calls[0]?.targetId)throw new Error('Connection is no longer current');
  if([arrivalTick,departureTick].some((v)=>v!=null&&(!Number.isSafeInteger(v)||v<0||v>=document.periodDays*24000))||arrivalTick!=null&&departureTick!=null&&departureTick<arrivalTick+authoringMinimumDwellForRun(document,from))throw new Error('Connection violates timetable period or minimum dwell');
  return {...document,runs:document.runs.map((r)=>r===from?{...r,calls:r.calls.map((c,i)=>i===r.calls.length-1?{...c,arrivalTick}:c)}:r===to?{...r,calls:r.calls.map((c,i)=>i===0?{...c,departureTick}:c)}:r),arrivalOverrides:document.arrivalOverrides.filter((o)=>o.runId!==fromRunId||o.callId!==from.calls.at(-1)?.id)};
}
export function validateOperationalTrips(document:TimetableAuthoringDocumentV1):AuthoringIssue[] {
  const errors:AuthoringIssue[]=[],ids=new Set<string>(),assigned=new Set<string>();
  for(const [i,trip] of (document.operationalTrips??[]).entries()){
    const path=`operationalTrips[${i}]`;
    if(!trip.id.trim()||ids.has(trip.id))errors.push(issue(path,'Operational trip ID must be nonempty and unique'));ids.add(trip.id);
    const jsonError=displayValuesError(trip.defaults);if(jsonError)errors.push(issue(`${path}.defaults`,jsonError));
    if(!trip.segments.length)errors.push(issue(path,'Operational trip requires complete runs and unit slots'));
    for(const [j,segment] of trip.segments.entries()){
      const duty=document.duties.find((d)=>d.id===segment.serviceId);
      if(!duty?.elements.some((e)=>e.type==='RUN'&&e.runId===segment.runId))errors.push(issue(`${path}.segments[${j}]`,'Operational trip run must belong to its duty'));
      if(!segment.unitSlots.length||segment.unitSlots.length>128||segment.unitSlots.some((slot)=>!Number.isSafeInteger(slot)||slot<1||slot>2147483647)||new Set(segment.unitSlots).size!==segment.unitSlots.length)errors.push(issue(`${path}.segments[${j}].unitSlots`,'Unit slots must be distinct positive integers (at most128)'));
      for(const slot of segment.unitSlots){const key=JSON.stringify([segment.serviceId,segment.runId,slot]);if(assigned.has(key))errors.push(issue(`${path}.segments[${j}]`,'A run unit slot cannot belong to multiple operational trips'));assigned.add(key);}
    }
  }
  return errors;
}
