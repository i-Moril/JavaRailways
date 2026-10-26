import type {AuthoringDutyElementV1,TimetableAuthoringDocumentV1} from './authoring';
import type {BoundStationProgram,StationProgramDefinition,StationProgramLiteral} from './types';
export interface DutyBlockState {type:string;id?:string;fields?:Record<string,string>;extraState?:{element:AuthoringDutyElementV1;blockId?:string;programSnapshot?:BoundStationProgram};next?:{block:DutyBlockState};inputs?:Record<string,unknown>;enabled?:boolean;disabledReasons?:string[];x?:number;y?:number}
export interface DutyWorkspaceState {blocks?:{languageVersion:number;blocks:DutyBlockState[]}}
export const dutyBlockId=(id:string)=>`duty-element:${encodeURIComponent(id)}`;
export const dutyElementId=(blockId:string)=>{try{return blockId.startsWith('duty-element:')?decodeURIComponent(blockId.slice('duty-element:'.length)):undefined;}catch{return undefined;}};
export const dutyElementLabel=(element:AuthoringDutyElementV1)=>element.type==='RUN'?element.runId:element.type==='PROGRAM'?element.call.programId:element.type==='INLINE'?'':element.call.type;
export function dutyElementsToBlocks(elements:AuthoringDutyElementV1[]):DutyWorkspaceState {
 let next:DutyBlockState|undefined;
 for(const element of [...elements].reverse())next={type:`rail_duty_${element.type}`,id:dutyBlockId(element.id),fields:{LABEL:dutyElementLabel(element)},extraState:{element,blockId:dutyBlockId(element.id)},...(next?{next:{block:next}}:{})};
 return {blocks:{languageVersion:0,blocks:[{type:'rail_duty_entry',id:'$duty-entry',x:30,y:25,...(next?{next:{block:next}}:{})}]}};
}
/** Toolbox clones acquire a fresh numeric element/call identity, never the template identity. */
export function instantiateDutyElement(template:AuthoringDutyElementV1,usedIds:Iterable<string>):AuthoringDutyElementV1 {
 const used=new Set(usedIds);let n=1;while(used.has(String(n)))n++;
 const element=structuredClone(template);element.id=String(n);
 if(element.type==='FUNCTION'||element.type==='PROGRAM')element.call.id=element.id;
 return element;
}
/** Blockly may reuse a flyout ID during drag; an empty domain ID still means template. */
export function loadDutyBlockElement(state:NonNullable<DutyBlockState['extraState']>,blockId:string,isFlyout:boolean,usedIds:Iterable<string>):AuthoringDutyElementV1 {
 return isFlyout||state.element.id&&state.blockId===blockId?structuredClone(state.element):instantiateDutyElement(state.element,usedIds);
}
export function defaultDutyProgramArguments(parameters:StationProgramDefinition['parameters']):Record<string,StationProgramLiteral> {
 return Object.fromEntries(parameters.map((parameter)=>[parameter.id,parameter.type==='JSON_OBJECT'?{}:parameter.type==='UNIT_SLOTS'?[1]:parameter.type==='ID_LIST'?[]:parameter.type==='DURATION'?1:parameter.type==='TICK'?0:'']));
}
export function dutyBlockElement(block:DutyBlockState):AuthoringDutyElementV1|undefined {
 return block.extraState?.blockId===block.id?block.extraState?.element:undefined;
}
/** Missing nodes are deletion only when a real Blockly delete event recorded their identities. */
export function dutyBlocksToElements(state:DutyWorkspaceState,elements:AuthoringDutyElementV1[],deletedIds:ReadonlySet<string>=new Set()):AuthoringDutyElementV1[] {
 const roots=state.blocks?.blocks;
 if(!Array.isArray(roots)||roots.length!==1||roots[0].type!=='rail_duty_entry')throw new Error('Connect every duty block to the entry');
 const map=new Map(elements.map((element)=>[element.id,element]));if(map.size!==elements.length)throw new Error('Duplicate duty element identity');
 const visited=new Set<string>();const ordered:AuthoringDutyElementV1[]=[];
 for(let block=roots[0].next?.block;block;block=block.next?.block){
  const stored=dutyBlockElement(block),id=block.id&&dutyElementId(block.id)||stored?.id,element=id?(map.get(id)??stored):undefined;
  if(!element||element.id!==id||visited.has(element.id)||block.type!==`rail_duty_${element.type}`||Object.keys(block.inputs??{}).length||block.enabled===false||block.disabledReasons?.length)throw new Error('Invalid duty block identity or structure');
  visited.add(element.id);ordered.push(element);
 }
 if(elements.some((element)=>!visited.has(element.id)&&!deletedIds.has(element.id)))throw new Error('Duty blocks cannot be discarded; reconnect or explicitly remove the element');
 if(new Set(ordered.filter((element)=>element.type==='RUN').map((element)=>element.runId)).size!==ordered.filter((element)=>element.type==='RUN').length)throw new Error('A run can only appear once in a duty');
 return ordered;
}
/** Adding calls pins the exact toolbox snapshot; reorders never adopt a newer library revision. */
export function applyDutyCanvasElements(document:TimetableAuthoringDocumentV1,dutyId:string,elements:AuthoringDutyElementV1[],snapshots:BoundStationProgram[]):TimetableAuthoringDocumentV1 {
 if(elements.some((element)=>element.type==='RUN'&&document.duties.some((duty)=>duty.id!==dutyId&&duty.elements.some((other)=>other.type==='RUN'&&other.runId===element.runId))))throw new Error('Run is already assigned to another duty');
 const boundPrograms=[...(document.boundPrograms??[])];
 for(const element of elements)if(element.type==='PROGRAM'&&!boundPrograms.some((item)=>item.programId===element.call.programId)){
  const snapshot=snapshots.find((item)=>item.programId===element.call.programId);
  if(!snapshot)throw new Error(`Missing program snapshot: ${element.call.programId}`);
  boundPrograms.push(structuredClone(snapshot));
 }
 return {...document,...(boundPrograms.length?{boundPrograms}:{}),duties:document.duties.map((duty)=>duty.id===dutyId?{...duty,elements}:duty)};
}
export function dutyWorkspacePrograms(state:DutyWorkspaceState):BoundStationProgram[] {
 const snapshots:BoundStationProgram[]=[];
 for(let block=state.blocks?.blocks[0]?.next?.block;block;block=block.next?.block)if(block.extraState?.programSnapshot)snapshots.push(block.extraState.programSnapshot);
 return snapshots;
}
interface DutyDraft {baseline:string;accepted:string;workspace:DutyWorkspaceState;deletedIds?:string[]}
const draftKey=(scope:string)=>`javarailways.duty-blocks/${encodeURIComponent(scope)}`;
export function readDutyBlockDraft(scope:string,elements:AuthoringDutyElementV1[]):DutyDraft|undefined {
 try{const raw=localStorage.getItem(draftKey(scope));if(!raw)return;const draft=JSON.parse(raw),json=JSON.stringify(elements);if(draft.baseline===json||draft.accepted===json)return draft;}catch{/* Best-effort draft only. */}
}
export function storeDutyBlockDraft(scope:string,baseline:string,accepted:string,workspace:DutyWorkspaceState,deletedIds:ReadonlySet<string>=new Set()){try{localStorage.setItem(draftKey(scope),JSON.stringify({baseline,accepted,workspace,deletedIds:[...deletedIds]}));}catch{/* Editing is independent of storage availability. */}}
export function clearDutyBlockDraft(scope:string){try{localStorage.removeItem(draftKey(scope));}catch{/* Best effort. */}}
export function getDutyBlockDraftValidity(scope:string,elements:AuthoringDutyElementV1[]):boolean {
 const draft=readDutyBlockDraft(scope,elements);if(!draft)return true;try{dutyBlocksToElements(draft.workspace,elements,new Set(draft.deletedIds??[]));return true;}catch{return false;}
}
