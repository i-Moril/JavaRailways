import {blocksToDefinition,type ProgramWorkspaceState} from './programBlocks';
import {validateStationProgram} from './stationPrograms';
import type {StationProgramDefinition} from './types';

export interface ProgramBlockSnapshot {workspace:ProgramWorkspaceState;parameters:StationProgramDefinition['parameters']}
interface ProgramBlockDraft {baseline:string;accepted:string;snapshot:ProgramBlockSnapshot}
const storageKey=(scope:string)=>`javarailways.program-blocks/${encodeURIComponent(scope)}`;
/** Retain the original server baseline when restoring against a later accepted edit. */
export function readProgramBlockDraft(scope:string,current:string):ProgramBlockDraft|undefined {
 try{
  const raw=localStorage.getItem(storageKey(scope));if(!raw)return;
  const draft=JSON.parse(raw);
  if(draft.baseline===current||draft.accepted===current)return {
   baseline:typeof draft.baseline==='string'?draft.baseline:current,
   accepted:typeof draft.accepted==='string'?draft.accepted:current,
   snapshot:draft.snapshot??{workspace:{},parameters:[]},
  };
 }catch{/* Best effort; disk definitions remain authoritative. */}
}
export function storeProgramBlockDraft(scope:string,baseline:string,accepted:string,snapshot:ProgramBlockSnapshot){try{localStorage.setItem(storageKey(scope),JSON.stringify({baseline,accepted,snapshot}));}catch{/* Storage failure cannot break editing. */}}
export function clearProgramBlockDraft(scope:string){try{localStorage.removeItem(storageKey(scope));}catch{/* Best effort. */}}
export function getProgramBlockDraftValidity(scope:string,value:StationProgramDefinition,context:'library'|'arrival'='arrival'):boolean {
 const draft=readProgramBlockDraft(scope,JSON.stringify(value));if(!draft)return true;
 const {snapshot}=draft;
 try{return !validateStationProgram(blocksToDefinition(snapshot.workspace,snapshot.parameters,context)).length;}catch{return false;}
}
