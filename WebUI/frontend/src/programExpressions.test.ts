import {describe,expect,it} from 'vitest';
import * as Blockly from 'blockly/core';
import {defineBlocks} from './ProgramBlockEditor';
import {translate} from './i18n';
import {evaluateProgramExpression,operationTypes} from './programExpressions';
import {blocksToDefinition,definitionToBlocks,type ProgramWorkspaceState} from './programBlocks';
import {stationProgramSchedule,validateStationProgram} from './stationPrograms';
import type {StationProgramDefinition,StationProgramLiteral,StationProgramOperation,StationProgramValue} from './types';
const lit=(value:StationProgramLiteral):StationProgramValue<StationProgramLiteral>=>({value});
const op=(type:StationProgramOperation,...operands:StationProgramValue<StationProgramLiteral>[]):StationProgramValue<StationProgramLiteral>=>({operation:{type,operands}});
const number=(n:number)=>lit(n),text=(s:string)=>lit(s);
const wait=(durationTicks:StationProgramValue<number>):StationProgramDefinition=>({parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks}]});
const object=(value:StationProgramValue<StationProgramLiteral>):StationProgramDefinition=>({parameters:[],steps:[{id:'show',type:'SET_DISPLAY',unitSlots:{value:[1]},values:{object:{result:value}}}]});
describe('pure program expressions',()=>{
 it.each<[StationProgramOperation,StationProgramLiteral[],StationProgramLiteral]>([
  ['ADD',[1,2],3],['SUBTRACT',[1,2],-1],['MULTIPLY',[2.5,4],10],['DIVIDE',[5,2],2.5],['MODULO',[-5,2],-1],['MIN',[1,2],1],['MAX',[1,2],2],['ROUND',[-1.5],-1],['FLOOR',[-1.5],-2],['CEIL',[-1.5],-1],['CONCAT',['Next: ','Central'],'Next: Central'],['SUBSTRING',['a😀z',1,3],'😀'],['REPLACE',['a.a.a','.','$'],'a$a$a'],['TEXT_LENGTH',['😀'],2],['TO_STRING',[1e-7],'0.0000001'],['TO_STRING',[-0],'0'],['TO_STRING',[5],'5'],['TO_NUMBER',[' +1.5e2 '],150],
 ])('evaluates %s with exact decimal and UTF16 semantics',(type,args,result)=>{expect(evaluateProgramExpression(op(type,...args.map(lit)),{})).toEqual(result);});
 it('composes bound numbers into waits and strings into identifiers and object values',()=>{
  const definition:StationProgramDefinition={parameters:[{id:'seconds',type:'NUMBER'},{id:'suffix',type:'STRING'}],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:op('MULTIPLY',{parameterId:'seconds'},number(20)) as StationProgramValue<number>},{id:'move',type:'MOVE',destinationId:op('CONCAT',text('central@'),{parameterId:'suffix'}) as StationProgramValue<string>,viaIds:{value:[]}}]};
  expect(validateStationProgram(definition)).toEqual([]);const args={seconds:2,suffix:'1'};expect(validateStationProgram(definition,args)).toEqual([]);expect(stationProgramSchedule(definition.steps,args,0,100).earliest).toBe(40);
  expect(validateStationProgram(definition,{...args,seconds:0.01}).length).toBeGreaterThan(0);
  expect(validateStationProgram(wait(op('SUBTRACT',number(1),number(1)) as StationProgramValue<number>)).length).toBeGreaterThan(0);
  expect(validateStationProgram(object(lit([false,null,1,''])))).toEqual([]);
 });
 it.each([op('DIVIDE',number(1),number(0)),op('MODULO',number(1),number(0)),op('TO_NUMBER',text('')),op('TO_NUMBER',text('0x10')),op('TO_NUMBER',text('Infinity')),op('TO_NUMBER',text('9007199254740992')),op('SUBSTRING',text('abc'),number(2),number(1)),op('SUBSTRING',text('abc'),number(0.5),number(2)),op('REPLACE',text('abc'),text(''),text('x')),op('REPLACE',text('a'.repeat(8192)),text('a'),text('x'.repeat(8192))),op('ADD',number(Number.MAX_SAFE_INTEGER),number(1))])('rejects invalid arithmetic, bounds or result sizes',(value)=>{expect(()=>evaluateProgramExpression(value,{})).toThrow();expect(validateStationProgram(object(value)).length).toBeGreaterThan(0);});
 it('preserves own special keys and rejects malformed operations and nested train info',()=>{
  const value={object:JSON.parse('{"__proto__":{"value":"safe"},"constructor":{"value":1}}')};const result=evaluateProgramExpression(value,{});expect(Object.hasOwn(result as object,'__proto__')).toBe(true);expect((result as Record<string,unknown>).__proto__).toBe('safe');expect(validateStationProgram({...object(lit('')),steps:[{id:'show',type:'SET_DISPLAY',unitSlots:{value:[1]},values:value}]})).toEqual([]);
  for(const name of ['__proto__','toString','UNKNOWN']){const bad={operation:{type:name,operands:[]}} as unknown as StationProgramValue<StationProgramLiteral>;expect(()=>evaluateProgramExpression(bad,{})).toThrow();expect(validateStationProgram(object(bad)).length).toBeGreaterThan(0);}
  expect(validateStationProgram(object({trainInfo:{name:'NAME'}} as unknown as StationProgramValue<StationProgramLiteral>)).length).toBeGreaterThan(0);
 });
 it('validates known subtrees independently of unbound siblings and preserves zero-valued duration operands',()=>{
  const definition=wait(op('ADD',{parameterId:'n'},op('DIVIDE',number(1),number(0))) as StationProgramValue<number>);definition.parameters=[{id:'n',type:'NUMBER'}];expect(validateStationProgram(definition).length).toBeGreaterThan(0);
  const valid=wait(op('ADD',{parameterId:'d'},number(1)) as StationProgramValue<number>);valid.parameters=[{id:'d',type:'DURATION'}];expect(validateStationProgram(valid)).toEqual([]);expect(validateStationProgram(valid,{d:0})).toEqual([]);expect(stationProgramSchedule(valid.steps,{d:0},0,100).earliest).toBe(1);
  expect(evaluateProgramExpression(op('TO_STRING',number(Number.MIN_VALUE)),{})).toBe('0.'+'0'.repeat(323)+'5');
 });
 it('enforces recursive size limits',()=>{let value=number(1);for(let i=0;i<17;i++)value=op('ADD',value,number(1));expect(()=>evaluateProgramExpression(value,{})).toThrow();expect(validateStationProgram(object(value)).length).toBeGreaterThan(0);});
});
describe('real Blockly expression sockets and serialization',()=>{
 const t=(key:Parameters<typeof translate>[1])=>translate('en-US',key);
 defineBlocks(t);
 const roundtrip=(definition:StationProgramDefinition)=>{const workspace=new Blockly.Workspace();try{Blockly.serialization.workspaces.load(definitionToBlocks(definition),workspace);const saved=Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState;return blocksToDefinition(saved,definition.parameters);}finally{workspace.dispose();}};
 it('roundtrips nested operation and object input trees using Blockly serialization',()=>{
  const definition=object(op('CONCAT',text('Next: '),op('TO_STRING',op('ADD',number(1),number(2)))));expect(roundtrip(definition)).toEqual(definition);
  const params={...object({parameterId:'label'}),parameters:[{id:'label',type:'STRING' as const}]};expect(roundtrip(params)).toEqual(params);
  const special={...object(text('')),steps:[{id:'show',type:'SET_DISPLAY' as const,unitSlots:{value:[1]},values:{object:JSON.parse('{"__proto__":{"value":"safe"}}')}}]};expect(roundtrip(special)).toEqual(special);
 });
 it('supports actual connection and disconnect events without widening action availability',()=>{
  const workspace=new Blockly.Workspace();try{const pause=workspace.newBlock('rail_WAIT_DURATION'),multiply=workspace.newBlock('rail_operation_MULTIPLY'),number=workspace.newBlock('rail_literal_NUMBER'),entry=workspace.newBlock('rail_entry');number.setFieldValue('20','VALUE');pause.getInput('durationTicks')!.connection!.connect(multiply.outputConnection!);multiply.getInput('ARG0')!.connection!.connect(number.outputConnection!);entry.nextConnection!.connect(pause.previousConnection!);expect(pause.getInputTargetBlock('durationTicks')).toBe(multiply);const saved=Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState;expect(()=>blocksToDefinition(saved,[])).toThrow('Invalid program expression');const other=workspace.newBlock('rail_literal_TICK');other.setFieldValue('2','VALUE');multiply.getInput('ARG1')!.connection!.connect(other.outputConnection!);const complete=blocksToDefinition(Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState,[]);expect(validateStationProgram(complete)).toEqual([]);expect(stationProgramSchedule(complete.steps,{},0,100).earliest).toBe(40);other.unplug();expect(()=>blocksToDefinition(Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState,[])).toThrow();}finally{workspace.dispose();}
 });
 it('retains invalid duplicate and blank keys in workspace rather than dropping entries',()=>{const state=definitionToBlocks({parameters:[],steps:[{id:'show',type:'SET_DISPLAY',unitSlots:{value:[1]},values:{object:{one:lit('a'),two:lit('b')}}}]});const builder=state.blocks!.blocks[0].next!.block.inputs!.values.block!;for(const key of ['one','']){builder.fields!.KEY1=key;expect(()=>blocksToDefinition(state,[])).toThrow('Invalid program expression');expect(builder.extraState!.count).toBe(2);}});
 it('provides every documented operation as a real block definition',()=>{for(const type of Object.keys(operationTypes))expect(Blockly.Blocks[`rail_operation_${type}`]).toBeDefined();});
});
