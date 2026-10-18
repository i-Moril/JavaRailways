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
 it('exposes real duty selectors, assignment, function configuration and reorder controls',()=>{
  const html=render(<DutyManagement duties={[{id:'duty-a',selector:{type:'TAKE_FROM_DEPOT',depotId:'depot-a',tick:50,tags:['commuter']},elements:[{id:'e',type:'FUNCTION',call:{id:'f',type:'WAIT',tick:60,durationTicks:20}}]}]} runs={[]} selectedId="duty-a" metadata={{depots:['depot-a']}} onSelect={noop} onAdd={noop} onDelete={noop} onPatch={noop} onAssign={noop} onAddFunction={noop} onPatchFunction={noop} onMove={noop} onRemove={noop} onViewRun={noop}/>);
  expect(html).toContain('Take from depot');expect(html).toContain('Wait duration (ticks)');expect(html).toContain('Move up');expect(html).toContain('Assign to');expect(html).not.toContain('PLACE_INITIAL');
 });
 it('run inspector renders actual local errors instead of unconditional success',()=>{
  const html=render(<RunRouteEditor run={{id:'r',type:'PASSENGER',lineDirection:'UP',diagramLineId:'line',calls:[]}} lines={[{id:'line',displayName:'Line',upStationIds:['a','b'],downStationIds:['b','a']}]} metadata={{}} busy={false} issues={[{path:'runs[0]',message:'Missing departure',severity:'error'}]} onError={noop} onChange={noop} onDelete={noop}/>);
  // The inspector starts on Calls; issue content is not announced as a success outside its tab.
  expect(html).not.toContain('No local issues');
 });
});
