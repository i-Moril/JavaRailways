import {LinesPage} from './LinesPage';
import {ProgramDefinitionEditor} from './ProgramFields';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe,expect,it } from 'vitest';
import { AuthoringValidation,simulationRunStateLabel,type AuthoringValidationProps } from './AuthoringValidation';
import { DutyManagement } from './DutyManagement';
import { I18nProvider,translate } from './i18n';
import { RunRouteEditor } from './RunRouteEditor';
const noop=()=>undefined;
const props:AuthoringValidationProps={duties:[{id:'duty-a',selector:{type:'TRAIN_NAME',name:'train'},elements:[]}],issues:[],compileError:'',profiles:[{type:'WORLD_TRAIN',id:'train',displayName:'Train A',carCount:2}],assignments:{},threshold:1200,conflicting:false,busy:false,compileReason:'',simulationReason:'Compile the current document successfully first.',source:'// generated',onCompile:noop,onSimulate:noop,onAssignment:noop,onThreshold:noop,onNavigate:noop};
const render=(element:React.ReactNode)=>renderToStaticMarkup(<I18nProvider>{element}</I18nProvider>);
describe('authoring workspace views',()=>{
 it('edits the cap in line settings and keeps run options free of a cap input',()=>{
   const line={id:'L',displayName:'Line',upStationIds:['a','b'],downStationIds:['b','a'],maximumSpeedMetersPerSecond:20};
   const html=render(<LinesPage world="world" metadata={{}} collection={{revision:0,lines:[line]}} onCollection={noop} run={async(operation)=>operation()}/>);
   expect(html).toContain('value="72"');expect(html).toContain('km/h');
   const run={id:'R',type:'PASSENGER' as const,lineId:'L',diagramLineId:'L',lineDirection:'UP' as const,calls:[]};
   const runHtml=render(<RunRouteEditor run={run} lines={[line]} metadata={{}} busy={false} onError={noop} onChange={noop} onDelete={noop}/>);
   expect(runHtml).not.toContain('aria-label="'+translate('en-US','ui.lineCap')+'"');
 });
 it('distinguishes a tentative platform from an admitted selection and shows the active branch',()=>{
  for(const committed of [false,true]){
   const html=render(<AuthoringValidation {...props} simulation={{status:'PERIOD_ENDED',simulatedTicks:100,runs:[{timetableId:'t',serviceId:'duty-a',trainId:'train',state:'WAITING_ROUTE',timetableElapsedTicks:100,lastLatenessTicks:0,nextStepIndex:4,activeStepIndex:2,activeStepBranchPath:[{arm:'ELSE',index:1}],selectedBerthRef:{stationId:'central',berthId:'long'},selectedBerthCommitted:committed}]}}/>);
   expect(html).toContain('central / long');expect(html).toContain(committed?'Admitted':'Awaiting route');expect(html).toContain('2 / When false 1');
  }
 });
 it('opens arrival editing from stopping call details and explains unsupported origin calls',()=>{
  const run={id:'r',type:'PASSENGER' as const,lineDirection:'UP' as const,diagramLineId:'L',calls:[{id:'origin',stationId:'a',targetId:'a',status:'ORIGIN' as const},{id:'terminal',stationId:'b',targetId:'b',status:'TERMINAL' as const}]};
  const lines=[{id:'L',displayName:'Line',upStationIds:['a','b'],downStationIds:['b','a']}];
  const terminal=render(<RunRouteEditor run={run} focusedCallId="terminal" lines={lines} metadata={{}} busy={false} onChange={noop} onError={noop} onDelete={noop}/>);
  expect(terminal).toContain(translate('en-US','programs.editArrival'));expect(terminal).toContain(translate('en-US','stationPrograms.hint'));
  expect(terminal).not.toContain('station-actions-reserved');
  const origin=render(<RunRouteEditor run={run} focusedCallId="origin" lines={lines} metadata={{}} busy={false} onChange={noop} onError={noop} onDelete={noop}/>);
  expect(origin).toContain(translate('en-US','stationPrograms.hint'));expect(origin).toContain(translate('en-US','programs.editArrival'));
 });
 it('provides the shared block canvas with a compact accessible history toolbar',()=>{
   const html=render(<ProgramDefinitionEditor fieldScope="branch" onChange={noop} value={{parameters:[],steps:[{id:'choose',type:'BRANCH',typeId:'demo:condition',version:1,parameters:{value:{}},thenSteps:[{id:'until',type:'WAIT_UNTIL',tick:{value:80}}],elseSteps:[{id:'pause',type:'WAIT_DURATION',durationTicks:{value:10}}]}]}}/>);
   expect(html).toContain('program-block-canvas');
   expect(html).not.toContain('aria-label="'+translate('en-US','programs.blockInsertTarget')+'"');
   expect(html).not.toContain('aria-label="'+translate('en-US','programs.addStep')+'"');
   expect(html).toContain(translate('en-US','programs.blockUndo'));expect(html).toContain(translate('en-US','programs.blockRedo'));
   expect(html).not.toContain('data-program-step="until"');
 });
 it('keeps inventory proof separate from unproven timing and capacity',()=>{
   const html=render(<AuthoringValidation {...props} compiled={{timetable:{id:'t',periodDays:1,services:[]},validation:{valid:true,errors:[],circulation:{inventory:'PROVEN',timing:'UNPROVEN',capacity:'UNPROVEN',periodicInventoryCycles:2,findings:[{code:'timing',detail:'No completion bound is available.',blocking:false}]}}}}/>);
   expect(html).toContain('Vehicle inventory');
   expect(html).toContain('Completion timing');
   expect(html).toContain('Depot capacity');
   expect(html).toContain('Proven');
   expect(html).toContain('Unproven');
   expect(html).toContain('No completion bound is available.');
   expect(html).toContain('Inventory closure does not prove timely returns');
 });

 it('does not request a second vehicle profile for a formation output duty',()=>{
   const html=render(<AuthoringValidation {...props} duties={[{id:'child',selector:{type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'split',outputId:'rest'},elements:[]}]}/>);
   expect(html).toContain('Vehicles are supplied by the fixed event output');
   expect(html).not.toContain('Simulation train profile child');
 });

 it('offers an inline selected-run title edit without committing during render',()=>{
  let renamed='';
  const html=render(<RunRouteEditor run={{id:'editable-service',type:'PASSENGER',lineDirection:'UP',diagramLineId:'line',calls:[]}} lines={[{id:'line',displayName:'Line',upStationIds:['a','b'],downStationIds:['b','a']}]} metadata={{}} busy={false} onRename={(id)=>{renamed=id;}} onError={noop} onChange={noop} onDelete={noop}/>);
  expect(html).toContain('Rename run');expect(html).toContain('editable-service');expect(renamed).toBe('');
 });

 it('renders editable time cells and dashes for absent events, with commit guidance',()=>{
  const html=render(<RunRouteEditor run={{id:'r',type:'PASSENGER',lineDirection:'UP',diagramLineId:'line',calls:[
    {id:'a',stationId:'Origin',targetId:'a',status:'ORIGIN',departureTick:100},
    {id:'b',stationId:'Stop',targetId:'b',status:'STOP',arrivalTick:200,departureTick:300},
    {id:'c',stationId:'Pass',targetId:'c',status:'PASS',arrivalTick:400},
    {id:'d',stationId:'Terminal',targetId:'d',status:'TERMINAL',arrivalTick:500},
  ]}} lines={[{id:'line',displayName:'Line',upStationIds:['Origin','Stop','Pass','Terminal'],downStationIds:['Terminal','Pass','Stop','Origin']}]} metadata={{}} busy={false} onError={noop} onChange={noop} onDelete={noop}/>);
  expect(html).toContain('Origin · Departure');expect(html).toContain('Stop · Arrival');expect(html).toContain('Stop · Departure');expect(html).toContain('Pass · Arrival');expect(html).toContain('Terminal · Arrival');
  expect(html).not.toContain('aria-label="Origin · Arrival"');expect(html).not.toContain('aria-label="Pass · Departure"');expect(html).toContain('aria-label="Terminal · Departure"');
  expect(html).not.toContain('ant-input-number-disabled');expect(html).toContain('Commit times with Enter or blur');
 });

 it('localizes known runtime states and preserves unknown values',()=>{
  expect(simulationRunStateLabel('COMPLETED',(key)=>translate('zh-CN',key))).toBe('已完成');
  expect(simulationRunStateLabel('WAITING_ROUTE',(key)=>translate('en-US',key))).toBe('Waiting for route');
  expect(simulationRunStateLabel('FUTURE_STATE',(key)=>translate('zh-CN',key))).toBe('FUTURE_STATE');
 });
 it('distinguishes unexecuted world/simulation checks from passing local validation',()=>{
  const html=render(<AuthoringValidation {...props}/>);
  expect(html).toContain('Not run (or inputs changed)');expect(html).toContain('Compile the current document successfully first.');expect(html).toContain('Simulation train profile duty-a');expect(html).not.toContain('Completed');
 });
 it('shows actual local and compiler errors alongside profile conflicts',()=>{
  const html=render(<AuthoringValidation {...props} issues={[{path:'runs[0].calls[1]',message:'Call target is required',severity:'error'}]} compileError="Compiler rejected source" conflicting simulationReason="Duties sharing a selector must use the same profile."/>);
  expect(html).toContain('Call target is required');expect(html).toContain('runs[0].calls[1]');expect(html).toContain('Compiler rejected source');expect(html).toContain('Duties sharing a selector');
 });
 it('shows the returned report and trace instead of fabricating successful trajectories',()=>{
  const html=render(<AuthoringValidation {...props} simulation={{status:'DELAY_EXCEEDED',simulatedTicks:800,observedDelayTicks:201,delayedServiceId:'duty-a',delayedStepIndex:3,runs:[{timetableId:'t',serviceId:'duty-a',trainId:'train',state:'WAITING',timetableElapsedTicks:800,lastLatenessTicks:201,nextStepIndex:3,message:'Waiting for route'}]}} delayedOrigin="duty:duty-a/element:e/run:run-a"/>);
  expect(html).toContain('Delay threshold exceeded');expect(html).toContain('Waiting for route');expect(html).toContain('duty:duty-a/element:e/run:run-a');expect(html).toContain('201');
 });
 it('exposes duty selectors and timeline instead of a scratch canvas',()=>{
  const duty={id:'duty-a',selector:{type:'TAKE_FROM_DEPOT' as const,depotId:'depot-a',tick:50,tags:['commuter']},elements:[]};
  const document={formatVersion:1 as const,id:'doc',periodDays:1,runs:[],duties:[duty],diagramLines:[],arrivalOverrides:[]};
  const html=render(<DutyManagement document={document} duties={[duty]} runs={[]} selectedId="duty-a" metadata={{depots:['depot-a']}} onSelect={noop} onAdd={noop} onDelete={noop} onPatch={noop} onAssign={noop} onRemove={noop} onViewRun={noop}/>);
  expect(html).toContain('Take from depot');expect(html).toContain('Duty timeline');expect(html).toContain('Select operation program');expect(html).not.toContain('blockly');expect(html).not.toContain('PLACE_INITIAL');
 });
 it('run inspector renders actual local errors instead of unconditional success',()=>{
  const html=render(<RunRouteEditor run={{id:'r',type:'PASSENGER',lineDirection:'UP',diagramLineId:'line',calls:[]}} lines={[{id:'line',displayName:'Line',upStationIds:['a','b'],downStationIds:['b','a']}]} metadata={{}} busy={false} issues={[{path:'runs[0]',message:'Missing departure',severity:'error'}]} onError={noop} onChange={noop} onDelete={noop}/>);
  // The inspector starts on Calls; issue content is not announced as a success outside its tab.
  expect(html).not.toContain('No local issues');
 });
});
