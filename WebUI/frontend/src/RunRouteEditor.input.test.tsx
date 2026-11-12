// @vitest-environment jsdom
import {act,useState} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {RunRouteEditor} from './RunRouteEditor';
import {I18nProvider} from './i18n';
import {graphServiceColor} from './graph';
import type {AuthoringRunV1} from './authoring';
import type {WorldMetadata} from './types';
vi.mock('./StationStopProgramEditor',()=>({StationStopProgramEditor:()=>null}));
vi.mock('./RunTitleEditor',()=>({RunTitleEditor:()=>null}));
vi.mock('./CommittedNumberInput',()=>({CommittedNumberInput:()=>null}));
vi.mock('antd',()=>{
 const Wrapper=({children}:any)=><div>{children}</div>;
 return {Switch:()=>null,Input:(props:any)=><input {...props}/>,Button:({children,onClick,disabled}:any)=><button onClick={onClick} disabled={disabled}>{children}</button>,Flex:Wrapper,Modal:()=>null,Segmented:()=>null,Space:Wrapper,Tag:Wrapper,Typography:{Text:Wrapper,Title:Wrapper,Paragraph:Wrapper},Tabs:({onChange}:any)=><button onClick={()=>onChange('options')}>Options</button>,
  Table:({columns,dataSource}:any)=><table><tbody>{dataSource.map((row:any)=><tr key={row.id}>{columns.map((column:any)=><td key={column.key}>{column.render(undefined,row)}</td>)}</tr>)}</tbody></table>,
  Select:({value,onChange,onSelect,options,showSearch,mode,...props}:any)=>mode==='tags'?<div>{value.map((id:string)=><span key={id}>{id}</span>)}<input aria-label={props['aria-label']} value={showSearch.searchValue} onChange={()=>{}} onInput={event=>showSearch.onSearch(event.currentTarget.value)} onKeyDown={event=>{if(event.key==='Enter'&&showSearch.searchValue)onChange([...value,showSearch.searchValue]);props.onKeyDown?.(event);}}/>{options.filter((option:any)=>showSearch.filterOption(showSearch.searchValue,option)).map((option:any)=><button key={option.value} onClick={()=>{onChange([...value,option.value]);onSelect?.(option.value);}}>{option.label}</button>)}</div>:<select value={value??''} onChange={event=>onChange(event.currentTarget.value)}>{options.map((option:any)=><option key={option.value} value={option.value}>{option.label}</option>)}</select>};
});
const initial:AuthoringRunV1={id:'R',type:'PASSENGER',diagramLineId:'L',lineDirection:'UP',calls:[{id:'a',stationId:'a',berthId:'1',targetId:'a@1',status:'ORIGIN'},{id:'b',stationId:'b',berthId:'1',targetId:'b@1',status:'TERMINAL'}]};
const metadata:WorldMetadata={wayPoints:['junction@1'],stations:[{stationId:'a',stationName:'Alpha',berthId:'1',destinationId:'a@1'},{stationId:'a',stationName:'Alpha',berthId:'2',destinationId:'a@2'},{stationId:'b',stationName:'Beta',berthId:'1',destinationId:'b@1'},{stationId:'b',stationName:'Beta',berthId:'2',destinationId:'b@2'},{stationId:'b',stationName:'Beta',berthId:'3',destinationId:'b@3'}]};
let container:HTMLDivElement,root:Root;
beforeEach(()=>{Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
async function mount(focusedCallId?:string){
 const change=vi.fn();let document:AuthoringRunV1=initial;
 function Harness(){const [run,setRun]=useState(initial);return <I18nProvider><RunRouteEditor automaticColor={graphServiceColor('light','run-1',['run-0','run-1'])} fieldScope="world/document" focusedCallId={focusedCallId} run={run} lines={[{id:'L',metadata:{name:'Line'},upStationIds:['a','b'],downStationIds:['b','a']}]} metadata={metadata} busy={false} onError={()=>{}} onDelete={()=>{}} onChange={next=>{document=next;change(next);setRun(next);}}/></I18nProvider>;}
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
it('keeps full waypoint via IDs editable and commits an ID-filtered candidate without changing the stop target',async()=>{
 const {change,current}=await mount('b');
 const input=()=>container.querySelector('input')!;
 const type=async(text:string)=>{await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input(),text);input().dispatchEvent(new Event('input',{bubbles:true}));});};
 await type('removed@1');await act(async()=>input().dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})));
 expect(current().calls[1].viaIds).toEqual(['removed@1']);expect(change).toHaveBeenCalledTimes(1);
 await type('JUNCTION');const candidate=Array.from(container.querySelectorAll('button')).find(button=>button.textContent==='junction@1')!;
 expect(candidate).toBeDefined();await act(async()=>candidate.click());
 expect(current().calls[1].viaIds).toEqual(['removed@1','junction@1']);expect(current().calls[1].targetId).toBe('b@1');expect(change).toHaveBeenCalledTimes(2);
});

it('assigns and resets a diagram-only run color from the options panel',async()=>{
 const {change,current}=await mount();
 await act(async()=>Array.from(container.querySelectorAll('button')).find(button=>button.textContent==='Options')!.click());
 const input=container.querySelector('input[type="color"]') as HTMLInputElement;
 expect(input.getAttribute('aria-label')).toBe('Run color');
 expect(input.value).toBe(graphServiceColor('light','run-1',['run-0','run-1']));
 await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,'#abcdef');input.dispatchEvent(new Event('input',{bubbles:true}));});
 expect(current().color).toBe('#abcdef');expect(change).toHaveBeenCalledTimes(1);
 await act(async()=>Array.from(container.querySelectorAll('button')).find(button=>button.textContent==='Reset to automatic')!.click());
 expect(current()).not.toHaveProperty('color');expect(change).toHaveBeenCalledTimes(2);
 expect(input.value).toBe(graphServiceColor('light','run-1',['run-0','run-1']));
});
