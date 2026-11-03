import {formationEventShape} from './formationAuthoring';
import { displayValuesError,validateArrivalProgram } from './stationPrograms';
import type { TimetableAuthoringDocumentV1 } from './authoring';

export interface AuthoringRecoveryState {
  version:1;
  owner:string;
  world:string;
  documentId:string;
  blockDraftKey?:string;
  formalScriptName:string;
  persistedFormalScriptName:string;
  document:TimetableAuthoringDocumentV1;
  savedDocumentJson:string;
  selectedRun:string;
  selectedDuty:string;
  sidebarTab:'runs'|'duties';
  graphLineId:string;
  profileAssignments:Record<string,string>;
  delayThresholdTicks:number;
  updatedAtEpochMillis:number;
}

const recoveryKey=(owner:string,world:string)=>`javarailways.authoringRecovery.${encodeURIComponent(owner)}.${encodeURIComponent(world)}`;

export function parseAuthoringRecovery(serialized:string|null,owner:string,world:string):AuthoringRecoveryState|undefined {
  if(!serialized)return undefined;
  try{
    const value=JSON.parse(serialized) as Partial<AuthoringRecoveryState>;
    if(value.version!==1||value.owner!==owner||value.world!==world||!value.document||typeof value.document!=='object'||typeof value.savedDocumentJson!=='string')return undefined;
    if(value.document.minimumDwellTicks!==undefined&&(!Number.isSafeInteger(value.document.minimumDwellTicks)||value.document.minimumDwellTicks<0))return undefined;
    if(value.document.operationalTrips!==undefined&&(!Array.isArray(value.document.operationalTrips)||value.document.operationalTrips.some((trip)=>!trip||typeof trip.id!=='string'||displayValuesError(trip.defaults)||!Array.isArray(trip.segments)||trip.segments.some((segment)=>!segment||typeof segment.serviceId!=='string'||typeof segment.runId!=='string'||!Array.isArray(segment.unitSlots)||!segment.unitSlots.length||segment.unitSlots.length>128||new Set(segment.unitSlots).size!==segment.unitSlots.length||segment.unitSlots.some((slot)=>!Number.isSafeInteger(slot)||slot<1||slot>2147483647)))))return undefined;
    if(value.document.formationEvents!==undefined&&(!Array.isArray(value.document.formationEvents)||!value.document.formationEvents.every(formationEventShape)))return undefined;
    for(const program of value.document.stationPrograms??[])if(validateArrivalProgram(program.arrival).length||validateArrivalProgram(program.departurePreparation).length)return undefined;
    for(const duty of value.document.duties??[]){
      if(duty.initialPosition!==undefined&&typeof duty.initialPosition!=='string')return undefined;
      for(const element of duty.elements??[]){
        if(element.type==='RUN'){if(typeof element.runId!=='string')return undefined;}
        else if(element.type==='PROGRAM'){
          if(!element.program||typeof element.program.programId!=='string'||!Number.isSafeInteger(element.tick)||!element.program.arguments||typeof element.program.arguments!=='object'||Array.isArray(element.program.arguments))return undefined;
          if(element.duration&&(element.duration.type==='MANUAL'?(!Number.isSafeInteger(element.duration.ticks)||element.duration.ticks<0):!['SIMULATED','PROGRAM'].includes(element.duration.type)))return undefined;
        }else if(element.type==='EVENT'||element.type==='FORMATION'){if(typeof element.eventId!=='string')return undefined;}else return undefined;
      }
    }
    if(typeof value.documentId!=='string'||typeof value.formalScriptName!=='string'||typeof value.persistedFormalScriptName!=='string')return undefined;
    return {
      version:1,owner,world,documentId:value.documentId,formalScriptName:value.formalScriptName,persistedFormalScriptName:value.persistedFormalScriptName,
      blockDraftKey:typeof value.blockDraftKey==='string'?value.blockDraftKey:undefined,
      document:value.document as TimetableAuthoringDocumentV1,savedDocumentJson:value.savedDocumentJson,
      selectedRun:typeof value.selectedRun==='string'?value.selectedRun:'',selectedDuty:typeof value.selectedDuty==='string'?value.selectedDuty:'',
      sidebarTab:value.sidebarTab==='duties'?'duties':'runs',graphLineId:typeof value.graphLineId==='string'?value.graphLineId:'',
      profileAssignments:value.profileAssignments&&typeof value.profileAssignments==='object'?value.profileAssignments:{},
      delayThresholdTicks:Number.isSafeInteger(value.delayThresholdTicks)&&value.delayThresholdTicks!>0?value.delayThresholdTicks!:1_200,
      updatedAtEpochMillis:Number.isFinite(value.updatedAtEpochMillis)?value.updatedAtEpochMillis!:0,
    };
  }catch{return undefined;}
}

export function loadAuthoringRecovery(owner:string,world:string):AuthoringRecoveryState|undefined {
  if(typeof window==='undefined'||!owner)return undefined;
  try{return parseAuthoringRecovery(window.localStorage.getItem(recoveryKey(owner,world)),owner,world);}catch{return undefined;}
}

export function saveAuthoringRecovery(state:Omit<AuthoringRecoveryState,'version'|'updatedAtEpochMillis'>):void {
  if(typeof window==='undefined')return;
  if(!state.owner)return;
  try{window.localStorage.setItem(recoveryKey(state.owner,state.world),JSON.stringify({...state,version:1,updatedAtEpochMillis:Date.now()}));}catch{/* Best-effort crash recovery must not interrupt editing. */}
}

export function clearAuthoringRecovery(owner:string,world:string):void {
  if(typeof window==='undefined'||!owner)return;
  try{window.localStorage.removeItem(recoveryKey(owner,world));}catch{/* Ignore unavailable storage. */}
}
