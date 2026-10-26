import type {AuthoringDutyElementV1} from './authoring';
export interface DutyBlockState {type:string;id?:string;fields?:Record<string,string>;next?:{block:DutyBlockState};inputs?:Record<string,unknown>;enabled?:boolean;disabledReasons?:string[];x?:number;y?:number}
export interface DutyWorkspaceState {blocks?:{languageVersion:number;blocks:DutyBlockState[]}}
export const dutyBlockId=(id:string)=>`duty-element:${encodeURIComponent(id)}`;
export const dutyElementId=(blockId:string)=>{try{return blockId.startsWith('duty-element:')?decodeURIComponent(blockId.slice('duty-element:'.length)):undefined;}catch{return undefined;}};
export const dutyElementLabel=(element:AuthoringDutyElementV1)=>element.type==='RUN'?element.runId:element.type==='PROGRAM'?element.call.programId:element.type==='INLINE'?'':element.call.type;
export function dutyElementsToBlocks(elements:AuthoringDutyElementV1[]):DutyWorkspaceState {
 let next:DutyBlockState|undefined;
 for(const element of [...elements].reverse())next={type:`rail_duty_${element.type}`,id:dutyBlockId(element.id),fields:{LABEL:dutyElementLabel(element)},...(next?{next:{block:next}}:{})};
 return {blocks:{languageVersion:0,blocks:[{type:'rail_duty_entry',id:'$duty-entry',x:30,y:25,...(next?{next:{block:next}}:{})}]}};
}
/** A reorder may never delete, duplicate or invent a typed element. */
export function dutyBlocksToElements(state:DutyWorkspaceState,elements:AuthoringDutyElementV1[]):AuthoringDutyElementV1[] {
 const roots=state.blocks?.blocks;
 if(!Array.isArray(roots)||roots.length!==1||roots[0].type!=='rail_duty_entry')throw new Error('Connect every duty block to the entry');
 const map=new Map(elements.map((element)=>[element.id,element]));if(map.size!==elements.length)throw new Error('Duplicate duty element identity');
 const visited=new Set<string>();const ordered:AuthoringDutyElementV1[]=[];
 for(let block=roots[0].next?.block;block;block=block.next?.block){
  const id=block.id&&dutyElementId(block.id),element=id?map.get(id):undefined;
  if(!element||visited.has(element.id)||block.type!==`rail_duty_${element.type}`||Object.keys(block.inputs??{}).length||block.enabled===false||block.disabledReasons?.length)throw new Error('Invalid duty block identity or structure');
  visited.add(element.id);ordered.push(element);
 }
 if(visited.size!==elements.length)throw new Error('Duty blocks cannot be discarded; reconnect or explicitly remove the element');
 return ordered;
}
interface DutyDraft {baseline:string;accepted:string;workspace:DutyWorkspaceState}
const draftKey=(scope:string)=>`javarailways.duty-blocks/${encodeURIComponent(scope)}`;
export function readDutyBlockDraft(scope:string,elements:AuthoringDutyElementV1[]):DutyDraft|undefined {
 try{const raw=localStorage.getItem(draftKey(scope));if(!raw)return;const draft=JSON.parse(raw),json=JSON.stringify(elements);if(draft.baseline===json||draft.accepted===json)return draft;}catch{/* Best-effort draft only. */}
}
export function storeDutyBlockDraft(scope:string,baseline:string,accepted:string,workspace:DutyWorkspaceState){try{localStorage.setItem(draftKey(scope),JSON.stringify({baseline,accepted,workspace}));}catch{/* Editing is independent of storage availability. */}}
export function clearDutyBlockDraft(scope:string){try{localStorage.removeItem(draftKey(scope));}catch{/* Best effort. */}}
export function getDutyBlockDraftValidity(scope:string,elements:AuthoringDutyElementV1[]):boolean {
 const draft=readDutyBlockDraft(scope,elements);if(!draft)return true;try{dutyBlocksToElements(draft.workspace,elements);return true;}catch{return false;}
}
