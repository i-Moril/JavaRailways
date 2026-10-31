// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {TimedOperationFields} from './TimedOperationFields';
import {I18nProvider} from './i18n';
import type {StationProgramDefinition,StationProgramRecord,TimedOperation} from './types';
const capture=vi.hoisted(()=>({dialog:undefined as any}));
vi.mock('./ProgramSimulationDialog',()=>({ProgramSimulationDialog:(p:any)=>{capture.dialog=p;return <div data-simulation-dialog/>;}}));
vi.mock('./CommittedNumberInput',()=>({CommittedNumberInput:(p:any)=><input aria-label={p['aria-label']} value={p.value} readOnly/>}));
vi.mock('antd',()=>{const Box=({children}:any)=><div>{children}</div>;return {ConfigProvider:Box,Space:Box,Tag:Box,Typography:{Paragraph:Box,Text:Box},Button:({children,onClick,disabled}:any)=><button disabled={disabled} onClick={onClick}>{children}</button>,Input:({value,onChange}:any)=><input value={value} onChange={onChange}/>,Select:()=>null};});
const bound:StationProgramDefinition={parameters:[],steps:[{id:'bound',type:'WAIT_DURATION',durationTicks:{value:123}}]};
const newer:StationProgramRecord={formatVersion:1,id:'program',name:'Newer library revision',revision:2,contentIdentity:'hash',definition:{parameters:[],steps:[{id:'newer',type:'REVERSE'}]}};
const operation:TimedOperation={id:'call',tick:500,program:{programId:'program',arguments:{}},duration:{type:'SIMULATED'}};
let root:Root,container:HTMLDivElement;
const change=vi.fn();
async function render(library:StationProgramRecord[]=[],readOnly=false){await act(async()=>root.render(<I18nProvider><TimedOperationFields operation={operation} onChange={change} fieldScope="owner/world/doc/duty/call" stationNames={new Map()} stationIds={[]} library={library} bindings={[{programId:'program',revision:1,contentIdentity:'old hash',definition:bound}]} readOnly={readOnly} programSimulation={{world:'world',owner:'owner',metadata:{},events:[{id:'anchor',tick:200}],profile:{type:'TRAIN_TEMPLATE',id:'emu'},plannedContext:{dutyId:'duty'}}}/></I18nProvider>));}
async function open(){await act(async()=>[...container.querySelectorAll('button')].find(button=>button.textContent==='Simulate function')!.click());}
beforeEach(()=>{capture.dialog=undefined;vi.clearAllMocks();Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
describe('timed-operation standalone simulation ownership',()=>{
 it('uses exact bound body even when the source library has a newer revision',async()=>{await render([newer]);await open();expect(capture.dialog.definition).toEqual(bound);expect(capture.dialog.definition).not.toEqual(newer.definition);expect(capture.dialog.arguments).toEqual({});expect(capture.dialog.startTick).toBe(500);expect(capture.dialog.plannedContext).toEqual({dutyId:'duty'});expect(capture.dialog.events).toEqual([{id:'anchor',tick:200}]);expect(change).not.toHaveBeenCalled();});
 it('allows existing deleted-source invocations and explicitly adopts only a fixed duration in one parent transaction',async()=>{await render();await open();expect(capture.dialog.definition).toEqual(bound);await act(async()=>capture.dialog.onAdoptDuration(123));expect(change).toHaveBeenCalledExactlyOnceWith({...operation,duration:{type:'MANUAL',ticks:123}});});
 it('allows a read-only graph reference preview but does not let it modify its owner',async()=>{await render([],true);await open();expect(capture.dialog.definition).toEqual(bound);expect(capture.dialog.onAdoptDuration).toBeUndefined();expect(change).not.toHaveBeenCalled();});
});
