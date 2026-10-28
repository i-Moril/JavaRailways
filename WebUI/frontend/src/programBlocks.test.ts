import {describe,expect,it,vi,afterEach} from 'vitest';
import {blocksToDefinition,definitionToBlocks,defaultProgramStep,programStepTypes,libraryStepTypes,type ProgramBlockState,type ProgramWorkspaceState} from './programBlocks';
import {getProgramBlockDraftValidity,clearProgramBlockDraft,readProgramBlockDraft,storeProgramBlockDraft} from './programBlockDrafts';
import type {StationProgramDefinition,StationProgramStep} from './types';
const clone=<T,>(value:T):T=>JSON.parse(JSON.stringify(value));
const first=(state:ProgramWorkspaceState)=>state.blocks!.blocks[0].next!.block;
const definition:StationProgramDefinition={parameters:[{id:'duration',type:'DURATION'},{id:'station',type:'STATION'}],steps:[
{id:'display',type:'SET_DISPLAY',unitSlots:{value:[1,2]},values:{value:{destination:'Central',nested:{a:[1,true,null]}}}},
{id:'display-remove',type:'SET_DISPLAY',unitSlots:{value:[1]},values:{value:{}},removeKeys:{value:['destination']}},
{id:'reset',type:'RESET_DISPLAY',unitSlots:{value:[2]}},
{id:'until',type:'WAIT_UNTIL',tick:{value:400}},
{id:'wait',type:'WAIT_DURATION',durationTicks:{parameterId:'duration'}},
{id:'reverse',type:'REVERSE'},
{id:'move',type:'MOVE',destinationId:{value:'Central'},viaIds:{value:['a','b']}},
{id:'select',type:'SELECT_STOP',stationId:{parameterId:'station'},berthIds:{value:['long']},selectionPolicy:'AVAILABLE_PRIORITY'},
{id:'store',type:'STORE_AT_DEPOT',depotId:{value:'home'},stablingPointIds:{value:['one']}},
{id:'extension',type:'EXTENSION',typeId:'demo:act',version:2,parameters:{value:{nested:{a:'b'}}}},
{id:'branch',type:'BRANCH',typeId:'demo:condition',version:3,parameters:{value:{}},thenSteps:[{id:'inside',type:'WAIT_DURATION',durationTicks:{value:10}}],elseSteps:[{id:'nested',type:'BRANCH',typeId:'demo:condition',version:1,parameters:{value:{}},thenSteps:[],elseSteps:[{id:'reverse-inside',type:'REVERSE'}]}]},
]};
describe('rail program Blockly mapping',()=>{
 it('builds toolbox steps on HTTP without crypto.randomUUID',()=>{
  vi.stubGlobal('crypto',{getRandomValues:crypto.getRandomValues.bind(crypto)});
  try{
   expect(crypto.randomUUID).toBeUndefined();
   const steps=programStepTypes.map((type)=>defaultProgramStep(type));
   expect(new Set(steps.map((step)=>step.id)).size).toBe(programStepTypes.length);
   for(const step of steps){expect(step.id).toMatch(/^step-[0-9a-f]{32}$/);expect(blocksToDefinition(definitionToBlocks({parameters:[],steps:[step]}),[]).steps).toEqual([step]);}
  }finally{vi.unstubAllGlobals();}
 });
 it('round trips supported actions, nested branches, expressions and omitted optional fields exactly',()=>{expect(blocksToDefinition(definitionToBlocks(definition),definition.parameters)).toEqual(definition);});
 it('keeps formation work out of the player program toolbox',()=>{
  for(const type of ['COUPLE','SPLIT']){
   expect(programStepTypes).not.toContain(type);expect(libraryStepTypes).not.toContain(type);
   const state:ProgramWorkspaceState={blocks:{languageVersion:0,blocks:[{type:'rail_entry',id:'entry',next:{block:{type:`rail_${type}`,id:'formation'}}}]}};
   expect(()=>blocksToDefinition(state,[])).toThrow('Unsupported action block');
  }
 });
 it('round trips every parameter reference type without resolving its value',()=>{
  const refDefinition=clone(definition);const all=refDefinition.steps as unknown as Record<string,unknown>[];let n=0;
  for(const step of all)for(const [key,value] of Object.entries(step))if(value&&typeof value==='object'&&!Array.isArray(value)&&('value' in value||'parameterId' in value))step[key]={parameterId:`p${n++}`};
  expect(blocksToDefinition(definitionToBlocks(refDefinition),refDefinition.parameters)).toEqual(refDefinition);
 });
 it('accepts an empty entry but rejects blank/multiple/dangling workspaces',()=>{
  expect(blocksToDefinition(definitionToBlocks({parameters:[],steps:[]}),[])).toEqual({parameters:[],steps:[]});
  expect(()=>blocksToDefinition({},[])).toThrow();
  const state=definitionToBlocks({parameters:[],steps:[]});state.blocks!.blocks.push({type:'rail_REVERSE',id:'loose'});
  expect(()=>blocksToDefinition(state,[])).toThrow('Connect all blocks');
 });
 it('preserves identities when moved and creates new domain identities for copied Blockly blocks',()=>{
  const state=definitionToBlocks({parameters:[],steps:[{id:'original',type:'REVERSE'},{id:'other',type:'REVERSE'}]});
  const original=first(state);const other=original.next!.block;delete original.next;other.next={block:original};state.blocks!.blocks[0].next={block:other};
  expect(blocksToDefinition(state,[]).steps.map((s)=>s.id)).toEqual(['other','original']);
  const copied=clone(original);copied.id='blockly-new-id';original.next={block:copied};const result=blocksToDefinition(state,[]);
  expect(result.steps[2].id).not.toBe('original');expect(result.steps[2].id).toMatch(/^step-[a-z0-9-]+$/);
  expect(blocksToDefinition(clone(state),[])).toEqual(result);
 });
 it('does not parse plain railway ids as JSON strings or add quotes',()=>{
  const state=definitionToBlocks({parameters:[],steps:[{id:'move',type:'MOVE',destinationId:{value:'a"b'},viaIds:{value:[]}}]});
  expect(first(state).inputs!.destinationId.block!.fields!.VALUE).toBe('a"b');
  expect((blocksToDefinition(state,[]).steps[0] as Extract<StationProgramStep,{type:'MOVE'}>).destinationId).toEqual({value:'a"b'});
 });
 it('rejects missing, malformed, mismatched and disabled inputs without silently executing them',()=>{
  const source=definitionToBlocks({parameters:[],steps:[{id:'pause',type:'WAIT_DURATION',durationTicks:{value:1}}]});
  const mutations:((block:ProgramBlockState)=>void)[]=[(b)=>{delete b.inputs!.durationTicks;},(b)=>{b.inputs!.durationTicks.block!.fields!.VALUE='-';},(b)=>{b.inputs!.durationTicks.block!.type='rail_literal_TICK';},(b)=>{b.enabled=false;},(b)=>{b.disabledReasons=['MANUALLY_DISABLED'];},(b)=>{b.inputs!.durationTicks.block!.disabledReasons=['MANUALLY_DISABLED'];},(b)=>{b.inputs!.surprise={block:{type:'rail_REVERSE',id:'unexpected'}};}];
  for(const mutation of mutations){const state=clone(source);mutation(first(state));expect(()=>blocksToDefinition(state,[])).toThrow();}
 });
 it('rejects duplicate block ids and over-deep graphs',()=>{
  const state=definitionToBlocks({parameters:[],steps:[{id:'a',type:'REVERSE'},{id:'a',type:'REVERSE'}]});expect(()=>blocksToDefinition(state,[])).toThrow('duplicate');
  let steps:StationProgramStep[]=[];for(let i=0;i<18;i++)steps=[{id:`b${i}`,type:'BRANCH',typeId:'demo:c',version:1,parameters:{value:{}},thenSteps:steps,elseSteps:[]}];
  expect(()=>blocksToDefinition(definitionToBlocks({parameters:[],steps}),[])).toThrow('depth');
 });
 it('limits arrival actions recursively and rejects arrival parameter declarations',()=>{
  for(const type of programStepTypes){const step=defaultProgramStep(type);const convert=()=>blocksToDefinition(definitionToBlocks({parameters:[],steps:[step]}),[],'arrival');if(['MOVE','SELECT_STOP','STORE_AT_DEPOT'].includes(type))expect(convert).toThrow('unavailable');else expect(convert).not.toThrow();}
  expect(()=>blocksToDefinition(definitionToBlocks({parameters:[],steps:[]}),[{id:'p',type:'STRING'}],'arrival')).toThrow('declare');
  const state=definitionToBlocks({parameters:[],steps:[{id:'b',type:'BRANCH',typeId:'demo:c',version:1,parameters:{value:{}},thenSteps:[{id:'r',type:'MOVE',destinationId:{value:'x'},viaIds:{value:[]}}],elseSteps:[]}]});expect(()=>blocksToDefinition(state,[],'arrival')).toThrow('unavailable');
 });
});
describe('incomplete workspace draft safety',()=>{
 afterEach(()=>vi.unstubAllGlobals());
 const memory=()=>{const values=new Map<string,string>();vi.stubGlobal('localStorage',{getItem:(k:string)=>values.get(k)??null,setItem:(k:string,v:string)=>values.set(k,v),removeItem:(k:string)=>values.delete(k)});return values;};
 it('matches initial disk baseline and last accepted recovered document, while isolating scope',()=>{
  const values=memory();const initial={parameters:[],steps:[]} satisfies StationProgramDefinition;const accepted={parameters:[],steps:[{id:'r',type:'REVERSE'}]} satisfies StationProgramDefinition;
  const workspace=definitionToBlocks(accepted);workspace.blocks!.blocks.push({type:'rail_REVERSE',id:'loose'});
  values.set('javarailways.program-blocks/'+encodeURIComponent('owner/world/call'),JSON.stringify({baseline:JSON.stringify(initial),accepted:JSON.stringify(accepted),snapshot:{workspace,parameters:[]}}));
  expect(getProgramBlockDraftValidity('owner/world/call',initial,'library')).toBe(false);
  expect(getProgramBlockDraftValidity('owner/world/call',accepted,'library')).toBe(false);
  expect(getProgramBlockDraftValidity('other/world/call',accepted,'library')).toBe(true);
  clearProgramBlockDraft('owner/world/call');expect(getProgramBlockDraftValidity('owner/world/call',accepted,'library')).toBe(true);
 });
 it('keeps the original server baseline after a locale restart and reopening unsaved edits',()=>{
  memory();
  const server={parameters:[],steps:[]} satisfies StationProgramDefinition;
  const edited={parameters:[],steps:[{id:'edited',type:'WAIT_DURATION',durationTicks:{value:30}}]} satisfies StationProgramDefinition;
  const scope='owner/world/library/revision1';const serverJson=JSON.stringify(server);const editedJson=JSON.stringify(edited);
  const snapshot={workspace:definitionToBlocks(edited),parameters:[]};
  storeProgramBlockDraft(scope,serverJson,editedJson,snapshot);
  // Locale changes recreate Blockly using the current accepted semantic edit.
  const localeRestart=readProgramBlockDraft(scope,editedJson)!;
  expect(localeRestart.baseline).toBe(serverJson);
  storeProgramBlockDraft(scope,localeRestart.baseline,editedJson,localeRestart.snapshot);
  // Reopening the library starts with the unchanged server record.
  const reopened=readProgramBlockDraft(scope,serverJson)!;
  expect(blocksToDefinition(reopened.snapshot.workspace,reopened.snapshot.parameters)).toEqual(edited);
  // Subsequent incomplete edits must remain matched to both source identities.
  reopened.snapshot.workspace.blocks!.blocks.push({type:'rail_REVERSE',id:'loose'});
  storeProgramBlockDraft(scope,reopened.baseline,editedJson,reopened.snapshot);
  expect(getProgramBlockDraftValidity(scope,server,'library')).toBe(false);
  expect(getProgramBlockDraftValidity(scope,edited,'library')).toBe(false);
 });
 it('blocks compatible malformed drafts and tolerates unavailable storage',()=>{
  const values=memory();const initial={parameters:[],steps:[]};values.set('javarailways.program-blocks/scope',JSON.stringify({baseline:JSON.stringify(initial),snapshot:{workspace:{},parameters:[]}}));
  expect(getProgramBlockDraftValidity('scope',initial)).toBe(false);
  vi.stubGlobal('localStorage',{getItem:()=>{throw new Error('denied');},removeItem:()=>{throw new Error('denied');}});expect(()=>clearProgramBlockDraft('scope')).not.toThrow();expect(getProgramBlockDraftValidity('scope',initial)).toBe(true);
 });
});
