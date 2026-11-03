import {evaluateProgramExpression,validateProgramExpression} from './programExpressions';
import {trainInfoError} from './trainInfo';
import type { StationProgramCall,StationProgramDefinition,StationProgramLiteral,StationProgramParameterType,StationProgramRecord,StationProgramStep,StationProgramValue } from './types';

const clone=<T,>(value:T):T=>JSON.parse(JSON.stringify(value)) as T;
const validId=(value:string)=>/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value);
const types:StationProgramParameterType[]=['NUMBER','TICK','DURATION','STRING','ID_LIST','JSON_OBJECT'];
export function stationProgramLiteralValid(value:unknown,type:StationProgramParameterType):boolean {
  if(type==='NUMBER')return typeof value==='number'&&Number.isFinite(value)&&(!Number.isInteger(value)||Number.isSafeInteger(value));
  if(type==='STRING')return typeof value==='string'&&value.length<=8192;
  if(type==='JSON_OBJECT')return programJsonObjectError(value)==='';
  if(type==='TICK'||type==='DURATION')return Number.isSafeInteger(value)&&Number(value)>=0;
  if(type==='ID_LIST')return Array.isArray(value)&&value.length<=256&&value.every((item)=>typeof item==='string'&&!!item.trim()&&item.length<=256);
  return false;
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
  const check=(value:StationProgramValue<StationProgramLiteral>,type:StationProgramParameterType,positive=false,allowTrainInfo=false)=>{
    if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==1){errors.push('Program value requires exactly value or parameterId');return;}
    if('trainInfo' in value){if(!allowTrainInfo)errors.push('Train information is unavailable in this input');else {const error=trainInfoError(value.trainInfo);if(error)errors.push(error);}return;}
    try{validateProgramExpression(value,type,parameters,stationProgramLiteralValid,args);}catch{errors.push('Invalid program expression (incompatible input or invalid value)');return;}
    if(positive){try{if(Number(resolveProgramValue(value,args??{}))<=0)errors.push('Wait duration must be positive');}catch{/* Unbound parameter expressions are evaluated when invoked. */}}
  };
  const checkId=(value:StationProgramValue<string>)=>{
    check(value,'STRING');
    let resolved:unknown;
    try{resolved=resolveProgramValue(value,args??{});}catch{return; /* Unbound expressions are checked when invoked. */}
    if(typeof resolved!=='string'||!resolved.trim()||resolved.length>256)errors.push('Action ID must be nonblank and at most256 characters');
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
    if(depth>0&&step.type==='STORE_AT_DEPOT')errors.push(`Fixed operations cannot be conditional: ${step.id}`);
    switch(step.type){
      case 'SET_DISPLAY':{
        check(step.values as StationProgramValue<StationProgramLiteral>,'JSON_OBJECT',false,true);if(step.removeKeys)check(step.removeKeys,'ID_LIST');
        let values:unknown;try{if(step.values&&!('trainInfo' in step.values))values=resolveProgramValue(step.values,args??{});}catch{/* Unbound parameters are checked when invoked. */}
        if(values!==undefined){const error=displayValuesError(values);if(error)errors.push(error);}
        const keys=step.removeKeys&&('value' in step.removeKeys?step.removeKeys.value:'parameterId' in step.removeKeys?args?.[step.removeKeys.parameterId]:undefined);
        if(Array.isArray(keys)&&new Set<unknown>(keys).size!==keys.length)errors.push('Display removal keys must be unique');
        break;
      }
      case 'RESET_DISPLAY':break;
      case 'WAIT_UNTIL':check(step.tick,'TICK');break;
      case 'WAIT_EVENT':if(!validId(step.eventId)||!Number.isSafeInteger(step.offsetTicks))errors.push('Invalid event wait');break;
      case 'WAIT_DURATION':check(step.durationTicks,'DURATION',true);break;
      case 'BRANCH':
        if(!['run_type','line_direction','station_id','run_id','line_id','call_status','duty_id'].some((field)=>step.typeId===`javarailways:plan_${field}`)||step.version!==1)errors.push('Conditions must use planned timetable context');
        visit(step.thenSteps,depth+1);visit(step.elseSteps,depth+1);
      // Condition and action calls share the same typed provider-call shape.
      case 'EXTENSION':
        if(typeof step.typeId!=='string'||step.typeId.length>256||! /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(step.typeId))errors.push('Extension typeId must be namespaced');
        if(!Number.isInteger(step.version)||step.version<1||step.version>2147483647)errors.push('Extension version must be a positive integer');
        check(step.parameters as StationProgramValue<StationProgramLiteral>,'JSON_OBJECT',false,step.type==='EXTENSION');break;
      case 'OPEN_DOORS':{
        if(step.doorSideOverride!=null){
          check(step.doorSideOverride,'STRING');
          let side:unknown;
          try{side=resolveProgramValue(step.doorSideOverride,args??{});}catch{/* Unbound STRING expressions are checked when invoked. */}
          if(side!==undefined&&!['LEFT','RIGHT','BOTH'].includes(side as string))errors.push('Door side override must be LEFT, RIGHT or BOTH');
        }
        break;
      }
      case 'REVERSE':case 'CLOSE_DOORS':case 'END':break;
      case 'ASYNC':if(!validId(step.taskId))errors.push('Invalid task ID');visit(step.steps,depth+1);break;
      case 'AWAIT':if(!validId(step.taskId))errors.push('Invalid task ID');break;
      case 'MOVE':checkId(step.destinationId);check(step.viaIds,'ID_LIST');break;
      case 'SELECT_STOP':
        checkId(step.stationId);check(step.berthIds,'ID_LIST');
        if(!['FIXED_PREFERENCE','AVAILABLE_PRIORITY'].includes(step.selectionPolicy))errors.push('Invalid station selection policy');
        {const expression=step.berthIds;const ids=expression&&('value' in expression?expression.value:'parameterId' in expression?args?.[expression.parameterId]:undefined);
        if(Array.isArray(ids)&&(!ids.length||ids.length>64||new Set<unknown>(ids).size!==ids.length))errors.push('Station selection requires distinct nonempty berth IDs (at most64)');}
        break;
      case 'STORE_AT_DEPOT':checkId(step.depotId);check(step.stablingPointIds,'ID_LIST');break;
      default:errors.push('Unsupported program step');
    }
  }
  };
  visit(definition.steps,0);
  return errors;
}
export const resolveProgramValue=<T extends StationProgramLiteral>(value:StationProgramValue<T>,args:Record<string,StationProgramLiteral>):T=>evaluateProgramExpression(value,args) as T;

export function validateStationProgramCall(snapshot:Pick<StationProgramRecord,'definition'>,call:StationProgramCall,periodTicks:number):string[] {
  if(!call||typeof call.arguments!=='object'||!call.arguments||Array.isArray(call.arguments))return ['Invalid program call arguments'];
  const errors=validateStationProgram(snapshot.definition,call.arguments);
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
        if(!Number.isSafeInteger(next)||next<0||next>=periodTicks)errors.push(`Invalid schedule at step: ${step.id}`);
        tick=Math.max(tick,next);earliest=Math.max(earliest,next);
      }
      if(step.type==='WAIT_DURATION')earliest+=resolveProgramValue(step.durationTicks,args);
      if(step.type==='ASYNC'){const child=visit(step.steps,tick,earliest);earliest=Math.max(earliest,child[1]);}
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
    if(step.type==='ASYNC'){const found=findProgramStep(step.steps,id);if(found)return found;}
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
    if(!['SET_DISPLAY','RESET_DISPLAY','WAIT_UNTIL','WAIT_EVENT','WAIT_DURATION','REVERSE','OPEN_DOORS','CLOSE_DOORS','END','ASYNC','AWAIT','BRANCH','EXTENSION'].includes(step.type))errors.push(`Unsupported arrival action: ${step.id} (${step.type})`);
    if(step.type==='OPEN_DOORS'&&'doorSideOverride' in step)errors.push(`Door side overrides are unavailable at arrival: ${step.id}`);
    if(step.type==='ASYNC')visit(step.steps);
    if(step.type==='BRANCH'){visit(step.thenSteps);visit(step.elseSteps);}
  }};
  visit(definition.steps);
  if(!errors.length&&periodTicks!==undefined)errors.push(...stationProgramSchedule(definition.steps,{},0,periodTicks).errors);
  return errors;
}

/** Copying creates new step identities without sharing mutable nested values. */
export function copyStationProgram(definition:StationProgramDefinition,idFactory:()=>string,shiftTicks=0):StationProgramDefinition {
  const copied=clone(definition);
  const visit=(steps:StationProgramStep[])=>{for(const step of steps){step.id=idFactory();if(step.type==='WAIT_UNTIL'&&'value' in step.tick){const tick=step.tick.value+shiftTicks;if(!Number.isSafeInteger(tick)||tick<0)throw new Error('Shifted arrival wait must be a non-negative safe integer');step.tick={value:tick};}if(step.type==='ASYNC')visit(step.steps);
    if(step.type==='BRANCH'){visit(step.thenSteps);visit(step.elseSteps);}}};
  visit(copied.steps);return copied;
}
