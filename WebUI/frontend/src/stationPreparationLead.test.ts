import {describe,expect,it} from 'vitest';
import * as Blockly from 'blockly/core';
import {defineBlocks} from './ProgramBlockEditor';
import {translate} from './i18n';
import {validatePreparationLead} from './stationPreparationLead';
import {blocksToDefinition,blocksToPreparationLead,definitionToBlocks,type ProgramWorkspaceState} from './programBlocks';
import {defaultStationStopProgram,validateStopProgram} from './stationPlans';
import {reconcileProgramEntryLead} from './programBlockDrafts';
import type {StationPreparationLead} from './types';
const lead:StationPreparationLead={operation:{type:'ADD',operands:[{doorCloseTicks:true},{value:10}]}};
const definition={parameters:[],steps:[]};
describe('actual train preparation lead',()=>{
 it('defaults to current train closing time, accepts fixed and numeric combinations',()=>{
  expect(defaultStationStopProgram('A').preparationLead).toEqual({doorCloseTicks:true});
  for(const value of [lead,{value:0},{operation:{type:'CEIL',operands:[{value:1.5}]}}] as StationPreparationLead[])expect(()=>validatePreparationLead(value)).not.toThrow();
  expect(validateStopProgram(defaultStationStopProgram('A'),[],100)).toEqual([]);
 });
 it('validates static branches and final finite nonnegative integer without guessing dynamic timing',()=>{
  for(const value of [{value:-1},{value:0.2},{value:Infinity},{value:1e20},{doorCloseTicks:false},{parameterId:'x'}, {operation:{type:'TO_NUMBER',operands:[{value:'20'}]}}, {operation:{type:'DIVIDE',operands:[{doorCloseTicks:true},{value:0}]}}, {operation:{type:'ADD',operands:[{doorCloseTicks:true},{operation:{type:'DIVIDE',operands:[{value:1},{value:0}]}}]}}])expect(()=>validatePreparationLead(value as StationPreparationLead)).toThrow();
 });
 it('roundtrips value input through real Blockly and preserves it across locale rebuilds',()=>{
  let state=definitionToBlocks(definition,lead);
  for(const locale of ['en-US','zh-CN'] as const){
   defineBlocks(((key:Parameters<typeof translate>[1])=>translate(locale,key)) as Parameters<typeof defineBlocks>[0]);
   const ws=new Blockly.Workspace();try{
    Blockly.serialization.workspaces.load(state,ws);
    expect(ws.getBlockById('$rail-entry')?.getInput('PREPARATION_LEAD')).toBeTruthy();
    state=Blockly.serialization.workspaces.save(ws) as ProgramWorkspaceState;
    expect(blocksToPreparationLead(state)).toEqual(lead);
    expect(blocksToDefinition(state,[],'arrival',true)).toEqual(definition);
   }finally{ws.dispose();}
  }
 });
 it('blocks missing lead operands, detached lead blocks and dynamic leaves inside ordinary actions',()=>{
  const state=definitionToBlocks(definition,lead);delete state.blocks!.blocks[0].inputs!.PREPARATION_LEAD.block!.inputs!.ARG1;
  expect(()=>blocksToDefinition(state,[],'arrival',true)).toThrow();
  const detached=definitionToBlocks(definition,lead);detached.blocks!.blocks.push({type:'rail_door_close_ticks',id:'loose'});
  expect(()=>blocksToDefinition(detached,[],'arrival',true)).toThrow();
  const ordinary=definitionToBlocks({parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{value:2}}]});ordinary.blocks!.blocks[0].next!.block.inputs!.durationTicks={block:{type:'rail_door_close_ticks',id:'bad'}};
  expect(()=>blocksToDefinition(ordinary,[])).toThrow();
  expect(()=>blocksToDefinition(definitionToBlocks(definition,lead),[])).toThrow();
 });
 it('compares accepted expression trees by content and preserves incomplete draft and original baseline',()=>{
  const workspace=definitionToBlocks(definition,lead);workspace.blocks!.blocks.push({type:'rail_WAIT_DURATION',id:'loose'});
  const draft={workspace,parameters:[],acceptedPreparationLead:lead};
  expect(reconcileProgramEntryLead(draft,structuredClone(lead))).toBe(draft);
  const next=reconcileProgramEntryLead(draft,{value:10});
  expect(blocksToPreparationLead(next.workspace)).toEqual({value:10});
  expect(next.workspace.blocks!.blocks[1]).toBe(workspace.blocks!.blocks[1]);
  expect(blocksToPreparationLead(draft.workspace)).toEqual(lead);
 });
});
