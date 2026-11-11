import {renderToStaticMarkup} from 'react-dom/server';
import {beforeEach,describe,expect,it,vi} from 'vitest';
import {I18nProvider} from './i18n';
import {TimedOperationFields} from './TimedOperationFields';
import type {StationProgramRecord,TimedOperation} from './types';
const captured=vi.hoisted(()=>({options:[] as {value:string;label:string;disabled?:boolean}[][]}));
vi.mock('antd',async(importOriginal)=>{const original=await importOriginal<typeof import('antd')>();return {...original,Select:(props:{'aria-label'?:string;options?:{value:string;label:string;disabled?:boolean}[]})=>{if(props['aria-label']==='Select operation program')captured.options.push(props.options??[]);return <span/>;}};});
const live:StationProgramRecord={formatVersion:1,id:'live',name:'2',definition:{parameters:[{id:'text',type:'STRING'}],steps:[]}};
const other:StationProgramRecord={formatVersion:1,id:'other',name:'3',definition:{parameters:[],steps:[]}};
const existing:TimedOperation={id:'call',tick:100,program:{programId:'live',arguments:{text:'Central'}}};
const render=(operation:TimedOperation,library:StationProgramRecord[]=[])=>renderToStaticMarkup(<I18nProvider><TimedOperationFields operation={operation} onChange={()=>{}} fieldScope="qa" stationNames={new Map()} stationIds={[]} library={library} onOpenProgramLibrary={()=>{}}/></I18nProvider>);
beforeEach(()=>{captured.options=[];});
describe('timetable program selection',()=>{
 it('offers only programs owned by this timetable and preserves the selected arguments',()=>{
  const html=render(existing,[other,live]);
  expect(captured.options[0]).toEqual([{value:'other',label:'3'},{value:'live',label:'2'}]);
  expect(html).toContain('value="Central"');
 });
 it('does not invent options for a program missing from this timetable',()=>{
  render({id:'call',tick:100,program:{programId:'missing',arguments:{}}},[live]);
  expect(captured.options[0]).toEqual([{value:'live',label:'2'}]);
 });
 it('keeps library editing available for a local program',()=>{
  const html=render(existing,[live]);expect(html).toContain('Edit in program library');
 });
});
