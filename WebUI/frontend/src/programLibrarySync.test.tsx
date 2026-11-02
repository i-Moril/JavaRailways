// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {StationProgramLibrary} from './StationProgramLibrary';
import {I18nProvider} from './i18n';
import {api} from './api';
import type {StationProgramRecord} from './types';
vi.mock('./api',()=>({api:{stationPrograms:vi.fn(),createStationProgram:vi.fn(),saveStationProgram:vi.fn(),deleteStationProgram:vi.fn()}}));
vi.mock('./ProgramFields',()=>({ProgramDefinitionEditor:({onChange,onValidityChange}:{onChange:(value:unknown)=>void;onValidityChange:(valid:boolean)=>void})=><><button onClick={()=>onChange({parameters:[],steps:[{id:'reverse',type:'REVERSE'}]})}>Edit test definition</button><button onClick={()=>onChange({parameters:[],steps:[{id:'next',type:'REVERSE'}]})}>Edit next definition</button><button onClick={()=>onValidityChange(false)}>Invalid canvas</button></>}));
vi.mock('./ProgramSimulationDialog',()=>({ProgramSimulationDialog:(p:{definition:unknown;valid:boolean;onAdoptDuration?:(ticks:number)=>void;onClose:()=>void})=><div data-simulation-valid={p.valid} data-simulation-definition={JSON.stringify(p.definition)}><button onClick={()=>{p.onAdoptDuration?.(123);p.onClose();}}>Adopt simulation estimate</button></div> }));
// Thin public control boundary: production Ant/Blockly behavior is covered separately.
vi.mock('antd',()=>{
 const Box=({children}:{children:React.ReactNode})=><div>{children}</div>;
 return {InputNumber:({onInput,onChange:_,onKeyDownCapture,onKeyDown,onBlur,value,changeOnBlur:__,stringMode:___,status:____,precision:_____,...props}:any)=><input {...props} value={value??''} onInput={event=>onInput(event.currentTarget.value)} onChange={()=>{}} onKeyDownCapture={onKeyDownCapture} onKeyDown={onKeyDown} onBlur={onBlur}/>,Alert:({title}:{title:string})=><p role="alert">{title}</p>,Button:({children,onClick,disabled,...p}:{children:React.ReactNode;onClick?:()=>void;disabled?:boolean;'aria-label'?:string})=><button disabled={disabled} aria-label={p['aria-label']} onClick={onClick}>{children}</button>,Flex:Box,Space:Box,Typography:{Title:Box,Text:Box,Paragraph:Box},Input:({value,onChange}:{value:string;onChange:React.ChangeEventHandler<HTMLInputElement>})=><input value={value} onChange={onChange}/>,Modal:({open,children,onOk,onCancel}:{open:boolean;children:React.ReactNode;onOk:()=>void;onCancel:()=>void})=>open?<section role="dialog">{children}<button onClick={onOk}>Confirm</button><button onClick={onCancel}>Cancel</button></section>:null};
});
const initial:StationProgramRecord={formatVersion:1,id:'123',name:'1',revision:1,contentIdentity:'sha256:'+'a'.repeat(64),definition:{parameters:[],steps:[]}};
let root:Root,container:HTMLDivElement;
const changed=vi.fn();
async function click(text:string){const button=[...container.querySelectorAll('button')].find((node)=>node.textContent===text||node.getAttribute('aria-label')===text);expect(button,`button ${text}`).toBeDefined();await act(async()=>button!.click());}
beforeEach(async()=>{vi.clearAllMocks();Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});vi.mocked(api.stationPrograms).mockResolvedValue([structuredClone(initial)]);vi.mocked(api.createStationProgram).mockResolvedValue({...initial,id:'456',name:'2'});vi.mocked(api.saveStationProgram).mockResolvedValue({...initial,revision:2});vi.mocked(api.deleteStationProgram).mockResolvedValue({removed:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);await act(async()=>root.render(<I18nProvider><StationProgramLibrary world="a" recoveryOwner="player" onLibraryChange={changed}/></I18nProvider>));});
afterEach(async()=>{await act(async()=>root.render(null));container.remove();vi.useRealTimers();});
const estimate=async(value:string)=>{const input=container.querySelector<HTMLInputElement>('input[aria-label="Estimated function duration (ticks, optional)"]')!;await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,value);input.dispatchEvent(new Event('input',{bubbles:true}));});await act(async()=>input.dispatchEvent(new FocusEvent('focusout',{bubbles:true})));};
describe('successful library writes notify the retained studio owner',()=>{
 it('saves a local estimate without changing the accepted program definition',async()=>{
  await click('1');await estimate('80');expect(api.saveStationProgram).not.toHaveBeenCalled();await click('Save draft');
  expect(api.saveStationProgram).toHaveBeenCalledWith('a','123','1',initial.definition,1,80);
 });
 it('adopts a simulated duration into the local estimate only until saved',async()=>{
  await click('1');await estimate('-');expect([...container.querySelectorAll('button')].find(button=>button.textContent==='Save draft')!.disabled).toBe(true);await click('Simulate function');await click('Adopt simulation estimate');expect(api.saveStationProgram).not.toHaveBeenCalled();
  expect(container.querySelector<HTMLInputElement>('input[aria-label="Estimated function duration (ticks, optional)"]')!.value).toBe('123');
  await click('Save draft');expect(api.saveStationProgram).toHaveBeenCalledWith('a','123','1',initial.definition,1,123);
 });
 it('replaces an invalid numeric draft when adopting the same already committed estimate',async()=>{
  await click('1');await estimate('123');await click('Edit test definition');await estimate('-');
  expect(container.querySelector<HTMLInputElement>('input[aria-label="Estimated function duration (ticks, optional)"]')!.value).toBe('-');
  expect([...container.querySelectorAll('button')].find(button=>button.textContent==='Save draft')!.disabled).toBe(true);
  await click('Simulate function');await click('Adopt simulation estimate');
  expect(container.querySelector<HTMLInputElement>('input[aria-label="Estimated function duration (ticks, optional)"]')!.value).toBe('123');
  expect([...container.querySelectorAll('button')].find(button=>button.textContent==='Save draft')!.disabled).toBe(false);
  await click('Save draft');expect(api.saveStationProgram).toHaveBeenCalledWith('a','123','1',{parameters:[],steps:[{id:'reverse',type:'REVERSE'}]},1,123);
 });
 it('keeps an unsaved estimate through rename while renaming only the server baseline',async()=>{
  await click('1');await estimate('80');await click('Rename 1');await click('Confirm');
  expect(api.saveStationProgram).toHaveBeenLastCalledWith('a','123','1',initial.definition,1,undefined);
  expect(container.querySelector<HTMLInputElement>('input[aria-label="Estimated function duration (ticks, optional)"]')!.value).toBe('80');
  await click('Save draft');expect(api.saveStationProgram).toHaveBeenLastCalledWith('a','123','1',initial.definition,2,80);
 });
 it('deletion discards the selected estimate and selecting another record loads its own metadata',async()=>{
  await click('1');await estimate('80');await click('Delete 1');await click('Confirm');expect(container.querySelector('input[aria-label="Estimated function duration (ticks, optional)"]')).toBeNull();
 });
 it('notifies create' ,async()=>{await click('New program');expect(api.createStationProgram).toHaveBeenCalled();expect(changed).toHaveBeenCalledTimes(1);});
 it('notifies rename',async()=>{await click('Rename 1');await click('Confirm');expect(api.saveStationProgram).toHaveBeenCalled();expect(changed).toHaveBeenCalledTimes(1);});
 it('notifies a saved definition',async()=>{await click('1');await click('Edit test definition');await click('Save draft');expect(api.saveStationProgram).toHaveBeenCalled();expect(changed).toHaveBeenCalledTimes(1);});
 it('notifies deletion and removes the actual library row',async()=>{await click('Delete 1');await click('Confirm');expect(api.deleteStationProgram).toHaveBeenCalledWith('a','123',1);expect(changed).toHaveBeenCalledTimes(1);expect(container.querySelector('[role="list"]')!.textContent).toBe('');});
 it('does not announce a failed deletion as success',async()=>{vi.mocked(api.deleteStationProgram).mockResolvedValue({removed:false});await click('Delete 1');await click('Confirm');expect(changed).not.toHaveBeenCalled();expect(container.querySelector('[role="list"]')!.textContent).toContain('1');});
 it('invalidates retained resources when deletion succeeds after leaving the library page',async()=>{let complete!:(value:{removed:boolean})=>void;vi.mocked(api.deleteStationProgram).mockReturnValueOnce(new Promise((resolve)=>{complete=resolve;}));await click('Delete 1');await click('Confirm');await act(async()=>root.render(null));await act(async()=>complete({removed:true}));expect(changed).toHaveBeenCalledTimes(1);});
 it('simulates the unsaved accepted canvas without saving or reloading its server baseline',async()=>{await click('1');await click('Edit test definition');await click('Simulate function');expect(JSON.parse(container.querySelector<HTMLElement>('[data-simulation-definition]')!.dataset.simulationDefinition!)).toEqual({parameters:[],steps:[{id:'reverse',type:'REVERSE'}]});expect(api.saveStationProgram).not.toHaveBeenCalled();expect(api.stationPrograms).toHaveBeenCalledTimes(1);});
 it('disables simulation for an incomplete canvas even when the last accepted definition is valid',async()=>{await click('1');await click('Invalid canvas');expect([...container.querySelectorAll('button')].find(button=>button.textContent==='Simulate function')!.disabled).toBe(true);expect(container.querySelector('[data-simulation-definition]')).toBeNull();});
});

describe('program definition autosave',()=>{
 it('does not save on load or selection, and debounces valid semantic edits',async()=>{
  vi.useFakeTimers();await click('1');await act(async()=>vi.advanceTimersByTime(2000));
  expect(api.saveStationProgram).not.toHaveBeenCalled();expect(api.createStationProgram).not.toHaveBeenCalled();
  vi.mocked(api.saveStationProgram).mockImplementation(async(_world,id,name,definition,revision)=>({...initial,id,name,definition,revision:revision+1}));
  await click('Edit test definition');await act(async()=>vi.advanceTimersByTime(999));expect(api.saveStationProgram).not.toHaveBeenCalled();
  await act(async()=>vi.advanceTimersByTime(1));expect(api.saveStationProgram).toHaveBeenCalledWith('a','123','1',{parameters:[],steps:[{id:'reverse',type:'REVERSE'}]},1,undefined);
  expect(container.querySelector('[role="status"]')!.textContent).toBe('Saved');
 });
 it('creates a requested new entry only once and never auto-creates an untouched file',async()=>{
  vi.useFakeTimers();await act(async()=>vi.advanceTimersByTime(2000));expect(api.createStationProgram).not.toHaveBeenCalled();
  await click('New program');await act(async()=>vi.advanceTimersByTime(3000));
  expect(api.createStationProgram).toHaveBeenCalledTimes(1);expect(api.saveStationProgram).not.toHaveBeenCalled();
 });
 it('retains incomplete canvas and does not autosave its last accepted definition',async()=>{
  vi.useFakeTimers();await click('1');await click('Edit test definition');await click('Invalid canvas');
  await act(async()=>vi.advanceTimersByTime(2000));expect(api.saveStationProgram).not.toHaveBeenCalled();
 });
 it('keeps editing enabled during a write and advances only the saved baseline',async()=>{
  vi.useFakeTimers();let finish!:(value:StationProgramRecord)=>void;
  vi.mocked(api.saveStationProgram).mockReturnValueOnce(new Promise(resolve=>{finish=resolve;}));
  await click('1');await click('Edit test definition');await act(async()=>vi.advanceTimersByTime(1000));
  expect(container.querySelector('fieldset')!.disabled).toBe(false);
  await click('Invalid canvas');await act(async()=>finish({...initial,revision:2,definition:{parameters:[],steps:[{id:'reverse',type:'REVERSE'}]}}));
  expect(container.querySelector('[role="status"]')!.textContent).toBe('Unsaved');
  expect([...container.querySelectorAll('button')].find(b=>b.textContent==='Save draft')!.disabled).toBe(true);
 });
 it('serializes a second edit behind the first save with the acknowledged revision',async()=>{
  vi.useFakeTimers();let finish!:(value:StationProgramRecord)=>void;
  vi.mocked(api.saveStationProgram).mockImplementation(async(_world,id,name,definition,revision)=>({...initial,id,name,definition,revision:revision+1}));
  vi.mocked(api.saveStationProgram).mockReturnValueOnce(new Promise(resolve=>{finish=resolve;}));
  await click('1');await click('Edit test definition');await act(async()=>vi.advanceTimersByTime(1000));
  await click('Edit next definition');await act(async()=>vi.advanceTimersByTime(5000));expect(api.saveStationProgram).toHaveBeenCalledTimes(1);
  await act(async()=>finish({...initial,revision:2,definition:{parameters:[],steps:[{id:'reverse',type:'REVERSE'}]}}));
  expect(container.querySelector('[role="status"]')!.textContent).toBe('Unsaved');
  await act(async()=>vi.advanceTimersByTime(1000));expect(api.saveStationProgram).toHaveBeenLastCalledWith('a','123','1',{parameters:[],steps:[{id:'next',type:'REVERSE'}]},2,undefined);
  expect(api.saveStationProgram).toHaveBeenCalledTimes(2);expect(container.querySelector('[role="status"]')!.textContent).toBe('Saved');
 });
 it('asks before discarding queued unsaved edits when creating another file',async()=>{
  vi.useFakeTimers();await click('1');await click('Edit test definition');await click('New program');
  expect(container.querySelector('[role="dialog"]')).not.toBeNull();expect(api.createStationProgram).not.toHaveBeenCalled();
  await act(async()=>vi.advanceTimersByTime(5000));expect(api.saveStationProgram).not.toHaveBeenCalled();
  await click('Cancel');await act(async()=>vi.advanceTimersByTime(1000));expect(api.saveStationProgram).toHaveBeenCalledTimes(1);
 });
 it('does not retry a conflict automatically and offers an explicit retry',async()=>{
  vi.useFakeTimers();vi.mocked(api.saveStationProgram).mockRejectedValue(new Error('Revision conflict'));
  await click('1');await click('Edit test definition');await act(async()=>vi.advanceTimersByTime(1000));
  await act(async()=>vi.advanceTimersByTime(10000));expect(api.saveStationProgram).toHaveBeenCalledTimes(1);
  expect(container.textContent).toContain('Revision conflict');await click('Retry save');expect(api.saveStationProgram).toHaveBeenCalledTimes(2);
 });
 it('cancels queued writes when the owner changes',async()=>{
  vi.useFakeTimers();await click('1');await click('Edit test definition');
  await act(async()=>root.render(<I18nProvider><StationProgramLibrary world="b" recoveryOwner="other" onLibraryChange={changed}/></I18nProvider>));
  await act(async()=>vi.advanceTimersByTime(2000));expect(api.saveStationProgram).not.toHaveBeenCalled();
 });
});

it('autosaves estimate-only edits and keeps an invalid estimate local',async()=>{
 vi.useFakeTimers();await click('1');await estimate('-');await act(async()=>vi.advanceTimersByTime(1500));expect(api.saveStationProgram).not.toHaveBeenCalled();
 vi.mocked(api.saveStationProgram).mockResolvedValue({...initial,revision:2,estimatedDurationTicks:80});
 await estimate('80');await act(async()=>vi.advanceTimersByTime(1000));
 expect(api.saveStationProgram).toHaveBeenLastCalledWith('a','123','1',initial.definition,1,80);
 expect(container.querySelector('[role="status"]')!.textContent).toBe('Saved');
 await act(async()=>vi.advanceTimersByTime(5000));expect(api.saveStationProgram).toHaveBeenCalledTimes(1);
});
