import {blocksToDefinition,type ProgramWorkspaceState} from './programBlocks';
import {validateStationProgram} from './stationPrograms';
import type {StationProgramDefinition} from './types';

export interface ProgramBlockSnapshot {workspace:ProgramWorkspaceState;parameters:StationProgramDefinition['parameters'];acceptedPreparationLeadTicks?:number}
/** Reconcile a recovered canvas with the accepted document lead without discarding loose blocks. */
export function reconcileProgramEntryLead(snapshot:ProgramBlockSnapshot,current:number|undefined):ProgramBlockSnapshot {
 if(current===undefined||snapshot.acceptedPreparationLeadTicks===current)return snapshot;
 const blocks=snapshot.workspace.blocks;
 if(!blocks)return snapshot;
 return {...snapshot,acceptedPreparationLeadTicks:current,workspace:{...snapshot.workspace,blocks:{...blocks,blocks:blocks.blocks.map((block)=>block.type==='rail_entry'?{...block,fields:{...block.fields,PREPARATION_LEAD:current}}:block)}}};
}
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
export function getProgramBlockDraftValidity(scope:string,value:StationProgramDefinition,context:'library'|'arrival'|'duty'='arrival'):boolean {
 const draft=readProgramBlockDraft(scope,JSON.stringify(value));if(!draft)return true;
 const {snapshot}=draft;
 try{return !validateStationProgram(blocksToDefinition(snapshot.workspace,snapshot.parameters,context)).length;}catch{return false;}
}
