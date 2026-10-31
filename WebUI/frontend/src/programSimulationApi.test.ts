import {afterEach,describe,expect,it,vi} from 'vitest';
import {api} from './api';
import {demoApi} from './frontendDev';
import type {ProgramSimulationRequest} from './types';
afterEach(()=>vi.unstubAllGlobals());
const input:ProgramSimulationRequest={programId:'unsaved',definition:{parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{value:42}}]},arguments:{},startTick:100,profile:{type:'TRAIN_TEMPLATE',id:'EMU'},initialPosition:{wayPointId:'point',reverseTrain:true},executionTimeoutMillis:60000,maximumDurationTicks:2400};
describe('program simulation transport',()=>{
 it('posts exact transient input to the authenticated encoded world endpoint without saving or fetching a library record',async()=>{
  const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify({status:'FAILED',reason:'placement failed'}),{status:200}));vi.stubGlobal('fetch',fetch);
  expect(await api.simulateProgram('world/a',input)).toEqual({status:'FAILED',reason:'placement failed'});expect(fetch).toHaveBeenCalledTimes(1);expect(fetch.mock.calls[0][0]).toBe('/api/v1/worlds/world%2Fa/programs/simulate');expect(fetch.mock.calls[0][1].method).toBe('POST');expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual(input);
 });
 it('propagates real failures and clears privileged state on unauthorized responses',async()=>{
  const dispatchEvent=vi.fn();vi.stubGlobal('window',{dispatchEvent});vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('Session expired',{status:401})));
  await expect(api.simulateProgram('world',input)).rejects.toThrow('Session expired');expect(dispatchEvent).toHaveBeenCalledWith(expect.objectContaining({type:'javarailways:unauthorized'}));
 });
 it('explicitly rejects frontend demo execution rather than inventing success',async()=>{await expect(demoApi.simulateProgram('demo-world',input)).rejects.toThrow('live authenticated server');});
});
