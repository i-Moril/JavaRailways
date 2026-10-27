import {findProgramStep} from './stationPrograms';
import type {TimetableAuthoringDocumentV1} from './authoring';
import type {SavedTimetableV1} from './types';
export interface ProgramSourceTarget {dutyId:string;elementId:string;callId:string;programId:string;revision:number;contentIdentity:string;stepId?:string}
/** Resolve current visual identities only; a trace from another document input is never used. */
export function resolveProgramSourceTarget(document:TimetableAuthoringDocumentV1,path:string,currentInput:string,compiled?:{input:string;timetable:SavedTimetableV1}):ProgramSourceTarget|undefined {
  if(compiled?.input===currentInput&&compiled.timetable.id===document.id){
    for(const duty of document.duties){
      const prefix=`[${document.id}/${duty.id}] step#`,index=path.indexOf(prefix);
      if(index<0)continue;
      const address=/^(\d+)((?:\/(?:then|else)\[\d+\])*)(?![\d/])/.exec(path.slice(index+prefix.length));
      const stepIndex=address?.[1];
      const branchPath=[...(address?.[2]??'').matchAll(/\/(then|else)\[(\d+)\]/g)].map((match)=>({arm:match[1].toUpperCase(),index:Number(match[2])}));
      const trace=stepIndex===undefined?undefined:compiled.timetable.compilationTrace?.find((item)=>item.serviceId===duty.id&&item.stepIndex===Number(stepIndex)&&JSON.stringify(item.branchPath??[])===JSON.stringify(branchPath));
      if(trace)return resolveProgramSourceTarget(document,trace.origin,currentInput);
    }
  }
  const indexed=/^duties\[(\d+)\]\.elements\[(\d+)\]/.exec(path);
  const targets:ProgramSourceTarget[]=[];
  document.duties.forEach((duty,dutyIndex)=>duty.elements.forEach((element,elementIndex)=>{
    if(element.type!=='PROGRAM')return;
    const snapshot=document.boundPrograms?.find((item)=>item.programId===element.call.programId);if(!snapshot)return;
    const stem=`duty:${duty.id}/element:${element.id}/call:${element.call.id}/program:${snapshot.programId}`;
    const offset=path.indexOf(stem);
    const byIndex=indexed&&Number(indexed[1])===dutyIndex&&Number(indexed[2])===elementIndex;
    if(offset<0&&!byIndex)return;
    const rest=offset<0?'':path.slice(offset+stem.length);
    if(rest&&!rest.startsWith('/step:')&&!/^[\s"':]/.test(rest))return;
    const stepId=rest.startsWith('/step:')?/^\/step:([A-Za-z0-9_-]+)/.exec(rest)?.[1]:undefined;
    if(rest.startsWith('/step:')&&(!stepId||!findProgramStep(snapshot.definition.steps,stepId)))return;
    targets.push({dutyId:duty.id,elementId:element.id,callId:element.call.id,programId:snapshot.programId,revision:snapshot.revision,contentIdentity:snapshot.contentIdentity,stepId});
  }));
  return targets.length===1?targets[0]:undefined;
}

export interface ArrivalSourceTarget {runId:string;callId:string;stepId?:string}
export function resolveArrivalSourceTarget(document:TimetableAuthoringDocumentV1,path:string,currentInput:string,compiled?:{input:string;timetable:SavedTimetableV1}):ArrivalSourceTarget|undefined {
  if(compiled?.input===currentInput&&compiled.timetable.id===document.id){
    for(const duty of document.duties){
      const prefix=`[${document.id}/${duty.id}] step#`,index=path.indexOf(prefix);
      if(index<0)continue;
      const address=/^(\d+)((?:\/(?:then|else)\[\d+\])*)(?![\d/])/.exec(path.slice(index+prefix.length));
      if(!address)continue;
      const branches=[...address[2].matchAll(/\/(then|else)\[(\d+)\]/g)].map((match)=>({arm:match[1].toUpperCase(),index:Number(match[2])}));
      const trace=compiled.timetable.compilationTrace?.find((item)=>item.serviceId===duty.id&&item.stepIndex===Number(address[1])&&JSON.stringify(item.branchPath??[])===JSON.stringify(branches));
      if(trace)return resolveArrivalSourceTarget(document,trace.origin,currentInput);
    }
  }
  const targets:ArrivalSourceTarget[]=[];
  for(const duty of document.duties)for(const element of duty.elements){
    if(element.type!=='RUN')continue;
    const run=document.runs.find((item)=>item.id===element.runId);if(!run)continue;
    for(const call of run.calls){
      const station=document.stationPrograms?.find((program)=>program.stationId===call.stationId);if(!station)continue;
      const stem=`duty:${duty.id}/element:${element.id}/run:${run.id}/arrival:${call.id}`;
      const offset=path.indexOf(stem);if(offset<0)continue;
      const rest=path.slice(offset+stem.length);
      if(rest&&!rest.startsWith('/step:')&&!/^[\s"':]/.test(rest))continue;
      const stepId=rest.startsWith('/step:')?/^\/step:([A-Za-z0-9_-]+)/.exec(rest)?.[1]:undefined;
      if(rest.startsWith('/step:')&&(!stepId||!(findProgramStep(station.arrival.steps,stepId)||findProgramStep(station.departurePreparation.steps,stepId))))continue;
      targets.push({runId:run.id,callId:call.id,stepId});
    }
  }
  return targets.length===1?targets[0]:undefined;
}
