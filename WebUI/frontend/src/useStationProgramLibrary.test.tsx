// @vitest-environment jsdom
import {act} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {api} from './api';
import {useStationProgramLibrary} from './useStationProgramLibrary';
import type {StationProgramRecord} from './types';
vi.mock('./api',()=>({api:{stationPrograms:vi.fn()}}));
const record=(name:string):StationProgramRecord=>({formatVersion:1,id:'123',name,revision:1,contentIdentity:'hash',definition:{parameters:[],steps:[]}});
const deferred=<T,>()=>{let resolve!:(value:T)=>void;const promise=new Promise<T>((done)=>{resolve=done;});return {promise,resolve};};
let root:Root,container:HTMLDivElement;
let result:ReturnType<typeof useStationProgramLibrary>;
function Probe({world='a',revision=0,page='graph'}:{world?:string;revision?:number;page?:string}){result=useStationProgramLibrary(world,page,revision);return <span>{result.records.map((record)=>record.name).join(',')}</span>;}
const render=async(props:Parameters<typeof Probe>[0])=>{await act(async()=>{root.render(<Probe {...props}/>);});};
beforeEach(()=>{vi.clearAllMocks();Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
describe('retained editor program-library resources',()=>{
 it('refreshes successful library changes even while the studio page stays graph, preserving the mounted owner',async()=>{
  vi.mocked(api.stationPrograms).mockResolvedValueOnce([record('old')]).mockResolvedValueOnce([]).mockResolvedValueOnce([record('new')]);
  await render({});expect(result.records).toEqual([record('old')]);
  await render({revision:1});expect(result.records).toEqual([]);
  await render({revision:2});expect(result.records).toEqual([record('new')]);
  expect(api.stationPrograms).toHaveBeenCalledTimes(3);
 });
 it('does not let a request from before deletion restore the removed library entry',async()=>{
  const old=deferred<StationProgramRecord[]>();vi.mocked(api.stationPrograms).mockReturnValueOnce(old.promise).mockResolvedValueOnce([]);
  await render({});await render({revision:1});expect(result.records).toEqual([]);
  await act(async()=>old.resolve([record('deleted')]));expect(result.records).toEqual([]);expect(result.loading).toBe(false);
 });
 it('isolates late responses from a previous world',async()=>{
  const old=deferred<StationProgramRecord[]>();vi.mocked(api.stationPrograms).mockReturnValueOnce(old.promise).mockResolvedValueOnce([record('world b')]);
  await render({});await render({world:'b'});await act(async()=>old.resolve([record('world a')]));
  expect(result.records).toEqual([record('world b')]);expect(api.stationPrograms).toHaveBeenLastCalledWith('b');
 });
 it('surfaces refresh failure without modifying bound document state',async()=>{
  vi.mocked(api.stationPrograms).mockResolvedValueOnce([record('deleted')]).mockRejectedValueOnce(new Error('offline'));
  await render({});await render({revision:1});expect(result.records).toEqual([]);expect(result.error).toContain('offline');expect(result.loading).toBe(false);
 });
});
