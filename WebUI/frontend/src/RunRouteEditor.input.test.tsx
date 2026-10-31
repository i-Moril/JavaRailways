// @vitest-environment jsdom
import {act,useState} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {RunRouteEditor} from './RunRouteEditor';
import {I18nProvider} from './i18n';
import type {AuthoringRunV1} from './authoring';
import type {WorldMetadata} from './types';
vi.mock('./StationStopProgramEditor',()=>({StationStopProgramEditor:()=>null}));
vi.mock('./RunTitleEditor',()=>({RunTitleEditor:()=>null}));
vi.mock('./CommittedNumberInput',()=>({CommittedNumberInput:()=>null}));
vi.mock('antd',()=>{
 const Wrapper=({children}:any)=><div>{children}</div>;
 return {Button:({children,onClick,disabled}:any)=><button onClick={onClick} disabled={disabled}>{children}</button>,Flex:Wrapper,Modal:()=>null,Segmented:()=>null,Space:Wrapper,Tag:Wrapper,Typography:{Text:Wrapper,Title:Wrapper,Paragraph:Wrapper},Tabs:()=>null,
  Table:({columns,dataSource}:any)=><table><tbody>{dataSource.map((row:any)=><tr key={row.id}>{columns.map((column:any)=><td key={column.key}>{column.render(undefined,row)}</td>)}</tr>)}</tbody></table>,
  Select:({value,onChange,onSelect,options,showSearch,mode,...props}:any)=>mode==='tags'?<div>{value.map((id:string)=><span key={id}>{id}</span>)}<input aria-label={props['aria-label']} value={showSearch.searchValue} onChange={()=>{}} onInput={event=>showSearch.onSearch(event.currentTarget.value)} onKeyDown={event=>{if(event.key==='Enter'&&showSearch.searchValue)onChange([...value,showSearch.searchValue]);props.onKeyDown?.(event);}}/>{options.filter((option:any)=>showSearch.filterOption(showSearch.searchValue,option)).map((option:any)=><button key={option.value} onClick={()=>{onChange([...value,option.value]);onSelect?.(option.value);}}>{option.label}</button>)}</div>:<select value={value??''} onChange={event=>onChange(event.currentTarget.value)}>{options.map((option:any)=><option key={option.value} value={option.value}>{option.label}</option>)}</select>};
});
const initial:AuthoringRunV1={id:'R',type:'PASSENGER',diagramLineId:'L',lineDirection:'UP',calls:[{id:'a',stationId:'a',berthId:'1',targetId:'a@1',status:'ORIGIN'},{id:'b',stationId:'b',berthId:'1',targetId:'b@1',status:'TERMINAL'}]};
const metadata:WorldMetadata={wayPoints:['junction@1'],destinations:[{id:'junction@1',displayName:'Junction'}],stations:[{stationId:'a',stationDisplayName:'Alpha',berthId:'1',destinationId:'a@1'},{stationId:'a',stationDisplayName:'Alpha',berthId:'2',destinationId:'a@2'},{stationId:'b',stationDisplayName:'Beta',berthId:'1',destinationId:'b@1'},{stationId:'b',stationDisplayName:'Beta',berthId:'2',destinationId:'b@2'},{stationId:'b',stationDisplayName:'Beta',berthId:'3',destinationId:'b@3'}]};
let container:HTMLDivElement,root:Root;
beforeEach(()=>{Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
async function mount(focusedCallId?:string){
 const change=vi.fn();let document:AuthoringRunV1=initial;
 function Harness(){const [run,setRun]=useState(initial);return <I18nProvider><RunRouteEditor fieldScope="world/document" focusedCallId={focusedCallId} run={run} lines={[{id:'L',displayName:'Line',upStationIds:['a','b'],downStationIds:['b','a']}]} metadata={metadata} busy={false} onError={()=>{}} onDelete={()=>{}} onChange={next=>{document=next;change(next);setRun(next);}}/></I18nProvider>;}
 await act(async()=>root.render(<Harness/>));
 return {change,current:()=>document};
}
it('offers only the current station berth suffixes and resolves shared suffixes within that station',async()=>{
 const {change,current}=await mount();
 const berth=(index:number)=>container.querySelectorAll('tbody select')[index] as HTMLSelectElement;
 expect(Array.from(berth(0).options,option=>[option.value,option.textContent])).toEqual([['1','1'],['2','2']]);
 expect(Array.from(berth(1).options,option=>[option.value,option.textContent])).toEqual([['1','1'],['2','2'],['3','3']]);
 await act(async()=>{berth(0).value='2';berth(0).dispatchEvent(new Event('change',{bubbles:true}));});
 expect(current().calls[0]).toMatchObject({berthId:'2',targetId:'a@2'});expect(current().calls[1].targetId).toBe('b@1');
 await act(async()=>{berth(1).value='2';berth(1).dispatchEvent(new Event('change',{bubbles:true}));});
 expect(current().calls[1]).toMatchObject({berthId:'2',targetId:'b@2'});expect(current().calls[0].targetId).toBe('a@2');expect(change).toHaveBeenCalledTimes(2);
});
it('keeps full waypoint via IDs editable and commits a name-filtered candidate by its canonical ID',async()=>{
 const {change,current}=await mount('b');
 const input=()=>container.querySelector('input')!;
 const type=async(text:string)=>{await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input(),text);input().dispatchEvent(new Event('input',{bubbles:true}));});};
 await type('removed@1');await act(async()=>input().dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})));
 expect(current().calls[1].viaIds).toEqual(['removed@1']);expect(change).toHaveBeenCalledTimes(1);
 await type('Junction');const candidate=Array.from(container.querySelectorAll('button')).find(button=>button.textContent==='Junction · junction@1')!;
 expect(candidate).toBeDefined();await act(async()=>candidate.click());
 expect(current().calls[1].viaIds).toEqual(['removed@1','junction@1']);expect(current().calls[1].targetId).toBe('b@1');expect(change).toHaveBeenCalledTimes(2);
});
