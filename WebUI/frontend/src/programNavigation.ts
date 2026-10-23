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
