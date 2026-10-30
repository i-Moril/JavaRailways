// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {StationProgramLibrary} from './StationProgramLibrary';
import {I18nProvider} from './i18n';
import {api} from './api';
import type {StationProgramRecord} from './types';
vi.mock('./api',()=>({api:{stationPrograms:vi.fn(),createStationProgram:vi.fn(),saveStationProgram:vi.fn(),deleteStationProgram:vi.fn()}}));
vi.mock('./ProgramFields',()=>({ProgramDefinitionEditor:({onChange,onValidityChange}:{onChange:(value:unknown)=>void;onValidityChange:(valid:boolean)=>void})=><><button onClick={()=>onChange({parameters:[],steps:[{id:'reverse',type:'REVERSE'}]})}>Edit test definition</button><button onClick={()=>onValidityChange(false)}>Invalid canvas</button></>}));
vi.mock('./ProgramSimulationDialog',()=>({ProgramSimulationDialog:(p:{definition:unknown;valid:boolean})=><div data-simulation-valid={p.valid} data-simulation-definition={JSON.stringify(p.definition)}/> }));
// Thin public control boundary: production Ant/Blockly behavior is covered separately.
vi.mock('antd',()=>{
 const Box=({children}:{children:React.ReactNode})=><div>{children}</div>;
 return {Alert:({title}:{title:string})=><p role="alert">{title}</p>,Button:({children,onClick,disabled,...p}:{children:React.ReactNode;onClick?:()=>void;disabled?:boolean;'aria-label'?:string})=><button disabled={disabled} aria-label={p['aria-label']} onClick={onClick}>{children}</button>,Flex:Box,Space:Box,Typography:{Title:Box,Text:Box,Paragraph:Box},Input:({value,onChange}:{value:string;onChange:React.ChangeEventHandler<HTMLInputElement>})=><input value={value} onChange={onChange}/>,Modal:({open,children,onOk,onCancel}:{open:boolean;children:React.ReactNode;onOk:()=>void;onCancel:()=>void})=>open?<section role="dialog">{children}<button onClick={onOk}>Confirm</button><button onClick={onCancel}>Cancel</button></section>:null};
});
const initial:StationProgramRecord={formatVersion:1,id:'123',name:'1',revision:1,contentIdentity:'sha256:'+'a'.repeat(64),definition:{parameters:[],steps:[]}};
let root:Root,container:HTMLDivElement;
const changed=vi.fn();
async function click(text:string){const button=[...container.querySelectorAll('button')].find((node)=>node.textContent===text||node.getAttribute('aria-label')===text);expect(button,`button ${text}`).toBeDefined();await act(async()=>button!.click());}
beforeEach(async()=>{vi.clearAllMocks();Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});vi.mocked(api.stationPrograms).mockResolvedValue([structuredClone(initial)]);vi.mocked(api.createStationProgram).mockResolvedValue({...initial,id:'456',name:'2'});vi.mocked(api.saveStationProgram).mockResolvedValue({...initial,revision:2});vi.mocked(api.deleteStationProgram).mockResolvedValue({removed:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);await act(async()=>root.render(<I18nProvider><StationProgramLibrary world="a" recoveryOwner="player" onLibraryChange={changed}/></I18nProvider>));});
afterEach(async()=>{await act(async()=>root.render(null));container.remove();});
describe('successful library writes notify the retained studio owner',()=>{
 it('notifies create',async()=>{await click('New program');expect(api.createStationProgram).toHaveBeenCalled();expect(changed).toHaveBeenCalledTimes(1);});
 it('notifies rename',async()=>{await click('Rename 1');await click('Confirm');expect(api.saveStationProgram).toHaveBeenCalled();expect(changed).toHaveBeenCalledTimes(1);});
 it('notifies a saved definition',async()=>{await click('1');await click('Edit test definition');await click('Save draft');expect(api.saveStationProgram).toHaveBeenCalled();expect(changed).toHaveBeenCalledTimes(1);});
 it('notifies deletion and removes the actual library row',async()=>{await click('Delete 1');await click('Confirm');expect(api.deleteStationProgram).toHaveBeenCalledWith('a','123',1);expect(changed).toHaveBeenCalledTimes(1);expect(container.querySelector('[role="list"]')!.textContent).toBe('');});
 it('does not announce a failed deletion as success',async()=>{vi.mocked(api.deleteStationProgram).mockResolvedValue({removed:false});await click('Delete 1');await click('Confirm');expect(changed).not.toHaveBeenCalled();expect(container.querySelector('[role="list"]')!.textContent).toContain('1');});
 it('invalidates retained resources when deletion succeeds after leaving the library page',async()=>{let complete!:(value:{removed:boolean})=>void;vi.mocked(api.deleteStationProgram).mockReturnValueOnce(new Promise((resolve)=>{complete=resolve;}));await click('Delete 1');await click('Confirm');await act(async()=>root.render(null));await act(async()=>complete({removed:true}));expect(changed).toHaveBeenCalledTimes(1);});
 it('simulates the unsaved accepted canvas without saving or reloading its server baseline',async()=>{await click('1');await click('Edit test definition');await click('Simulate function');expect(JSON.parse(container.querySelector<HTMLElement>('[data-simulation-definition]')!.dataset.simulationDefinition!)).toEqual({parameters:[],steps:[{id:'reverse',type:'REVERSE'}]});expect(api.saveStationProgram).not.toHaveBeenCalled();expect(api.stationPrograms).toHaveBeenCalledTimes(1);});
 it('disables simulation for an incomplete canvas even when the last accepted definition is valid',async()=>{await click('1');await click('Invalid canvas');expect([...container.querySelectorAll('button')].find(button=>button.textContent==='Simulate function')!.disabled).toBe(true);expect(container.querySelector('[data-simulation-definition]')).toBeNull();});
});
