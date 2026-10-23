import type {AuthoringIssue,TimetableAuthoringDocumentV1} from './authoring';
import {resolveProgramValue,validateStationProgramCall} from './stationPrograms';

export type FormationDisposition={type:'HOLD'}|{type:'DUTY';dutyId:string};
export interface FormationOutput {id:string;disposition:FormationDisposition}
export type AuthoringFormationEvent=
  | {id:string;kind:'SPLIT';coordinatorDutyId:string;partition:{from:'HEAD';firstUnitCount:number};outputs:(FormationOutput&{group:'FIRST'|'REST'})[]}
  | {id:string;kind:'COUPLE';coordinatorDutyId:string;approachingDutyId:string;stationaryDutyId:string;stationaryEnd:'HEAD'|'TAIL';rendezvous:{approachDestinationId:string;stationaryDestinationId:string};maximumApproachSpeedMetersPerSecond:number;output:FormationOutput};
export const formationOutputs=(event:AuthoringFormationEvent):FormationOutput[]=>event.kind==='SPLIT'?event.outputs:[event.output];
const fixedId=(id:unknown):id is string=>typeof id==='string'&&!!id.trim()&&id===id.trim()&&id.length<=256;

/** Strict structure shared with recovery; referential validation is a separate authoring diagnostic. */
export function formationEventShape(value:unknown):value is AuthoringFormationEvent {
  if(!value||typeof value!=='object'||Array.isArray(value))return false;
  const e=value as Record<string,unknown>;
  const fields=(object:Record<string,unknown>,keys:string[])=>Object.keys(object).length===keys.length&&keys.every((key)=>key in object);
  const output=(o:unknown,split=false):boolean=>{
    if(!o||typeof o!=='object'||Array.isArray(o))return false;
    const v=o as Record<string,unknown>,d=v.disposition as Record<string,unknown>|undefined;
    return fields(v,split?['id','group','disposition']:['id','disposition'])&&fixedId(v.id)&&(!split||v.group==='FIRST'||v.group==='REST')&&!!d&&typeof d==='object'&&
      (d.type==='HOLD'?fields(d,['type']):d.type==='DUTY'&&fields(d,['type','dutyId'])&&fixedId(d.dutyId));
  };
  if(!fixedId(e.id)||!fixedId(e.coordinatorDutyId))return false;
  if(e.kind==='SPLIT'){
    const p=e.partition as Record<string,unknown>|undefined;
    return fields(e,['id','kind','coordinatorDutyId','partition','outputs'])&&!!p&&typeof p==='object'&&fields(p,['from','firstUnitCount'])&&p.from==='HEAD'&&Number.isSafeInteger(p.firstUnitCount)&&Number(p.firstUnitCount)>0&&Number(p.firstUnitCount)<=2147483647&&Array.isArray(e.outputs)&&e.outputs.length===2&&e.outputs.every((o)=>output(o,true))&&new Set(e.outputs.map((o)=>o.group)).size===2&&new Set(e.outputs.map((o)=>o.id)).size===2;
  }
  if(e.kind==='COUPLE'){
    const r=e.rendezvous as Record<string,unknown>|undefined;
    return fields(e,['id','kind','coordinatorDutyId','approachingDutyId','stationaryDutyId','stationaryEnd','rendezvous','maximumApproachSpeedMetersPerSecond','output'])&&fixedId(e.approachingDutyId)&&fixedId(e.stationaryDutyId)&&e.approachingDutyId!==e.stationaryDutyId&&[e.approachingDutyId,e.stationaryDutyId].includes(e.coordinatorDutyId)&&['HEAD','TAIL'].includes(String(e.stationaryEnd))&&!!r&&typeof r==='object'&&fields(r,['approachDestinationId','stationaryDestinationId'])&&fixedId(r.approachDestinationId)&&fixedId(r.stationaryDestinationId)&&typeof e.maximumApproachSpeedMetersPerSecond==='number'&&Number.isFinite(e.maximumApproachSpeedMetersPerSecond)&&e.maximumApproachSpeedMetersPerSecond>0&&output(e.output);
  }
  return false;
}

export function validateFormationAuthoring(document:TimetableAuthoringDocumentV1):AuthoringIssue[] {
  const issues:AuthoringIssue[]=[];
  const error=(path:string,message:string)=>issues.push({path,message,severity:'error'});
  const events=document.formationEvents??[];
  if(!Array.isArray(events)){error('formationEvents','Formation events must be an array');return issues;}
  const catalog=new Map<string,AuthoringFormationEvent>();
  for(const [index,event] of events.entries()){
    const path=`formationEvents[${index}]`;
    if(!formationEventShape(event)){error(path,'Invalid fixed formation event');continue;}
    if(catalog.has(event.id))error(path,'Duplicate formation event ID');
    catalog.set(event.id,event);
  }
  const invocations=new Map<string,string[]>();
  for(const [di,duty] of document.duties.entries())for(const [ei,element] of duty.elements.entries()){
    if(element.type!=='PROGRAM')continue;
    const snapshot=document.boundPrograms?.find((p)=>p.programId===element.call.programId);
    if(!snapshot||validateStationProgramCall(snapshot,element.call,document.periodDays*24000).length)continue;
    for(const [si,step] of snapshot.definition.steps.entries())if(step.type==='COUPLE'||step.type==='SPLIT'){
      const id=resolveProgramValue(step.eventId,element.call.arguments),event=catalog.get(id),path=`duties[${di}].elements[${ei}]`;
      invocations.set(id,[...(invocations.get(id)??[]),duty.id]);
      if(!event||event.kind!==step.type){error(path,`Missing or mismatched formation event: ${id}`);continue;}
      const continues=formationOutputs(event).some((o)=>o.disposition.type==='DUTY'&&o.disposition.dutyId===duty.id);
      if(!continues&&(ei!==duty.elements.length-1||si!==snapshot.definition.steps.length-1))error(path,'Consumed formation participant must end at the fixed event');
    }
  }
  for(const event of catalog.values()){
    const path=`formationEvents[${events.indexOf(event)}]`,participants=event.kind==='SPLIT'?[event.coordinatorDutyId]:[event.approachingDutyId,event.stationaryDutyId];
    const calls=invocations.get(event.id)??[];
    for(const participant of participants)if(!document.duties.some((d)=>d.id===participant)||calls.filter((id)=>id===participant).length!==1)error(path,`Event requires exactly one invocation by duty: ${participant}`);
    if(calls.some((id)=>!participants.includes(id)))error(path,'Event invoked by a nonparticipant duty');
    const outputs=formationOutputs(event),assigned=outputs.flatMap((o)=>o.disposition.type==='DUTY'?[o.disposition.dutyId]:[]);
    if(new Set(assigned).size!==assigned.length)error(path,'Formation outputs cannot share a duty');
    for(const output of outputs){
      const takers=document.duties.filter((d)=>d.selector.type==='TAKE_FROM_FORMATION_OUTPUT'&&d.selector.eventId===event.id&&d.selector.outputId===output.id);
      const target=output.disposition.type==='DUTY'?output.disposition.dutyId:undefined;
      if(target&&!document.duties.some((d)=>d.id===target))error(path,`Unknown output duty: ${target}`);
      if(!target||participants.includes(target)){if(takers.length)error(path,'HOLD or continuing output cannot be reacquired');}
      else if(takers.length!==1||takers[0].id!==target)error(path,`Output ${output.id} requires reciprocal acquisition by ${target}`);
    }
  }
  for(const [index,duty] of document.duties.entries())if(duty.selector.type==='TAKE_FROM_FORMATION_OUTPUT'){
    const selector=duty.selector,event=catalog.get(selector.eventId),output=event&&formationOutputs(event).find((o)=>o.id===selector.outputId);
    if(!output||output.disposition.type!=='DUTY'||output.disposition.dutyId!==duty.id)error(`duties[${index}].selector`,'Formation acquisition must reciprocally own the declared output');
    if(!Number.isSafeInteger(selector.tick)||selector.tick<0||selector.tick>=document.periodDays*24000)error(`duties[${index}].selector.tick`,'Acquisition tick must be inside the timetable period');
  }
  return issues;
}

/** Rename every declared duty reference in the same document/history transaction. */
export function renameFormationDuty(events:AuthoringFormationEvent[]|undefined,previousId:string,nextId:string):AuthoringFormationEvent[]|undefined {
  const output=(o:FormationOutput):FormationOutput=>o.disposition.type==='DUTY'&&o.disposition.dutyId===previousId?{...o,disposition:{type:'DUTY',dutyId:nextId}}:o;
  return events?.map((event)=>{
    const coordinatorDutyId=event.coordinatorDutyId===previousId?nextId:event.coordinatorDutyId;
    return event.kind==='SPLIT'?{...event,coordinatorDutyId,outputs:event.outputs.map((o)=>({...output(o),group:o.group}))}:{...event,coordinatorDutyId,approachingDutyId:event.approachingDutyId===previousId?nextId:event.approachingDutyId,stationaryDutyId:event.stationaryDutyId===previousId?nextId:event.stationaryDutyId,output:output(event.output)};
  });
}
