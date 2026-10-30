import {afterEach,describe,it,expect,vi} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {trainInfoError,trainInfoFromRows,defaultTrainInfoRows} from './trainInfo';
import {blocksToDefinition,definitionToBlocks} from './programBlocks';
import {validateArrivalProgram,validateStationProgram} from './stationPrograms';
import {emptyAuthoringDocument,generateTimetableSource} from './authoring';
import {getProgramBlockDraftValidity,readProgramBlockDraft,storeProgramBlockDraft} from './programBlockDrafts';
import {TrainInfoFields} from './TrainInfoFields';
import {I18nProvider} from './i18n';
import type {StationProgramDefinition,StationProgramStep} from './types';
const bindings={name:'NAME',carCount:'CAR_COUNT',unitCount:'UNIT_COUNT',direction:'DIRECTION'} as const;
const display=():StationProgramDefinition=>({parameters:[],steps:[{id:'display',type:'SET_DISPLAY',unitSlots:{value:[1]},values:{trainInfo:bindings}}]});
afterEach(()=>vi.unstubAllGlobals());
describe('explicit current train information sources',()=>{
 it('round trips allowed action sources in library and station contexts without evaluating them',()=>{
  const definition=display();definition.steps.push({id:'extension',type:'EXTENSION',typeId:'demo:show',version:1,parameters:{trainInfo:bindings}});
  for(const context of ['library','arrival'] as const)expect(blocksToDefinition(definitionToBlocks(definition),[],context)).toEqual(definition);
  expect(validateStationProgram(definition)).toEqual([]);expect(validateArrivalProgram(definition)).toEqual([]);
 });
 it('does not interpret literal objects resembling the explicit source',()=>{
  const definition:StationProgramDefinition={parameters:[],steps:[{id:'literal',type:'SET_DISPLAY',unitSlots:{value:[1]},values:{value:{trainInfo:{name:'NAME'}}}}]};
  const blocks=definitionToBlocks(definition);expect(blocks.blocks!.blocks[0].next!.block.inputs!.values.block!.type).toBe('rail_literal_JSON_OBJECT');expect(blocksToDefinition(blocks,[])).toEqual(definition);
 });
 it('rejects sources in condition, routing, waits and other action fields',()=>{
  for(const step of [
   {id:'condition',type:'BRANCH',typeId:'javarailways:plan_run_id',version:1,parameters:{trainInfo:bindings},thenSteps:[],elseSteps:[]},
   {id:'wait',type:'WAIT_UNTIL',tick:{trainInfo:bindings}},
   {id:'move',type:'MOVE',destinationId:{trainInfo:bindings},viaIds:{value:[]}},
   {id:'display',type:'SET_DISPLAY',unitSlots:{trainInfo:bindings},values:{value:{}}},
  ]){
   const definition={parameters:[],steps:[step]} as unknown as StationProgramDefinition;
   expect(validateStationProgram(definition).join(' ')).toContain('unavailable');expect(()=>blocksToDefinition(definitionToBlocks(definition),[])).toThrow('unavailable');
  }
 });
 it('rejects malformed maps; preserves whitespace, UTF16 limits and own special keys',()=>{
  for(const value of [null,[],{}, {x:'SPEED'}, {' ':'NAME'}, {['x'.repeat(257)]:'NAME'},Object.fromEntries(Array.from({length:65},(_,i)=>[i,'NAME']))])expect(trainInfoError(value)).not.toBe('');
  const map=JSON.parse('{"__proto__":"NAME","constructor":"CAR_COUNT"," name ":"NAME","😀":"DIRECTION"}');
  const definition=display();(definition.steps[0] as Extract<StationProgramStep,{type:'SET_DISPLAY'}>).values={trainInfo:map};
  expect(blocksToDefinition(definitionToBlocks(definition),[])).toEqual(definition);expect(Object.hasOwn(trainInfoFromRows(JSON.stringify([{key:'__proto__',field:'NAME'}])),'__proto__')).toBe(true);
  expect(trainInfoError({['😀'.repeat(128)]:'NAME'})).toBe('');expect(trainInfoError({['😀'.repeat(129)]:'NAME'})).not.toBe('');
 });
 it('preserves duplicate, blank and malformed raw rows as invalid recovery drafts',()=>{
  const memory=new Map<string,string>();vi.stubGlobal('localStorage',{getItem:(k:string)=>memory.get(k)??null,setItem:(k:string,v:string)=>memory.set(k,v)});
  const definition=display(),baseline=JSON.stringify(definition);
  for(const raw of ['[{"key":"same","field":"NAME"},{"key":"same","field":"CAR_COUNT"}]','[{"key":"","field":"NAME"}]','not-json']){
   const workspace=definitionToBlocks(definition);workspace.blocks!.blocks[0].next!.block.inputs!.values.block!.fields!.VALUE=raw;
   storeProgramBlockDraft('train-info',baseline,baseline,{workspace,parameters:[]});expect(getProgramBlockDraftValidity('train-info',definition,'library')).toBe(false);expect(readProgramBlockDraft('train-info',baseline)!.snapshot.workspace).toEqual(workspace);
  }
 });
 it('generates bindings unchanged rather than reading a simulation reference train',()=>{
  const document={...emptyAuthoringDocument(),duties:[{id:'1',initialPosition:'a',selector:{type:'TRAIN_NAME' as const,name:'actual-train'},referenceVehicle:{type:'TRAIN_TEMPLATE' as const,id:'reference'},elements:[{id:'call',type:'PROGRAM' as const,tick:100,program:{programId:'program',arguments:{}}}]}],boundPrograms:[{programId:'program',revision:1,contentIdentity:'sha256:'+ 'a'.repeat(64),definition:display()}]};
  const generated=generateTimetableSource(document,[]);expect(generated.issues).toEqual([]);expect(generated.source).toContain('trainInfo');expect(generated.source).toContain('CAR_COUNT');expect(generated.source).not.toContain('reference');
 });
 it('renders property rows and explains canonical direction without changing the source',()=>{
  const html=renderToStaticMarkup(<I18nProvider><TrainInfoFields raw={JSON.stringify(defaultTrainInfoRows())} onChange={()=>{throw new Error('render mutation');}}/></I18nProvider>);
  expect(html).toContain('Read current train information');expect(html).toContain('FORWARD / REVERSED');expect(html).toContain('not line or geographic direction');expect(html).toContain('Output key 4');
 });
});
