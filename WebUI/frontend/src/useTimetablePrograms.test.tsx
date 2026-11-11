// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {useTimetablePrograms} from './useTimetablePrograms';
import {api} from './api';
vi.mock('./api',()=>({api:{timetablePrograms:vi.fn()}}));
let result:ReturnType<typeof useTimetablePrograms>,root:Root,container:HTMLDivElement;
function Probe({world='a',documentId='doc',revision=0}:{world?:string;documentId?:string;revision?:number}){result=useTimetablePrograms(world,documentId,revision);return <span>{result.records.map((record)=>record.name).join(',')}</span>;}
const record={formatVersion:1 as const,id:'1',name:'Program',definition:{parameters:[],steps:[]}};
beforeEach(async()=>{Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});vi.clearAllMocks();vi.mocked(api.timetablePrograms).mockResolvedValue([record]);container=document.createElement('div');document.body.append(container);root=createRoot(container);});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
describe('timetable program loading',()=>{
 it('loads programs only for the active timetable',async()=>{await act(async()=>root.render(<Probe/>));expect(api.timetablePrograms).toHaveBeenCalledWith('a','doc');expect(result.records).toEqual([record]);await act(async()=>root.render(<Probe documentId=""/>));expect(api.timetablePrograms).toHaveBeenCalledTimes(1);expect(result.records).toEqual([]);});
 it('discards a late response after the timetable identity changes',async()=>{let resolveFirst:(value:typeof record[])=>void=()=>{};vi.mocked(api.timetablePrograms).mockImplementationOnce(()=>new Promise((done)=>{resolveFirst=done;})).mockResolvedValue([]);await act(async()=>root.render(<Probe documentId="first"/>));await act(async()=>root.render(<Probe documentId="second"/>));await act(async()=>resolveFirst([record]));expect(result.records).toEqual([]);});
});
