import {validSimulationTimeout} from './SimulationTimeoutInput';
import {resolveEventWaits} from './stationPlans';
import {validateStationProgram} from './stationPrograms';
import type {ProgramSimulationRequest,ProgramSimulationResult,TimedOperation,WorldMetadata} from './types';

export const MAX_PROGRAM_SIMULATION_TICK=Math.floor(Number.MAX_SAFE_INTEGER/24000)*24000-1;

/** A call is measured in its own scenario, never fabricated into a timetable report. */
export function buildProgramSimulationRequest(input:ProgramSimulationRequest,metadata:WorldMetadata,events:TimedOperation[]=[]):ProgramSimulationRequest {
 if(!validSimulationTimeout(input.executionTimeoutMillis))throw new Error('Invalid simulation execution timeout');
 if(!input.programId.trim())throw new Error('A program is required');
 if(!Number.isSafeInteger(input.startTick)||input.startTick<0||input.startTick>=MAX_PROGRAM_SIMULATION_TICK)throw new Error('Invalid simulation start tick');
 if(!Number.isSafeInteger(input.maximumDurationTicks)||input.maximumDurationTicks<=0||input.maximumDurationTicks>MAX_PROGRAM_SIMULATION_TICK-input.startTick)throw new Error('Invalid simulation advance limit');
 if(!metadata.simulationProfiles?.some(profile=>profile.type===input.profile.type&&profile.id===input.profile.id))throw new Error('Select an available reference vehicle');
 if(!metadata.programSimulationWayPoints?.some(point=>point.id===input.initialPosition.wayPointId))throw new Error('Select a waypoint with an explicit direction');
 if(typeof input.initialPosition.reverseTrain!=='boolean')throw new Error('Choose whether to reverse the placed train explicitly');
 const definition=resolveEventWaits(input.definition,events,MAX_PROGRAM_SIMULATION_TICK+1),errors=validateStationProgram(definition,input.arguments);
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
