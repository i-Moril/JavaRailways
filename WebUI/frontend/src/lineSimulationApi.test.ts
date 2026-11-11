import { afterEach,describe,expect,it,vi } from 'vitest';
import { api } from './api';
import type { LineSimulationRequest } from './types';
const measurement={measurementSource:'function main(timetable) {}',firstDepartureTick:1200,fixedDwellTicks:200,minimumDwellTicks:100,calls:[
  {callId:'origin',stationId:'a',destinationId:'origin@1',kind:'ORIGIN' as const,viaIds:[]},
  {callId:'terminal',stationId:'b',destinationId:'terminal@1',kind:'TERMINAL' as const,viaIds:['junction']},
]};
afterEach(()=>vi.unstubAllGlobals());
describe('line Reset inline maximum speed contract',()=>{
  it('rejects insufficient-work responses with the precise diagnostic, never as partial success',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({code:'LINE_DWELL_INSUFFICIENT',error:'b: 50 ticks required, 40 supplied'}),{status:422})));
    await expect(api.simulateLine('world','line',{...measurement,profileType:'TRAIN_TEMPLATE',profileId:'EMU',direction:'UP'},60000)).rejects.toMatchObject({code:'LINE_DWELL_INSUFFICIENT',detail:'b: 50 ticks required, 40 supplied'});
  });
  it('sends an unsaved explicit cap in metres per second on the existing line endpoint',async()=>{
    const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify({lineRevision:2,totalTravelSeconds:10,legs:[]}),{status:200}));vi.stubGlobal('fetch',fetch);
    const profile:LineSimulationRequest={...measurement,profileType:'TRAIN_TEMPLATE',profileId:'EMU',direction:'UP',maximumSpeedMetersPerSecond:20,destinationIds:['origin@1','terminal@1']};
    await api.simulateLine('world/a','diagram/line',profile,12500);
    expect(fetch).toHaveBeenCalledWith('/api/v1/worlds/world%2Fa/lines/diagram%2Fline/simulate',expect.objectContaining({method:'POST'}));
    const body=JSON.parse(fetch.mock.calls[0][1].body);expect(body.executionTimeoutMillis).toBe(12500);expect(body.maximumSpeedMetersPerSecond).toBe(20);expect(body.timetableId).toBeUndefined();expect(body.serviceId).toBeUndefined();expect(body.destinationIds).toEqual(profile.destinationIds);
    expect(body).toMatchObject(measurement);
  });
  it('sends the user wall-clock budget on full timetable simulation',async()=>{
    const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify({status:'COMPLETED',runs:[],simulatedTicks:0}),{status:200}));vi.stubGlobal('fetch',fetch);
    await api.simulateTimetable('world',{id:'t',periodDays:1,services:[]},[],1200,90001);
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({timetable:{id:'t',periodDays:1,services:[]},profileAssignments:[],delayThresholdTicks:1200,executionTimeoutMillis:90001});
  });
  it('preserves omitted and null cap semantics without inventing stored context',async()=>{
    const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify({lineRevision:2,totalTravelSeconds:10,legs:[]}),{status:200}));vi.stubGlobal('fetch',fetch);
    const profile:LineSimulationRequest={...measurement,profileType:'WORLD_TRAIN',profileId:'train',direction:'DOWN'};
    await api.simulateLine('world','line',profile,60000);expect(JSON.parse(fetch.mock.calls[0][1].body)).not.toHaveProperty('maximumSpeedMetersPerSecond');
    await api.simulateLine('world','line',{...profile,maximumSpeedMetersPerSecond:null},60000);expect(JSON.parse(fetch.mock.calls[1][1].body).maximumSpeedMetersPerSecond).toBeNull();
  });
});
