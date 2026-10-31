import {describe,expect,it} from 'vitest';
import * as Blockly from 'blockly/core';
import {defineBlocks,isProgramDestinationLiteral} from './ProgramBlockEditor';
import {translate} from './i18n';
import {evaluateProgramExpression,operationTypes} from './programExpressions';
import {blocksToDefinition,definitionToBlocks,type ProgramWorkspaceState} from './programBlocks';
import {stationProgramSchedule,validateStationProgram} from './stationPrograms';
import type {StationProgramDefinition,StationProgramLiteral,StationProgramOperation,StationProgramValue} from './types';
const lit=(value:StationProgramLiteral):StationProgramValue<StationProgramLiteral>=>({value});
const op=(type:StationProgramOperation,...operands:StationProgramValue<StationProgramLiteral>[]):StationProgramValue<StationProgramLiteral>=>({operation:{type,operands}});
const number=(n:number)=>lit(n),text=(s:string)=>lit(s);
const wait=(durationTicks:StationProgramValue<number>):StationProgramDefinition=>({parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks}]});
const object=(value:StationProgramValue<StationProgramLiteral>):StationProgramDefinition=>({parameters:[],steps:[{id:'show',type:'SET_DISPLAY',values:{object:{result:value}}}]});
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
  const value={object:JSON.parse('{"__proto__":{"value":"safe"},"constructor":{"value":1}}')};const result=evaluateProgramExpression(value,{});expect(Object.hasOwn(result as object,'__proto__')).toBe(true);expect((result as Record<string,unknown>).__proto__).toBe('safe');expect(validateStationProgram({...object(lit('')),steps:[{id:'show',type:'SET_DISPLAY',values:value}]})).toEqual([]);
  for(const name of ['__proto__','toString','UNKNOWN']){const bad={operation:{type:name,operands:[]}} as unknown as StationProgramValue<StationProgramLiteral>;expect(()=>evaluateProgramExpression(bad,{})).toThrow();expect(validateStationProgram(object(bad)).length).toBeGreaterThan(0);}
  expect(validateStationProgram(object({trainInfo:{name:'NAME'}} as unknown as StationProgramValue<StationProgramLiteral>)).length).toBeGreaterThan(0);
 });
 it('validates known subtrees independently of unbound siblings and preserves zero-valued duration operands',()=>{
  const definition=wait(op('ADD',{parameterId:'n'},op('DIVIDE',number(1),number(0))) as StationProgramValue<number>);definition.parameters=[{id:'n',type:'NUMBER'}];expect(validateStationProgram(definition).length).toBeGreaterThan(0);
  const valid=wait(op('ADD',{parameterId:'d'},number(1)) as StationProgramValue<number>);valid.parameters=[{id:'d',type:'DURATION'}];expect(validateStationProgram(valid)).toEqual([]);expect(validateStationProgram(valid,{d:0})).toEqual([]);expect(stationProgramSchedule(valid.steps,{d:0},0,100).earliest).toBe(1);
  expect(evaluateProgramExpression(op('TO_STRING',number(Number.MIN_VALUE)),{})).toBe('0.'+'0'.repeat(323)+'5');
 });
 it('reuses one string parameter across action IDs, string operations and object entries',()=>{
  const definition:StationProgramDefinition={parameters:[{id:'text',type:'STRING'}],steps:[
   {id:'move',type:'MOVE',destinationId:{parameterId:'text'},viaIds:{value:[]}},
   {id:'select',type:'SELECT_STOP',stationId:{parameterId:'text'},berthIds:{value:['1']},selectionPolicy:'FIXED_PREFERENCE'},
   {id:'display',type:'SET_DISPLAY',values:{object:{original:{parameterId:'text'},joined:op('CONCAT',text('Next: '),{parameterId:'text'})}}},
   {id:'store',type:'STORE_AT_DEPOT',depotId:{parameterId:'text'},stablingPointIds:{value:[]}},
  ]};
  expect(validateStationProgram(definition)).toEqual([]);expect(validateStationProgram(definition,{text:'central'})).toEqual([]);
  for(const value of ['', '   ', 'a'.repeat(257)])expect(validateStationProgram(definition,{text:value})).toContain('Action ID must be nonblank and at most256 characters');
  const display={...definition,steps:[definition.steps[2]]};
  for(const value of ['', '   ', 'a'.repeat(8186)])expect(validateStationProgram(display,{text:value})).toEqual([]);
  expect(validateStationProgram({...object({parameterId:'text'}),parameters:definition.parameters},{text:'a'.repeat(8192)})).toEqual([]);
 });
 it.each(['MOVE','SELECT_STOP','STORE_AT_DEPOT'] as const)('checks resolved string limits at the %s ID sink',type=>{
  const make=(value:StationProgramValue<string>):StationProgramDefinition=>({parameters:[],steps:[type==='MOVE'?{id:'action',type,destinationId:value,viaIds:{value:[]}}:type==='SELECT_STOP'?{id:'action',type,stationId:value,berthIds:{value:['1']},selectionPolicy:'FIXED_PREFERENCE'}:{id:'action',type,depotId:value,stablingPointIds:{value:[]}}]});
  for(const value of ['', ' ', 'a'.repeat(257)])expect(validateStationProgram(make(op('CONCAT',text(value),text('')) as StationProgramValue<string>))).toContain('Action ID must be nonblank and at most256 characters');
  expect(validateStationProgram(make(text('a'.repeat(256)) as StationProgramValue<string>))).toEqual([]);
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
  const special={...object(text('')),steps:[{id:'show',type:'SET_DISPLAY' as const,values:{object:JSON.parse('{"__proto__":{"value":"safe"}}')}}]};expect(roundtrip(special)).toEqual(special);
 });
 it('supports actual connection and disconnect events without widening action availability',()=>{
  const workspace=new Blockly.Workspace();try{const pause=workspace.newBlock('rail_WAIT_DURATION'),multiply=workspace.newBlock('rail_operation_MULTIPLY'),number=workspace.newBlock('rail_literal_NUMBER'),entry=workspace.newBlock('rail_entry');number.setFieldValue('20','VALUE');pause.getInput('durationTicks')!.connection!.connect(multiply.outputConnection!);multiply.getInput('ARG0')!.connection!.connect(number.outputConnection!);entry.nextConnection!.connect(pause.previousConnection!);expect(pause.getInputTargetBlock('durationTicks')).toBe(multiply);const saved=Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState;expect(()=>blocksToDefinition(saved,[])).toThrow('Invalid program expression');const other=workspace.newBlock('rail_literal_TICK');other.setFieldValue('2','VALUE');multiply.getInput('ARG1')!.connection!.connect(other.outputConnection!);const complete=blocksToDefinition(Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState,[]);expect(validateStationProgram(complete)).toEqual([]);expect(stationProgramSchedule(complete.steps,{},0,100).earliest).toBe(40);other.unplug();expect(()=>blocksToDefinition(Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState,[])).toThrow();}finally{workspace.dispose();}
 });
 it.each([['rail_operation_SUBSTRING','ARG0'],['rail_operation_REPLACE','ARG0'],['rail_MOVE','destinationId']])('connects joined text to %s %s, including replacing a literal',(type,input)=>{
  const workspace=new Blockly.Workspace();try{const parent=workspace.newBlock(type),original=workspace.newBlock('rail_literal_STRING'),join=workspace.newBlock('rail_operation_CONCAT');parent.getInput(input)!.connection!.connect(original.outputConnection!);parent.getInput(input)!.connection!.connect(join.outputConnection!);expect(parent.getInputTargetBlock(input)).toBe(join);expect(original.getParent()).toBeNull();}finally{workspace.dispose();}
 });
 it('replaces a saved destination with joined text without leaving a loose literal and restores the original when unplugged',()=>{
  const definition:StationProgramDefinition={parameters:[],steps:[{id:'move',type:'MOVE',destinationId:{value:'original@1'},viaIds:{value:[]}}]};
  const workspace=new Blockly.Workspace(),reloaded=new Blockly.Workspace();try{
   Blockly.serialization.workspaces.load(definitionToBlocks(definition),workspace);const move=workspace.getBlockById('$rail-step/move')!,original=move.getInputTargetBlock('destinationId')!;expect(original.isShadow()).toBe(true);
   const joined=op('CONCAT',text('central@'),text('1'));const joinedDefinition={...definition,steps:[{...definition.steps[0],destinationId:joined}]} as StationProgramDefinition;
   const joinedState=definitionToBlocks(joinedDefinition).blocks!.blocks[0].next!.block.inputs!.destinationId.block!;
   const join=Blockly.serialization.blocks.append(joinedState,workspace);move.getInput('destinationId')!.connection!.connect(join.outputConnection!);
   expect(workspace.getTopBlocks(false).map(block=>block.type)).toEqual(['rail_entry']);
   const saved=Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState;expect(blocksToDefinition(saved,[])).toEqual(joinedDefinition);
   Blockly.serialization.workspaces.load(saved,reloaded);expect(blocksToDefinition(Blockly.serialization.workspaces.save(reloaded) as ProgramWorkspaceState,[])).toEqual(joinedDefinition);
   reloaded.getBlockById(join.id)!.dispose(false);expect(reloaded.getBlockById('$rail-step/move')!.getInputTargetBlock('destinationId')!.getFieldValue('VALUE')).toBe('original@1');expect(blocksToDefinition(Blockly.serialization.workspaces.save(reloaded) as ProgramWorkspaceState,[])).toEqual(definition);
  }finally{workspace.dispose();reloaded.dispose();}
 });
 it('keeps sidebar literal and parameter switches replaceable without retaining stale shadow values',()=>{
  const definition:StationProgramDefinition={parameters:[{id:'target',type:'STRING'}],steps:[{id:'move',type:'MOVE',destinationId:{value:'old@1'},viaIds:{value:[]}}]};const workspace=new Blockly.Workspace();try{
   Blockly.serialization.workspaces.load(definitionToBlocks(definition),workspace);const move=workspace.getBlockById('$rail-step/move')!,connection=move.getInput('destinationId')!.connection!;
   move.getInputTargetBlock('destinationId')!.dispose(false);const parameter=workspace.newBlock('rail_parameter_STRING');parameter.setFieldValue('target','VALUE');connection.connect(parameter.outputConnection!);expect(blocksToDefinition(Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState,definition.parameters).steps[0]).toMatchObject({destinationId:{parameterId:'target'}});
   parameter.dispose(false);const literal=workspace.newBlock('rail_literal_STRING');literal.setShadow(true);literal.setFieldValue('new@2','VALUE');connection.connect(literal.outputConnection!);
   const join=workspace.newBlock('rail_operation_CONCAT');connection.connect(join.outputConnection!);expect(workspace.getTopBlocks(false).map(block=>block.type)).toEqual(['rail_entry']);join.dispose(false);expect(move.getInputTargetBlock('destinationId')!.getFieldValue('VALUE')).toBe('new@2');
   const realLiteral=workspace.newBlock('rail_literal_STRING');realLiteral.setFieldValue('authored@3','VALUE');connection.connect(realLiteral.outputConnection!);const secondJoin=workspace.newBlock('rail_operation_CONCAT');connection.connect(secondJoin.outputConnection!);expect(realLiteral.getParent()).toBeNull();expect(workspace.getBlockById(realLiteral.id)).toBe(realLiteral);expect(()=>blocksToDefinition(Blockly.serialization.workspaces.save(workspace) as ProgramWorkspaceState,definition.parameters)).toThrow('Connect all blocks');
  }finally{workspace.dispose();}
 });
 it.each(['zh-CN','en-US'] as const)('labels operands by their meaning in %s without changing socket names',(locale)=>{
  defineBlocks(key=>translate(locale,key));const workspace=new Blockly.Workspace();try{
   const substring=workspace.newBlock('rail_operation_SUBSTRING'),replace=workspace.newBlock('rail_operation_REPLACE');
   expect(['ARG0','ARG1','ARG2'].map(name=>substring.getInput(name)!.fieldRow[0].getValue())).toEqual(locale==='zh-CN'?['原文本','起始位置（从 0 开始，含）','结束位置（不含）']:['Original text','Start index (zero-based, inclusive)','End index (exclusive)']);
   expect(['ARG0','ARG1','ARG2'].map(name=>replace.getInput(name)!.fieldRow[0].getValue())).toEqual(locale==='zh-CN'?['原文本','查找文本','替换为']:['Original text','Search text','Replacement text']);
   for(const [type,spec] of Object.entries(operationTypes)){const block=workspace.newBlock(`rail_operation_${type}`);spec.inputs.forEach((_,index)=>expect(block.getInput(`ARG${index}`)!.fieldRow[0].getValue()).not.toMatch(/^programs\.|^Input [123]$|^输入 [123]$/));}
  }finally{workspace.dispose();defineBlocks(t);}
 });
 it('retains invalid duplicate and blank keys in workspace rather than dropping entries',()=>{const state=definitionToBlocks({parameters:[],steps:[{id:'show',type:'SET_DISPLAY',values:{object:{one:lit('a'),two:lit('b')}}}]});const builder=state.blocks!.blocks[0].next!.block.inputs!.values.block!;for(const key of ['one','']){builder.fields!.KEY1=key;expect(()=>blocksToDefinition(state,[])).toThrow('Invalid program expression');expect(builder.extraState!.count).toBe(2);}});
 it('uses the same STRING socket and parameter for every scalar ID action',()=>{
  const definition:StationProgramDefinition={parameters:[{id:'text',type:'STRING'}],steps:[{id:'move',type:'MOVE',destinationId:{parameterId:'text'},viaIds:{value:[]}},{id:'select',type:'SELECT_STOP',stationId:{parameterId:'text'},berthIds:{value:['1']},selectionPolicy:'FIXED_PREFERENCE'},{id:'store',type:'STORE_AT_DEPOT',depotId:{parameterId:'text'},stablingPointIds:{value:[]}}]};
  expect(roundtrip(definition)).toEqual(definition);
  const workspace=new Blockly.Workspace();try{for(const [type,name] of [['rail_MOVE','destinationId'],['rail_SELECT_STOP','stationId'],['rail_STORE_AT_DEPOT','depotId']]){const action=workspace.newBlock(type),literal=workspace.newBlock('rail_literal_STRING');expect(isProgramDestinationLiteral(literal)).toBe(false);expect(action.getInput(name)!.connection!.getCheck()).toEqual(['STRING']);action.getInput(name)!.connection!.connect(literal.outputConnection!);expect(action.getInputTargetBlock(name)).toBe(literal);expect(isProgramDestinationLiteral(literal)).toBe(type==='rail_MOVE');literal.unplug();const parameter=workspace.newBlock('rail_parameter_STRING');action.getInput(name)!.connection!.connect(parameter.outputConnection!);expect(action.getInputTargetBlock(name)).toBe(parameter);expect(isProgramDestinationLiteral(parameter)).toBe(false);}}finally{workspace.dispose();}
 });
 it('provides every documented operation as a real block definition',()=>{for(const type of Object.keys(operationTypes))expect(Blockly.Blocks[`rail_operation_${type}`]).toBeDefined();});
});
