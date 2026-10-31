// @vitest-environment jsdom
import {act,useState} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {RunRouteEditor} from './RunRouteEditor';
import {I18nProvider} from './i18n';
import type {AuthoringRunV1} from './authoring';
vi.mock('./StationStopProgramEditor',()=>({StationStopProgramEditor:()=>null}));
vi.mock('./RunTitleEditor',()=>({RunTitleEditor:()=>null}));
vi.mock('./CommittedNumberInput',()=>({CommittedNumberInput:()=>null}));
vi.mock('antd',()=>{
 const Wrapper=({children}:any)=><div>{children}</div>;
 return {Button:({children,onClick,disabled}:any)=><button onClick={onClick} disabled={disabled}>{children}</button>,Flex:Wrapper,Modal:()=>null,Segmented:()=>null,Select:()=>null,Space:Wrapper,Tag:Wrapper,Typography:{Text:Wrapper,Title:Wrapper,Paragraph:Wrapper},Tabs:()=>null,
  Table:({columns,dataSource}:any)=><table><tbody>{dataSource.map((row:any)=><tr key={row.id}>{columns.map((column:any)=><td key={column.key}>{column.render(undefined,row)}</td>)}</tr>)}</tbody></table>,
  AutoComplete:({value,onChange,onSelect,options,filterOption,defaultActiveFirstOption:_,...props}:any)=><div><input {...props} value={value} onChange={()=>{}} onInput={event=>onChange(event.currentTarget.value)}/>{options.filter((option:any)=>filterOption(value,option)).map((option:any)=><button key={option.value} onClick={()=>{onChange(option.value);onSelect(option.value);}}>{option.label}</button>)}</div>};
});
const initial:AuthoringRunV1={id:'R',type:'PASSENGER',diagramLineId:'L',lineDirection:'UP',calls:[{id:'a',stationId:'a',berthId:'1',targetId:'a@1',status:'ORIGIN'},{id:'b',stationId:'b',berthId:'1',targetId:'b@1',status:'TERMINAL'}]};
let container:HTMLDivElement,root:Root;
beforeEach(()=>{Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
it('keeps an unknown manual berth in the run for validation, resolves known IDs only within its station, and commits once',async()=>{
 const change=vi.fn();let document:AuthoringRunV1=initial;
 function Harness(){const [run,setRun]=useState(initial);return <I18nProvider><RunRouteEditor fieldScope="world/document" run={run} lines={[{id:'L',displayName:'Line',upStationIds:['a','b'],downStationIds:['b','a']}]} metadata={{stations:[{stationId:'a',stationDisplayName:'Alpha',berthId:'1',destinationId:'a@1'},{stationId:'a',stationDisplayName:'Alpha',berthId:'2',destinationId:'a@2'},{stationId:'b',stationDisplayName:'Beta',berthId:'2',destinationId:'b@2'}]}} busy={false} onError={()=>{}} onDelete={()=>{}} onChange={next=>{document=next;change(next);setRun(next);}}/></I18nProvider>;}
 await act(async()=>root.render(<Harness/>));
 const input=()=>container.querySelector<HTMLInputElement>('input[aria-label="Alpha · Berth"]')!;
 const type=async(text:string)=>{await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input(),text);input().dispatchEvent(new Event('input',{bubbles:true}));});};
 const key=async(key:string)=>{await act(async()=>input().dispatchEvent(new KeyboardEvent('keydown',{key,bubbles:true})));};
 await type('removed');await key('Enter');await act(async()=>input().dispatchEvent(new FocusEvent('focusout',{bubbles:true})));expect(change).toHaveBeenCalledTimes(1);expect(document.calls[0]).toMatchObject({berthId:'removed',targetId:''});expect(input().value).toBe('removed');expect(document.calls[1].targetId).toBe('b@1');
 await type('cancelled');await key('Escape');expect(input().value).toBe('removed');expect(change).toHaveBeenCalledTimes(1);
 await type('2');await key('Enter');expect(change).toHaveBeenCalledTimes(2);expect(document.calls[0]).toMatchObject({berthId:'2',targetId:'a@2'});expect(document.calls[1].targetId).toBe('b@1');
});
