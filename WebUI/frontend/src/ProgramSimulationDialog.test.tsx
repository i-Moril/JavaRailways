// @vitest-environment jsdom
import {act,StrictMode} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {ProgramSimulationDialog} from './ProgramSimulationDialog';
import {I18nProvider} from './i18n';
import {api} from './api';
import type {ProgramSimulationResult,StationProgramDefinition,WorldMetadata} from './types';
vi.mock('./api',()=>({api:{simulateProgram:vi.fn()}}));
// Public component events rendered into real mounted React DOM; browser smoke uses real Ant.
vi.mock('antd',async()=>{
 const React=await import('react');const Reversal=React.createContext<boolean|undefined>(undefined);
 const Radio=Object.assign(({value,children}:{value:boolean;children:React.ReactNode})=><label><input type="radio" name="reverseTrain" value={String(value)} checked={React.useContext(Reversal)===value} readOnly/>{children}</label>,{Group:({value,onChange,children,...props}:any)=><Reversal.Provider value={value}><fieldset {...props} onChange={event=>onChange({target:{value:event.target instanceof HTMLInputElement&&event.target.value==='true'}})}>{children}</fieldset></Reversal.Provider>});
 const Text=({children}:any)=><span>{children}</span>;
 return {Alert:({title,description}:any)=><div role="status">{title}{description}</div>,Button:({children,loading:_,type:__,...props}:any)=><button {...props}>{children}</button>,Modal:({children,footer,title,onCancel}:any)=><section role="dialog"><h1>{title}</h1><button onClick={onCancel}>Close dialog</button>{children}{footer}</section>,Radio,Space:({children}:any)=><div>{children}</div>,Typography:{Text},
 Select:({options,value,onChange,placeholder,showSearch:_,optionFilterProp:__,...props}:any)=><select {...props} value={value??''} onChange={event=>onChange(event.target.value)}><option value="">{placeholder}</option>{options.map((option:any)=><option key={option.value} value={option.value}>{option.label}</option>)}</select>,
 InputNumber:({onInput,onChange:_,onKeyDownCapture,onKeyDown,onBlur,value,changeOnBlur:__,stringMode:___,status:____,precision:_____,...props}:any)=><input {...props} value={value??''} onInput={event=>onInput(event.currentTarget.value)} onChange={()=>{}} onKeyDownCapture={onKeyDownCapture} onKeyDown={onKeyDown} onBlur={onBlur}/>,
 Input:Object.assign(({value,onChange,...props}:any)=><input {...props} value={value} onChange={onChange}/>,{TextArea:({status:_,...props}:any)=><textarea {...props}/>})};
});
const definition:StationProgramDefinition={parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{value:100}}]};
const metadata:WorldMetadata={simulationProfiles:[{type:'TRAIN_TEMPLATE',id:'emu',displayName:'EMU',carCount:2}],wayPoints:['undirected'],programSimulationWayPoints:[{id:'point',displayName:'Point',railId:'rail',direction:'BA',directionSource:'RAIL'}]};
// Scenario setup takes50 ticks after the planned start; only the actual function's100 ticks are adopted.
const complete:ProgramSimulationResult={status:'COMPLETED',simulatedTicks:250,startTick:150,endTick:250,durationTicks:100,initialPosition:{wayPointId:'point',direction:'BA',directionSource:'RAIL',reverseTrain:true}};
let container:HTMLDivElement,root:Root;
const deferred=()=>{let resolve!:(value:ProgramSimulationResult)=>void;const promise=new Promise<ProgramSimulationResult>(done=>resolve=done);return {promise,resolve};};
const render=async(p:Partial<Parameters<typeof ProgramSimulationDialog>[0]>={})=>{await act(async()=>root.render(<StrictMode><I18nProvider><ProgramSimulationDialog world="world" owner="owner" metadata={metadata} periodDays={1} programId="1" definition={definition} startTick={100} onClose={()=>{}} {...p}/></I18nProvider></StrictMode>));};
const button=(text:string)=>[...container.querySelectorAll('button')].find(button=>button.textContent===text)!;
const click=async(text:string)=>{await act(async()=>button(text).click());};
const select=async(label:string,value:string)=>{const element=container.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!;await act(async()=>{element.value=value;element.dispatchEvent(new Event('change',{bubbles:true}));});};
const reverse=async(value:boolean)=>{await act(async()=>container.querySelector<HTMLInputElement>(`input[value="${value}"]`)!.click());};
const input=async(label:string,value:string)=>{const element=container.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(element,value);element.dispatchEvent(new Event('input',{bubbles:true}));});};
const ready=async()=>{await select('Simulation reference vehicle','TRAIN_TEMPLATE:emu');await select('Starting waypoint','point');await reverse(true);};
beforeEach(()=>{vi.clearAllMocks();Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
describe('mounted standalone invocation scenario',()=>{
 it('requires an explicit reversal choice, uses only directed points and adopts a completed measurement only on request',async()=>{
  const adopt=vi.fn();vi.mocked(api.simulateProgram).mockResolvedValue(complete);await render({onAdoptDuration:adopt});expect(button('Start simulation').disabled).toBe(true);expect(container.textContent).not.toContain('undirected');
  await select('Simulation reference vehicle','TRAIN_TEMPLATE:emu');await select('Starting waypoint','point');expect(container.querySelector('input[type="radio"]:checked')).toBeNull();expect(button('Start simulation').disabled).toBe(true);await reverse(true);await click('Start simulation');
  expect(api.simulateProgram).toHaveBeenCalledExactlyOnceWith('world',expect.objectContaining({definition,arguments:{},startTick:100,profile:{type:'TRAIN_TEMPLATE',id:'emu'},initialPosition:{wayPointId:'point',reverseTrain:true}}));expect(container.textContent).toContain('Actual function duration: 100 tick');expect(adopt).not.toHaveBeenCalled();await click('Use as planned duration');expect(adopt).toHaveBeenCalledExactlyOnceWith(100);
 });
 it('invalidates a pending response after A to B to A and allows a new request',async()=>{
  const first=deferred();vi.mocked(api.simulateProgram).mockReturnValueOnce(first.promise).mockResolvedValueOnce(complete);await render();await ready();await click('Start simulation');await reverse(false);await reverse(true);await act(async()=>first.resolve(complete));expect(container.textContent).not.toContain('Actual function duration:');expect(button('Start simulation').disabled).toBe(false);await click('Start simulation');expect(container.textContent).toContain('Actual function duration: 100 tick');
 });
 it('accepts an explicit keep-direction false without applying a hidden default or respawning across the waypoint',async()=>{
  vi.mocked(api.simulateProgram).mockResolvedValue({...complete,initialPosition:{...complete.initialPosition,reverseTrain:false}});await render();await select('Simulation reference vehicle','TRAIN_TEMPLATE:emu');await select('Starting waypoint','point');expect(button('Start simulation').disabled).toBe(true);await reverse(false);expect(button('Start simulation').disabled).toBe(false);await click('Start simulation');
  expect(api.simulateProgram).toHaveBeenCalledExactlyOnceWith('world',expect.objectContaining({initialPosition:{wayPointId:'point',reverseTrain:false}}));expect(container.textContent).toContain('head and tail swap while the train occupies the same positions');expect(container.textContent).toContain('Actual function duration excludes this preparation');expect(container.textContent).toContain('Actual function duration: 100 tick');
 });
 it('blocks incomplete numeric drafts and hides a measurement even when accepted values have not changed',async()=>{
  vi.mocked(api.simulateProgram).mockResolvedValue(complete);await render({onAdoptDuration:vi.fn()});await ready();await click('Start simulation');await input('Simulation advance limit (ticks)','-');expect(button('Start simulation').disabled).toBe(true);expect(button('Use as planned duration').disabled).toBe(true);expect(container.textContent).not.toContain('Actual function duration:');
 });
 it('rejects responses from previous owners and invalid raw parent state',async()=>{
  const old=deferred();vi.mocked(api.simulateProgram).mockReturnValueOnce(old.promise);await render({onAdoptDuration:vi.fn()});await ready();await click('Start simulation');await render({owner:'another-owner',valid:false,onAdoptDuration:vi.fn()});await act(async()=>old.resolve(complete));expect(container.textContent).not.toContain('Actual function duration:');expect(button('Start simulation').disabled).toBe(true);expect(button('Use as planned duration').disabled).toBe(true);
 });
 it('shows failed and limited results without an adoptable duration',async()=>{
  const adopt=vi.fn();vi.mocked(api.simulateProgram).mockResolvedValue({...complete,status:'LIMIT_REACHED',durationTicks:undefined,endTick:undefined,reason:'Still waiting',failedProgramStepId:'wait'});await render({onAdoptDuration:adopt});await ready();await click('Start simulation');expect(container.textContent).toContain('complete duration is unknown');expect(container.textContent).toContain('Still waiting');expect(container.textContent).toContain('Failed step: wait');expect(button('Use as planned duration').disabled).toBe(true);expect(adopt).not.toHaveBeenCalled();
 });
});
