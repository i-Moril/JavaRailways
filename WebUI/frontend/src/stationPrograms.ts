import type { TimetableAuthoringDocumentV1 } from './authoring';
import type { BoundStationProgram,StationProgramCall,StationProgramDefinition,StationProgramLiteral,StationProgramParameterType,StationProgramRecord,StationProgramStep,StationProgramValue } from './types';

const clone=<T,>(value:T):T=>JSON.parse(JSON.stringify(value)) as T;
const validId=(value:string)=>/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value);
const types:StationProgramParameterType[]=['TICK','DURATION','DESTINATION','STATION','DEPOT','FORMATION_EVENT','STRING','ID_LIST','JSON_OBJECT','UNIT_SLOTS'];
export function stationProgramLiteralValid(value:unknown,type:StationProgramParameterType):boolean {
  if(type==='UNIT_SLOTS')return Array.isArray(value)&&value.length>0&&value.length<=128&&value.every((slot)=>Number.isSafeInteger(slot)&&slot>0&&slot<=2147483647)&&new Set(value).size===value.length;
  if(type==='JSON_OBJECT')return programJsonObjectError(value)==='';
  if(type==='TICK'||type==='DURATION')return Number.isSafeInteger(value)&&Number(value)>=0;
  if(type==='ID_LIST')return Array.isArray(value)&&value.length<=256&&value.every((item)=>typeof item==='string'&&!!item.trim()&&item.length<=256);
  return typeof value==='string'&&!!value.trim()&&value.length<=256;
}
export function validateStationProgram(definition:StationProgramDefinition,args?:Record<string,StationProgramLiteral>):string[] {
  const errors:string[]=[];
  if(!definition||!Array.isArray(definition.parameters)||!Array.isArray(definition.steps))return ['Invalid program definition shape'];
  const parameters=new Map<string,StationProgramParameterType>();
  if(definition.parameters.length>64||definition.steps.length>256)errors.push('Program exceeds parameter/step limits');
  for(const parameter of definition.parameters){
    if(!parameter||typeof parameter.id!=='string'||typeof parameter.type!=='string'){errors.push('Invalid parameter shape');continue;}
    if(!validId(parameter.id)||parameters.has(parameter.id))errors.push(`Invalid or duplicate parameter ID: ${parameter.id}`);
    if(!types.includes(parameter.type))errors.push(`Unknown parameter type: ${parameter.type}`);
    parameters.set(parameter.id,parameter.type);
    if(args&&!stationProgramLiteralValid(args[parameter.id],parameter.type))errors.push(`Invalid argument: ${parameter.id}`);
  }
  if(args)for(const name of Object.keys(args))if(!parameters.has(name))errors.push(`Unknown argument: ${name}`);
  const check=(value:StationProgramValue<StationProgramLiteral>,type:StationProgramParameterType,positive=false)=>{
    if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==1){errors.push('Program value requires exactly value or parameterId');return;}
    if('parameterId' in value){
      if(parameters.get(value.parameterId)!==type)errors.push(`Unknown or incompatible parameter: ${value.parameterId}`);
      else if(args&&positive&&Number(args[value.parameterId])<=0)errors.push('Wait duration must be positive');
    }else if(!('value' in value)||!stationProgramLiteralValid(value.value,type)||positive&&Number(value.value)<=0)errors.push(`Invalid ${type} value`);
  };
  const ids=new Set<string>();
  let count=0;
  const visit=(steps:StationProgramStep[],depth:number)=>{
  if(!Array.isArray(steps)||depth>16){errors.push('Invalid branch steps or depth exceeds16');return;}
  for(const step of steps){
    if(++count>256){errors.push('Program exceeds256 total steps');return;}
    if(!step||typeof step.id!=='string'||typeof step.type!=='string'){errors.push('Invalid step shape');continue;}
    if(!validId(step.id)||ids.has(step.id))errors.push(`Invalid or duplicate step ID: ${step.id}`);
    ids.add(step.id);
    if(depth>0&&['COUPLE','SPLIT','STORE_AT_DEPOT'].includes(step.type))errors.push(`Fixed operations cannot be conditional: ${step.id}`);
    switch(step.type){
      case 'SET_DISPLAY':{
        check(step.unitSlots,'UNIT_SLOTS');check(step.values,'JSON_OBJECT');if(step.removeKeys)check(step.removeKeys,'ID_LIST');
        const values=step.values&&('value' in step.values?step.values.value:args?.[step.values.parameterId]);
        if(values!==undefined){const error=displayValuesError(values);if(error)errors.push(error);}
        const keys=step.removeKeys&&('value' in step.removeKeys?step.removeKeys.value:args?.[step.removeKeys.parameterId]);
        if(Array.isArray(keys)&&new Set<unknown>(keys).size!==keys.length)errors.push('Display removal keys must be unique');
        break;
      }
      case 'RESET_DISPLAY':check(step.unitSlots,'UNIT_SLOTS');break;
      case 'WAIT_UNTIL':check(step.tick,'TICK');break;
      case 'WAIT_DURATION':check(step.durationTicks,'DURATION',true);break;
      case 'BRANCH':visit(step.thenSteps,depth+1);visit(step.elseSteps,depth+1);
      // Condition and action calls share the same typed provider-call shape.
      case 'EXTENSION':
        if(typeof step.typeId!=='string'||step.typeId.length>256||! /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(step.typeId))errors.push('Extension typeId must be namespaced');
        if(!Number.isInteger(step.version)||step.version<1||step.version>2147483647)errors.push('Extension version must be a positive integer');
        check(step.parameters,'JSON_OBJECT');break;
      case 'COUPLE':case 'SPLIT':check(step.eventId,'FORMATION_EVENT');break;
      case 'REVERSE':break;
      case 'MOVE':check(step.destinationId,'DESTINATION');check(step.viaIds,'ID_LIST');break;
      case 'SELECT_STOP':
        check(step.stationId,'STATION');check(step.berthIds,'ID_LIST');
        if(!['FIXED_PREFERENCE','AVAILABLE_PRIORITY'].includes(step.selectionPolicy))errors.push('Invalid station selection policy');
        {const expression=step.berthIds;const ids=expression&&('value' in expression?expression.value:args?.[expression.parameterId]);
        if(Array.isArray(ids)&&(!ids.length||ids.length>64||new Set<unknown>(ids).size!==ids.length))errors.push('Station selection requires distinct nonempty berth IDs (at most64)');}
        break;
      case 'STORE_AT_DEPOT':check(step.depotId,'DEPOT');check(step.stablingPointIds,'ID_LIST');break;
      default:errors.push('Unsupported program step');
    }
  }
  };
  visit(definition.steps,0);
  return errors;
}
export const resolveProgramValue=<T extends StationProgramLiteral>(value:StationProgramValue<T>,args:Record<string,StationProgramLiteral>):T=>('parameterId' in value?args[value.parameterId]:value.value) as T;

export function validateStationProgramCall(snapshot:BoundStationProgram,call:StationProgramCall,periodTicks:number):string[] {
  if(!call||typeof call.arguments!=='object'||!call.arguments||Array.isArray(call.arguments))return ['Invalid program call arguments'];
  const errors=validateStationProgram(snapshot.definition,call.arguments);
  if(!snapshot.definition.steps?.length)errors.push('Program requires at least one step');
  if(!validId(call.id))errors.push('Invalid program call ID');
  if(!Number.isSafeInteger(call.tick)||call.tick<0||call.tick>=periodTicks)errors.push('Program call tick must be inside the timetable period');
  if(errors.length)return errors;
  return stationProgramSchedule(snapshot.definition.steps,call.arguments,call.tick,periodTicks).errors;
}
/** Requires a structurally validated program and arguments. */
export function stationProgramSchedule(steps:StationProgramStep[],args:Record<string,StationProgramLiteral>,startTick:number,periodTicks:number){
  const errors:string[]=[];
  const visit=(steps:StationProgramStep[],startTick:number,startEarliest:number):[number,number]=>{
    let tick=startTick,earliest=startEarliest;
    for(const step of steps){
      if(step.type==='WAIT_UNTIL'){
        const next=resolveProgramValue(step.tick,args);
        if(next<tick||next>=periodTicks)errors.push(`Invalid schedule at step: ${step.id}`);
        tick=next;earliest=Math.max(earliest,next);
      }
      if(step.type==='WAIT_DURATION')earliest+=resolveProgramValue(step.durationTicks,args);
      if(step.type==='BRANCH'){
        const yes=visit(step.thenSteps,tick,earliest),no=visit(step.elseSteps,tick,earliest);
        tick=Math.max(yes[0],no[0]);earliest=Math.max(yes[1],no[1]);
      }
      if(!Number.isSafeInteger(earliest)||earliest>=periodTicks)errors.push(`Program wait crosses timetable period at step: ${step.id}`);
    }
    return [tick,earliest];
  };
  const [,earliest]=visit(steps,startTick,startTick);
  return {errors,earliest};
}
export function adoptStationProgram(document:TimetableAuthoringDocumentV1,record:StationProgramRecord):TimetableAuthoringDocumentV1 {
  if(record.archived)throw new Error('Archived programs cannot be newly bound');
  if(!validId(record.id)||!Number.isSafeInteger(record.revision)||record.revision<1||!/^sha256:[a-f0-9]{64}$/.test(record.contentIdentity))throw new Error('Invalid program library record');
  const errors=validateStationProgram(record.definition);
  if(errors.length)throw new Error(errors.join('; '));
  const existing=document.boundPrograms?.find((item)=>item.programId===record.id);
  if(existing?.revision===record.revision&&existing.contentIdentity===record.contentIdentity)return document;
  const snapshot:BoundStationProgram={programId:record.id,revision:record.revision,contentIdentity:record.contentIdentity,definition:clone(record.definition)};
  return {...document,boundPrograms:[...(document.boundPrograms??[]).filter((item)=>item.programId!==record.id),snapshot]};
}

/** Finite JSON data, with no JavaScript or recursive parameter interpolation. */
export function programJsonObjectError(value:unknown):string {
  if(!value||typeof value!=='object'||Array.isArray(value))return 'JSON_OBJECT must be a JSON object';
  let nodes=0;
  const walk=(node:unknown,depth:number):string=>{
    if(depth>16||++nodes>4096)return 'JSON_OBJECT exceeds depth16 or4096 values';
    if(node===null||typeof node==='boolean')return '';
    if(typeof node==='number')return Number.isFinite(node)&&(!Number.isInteger(node)||Number.isSafeInteger(node))?'':'JSON number must be finite and integral values must be safe integers';
    if(typeof node==='string')return node.length<=8192?'':'JSON string exceeds8192 characters';
    if(typeof node!=='object')return 'Unsupported JSON value';
    if(Array.isArray(node)){for(const child of node){const error=walk(child,depth+1);if(error)return error;}return '';}
    if(Object.getPrototypeOf(node)!==Object.prototype&&Object.getPrototypeOf(node)!==null)return 'Unsupported JSON object';
    for(const [key,child] of Object.entries(node)){if(key.length>8192)return 'JSON object key exceeds8192 characters';const error=walk(child,depth+1);if(error)return error;}
    return '';
  };
  return walk(value,0);
}

export function findProgramStep(steps:StationProgramStep[],id:string):StationProgramStep|undefined {
  for(const step of steps){
    if(step.id===id)return step;
    if(step.type==='BRANCH'){
      const found=findProgramStep(step.thenSteps,id)??findProgramStep(step.elseSteps,id);
      if(found)return found;
    }
  }
}

export function displayValuesError(value:unknown):string {
  const error=programJsonObjectError(value);if(error)return error;
  const keys=Object.keys(value as object);
  return keys.length>128||keys.some((key)=>!key.trim())||JSON.stringify(value).length>65536?'Display values require at most128 nonblank keys and64 KiB':'';
}

/** Arrival programs run inside a stop: they may not change its route or formation. */
export function validateArrivalProgram(definition:StationProgramDefinition,periodTicks?:number):string[] {
  const errors=validateStationProgram(definition);
  if(errors.length)return errors;
  if(definition.parameters.length)errors.push('Arrival programs cannot declare parameters');
  const visit=(steps:StationProgramStep[])=>{for(const step of steps){
    if(!['SET_DISPLAY','RESET_DISPLAY','WAIT_UNTIL','WAIT_DURATION','BRANCH','EXTENSION'].includes(step.type))errors.push(`Unsupported arrival action: ${step.id} (${step.type})`);
    if(step.type==='BRANCH'){visit(step.thenSteps);visit(step.elseSteps);}
  }};
  visit(definition.steps);
  if(!errors.length&&periodTicks!==undefined)errors.push(...stationProgramSchedule(definition.steps,{},0,periodTicks).errors);
  return errors;
}

/** Copying creates new step identities without sharing mutable nested values. */
export function copyStationProgram(definition:StationProgramDefinition,idFactory:()=>string,shiftTicks=0):StationProgramDefinition {
  const copied=clone(definition);
  const visit=(steps:StationProgramStep[])=>{for(const step of steps){step.id=idFactory();if(step.type==='WAIT_UNTIL'&&'value' in step.tick){const tick=step.tick.value+shiftTicks;if(!Number.isSafeInteger(tick)||tick<0)throw new Error('Shifted arrival wait must be a non-negative safe integer');step.tick={value:tick};}if(step.type==='BRANCH'){visit(step.thenSteps);visit(step.elseSteps);}}};
  visit(copied.steps);return copied;
}
