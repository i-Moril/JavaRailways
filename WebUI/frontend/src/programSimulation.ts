import {resolveEventWaits} from './stationPlans';
import {validateStationProgram} from './stationPrograms';
import type {ProgramSimulationRequest,ProgramSimulationResult,TimedOperation,WorldMetadata} from './types';

/** A call is measured in its own scenario, never fabricated into a timetable report. */
export function buildProgramSimulationRequest(input:ProgramSimulationRequest,metadata:WorldMetadata,events:TimedOperation[]=[]):ProgramSimulationRequest {
 if(!input.programId.trim())throw new Error('A program is required');
 if(!Number.isSafeInteger(input.periodDays)||input.periodDays<1||!Number.isSafeInteger(input.periodDays*24000))throw new Error('Invalid timetable period');
 const periodTicks=input.periodDays*24000;
 if(!Number.isSafeInteger(input.startTick)||input.startTick<0||input.startTick>=periodTicks)throw new Error('Start tick must be inside the timetable period');
 if(!Number.isSafeInteger(input.maximumDurationTicks)||input.maximumDurationTicks<=0||input.maximumDurationTicks>periodTicks-input.startTick)throw new Error('Simulation limit must fit inside the remaining timetable period');
 if(!metadata.simulationProfiles?.some(profile=>profile.type===input.profile.type&&profile.id===input.profile.id))throw new Error('Select an available reference vehicle');
 if(!metadata.programSimulationWayPoints?.some(point=>point.id===input.initialPosition.wayPointId))throw new Error('Select a waypoint with an explicit direction');
 if(typeof input.initialPosition.reverseTrain!=='boolean')throw new Error('Choose whether to reverse the placed train explicitly');
 const definition=resolveEventWaits(input.definition,events,periodTicks),errors=validateStationProgram(definition,input.arguments);
 if(errors.length)throw new Error(errors.join('; '));
 return structuredClone({...input,definition});
}

export function completedProgramDuration(result:ProgramSimulationResult):number|undefined {
 const {startTick,endTick,durationTicks}=result;
 if(result.status!=='COMPLETED'||typeof startTick!=='number'||typeof endTick!=='number'||typeof durationTicks!=='number')return undefined;
 return Number.isSafeInteger(startTick)&&Number.isSafeInteger(endTick)&&Number.isSafeInteger(durationTicks)&&startTick>=0&&endTick>=startTick&&durationTicks===endTick-startTick?durationTicks:undefined;
}

/** Epochs protect A→B→A edits, another request, close and unmount, not only equal JSON. */
export class ProgramSimulationRequestOwner {
 private generation=0;
 private key='';
 private active=true;
 activate(){this.active=true;this.generation++;}
 update(key:string){if(key!==this.key){this.key=key;this.generation++;}}
 invalidate(){this.generation++;}
 begin(){return ++this.generation;}
 current(token:number){return this.active&&token===this.generation;}
 dispose(){this.active=false;this.generation++;}
}
