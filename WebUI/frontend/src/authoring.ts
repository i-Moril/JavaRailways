import type { DiagramLineV1, SavedTimetableV1 } from './types';

export type AuthoringDoorSide = 'NONE'|'LEFT'|'RIGHT'|'BOTH';
export interface AuthoringRunCallV1 {
  id:string;
  targetId:string;
  arrivalTick?:number|null;
  departureTick?:number|null;
  minimumDwellTicks?:number|null;
  stoppingOffsetMetersBefore?:number|null;
  doorSide?:AuthoringDoorSide|null;
}
export interface AuthoringRunV1 {
  id:string;
  type:'PASSENGER'|'DEADHEAD';
  diagramLineId?:string|null;
  calls:AuthoringRunCallV1[];
}
export type AuthoringDutySelectorV1 =
  | {type:'TRAIN_NAME';name:string}
  | {type:'TAKE_FROM_DEPOT';depotId:string;tick:number;tags:string[]}
  | {type:'TAKE_FROM_WAYPOINT';wayPointIds:string[];tick:number};
export type AuthoringFunctionCallV1 =
  | {id:string;type:'PLACE_INITIAL';trainName:string;wayPointId:string}
  | {id:string;type:'REVERSE';tick:number}
  | {id:string;type:'WAIT';tick:number;durationTicks:number}
  | {id:string;type:'STORE_AT_DEPOT';tick:number;depotId:string;stablingPointIds:string[]};
export type AuthoringDutyElementV1 =
  | {id:string;type:'RUN';runId:string}
  | {id:string;type:'FUNCTION';call:AuthoringFunctionCallV1};
export interface AuthoringDutyV1 {
  id:string;
  selector:AuthoringDutySelectorV1;
  elements:AuthoringDutyElementV1[];
}
export interface ArrivalOverrideV1 {runId:string;callId:string;tick:number}
export interface TimetableAuthoringDocumentV1 {
  formatVersion:1;
  id:string;
  periodDays:number;
  runs:AuthoringRunV1[];
  duties:AuthoringDutyV1[];
  diagramLines:DiagramLineV1[];
  arrivalOverrides:ArrivalOverrideV1[];
}
export interface AuthoringIssue {path:string;message:string;severity:'error'|'warning'}
export interface GeneratedTimetableSource {
  source:string;
  issues:AuthoringIssue[];
  documentHashInput:string;
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

const issue=(path:string,message:string):AuthoringIssue=>({path,message,severity:'error'});
const nonNegativeInteger=(value:number)=>Number.isSafeInteger(value)&&value>=0;
const quote=(value:string)=>JSON.stringify(value);
const origin=(dutyId:string,elementId:string,runId?:string,functionId?:string)=>
  [`duty:${dutyId}`,`element:${elementId}`,runId&&`run:${runId}`,functionId&&`function:${functionId}`].filter(Boolean).join('/');

export function validateAuthoringDocument(document:TimetableAuthoringDocumentV1):AuthoringIssue[] {
  const issues:AuthoringIssue[]=[];
  if(document.formatVersion!==1) issues.push(issue('formatVersion','Unsupported editor document version'));
  if(!document.id.trim()) issues.push(issue('id','Timetable ID is required'));
  if(!Number.isSafeInteger(document.periodDays)||document.periodDays<1) issues.push(issue('periodDays','Period days must be a positive integer'));
  const periodTicks=Math.max(0,document.periodDays*24_000);
  const runIds=new Set<string>();
  document.runs.forEach((run,runIndex)=>{
    const path=`runs[${runIndex}]`;
    if(!run.id.trim()) issues.push(issue(`${path}.id`,'Run ID is required'));
    else if(runIds.has(run.id)) issues.push(issue(`${path}.id`,`Duplicate run ID: ${run.id}`));
    runIds.add(run.id);
    if(run.calls.length<2) issues.push(issue(`${path}.calls`,'A run requires an origin and a destination'));
    run.calls.forEach((call,callIndex)=>{
      const callPath=`${path}.calls[${callIndex}]`;
      if(!call.id.trim()) issues.push(issue(`${callPath}.id`,'Call ID is required'));
      if(!call.targetId.trim()) issues.push(issue(`${callPath}.targetId`,'Call target is required'));
      for(const [name,value] of [['arrivalTick',call.arrivalTick],['departureTick',call.departureTick]] as const) {
        if(value!=null&&(!nonNegativeInteger(value)||value>=periodTicks)) issues.push(issue(`${callPath}.${name}`,`${name} must be inside the timetable period`));
      }
      if(call.minimumDwellTicks!=null&&!nonNegativeInteger(call.minimumDwellTicks)) issues.push(issue(`${callPath}.minimumDwellTicks`,'Minimum dwell must be a non-negative integer'));
      if(call.arrivalTick!=null&&call.departureTick!=null&&call.departureTick<call.arrivalTick) issues.push(issue(callPath,'Departure must not be earlier than arrival'));
      if(callIndex<run.calls.length-1&&call.departureTick==null) issues.push(issue(`${callPath}.departureTick`,'Every non-terminal call requires a departure tick'));
    });
  });
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
      if(!nonNegativeInteger(duty.selector.tick)||duty.selector.tick>=periodTicks) issues.push(issue(`${path}.selector.tick`,'Acquisition tick must be inside the timetable period'));
    }
    if(duty.selector.type==='TAKE_FROM_WAYPOINT') {
      if(!duty.selector.wayPointIds.length||duty.selector.wayPointIds.some((id)=>!id.trim())) issues.push(issue(`${path}.selector.wayPointIds`,'At least one waypoint is required'));
      if(!nonNegativeInteger(duty.selector.tick)||duty.selector.tick>=periodTicks) issues.push(issue(`${path}.selector.tick`,'Acquisition tick must be inside the timetable period'));
    }
    let previousRun:AuthoringRunV1|undefined;
    duty.elements.forEach((element,elementIndex)=>{
      const elementPath=`${path}.elements[${elementIndex}]`;
      if(element.type==='RUN') {
        const run=document.runs.find((candidate)=>candidate.id===element.runId);
        if(!run) issues.push(issue(`${elementPath}.runId`,`Unknown run: ${element.runId}`));
        const owner=assignments.get(element.runId);
        if(owner) issues.push(issue(`${elementPath}.runId`,`Run ${element.runId} is already assigned to duty ${owner}`));
        else assignments.set(element.runId,duty.id);
        if(run&&previousRun) {
          const previousTerminal=previousRun.calls.at(-1)?.targetId;
          const nextOrigin=run.calls[0]?.targetId;
          if(previousTerminal&&nextOrigin&&previousTerminal!==nextOrigin) issues.push(issue(elementPath,`Run location discontinuity: ${previousTerminal} -> ${nextOrigin}`));
          const previousEnd=previousRun.calls.at(-1)?.arrivalTick ?? previousRun.calls.at(-1)?.departureTick;
          const nextStart=run.calls[0]?.departureTick;
          if(previousEnd!=null&&nextStart!=null&&nextStart<previousEnd) issues.push(issue(elementPath,`Run overlaps previous run by ${previousEnd-nextStart} ticks`));
        }
        if(run) previousRun=run;
      } else {
        const call=element.call;
        if(call.type==='PLACE_INITIAL'&&elementIndex!==0) issues.push(issue(elementPath,'Initial placement must be the first duty element'));
        if(call.type==='PLACE_INITIAL'&&duty.selector.type!=='TRAIN_NAME') issues.push(issue(elementPath,'Initial placement requires a named-train selector'));
        if(call.type==='PLACE_INITIAL'&&call.trainName!==((duty.selector.type==='TRAIN_NAME'&&duty.selector.name)||'')) issues.push(issue(elementPath,'Initial placement train must match the duty selector'));
        if('tick' in call&&(!nonNegativeInteger(call.tick)||call.tick>=periodTicks)) issues.push(issue(`${elementPath}.call.tick`,'Function tick must be inside the timetable period'));
        if(call.type==='WAIT'&&(!Number.isSafeInteger(call.durationTicks)||call.durationTicks<=0)) issues.push(issue(`${elementPath}.call.durationTicks`,'Wait duration must be a positive integer'));
      }
    });
  });
  document.runs.forEach((run,runIndex)=>{if(!assignments.has(run.id))issues.push(issue(`runs[${runIndex}]`,`Run ${run.id} is not assigned to a duty`));});
  return issues;
}

function selectorSource(selector:AuthoringDutySelectorV1):string {
  if(selector.type==='TRAIN_NAME') return quote(selector.name);
  if(selector.type==='TAKE_FROM_WAYPOINT') return `timetable.takeFromWaypoint([${selector.wayPointIds.map(quote).join(', ')}], ${selector.tick})`;
  const options=selector.tags.length?`, { tags: [${[...selector.tags].sort().map(quote).join(', ')}] }`:'';
  return options
    ? `timetable.takeFromDepot(${quote(selector.depotId)}${options}, ${selector.tick})`
    : `timetable.takeFromDepot(${quote(selector.depotId)}, ${selector.tick})`;
}

function destinationOptions(call:AuthoringRunCallV1,override?:number):string {
  const fields:string[]=[];
  const arrival=override??call.arrivalTick;
  if(arrival!=null) fields.push(`arrivalTick: ${arrival}`);
  if(call.minimumDwellTicks!=null) fields.push(`minimumDwellTicks: ${call.minimumDwellTicks}`);
  if(call.stoppingOffsetMetersBefore!=null) fields.push(`stoppingOffsetMetersBefore: ${call.stoppingOffsetMetersBefore}`);
  if(call.doorSide) fields.push(`doorSide: ${quote(call.doorSide)}`);
  return fields.length?`, { ${fields.join(', ')} }`:'';
}

export function generateTimetableSource(document:TimetableAuthoringDocumentV1):GeneratedTimetableSource {
  const issues=validateAuthoringDocument(document);
  const lines:string[]=['// Generated by JavaRailways WebUI. Do not edit in structured mode.','function main(timetable) {',`    timetable.setPeriodDays(${document.periodDays})`];
  for(const line of document.diagramLines) {
    lines.push('',`    timetable.diagramLine(${quote(line.id)}, {`,`        upStations: [${line.upStationIds.map(quote).join(', ')}],`,`        downStations: [${line.downStationIds.map(quote).join(', ')}]`,`    })`);
  }
  for(const duty of document.duties) {
    for(const element of duty.elements) if(element.type==='FUNCTION'&&element.call.type==='PLACE_INITIAL') {
      lines.push('',`    timetable.setInitialPosition(timetable.named(${quote(element.call.trainName)}), timetable.named(${quote(element.call.wayPointId)}))`);
    }
  }
  for(const duty of document.duties) {
    const assignedRuns=duty.elements.flatMap((element)=>element.type==='RUN'?[document.runs.find((run)=>run.id===element.runId)].filter(Boolean) as AuthoringRunV1[]:[]);
    const diagramIds=Array.from(new Set(assignedRuns.map((run)=>run.diagramLineId).filter(Boolean)));
    const serviceOptions=diagramIds.length===1?`, { diagramLineId: ${quote(diagramIds[0]!)} }`:'';
    lines.push('',`    timetable.service(${quote(duty.id)}, ${selectorSource(duty.selector)}${serviceOptions}, t => {`);
    let emittedCheck=false;
    for(const element of duty.elements) {
      if(element.type==='RUN') {
        const run=document.runs.find((candidate)=>candidate.id===element.runId);
        if(!run) continue;
        const scoped='scoped';
        lines.push(`        t.origin(${quote(origin(duty.id,element.id,run.id))}, ${scoped} => {`);
        if(!emittedCheck&&duty.selector.type==='TRAIN_NAME'&&run.calls[0]?.targetId) {
          lines.push(`            ${scoped}.check(${quote(run.calls[0].targetId)})`);
          emittedCheck=true;
        }
        for(let index=1;index<run.calls.length;index++) {
          const previous=run.calls[index-1],call=run.calls[index];
          if(previous.departureTick==null||!call.targetId) continue;
          const override=document.arrivalOverrides.find((item)=>item.runId===run.id&&item.callId===call.id)?.tick;
          lines.push(`            ${scoped}.at(${previous.departureTick}).destination(${quote(call.targetId)}${destinationOptions(call,override)})`);
        }
        lines.push('        })');
      } else {
        const call=element.call;
        if(call.type==='PLACE_INITIAL') continue;
        lines.push(`        t.origin(${quote(origin(duty.id,element.id,undefined,call.id))}, scoped => {`);
        if(call.type==='REVERSE') lines.push(`            scoped.at(${call.tick}).reverse()`);
        if(call.type==='WAIT') lines.push(`            scoped.at(${call.tick}).wait(${call.durationTicks})`);
        if(call.type==='STORE_AT_DEPOT') {
          const options=call.stablingPointIds.length?`, { stablingPoints: [${call.stablingPointIds.map(quote).join(', ')}] }`:'';
          lines.push(`            scoped.at(${call.tick}).storeAtDepot(${quote(call.depotId)}${options})`);
        }
        lines.push('        })');
      }
    }
    lines.push('    })');
  }
  lines.push('}','');
  return {source:lines.join('\n'),issues,documentHashInput:JSON.stringify(document)};
}

export interface CompiledEditorDocument {
  document:TimetableAuthoringDocumentV1;
  generatedSource:string;
  timetable?:SavedTimetableV1;
}
