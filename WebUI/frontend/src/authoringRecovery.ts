import {formationEventShape} from './formationAuthoring';
import { validateStationProgram } from './stationPrograms';
import type { TimetableAuthoringDocumentV1 } from './authoring';

export interface AuthoringRecoveryState {
  version:1;
  owner:string;
  world:string;
  documentId:string;
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
    if(value.document.formationEvents!==undefined&&(!Array.isArray(value.document.formationEvents)||!value.document.formationEvents.every(formationEventShape)))return undefined;
    if(value.document.boundPrograms!==undefined){
      if(!Array.isArray(value.document.boundPrograms)||value.document.boundPrograms.some((snapshot)=>!snapshot||typeof snapshot.programId!=='string'||!Number.isSafeInteger(snapshot.revision)||snapshot.revision<1||typeof snapshot.contentIdentity!=='string'||validateStationProgram(snapshot.definition).length>0))return undefined;
    }
    for(const duty of value.document.duties??[])for(const element of duty.elements??[])if(element.type==='PROGRAM'){
      if(!element.call||typeof element.call.id!=='string'||typeof element.call.programId!=='string'||typeof element.call.tick!=='number'||!element.call.arguments||typeof element.call.arguments!=='object'||Array.isArray(element.call.arguments))return undefined;
    }
    if(typeof value.documentId!=='string'||typeof value.formalScriptName!=='string'||typeof value.persistedFormalScriptName!=='string')return undefined;
    return {
      version:1,owner,world,documentId:value.documentId,formalScriptName:value.formalScriptName,persistedFormalScriptName:value.persistedFormalScriptName,
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
