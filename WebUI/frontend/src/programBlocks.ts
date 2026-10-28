import type {StationProgramDefinition,StationProgramParameterType,StationProgramStep} from './types';

export const programStepTypes:StationProgramStep['type'][]=['SET_DISPLAY','RESET_DISPLAY','WAIT_UNTIL','WAIT_EVENT','WAIT_DURATION','REVERSE','MOVE','SELECT_STOP','STORE_AT_DEPOT','EXTENSION','BRANCH','OPEN_DOORS','CLOSE_DOORS','END','ASYNC','AWAIT'];
export const libraryStepTypes:StationProgramStep['type'][]=['SET_DISPLAY','RESET_DISPLAY','WAIT_UNTIL','WAIT_EVENT','WAIT_DURATION','REVERSE','MOVE','STORE_AT_DEPOT','EXTENSION','BRANCH'];
export const arrivalStepTypes:StationProgramStep['type'][]=['SET_DISPLAY','RESET_DISPLAY','WAIT_UNTIL','WAIT_EVENT','WAIT_DURATION','REVERSE','OPEN_DOORS','CLOSE_DOORS','END','ASYNC','AWAIT','EXTENSION','BRANCH'];
export const expressionFields:Partial<Record<StationProgramStep['type'],Record<string,StationProgramParameterType>>>={
 SET_DISPLAY:{unitSlots:'UNIT_SLOTS',values:'JSON_OBJECT',removeKeys:'ID_LIST'},RESET_DISPLAY:{unitSlots:'UNIT_SLOTS'},WAIT_UNTIL:{tick:'TICK'},WAIT_DURATION:{durationTicks:'DURATION'},MOVE:{destinationId:'DESTINATION',viaIds:'ID_LIST'},SELECT_STOP:{stationId:'STATION',berthIds:'ID_LIST'},STORE_AT_DEPOT:{depotId:'DEPOT',stablingPointIds:'ID_LIST'},EXTENSION:{parameters:'JSON_OBJECT'},BRANCH:{parameters:'JSON_OBJECT'},
};
export interface ProgramBlockState {type:string;id?:string;fields?:Record<string,string|number>;inputs?:Record<string,{block?:ProgramBlockState}>;next?:{block:ProgramBlockState};x?:number;y?:number;enabled?:boolean;disabledReasons?:string[]}
export interface ProgramWorkspaceState {blocks?:{languageVersion:number;blocks:ProgramBlockState[]}}
const newStepId=(id:string)=>id.startsWith('$rail-step/')?id.slice(11):`step-${Array.from(id).map((char)=>char.charCodeAt(0).toString(16).padStart(4,'0')).join('')}`;
export function definitionToBlocks(definition:StationProgramDefinition):ProgramWorkspaceState {
 const chain=(steps:StationProgramStep[]):ProgramBlockState|undefined=>{
  let next:ProgramBlockState|undefined;
  for(const step of [...steps].reverse()){
   const source=step as unknown as Record<string,unknown>;const inputs:NonNullable<ProgramBlockState['inputs']>={};
   for(const [name,type] of Object.entries(expressionFields[step.type]??{}))if(source[name]!==undefined){const expression=source[name] as {parameterId?:string;value?:unknown};inputs[name]={block:{type:`rail_${'parameterId' in expression?'parameter':'literal'}_${type}`,id:`e_${step.id}_${name}`,fields:'parameterId' in expression?{VALUE:expression.parameterId??''}:{VALUE:['TICK','DURATION','ID_LIST','UNIT_SLOTS','JSON_OBJECT'].includes(type)?JSON.stringify(expression.value):String(expression.value)}}};}
   if(step.type==='BRANCH')for(const arm of ['thenSteps','elseSteps'] as const){const child=chain(step[arm]);if(child)inputs[arm]={block:child};}
   if(step.type==='ASYNC'){const child=chain(step.steps);if(child)inputs.steps={block:child};}
   const fields:Record<string,string|number>={};
   if(step.type==='ASYNC'||step.type==='AWAIT')fields.TASK_ID=step.taskId;
   if(step.type==='EXTENSION'||step.type==='BRANCH'){fields.TYPE_ID=step.typeId;fields.VERSION=String(step.version);}
   if(step.type==='SELECT_STOP')fields.POLICY=step.selectionPolicy;
   if(step.type==='WAIT_EVENT'){fields.EVENT_ID=step.eventId;fields.OFFSET=String(step.offsetTicks);}
   next={type:`rail_${step.type}`,id:`$rail-step/${step.id}`,fields,inputs,...(next?{next:{block:next}}:{})};
  }
  return next;
 };
 const first=chain(definition.steps);
 return {blocks:{languageVersion:0,blocks:[{type:'rail_entry',id:'$rail-entry',x:40,y:35,...(first?{next:{block:first}}:{})}]}};
}
/** Strict structure reader: every workspace block must belong to the single entry tree. */
export function blocksToDefinition(state:ProgramWorkspaceState,parameters:StationProgramDefinition['parameters'],context:'library'|'arrival'|'duty'='library'):StationProgramDefinition {
 const top=state.blocks?.blocks;
 if(!Array.isArray(top)||top.length!==1||top[0].type!=='rail_entry')throw new Error('Connect all blocks to the program entry');
 const visited=new Set<string>();let count=0;
 const mark=(block:ProgramBlockState)=>{if(block.enabled===false||block.disabledReasons?.length)throw new Error('Disabled blocks cannot be saved');if(!block.id||visited.has(block.id))throw new Error('Missing or duplicate block identity');visited.add(block.id);};
 mark(top[0]);
 const chain=(first:ProgramBlockState|undefined,depth:number):StationProgramStep[]=>{
  if(depth>16)throw new Error('Program branch depth exceeds16');
  const result:StationProgramStep[]=[];
  for(let block=first;block;block=block.next?.block){
   mark(block);if(++count>256)throw new Error('Program exceeds256 steps');
   const type=block.type.slice(5) as StationProgramStep['type'];
   if(!programStepTypes.includes(type)||block.type!==`rail_${type}`)throw new Error('Unsupported action block');
   if(context==='arrival'&&!arrivalStepTypes.includes(type))throw new Error('This action is unavailable at arrival');
   const step:Record<string,unknown>={id:newStepId(block.id!),type};
   const expected=new Set(Object.keys(expressionFields[type]??{}));
   if(type==='BRANCH'){expected.add('thenSteps');expected.add('elseSteps');}
   if(type==='ASYNC')expected.add('steps');
   for(const name of Object.keys(block.inputs??{}))if(!expected.has(name))throw new Error(`Unexpected block input: ${name}`);
   for(const [name,valueType] of Object.entries(expressionFields[type]??{})){
    const expression=block.inputs?.[name]?.block;
    if(!expression&&type==='SET_DISPLAY'&&name==='removeKeys')continue;
    if(!expression)throw new Error(`Missing input: ${name}`);
    mark(expression);
    if(expression.next||Object.keys(expression.inputs??{}).length)throw new Error('Malformed expression block');
    const raw=expression.fields?.VALUE;
    if(typeof raw!=='string')throw new Error(`Missing value: ${name}`);
    if(expression.type===`rail_parameter_${valueType}`)step[name]={parameterId:raw};
    else if(expression.type===`rail_literal_${valueType}`){try{step[name]={value:['TICK','DURATION','ID_LIST','UNIT_SLOTS','JSON_OBJECT'].includes(valueType)?JSON.parse(raw):raw};}catch{throw new Error(`Invalid value: ${name}`);}}
    else throw new Error(`Incompatible input: ${name}`);
   }
   if(type==='EXTENSION'||type==='BRANCH'){step.typeId=block.fields?.TYPE_ID??'';step.version=Number(block.fields?.VERSION);}
   if(type==='SELECT_STOP')step.selectionPolicy=block.fields?.POLICY;
   if(type==='WAIT_EVENT'){step.eventId=block.fields?.EVENT_ID??'';step.offsetTicks=Number(block.fields?.OFFSET??0);}
   if(type==='BRANCH')for(const arm of ['thenSteps','elseSteps'])step[arm]=chain(block.inputs?.[arm]?.block,depth+1);
   if(type==='ASYNC'||type==='AWAIT')step.taskId=block.fields?.TASK_ID??'';
   if(type==='ASYNC')step.steps=chain(block.inputs?.steps?.block,depth+1);
   result.push(step as unknown as StationProgramStep);
  }
  return result;
 };
 if(context!=='library'&&parameters.length)throw new Error('Arrival programs cannot declare parameters');
 return {parameters,steps:chain(top[0].next?.block,0)};
}
export function blockStepId(blockId:string){return newStepId(blockId);}
export function defaultProgramStep(type:StationProgramStep['type'],provider?:{typeId:string;version:number}):StationProgramStep{
 const step:Record<string,unknown>={id:`step-${Array.from(crypto.getRandomValues(new Uint8Array(16)),(byte)=>byte.toString(16).padStart(2,'0')).join('')}`,type};
 for(const [name,valueType] of Object.entries(expressionFields[type]??{}))step[name]={value:valueType==='UNIT_SLOTS'?[1]:valueType==='ID_LIST'?[]:valueType==='JSON_OBJECT'?{}:valueType==='DURATION'?1:valueType==='TICK'?0:''};
 if(type==='BRANCH'||type==='EXTENSION'){step.typeId=provider?.typeId??'';step.version=provider?.version??1;}
 if(type==='BRANCH'){step.thenSteps=[];step.elseSteps=[];}
 if(type==='SELECT_STOP')step.selectionPolicy='FIXED_PREFERENCE';
 if(type==='WAIT_EVENT'){step.eventId='event';step.offsetTicks=0;}
 if(type==='ASYNC'||type==='AWAIT')step.taskId='task';
 if(type==='ASYNC')step.steps=[];
 return step as unknown as StationProgramStep;
}
