// @vitest-environment jsdom
import {act,useState} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {DutyTimeline} from './DutyTimeline';
import {I18nProvider} from './i18n';
import type {TimetableAuthoringDocumentV1} from './authoring';
import type {WorldMetadata} from './types';
vi.mock('./CommittedNumberInput',()=>({CommittedNumberInput:()=>null}));
vi.mock('antd',()=>{
 const Wrapper=({children}:any)=><div>{children}</div>;
 return {Alert:()=>null,Button:({children,onClick,disabled}:any)=><button onClick={onClick} disabled={disabled}>{children}</button>,ConfigProvider:Wrapper,Dropdown:Wrapper,Flex:Wrapper,Select:()=>null,Space:Object.assign(Wrapper,{Compact:Wrapper}),Typography:{Text:Wrapper,Title:Wrapper,Paragraph:Wrapper},
  AutoComplete:({value,onChange,onSelect,options,filterOption,defaultActiveFirstOption:_,...props}:any)=><div><input {...props} value={value} onChange={()=>{}} onInput={event=>onChange(event.currentTarget.value)}/>{options.filter((option:any)=>filterOption(value,option)).map((option:any)=><button key={option.value} onClick={()=>{onChange(option.value);onSelect(option.value);}}>{option.label}</button>)}</div>};
});
const initial:TimetableAuthoringDocumentV1={formatVersion:1,id:'doc',periodDays:1,diagramLines:[],runs:[],arrivalOverrides:[],duties:[{id:'1',selector:{type:'TRAIN_NAME',name:'approach'},elements:[{id:'join',type:'FORMATION',eventId:'couple'}]},{id:'2',selector:{type:'TRAIN_NAME',name:'waiting'},elements:[{id:'join',type:'FORMATION',eventId:'couple'}]}],formationEvents:[{id:'couple',kind:'COUPLE',tick:100,duration:{type:'MANUAL',ticks:10},coordinatorDutyId:'1',approachingDutyId:'1',stationaryDutyId:'2',stationaryEnd:'TAIL',rendezvous:{approachDestinationId:'old-a',stationaryDestinationId:'old-b'},maximumApproachSpeedMetersPerSecond:1,output:{id:'joined',disposition:{type:'DUTY',dutyId:'1'}}}]};
const metadata:WorldMetadata={wayPoints:['approach@1','stationary@2','unrelated'],destinations:[{id:'approach@1',displayName:'进站连挂点'},{id:'stationary@2',displayName:'停留连挂点'},{id:'unrelated',displayName:'非候选路径点'}]};
let container:HTMLDivElement,root:Root;
beforeEach(()=>{Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
it('wires existing metadata names through the duty timeline to both rendezvous fields and commits canonical IDs',async()=>{
 const patch=vi.fn();let latest=initial;
 function Harness(){const [document,setDocument]=useState(initial);return <I18nProvider><DutyTimeline document={document} duty={document.duties[0]} fieldScope="world/document/duty1" library={[]} destinations={['approach@1','stationary@2']} stationNames={new Map()} stationIds={[]} programSimulation={{world:'world',owner:'duty1',metadata,periodDays:1}} onAssign={()=>{}} onPatch={()=>{}} onRemove={()=>{}} onViewRun={()=>{}} onPatchFormation={event=>{patch(event);latest={...document,formationEvents:[event]};setDocument(latest);}}/></I18nProvider>;}
 await act(async()=>root.render(<Harness/>));
 const fields=()=>[...container.querySelectorAll<HTMLInputElement>('input')];
 expect(fields()).toHaveLength(2);expect(fields().map(input=>input.value)).toEqual(['old-a','old-b']);
 for(const [index,name,label,id] of [[0,'进站连挂点','进站连挂点 · approach@1','approach@1'],[1,'停留连挂点','停留连挂点 · stationary@2','stationary@2']] as const){
  await act(async()=>{const input=fields()[index];Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,name);input.dispatchEvent(new Event('input',{bubbles:true}));});
  const option=[...fields()[index].parentElement!.querySelectorAll('button')].find(button=>button.textContent===label);expect(option).toBeDefined();expect(fields()[index].parentElement!.textContent).not.toContain('非候选路径点');await act(async()=>option!.click());expect(fields()[index].value).toBe(id);
  await act(async()=>fields()[index].dispatchEvent(new FocusEvent('focusout',{bubbles:true})));
 }
 expect(patch).toHaveBeenCalledTimes(2);const event=latest.formationEvents![0];expect(event.kind==='COUPLE'&&event.rendezvous).toEqual({approachDestinationId:'approach@1',stationaryDestinationId:'stationary@2'});
});
