import {validatePreparationLead} from './stationPreparationLead';
import type {StationProgramDefinition,StationProgramStep,StationStopProgram,TimetableEventPoint} from './types';
import {validateArrivalProgram} from './stationPrograms';

export function defaultStationStopProgram(stationId:string):StationStopProgram{return {stationId,preparationLead:{doorCloseTicks:true},arrival:{parameters:[],steps:[{id:'open',type:'OPEN_DOORS'},{id:'arrival-end',type:'END'}]},departurePreparation:{parameters:[],steps:[{id:'close',type:'CLOSE_DOORS'},{id:'preparation-end',type:'END'}]}};}
export function resolveEventWaits(definition:StationProgramDefinition,events:TimetableEventPoint[],periodTicks:number):StationProgramDefinition{
 const visit=(steps:StationProgramStep[]):StationProgramStep[]=>steps.map((step)=>{
  if(step.type==='WAIT_EVENT'){
   const event=events.find((event)=>event.id===step.eventId);if(!event)throw new Error(`Unknown event: ${step.eventId}`);
   const tick=event.tick+step.offsetTicks;if(!Number.isSafeInteger(tick)||tick<0||tick>=periodTicks)throw new Error(`Event wait outside timetable period: ${step.eventId}`);
   return {id:step.id,type:'WAIT_UNTIL',tick:{value:tick}};
  }
  if(step.type==='ASYNC')return {...step,steps:visit(step.steps)};
  if(step.type==='BRANCH')return {...step,thenSteps:visit(step.thenSteps),elseSteps:visit(step.elseSteps)};
  return step;
 });return {...definition,steps:visit(definition.steps)};
}
export function validateStopProgram(program:StationStopProgram,events:TimetableEventPoint[],periodTicks:number):string[]{
 const errors:string[]=[];
 if(!program.stationId.trim())errors.push('Invalid station');
 try{validatePreparationLead(program.preparationLead);}catch{errors.push('Invalid preparation lead');}
 const tasks=new Set<string>();const awaits:string[]=[];
 for(const definition of [program.arrival,program.departurePreparation]){
  errors.push(...validateArrivalProgram(definition));
  const walk=(steps:StationProgramStep[],async:boolean):boolean=>{
   let ended=false;
   for(const step of steps){
    if(ended)errors.push('Unreachable action after END');
    if(step.type==='END'){if(async)errors.push('END is not allowed in a nonblocking task');ended=true;}
    if(step.type==='ASYNC'){if(tasks.has(step.taskId))errors.push(`Duplicate task: ${step.taskId}`);tasks.add(step.taskId);walk(step.steps,true);}
    if(step.type==='AWAIT')awaits.push(step.taskId);
    if(step.type==='BRANCH'){
     const thenEnd=walk(step.thenSteps,async),elseEnd=walk(step.elseSteps,async);ended=ended||(thenEnd&&elseEnd);
    }
   }return ended;
  };
  if(!walk(definition.steps,false))errors.push('Every main path must end with END');
  try{resolveEventWaits(definition,events,periodTicks);}catch(error){errors.push(String(error instanceof Error?error.message:error));}
 }
 for(const id of awaits)if(!tasks.has(id))errors.push(`Unknown local task: ${id}`);
 return errors;
}
