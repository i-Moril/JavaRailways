import {afterEach,describe,it,expect,vi} from 'vitest';
import {dutyBlockId,dutyBlocksToElements,dutyElementId,dutyElementsToBlocks,getDutyBlockDraftValidity,readDutyBlockDraft,storeDutyBlockDraft} from './dutyBlocks';
import type {AuthoringDutyElementV1} from './authoring';
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
});
