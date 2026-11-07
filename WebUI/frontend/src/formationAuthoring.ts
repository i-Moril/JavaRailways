import type {AuthoringDutyElementV1,AuthoringDutySelectorV1,AuthoringIssue,TimetableAuthoringDocumentV1} from './authoring';
import type {TimedOperationDuration} from './types';

export type FormationDisposition={type:'HOLD'}|{type:'DUTY';dutyId:string};
export interface FormationOutput {id:string;disposition:FormationDisposition}
interface FormationTiming {tick:number;duration?:TimedOperationDuration;stationId?:string}
export type AuthoringFormationEvent=FormationTiming & (
  | {id:string;kind:'SPLIT';coordinatorDutyId:string;partition:{from:'HEAD';firstUnitCount:number};outputs:(FormationOutput&{group:'FIRST'|'REST'})[]}
  | {id:string;kind:'COUPLE';coordinatorDutyId:string;approachingDutyId:string;stationaryDutyId:string;stationaryEnd:'HEAD'|'TAIL';rendezvous:{approachDestinationId:string;stationaryDestinationId:string};maximumApproachSpeedMetersPerSecond:number;output:FormationOutput});
export const formationOutputs=(event:AuthoringFormationEvent):FormationOutput[]=>event.kind==='SPLIT'?event.outputs:[event.output];
const fixedId=(id:unknown):id is string=>typeof id==='string'&&!!id.trim()&&id===id.trim()&&id.length<=256;

/** Strict structure shared with recovery; referential validation is a separate authoring diagnostic. */
export function formationEventShape(value:unknown):value is AuthoringFormationEvent {
  if(!value||typeof value!=='object'||Array.isArray(value))return false;
  const e=value as Record<string,unknown>;
  const fields=(object:Record<string,unknown>,keys:string[],optional:string[]=[])=>Object.keys(object).every((key)=>keys.includes(key)||optional.includes(key))&&keys.every((key)=>key in object);
  const output=(o:unknown,split=false):boolean=>{
    if(!o||typeof o!=='object'||Array.isArray(o))return false;
    const v=o as Record<string,unknown>,d=v.disposition as Record<string,unknown>|undefined;
    return fields(v,split?['id','group','disposition']:['id','disposition'])&&fixedId(v.id)&&(!split||v.group==='FIRST'||v.group==='REST')&&!!d&&typeof d==='object'&&
      (d.type==='HOLD'?fields(d,['type']):d.type==='DUTY'&&fields(d,['type','dutyId'])&&fixedId(d.dutyId));
  };
  if(!fixedId(e.id)||!fixedId(e.coordinatorDutyId)||!Number.isSafeInteger(e.tick)||Number(e.tick)<0)return false;
  if(e.stationId!==undefined&&!fixedId(e.stationId))return false;
  if(e.duration!==undefined){const d=e.duration as Record<string,unknown>;if(!d||typeof d!=='object'||!(d.type==='SIMULATED'?fields(d,['type']):d.type==='MANUAL'&&fields(d,['type','ticks'])&&Number.isSafeInteger(d.ticks)&&Number(d.ticks)>=0))return false;}
  if(e.kind==='SPLIT'){
    const p=e.partition as Record<string,unknown>|undefined;
    return fields(e,['id','kind','coordinatorDutyId','partition','outputs','tick'],['duration','stationId'])&&!!p&&typeof p==='object'&&fields(p,['from','firstUnitCount'])&&p.from==='HEAD'&&Number.isSafeInteger(p.firstUnitCount)&&Number(p.firstUnitCount)>0&&Number(p.firstUnitCount)<=2147483647&&Array.isArray(e.outputs)&&e.outputs.length===2&&e.outputs.every((o)=>output(o,true))&&new Set(e.outputs.map((o)=>o.group)).size===2&&new Set(e.outputs.map((o)=>o.id)).size===2;
  }
  if(e.kind==='COUPLE'){
    const r=e.rendezvous as Record<string,unknown>|undefined;
    return fields(e,['id','kind','coordinatorDutyId','approachingDutyId','stationaryDutyId','stationaryEnd','rendezvous','maximumApproachSpeedMetersPerSecond','output','tick'],['duration','stationId'])&&fixedId(e.approachingDutyId)&&fixedId(e.stationaryDutyId)&&e.approachingDutyId!==e.stationaryDutyId&&[e.approachingDutyId,e.stationaryDutyId].includes(e.coordinatorDutyId)&&['HEAD','TAIL'].includes(String(e.stationaryEnd))&&!!r&&typeof r==='object'&&fields(r,['approachDestinationId','stationaryDestinationId'])&&typeof r.approachDestinationId==='string'&&typeof r.stationaryDestinationId==='string'&&typeof e.maximumApproachSpeedMetersPerSecond==='number'&&Number.isFinite(e.maximumApproachSpeedMetersPerSecond)&&e.maximumApproachSpeedMetersPerSecond>0&&output(e.output);
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
  for(const [di,duty] of document.duties.entries())for(const [ei,element] of duty.elements.entries())if(element.type==='FORMATION'){
    const event=catalog.get(element.eventId),path=`duties[${di}].elements[${ei}]`;
    if(!event){error(path,`Missing formation event: ${element.eventId}`);continue;}
    invocations.set(event.id,[...(invocations.get(event.id)??[]),duty.id]);
    const ordered=duty.elements.map((e,index)=>({e,index})).sort((a,b)=>formationTaskStart(document,a.e)-formationTaskStart(document,b.e)||a.index-b.index);
    if(!formationOutputs(event).some((o)=>o.disposition.type==='DUTY'&&o.disposition.dutyId===duty.id)&&ordered.at(-1)?.index!==ei)error(path,'Consumed formation participant must end at the fixed event');
  }
  for(const event of catalog.values()){
    const path=`formationEvents[${events.indexOf(event)}]`,participants=formationParticipants(event);
    const calls=invocations.get(event.id)??[];
    if(event.kind==='COUPLE'&&(!fixedId(event.rendezvous.approachDestinationId)||!fixedId(event.rendezvous.stationaryDestinationId)))error(path,'Coupling rendezvous destinations are required');
    if(event.tick>=document.periodDays*24000)error(path,'Formation tick must be inside the timetable period');
    const end=formationPlannedEnd(event);if(end!==undefined&&end>=document.periodDays*24000)error(path,'Formation duration crosses the timetable period');
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
    if(event&&formationParticipants(event).includes(duty.id))error(`duties[${index}].selector`,'Participant cannot reacquire its formation output');
    const available=event&&(formationPlannedEnd(event)??event.tick);
    if(available!==undefined)for(const element of duty.elements){const start=element.type==='RUN'?document.runs.find((run)=>run.id===element.runId)?.calls[0]?.departureTick:element.type==='FORMATION'?catalog.get(element.eventId)?.tick:element.type==='PROGRAM'?element.tick:document.events?.find((e)=>e.id===element.eventId)?.tick;if(start!=null&&start<available)error(`duties[${index}].elements`,'Task starts before its formation output is available');}
  }
  const dependencies=new Map(document.duties.map((duty)=>[duty.id,duty.selector.type==='TAKE_FROM_FORMATION_OUTPUT'?formationParticipants(catalog.get(duty.selector.eventId)):[]]));
  const visiting=new Set<string>(),visited=new Set<string>();
  function visit(id:string):boolean{if(visiting.has(id))return true;if(visited.has(id))return false;visiting.add(id);for(const parent of dependencies.get(id)??[])if(visit(parent))return true;visiting.delete(id);visited.add(id);return false;}
  for(const duty of document.duties)if(visit(duty.id)){error('formationEvents','Formation source dependency cycle');break;}
  const eventDependencies=new Map<string,Set<string>>(Array.from(catalog.keys(),(id)=>[id,new Set<string>()]));
  for(const duty of document.duties){
    let previous=duty.selector.type==='TAKE_FROM_FORMATION_OUTPUT'?duty.selector.eventId:undefined;
    const elements=duty.elements.map((e,index)=>({e,index})).sort((a,b)=>formationTaskStart(document,a.e)-formationTaskStart(document,b.e)||a.index-b.index);
    for(const {e} of elements)if(e.type==='FORMATION'){
      if(previous)eventDependencies.get(e.eventId)?.add(previous);
      previous=e.eventId;
    }
  }
  const eventVisiting=new Set<string>(),eventVisited=new Set<string>();
  function visitEvent(id:string):boolean{if(eventVisiting.has(id))return true;if(eventVisited.has(id))return false;eventVisiting.add(id);for(const parent of eventDependencies.get(id)??[])if(visitEvent(parent))return true;eventVisiting.delete(id);eventVisited.add(id);return false;}
  for(const id of catalog.keys())if(visitEvent(id)){error('formationEvents','Formation task dependency cycle');break;}

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

export function formationParticipants(event:AuthoringFormationEvent|undefined):string[]{return !event?[]:event.kind==='SPLIT'?[event.coordinatorDutyId]:[event.approachingDutyId,event.stationaryDutyId];}
/** Omitted duration is unknown, never an invented zero-length operation. */
export function formationPlannedEnd(event:AuthoringFormationEvent,measured?:number):number|undefined {const duration=event.duration?.type==='MANUAL'?event.duration.ticks:measured;return duration===undefined?undefined:event.tick+duration;}

/** Changes shared references and vehicle ownership in one document/history transaction. */
export function updateDutyFormation(document:TimetableAuthoringDocumentV1,event:AuthoringFormationEvent,elementIdFactory:()=>string=()=>`formation:${event.id}`):TimetableAuthoringDocumentV1 {
 if(event.kind==='SPLIT'&&(!Number.isSafeInteger(event.partition.firstUnitCount)||event.partition.firstUnitCount<1||event.partition.firstUnitCount>2147483647))throw new Error('Split unit count must be a positive Int32 integer');
 const participants=formationParticipants(event);
 if(new Set(participants).size!==participants.length||!participants.includes(event.coordinatorDutyId))throw new Error('Formation coordinator must be one of its distinct participants');
 if(participants.some((id)=>!document.duties.some((duty)=>duty.id===id)))throw new Error('Formation participants must be existing duties');
 const targets=formationOutputs(event).flatMap((o)=>o.disposition.type==='DUTY'?[o.disposition.dutyId]:[]);
 if(new Set(targets).size!==targets.length)throw new Error('Formation outputs cannot share a duty');
 if(targets.some((id)=>!document.duties.some((duty)=>duty.id===id)))throw new Error('Receiving duty must already exist');
 for(const target of targets.filter((id)=>!participants.includes(id))){const receiver=document.duties.find((duty)=>duty.id===target)!;if(receiver.selector.type==='TAKE_FROM_FORMATION_OUTPUT'&&receiver.selector.eventId!==event.id)throw new Error('Receiving duty already acquires another formation output');}

 const duties=document.duties.map((duty)=>{
  const existing=duty.elements.find((element)=>element.type==='FORMATION'&&element.eventId===event.id);
  let elements=duty.elements.filter((element)=>element.type!=='FORMATION'||element.eventId!==event.id||participants.includes(duty.id));
  if(participants.includes(duty.id)&&!existing)elements=[...elements,{id:elementIdFactory(),type:'FORMATION',eventId:event.id}];
  const output=formationOutputs(event).find((o)=>o.disposition.type==='DUTY'&&o.disposition.dutyId===duty.id);
  const selector=output&&!participants.includes(duty.id)?{type:'TAKE_FROM_FORMATION_OUTPUT' as const,eventId:event.id,outputId:output.id}:duty.selector;
  // A removed assignment deliberately retains its dangling source until the player chooses another.
  return {...duty,selector,elements};
 });
 const formationEvents=(document.formationEvents??[]).map((other)=>other.id===event.id?event:other);
 if(!formationEvents.some((other)=>other.id===event.id))formationEvents.push(event);
 return {...document,duties,formationEvents};
}
export function removeDutyFormation(document:TimetableAuthoringDocumentV1,eventId:string):TimetableAuthoringDocumentV1{return {...document,formationEvents:document.formationEvents?.filter((e)=>e.id!==eventId),duties:document.duties.map((duty)=>({...duty,elements:duty.elements.filter((e)=>e.type!=='FORMATION'||e.eventId!==eventId)}))};}
export function createDutyFormation(document:TimetableAuthoringDocumentV1,dutyId:string,kind:'SPLIT'|'COUPLE',eventId:string,elementIdFactory:()=>string):TimetableAuthoringDocumentV1 {
 if(document.formationEvents?.some((event)=>event.id===eventId))throw new Error('Duplicate formation event ID');
 const duty=document.duties.find((d)=>d.id===dutyId);if(!duty)throw new Error('Unknown duty');
 const timing={tick:0,duration:{type:'SIMULATED' as const}};
 const output={id:`${eventId}:output`,disposition:{type:'DUTY' as const,dutyId}};
 let event:AuthoringFormationEvent;
 if(kind==='SPLIT')event={...timing,id:eventId,kind,coordinatorDutyId:dutyId,partition:{from:'HEAD',firstUnitCount:1},outputs:[{...output,group:'FIRST'},{id:`${eventId}:rest`,group:'REST',disposition:{type:'HOLD'}}]};
 else {const other=document.duties.find((d)=>d.id!==dutyId);if(!other)throw new Error('Coupling requires another existing duty');event={...timing,id:eventId,kind,coordinatorDutyId:dutyId,approachingDutyId:dutyId,stationaryDutyId:other.id,stationaryEnd:'HEAD',rendezvous:{approachDestinationId:'',stationaryDestinationId:''},maximumApproachSpeedMetersPerSecond:1,output};}
 return updateDutyFormation(document,event,elementIdFactory);
}

function mapFormationOutputs(event:AuthoringFormationEvent,map:(output:FormationOutput)=>FormationOutput):AuthoringFormationEvent{return event.kind==='SPLIT'?{...event,outputs:event.outputs.map((o)=>({...map(o),group:o.group}))}:{...event,output:map(event.output)};}
/** Releasing a received source must release the matching declaration as well. */
export function setDutyVehicleSource(document:TimetableAuthoringDocumentV1,dutyId:string,selector:AuthoringDutySelectorV1):TimetableAuthoringDocumentV1 {
 const duty=document.duties.find((d)=>d.id===dutyId);if(!duty)throw new Error('Unknown duty');
 const previous=duty.selector;
 return {...document,duties:document.duties.map((d)=>d.id===dutyId?{...d,selector}:d),formationEvents:document.formationEvents?.map((event)=>previous.type==='TAKE_FROM_FORMATION_OUTPUT'&&event.id===previous.eventId?mapFormationOutputs(event,(o)=>o.id===previous.outputId&&o.disposition.type==='DUTY'&&o.disposition.dutyId===dutyId&&!(selector.type==='TAKE_FROM_FORMATION_OUTPUT'&&selector.eventId===event.id&&selector.outputId===o.id)?{...o,disposition:{type:'HOLD'}}:o):event)};
}

function formationTaskStart(document:TimetableAuthoringDocumentV1,element:AuthoringDutyElementV1):number {
 return element.type==='RUN'?document.runs.find((run)=>run.id===element.runId)?.calls[0]?.departureTick??0:element.type==='FORMATION'?document.formationEvents?.find((event)=>event.id===element.eventId)?.tick??0:element.type==='PROGRAM'?element.tick:document.events?.find((event)=>event.id===element.eventId)?.tick??0;
}
