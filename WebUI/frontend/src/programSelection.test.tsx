import {renderToStaticMarkup} from 'react-dom/server';
import {beforeEach,describe,expect,it,vi} from 'vitest';
import {I18nProvider} from './i18n';
import {TimedOperationFields} from './TimedOperationFields';
import {DutyTimeline} from './DutyTimeline';
import type {TimetableAuthoringDocumentV1} from './authoring';
import type {StationProgramRecord,TimedOperation} from './types';
const captured=vi.hoisted(()=>({options:[] as {value:string;label:string;disabled?:boolean}[][]}));
vi.mock('antd',async(importOriginal)=>{const original=await importOriginal<typeof import('antd')>();return {...original,Select:(props:{'aria-label'?:string;options?:{value:string;label:string;disabled?:boolean}[]})=>{if(props['aria-label']==='Select operation program')captured.options.push(props.options??[]);return <span/>;}};});
const binding={programId:'deleted',revision:1,contentIdentity:'hash',definition:{parameters:[{id:'units',type:'UNIT_SLOTS' as const}],steps:[]}};
const live:StationProgramRecord={formatVersion:1,id:'live',name:'2',revision:1,contentIdentity:'hash',definition:{parameters:[],steps:[]}};
const existing:TimedOperation={id:'call',tick:100,program:{programId:'deleted',arguments:{units:[1]}}};
const render=(operation:TimedOperation,library:StationProgramRecord[]=[])=>renderToStaticMarkup(<I18nProvider><TimedOperationFields operation={operation} onChange={()=>{}} fieldScope="qa" stationNames={new Map()} stationIds={[]} library={library} bindings={[binding,{...binding,programId:'another-deleted'}]} onOpenProgramLibrary={()=>{}}/></I18nProvider>);
beforeEach(()=>{captured.options=[];});
describe('deleted program source vs retained call snapshot',()=>{
 it('shows only the current deleted-source binding, disabled, and preserves its arguments without a dead source link',()=>{
  const operation=structuredClone(existing),html=render(operation,[live]);
  expect(captured.options[0]).toEqual([{value:'deleted',label:'deleted · Bound snapshot only (no library source)',disabled:true},{value:'live',label:'2',disabled:false}]);
  expect(html).toContain('Unit 1');expect(html).not.toContain('Edit in program library');expect(operation).toEqual(existing);
 });
 it('does not offer any deleted-source binding for a new call',()=>{
  render({id:'new',tick:100},[live]);expect(captured.options[0]).toEqual([{value:'live',label:'2',disabled:false}]);
 });
 it('keeps library source editing available when the source still exists',()=>{
  const html=render({...existing,program:{programId:'live',arguments:{}}},[live]);expect(html).toContain('Edit in program library');
 });
 it('does not offer recovered orphan snapshots in the new duty-task selector',()=>{
  const document:TimetableAuthoringDocumentV1={formatVersion:1,id:'doc',periodDays:1,diagramLines:[],runs:[],arrivalOverrides:[],duties:[{id:'d',selector:{type:'TRAIN_NAME',name:'train'},elements:[]}],boundPrograms:[binding]};
  const before=structuredClone(document);
  renderToStaticMarkup(<I18nProvider><DutyTimeline document={document} duty={document.duties[0]} fieldScope="qa" library={[live]} stationNames={new Map()} stationIds={[]} onAssign={()=>{}} onPatch={()=>{}} onRemove={()=>{}} onViewRun={()=>{}}/></I18nProvider>);
  expect(captured.options[0]).toEqual([{value:'live',label:'2'}]);expect(document).toEqual(before);
 });
});
