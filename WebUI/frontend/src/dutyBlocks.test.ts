import {afterEach,describe,it,expect,vi} from 'vitest';
import {applyDutyCanvasElements,defaultDutyProgramArguments,dutyBlockId,dutyBlocksToElements,dutyWorkspacePrograms,instantiateDutyElement,loadDutyBlockElement,dutyElementId,dutyElementsToBlocks,getDutyBlockDraftValidity,readDutyBlockDraft,storeDutyBlockDraft} from './dutyBlocks';
import type {AuthoringDutyElementV1,TimetableAuthoringDocumentV1} from './authoring';
const elements=():AuthoringDutyElementV1[]=>[
 {id:'r/中文|1',type:'RUN',runId:'run-one'},
 {id:'fn',type:'FUNCTION',call:{id:'reverse',type:'REVERSE',tick:100}},
 {id:'shared',type:'PROGRAM',call:{id:'call',programId:'turnback',tick:120,arguments:{platform:'A'}}},
 {id:'local',type:'INLINE',tick:130,definition:{parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{value:20}}]}},
];
afterEach(()=>vi.unstubAllGlobals());
describe('duty canvas domain boundary',()=>{
 it('round trips every typed element without losing fields or stable identity',()=>{
  const value=elements();expect(dutyBlocksToElements(dutyElementsToBlocks(value),value)).toEqual(value);
  expect(dutyBlocksToElements(dutyElementsToBlocks(value),value)[3]).toBe(value[3]);
  expect(dutyElementId(dutyBlockId(value[0].id))).toBe(value[0].id);
 });
 it('accepts a complete reordered chain but never drops a disconnected element',()=>{
  const value=elements(),reordered=[value[2],value[0],value[3],value[1]];
  expect(dutyBlocksToElements(dutyElementsToBlocks(reordered),value)).toEqual(reordered);
  const state=dutyElementsToBlocks(value);const first=state.blocks!.blocks[0].next!.block,second=first.next!.block;delete first.next;
  state.blocks!.blocks.push(second);expect(()=>dutyBlocksToElements(state,value)).toThrow('Connect every');
  state.blocks!.blocks.pop();expect(()=>dutyBlocksToElements(state,value)).toThrow('cannot be discarded');
 });
 it('rejects invented, duplicated, disabled and differently typed blocks',()=>{
  for(const mutate of [
   (block:ReturnType<typeof dutyElementsToBlocks>)=>{block.blocks!.blocks[0].next!.block.id=dutyBlockId('unknown');},
   (block:ReturnType<typeof dutyElementsToBlocks>)=>{block.blocks!.blocks[0].next!.block.type='rail_duty_INLINE';},
   (block:ReturnType<typeof dutyElementsToBlocks>)=>{block.blocks!.blocks[0].next!.block.enabled=false;},
   (block:ReturnType<typeof dutyElementsToBlocks>)=>{block.blocks!.blocks[0].next!.block.next!.block.id=block.blocks!.blocks[0].next!.block.id;},
  ]){const value=elements(),state=dutyElementsToBlocks(value);mutate(state);expect(()=>dutyBlocksToElements(state,value)).toThrow();}
 });
 it('preserves a disconnected draft across reopening and locale rebuild with its original baseline',()=>{
  const data=new Map<string,string>();vi.stubGlobal('localStorage',{getItem:(key:string)=>data.get(key)??null,setItem:(key:string,value:string)=>data.set(key,value)});
  const value=elements(),baseline=JSON.stringify(value),accepted=[value[1],value[0],value[2],value[3]],workspace=dutyElementsToBlocks(accepted);
  storeDutyBlockDraft('alice/world/doc/duty/one',baseline,JSON.stringify(accepted),workspace);
  const draft=readDutyBlockDraft('alice/world/doc/duty/one',accepted)!;expect(draft.baseline).toBe(baseline);
  const orphan=draft.workspace.blocks!.blocks[0].next!.block.next!.block;delete draft.workspace.blocks!.blocks[0].next!.block.next;draft.workspace.blocks!.blocks.push(orphan);
  storeDutyBlockDraft('alice/world/doc/duty/one',draft.baseline,JSON.stringify(accepted),draft.workspace);
  expect(getDutyBlockDraftValidity('alice/world/doc/duty/one',value)).toBe(false);
  expect(getDutyBlockDraftValidity('alice/world/doc/duty/one',accepted)).toBe(false);
  expect(getDutyBlockDraftValidity('other/world/doc/duty/one',accepted)).toBe(true);
 });
 it('deletes an explicitly removed block and heals the sequence without silently discarding missing nodes',()=>{
  const value=elements(),remaining=[value[0],value[2],value[3]],state=dutyElementsToBlocks(remaining);
  expect(()=>dutyBlocksToElements(state,value)).toThrow('cannot be discarded');
  expect(dutyBlocksToElements(state,value,new Set(['fn']))).toEqual(remaining);
  expect(dutyBlocksToElements(dutyElementsToBlocks(value),remaining)).toEqual(value);
 });
 it('keeps toolbox payload and numeric identity through an unfinished draft and later reconnection',()=>{
  const value=elements(),created=instantiateDutyElement({id:'',type:'INLINE',definition:{parameters:[],steps:[]}},['1','2',...value.map((element)=>element.id)]);
  expect(created.id).toBe('3');
  const connected=dutyElementsToBlocks([...value,created]);
  // Actual toolbox clones have Blockly IDs rather than an encoded pre-existing domain ID.
  let block=connected.blocks!.blocks[0].next!.block;while(block.next)block=block.next.block;
  block.id='blockly-created';block.extraState!.blockId=block.id;
  expect(dutyBlocksToElements(connected,value)).toEqual([...value,created]);
  const data=new Map<string,string>();vi.stubGlobal('localStorage',{getItem:(key:string)=>data.get(key)??null,setItem:(key:string,value:string)=>data.set(key,value)});
  const incomplete=dutyElementsToBlocks(value);incomplete.blocks!.blocks.push(block);
  storeDutyBlockDraft('scope',JSON.stringify(value),JSON.stringify(value),incomplete);
  expect(getDutyBlockDraftValidity('scope',value)).toBe(false);
  expect(readDutyBlockDraft('scope',value)!.workspace.blocks!.blocks[1].extraState!.element).toEqual(created);
 });
 it('restores deliberately deleted identities in an incomplete draft after reopening',()=>{
  const data=new Map<string,string>();vi.stubGlobal('localStorage',{getItem:(key:string)=>data.get(key)??null,setItem:(key:string,value:string)=>data.set(key,value)});
  const value=elements(),workspace=dutyElementsToBlocks(value.slice(1)),first=workspace.blocks!.blocks[0].next!.block;
  delete workspace.blocks!.blocks[0].next;workspace.blocks!.blocks.push(first);
  storeDutyBlockDraft('scope',JSON.stringify(value),JSON.stringify(value),workspace,new Set([value[0].id]));
  const draft=readDutyBlockDraft('scope',value)!;
  expect(getDutyBlockDraftValidity('scope',value)).toBe(false);
  workspace.blocks!.blocks[0].next={block:workspace.blocks!.blocks.pop()!};
  expect(dutyBlocksToElements(workspace,value,new Set(draft.deletedIds))).toEqual(value.slice(1));
 });
 it('a duty RUN deletion leaves the shared run definition and other duties unchanged',()=>{
  const run=elements()[0],other={id:'other',selector:{type:'TRAIN_NAME',name:'other'},elements:[]} as const;
  const document={runs:[{id:'run-one'}],duties:[{id:'duty',selector:{type:'TRAIN_NAME',name:'train'},elements:[run]},other]} as unknown as TimetableAuthoringDocumentV1;
  const next=applyDutyCanvasElements(document,'duty',[],[]);
  expect(next.runs).toBe(document.runs);expect(next.duties[1]).toBe(document.duties[1]);expect(next.duties[0].elements).toEqual([]);
 });
 it('pins the drag-time library snapshot once and never adopts a newer revision when reordering',()=>{
  const call=elements()[2],old={programId:'turnback',revision:2,contentIdentity:'old',definition:{parameters:[],steps:[]}},newer={...old,revision:3,contentIdentity:'new'};
  const document={duties:[{id:'duty',selector:{type:'TRAIN_NAME',name:'train'},elements:[]}]} as unknown as TimetableAuthoringDocumentV1;
  const state=dutyElementsToBlocks([call]);state.blocks!.blocks[0].next!.block.extraState!.programSnapshot=old;
  const next=applyDutyCanvasElements(document,'duty',[call],dutyWorkspacePrograms(state));
  expect(next.boundPrograms).toEqual([old]);expect(next.boundPrograms![0]).not.toBe(old);
  expect(applyDutyCanvasElements(next,'duty',[call],[newer]).boundPrograms).toEqual([old]);
  expect(()=>applyDutyCanvasElements(document,'duty',[call],[])).toThrow('Missing program snapshot');
 });
 it('prevents a duplicated RUN clone from creating a second duty assignment',()=>{
  const run=elements()[0],copy=instantiateDutyElement(run,[run.id]);
  expect(()=>dutyBlocksToElements(dutyElementsToBlocks([run,copy]),[run])).toThrow('only appear once');
 });
 it('creates typed program arguments and independently identified function calls',()=>{
  expect(defaultDutyProgramArguments([{id:'json',type:'JSON_OBJECT'},{id:'slots',type:'UNIT_SLOTS'},{id:'ids',type:'ID_LIST'},{id:'duration',type:'DURATION'},{id:'tick',type:'TICK'},{id:'name',type:'STRING'}])).toEqual({json:{},slots:[1],ids:[],duration:1,tick:0,name:''});
  const template=elements()[1],copy=instantiateDutyElement(template,['1']);
  expect(copy.id).toBe('2');if(copy.type==='FUNCTION')expect(copy.call.id).toBe('2');expect(template.id).toBe('fn');
 });

 it('rejects stale RUN toolbox blocks if another duty acquired the run meanwhile',()=>{
  const run=elements()[0],document={duties:[{id:'other',elements:[run]},{id:'duty',elements:[]}]} as unknown as TimetableAuthoringDocumentV1;
  expect(()=>applyDutyCanvasElements(document,'duty',[instantiateDutyElement(run,[])],[])).toThrow('already assigned');
 });

 it('instantiates a flyout template even when Blockly reuses the exact flyout block ID',()=>{
  const template={element:{id:'',type:'RUN',runId:'R2'},blockId:'reused-flyout-id'} as const;
  const main=loadDutyBlockElement(template,'reused-flyout-id',false,['1']);
  expect(main).toEqual({id:'2',type:'RUN',runId:'R2'});
  expect(loadDutyBlockElement(template,'reused-flyout-id',true,['1'])).toEqual(template.element);
  const saved={element:main,blockId:'reused-flyout-id'};
  expect(loadDutyBlockElement(saved,'reused-flyout-id',false,['1','2'])).toEqual(main);
  expect(loadDutyBlockElement(saved,'different-copy-id',false,['1','2'])).toEqual({id:'3',type:'RUN',runId:'R2'});
  const state=dutyElementsToBlocks([main]),block=state.blocks!.blocks[0].next!.block;
  block.id='reused-flyout-id';block.extraState={element:main,blockId:block.id};
  expect(dutyBlocksToElements(state,[])).toEqual([main]);
 });

});
