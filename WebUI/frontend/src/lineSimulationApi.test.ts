import { afterEach,describe,expect,it,vi } from 'vitest';
import { api } from './api';
import type { SimulationProfile } from './types';
afterEach(()=>vi.unstubAllGlobals());
describe('line Reset inline maximum speed contract',()=>{
  it('sends an unsaved explicit cap in metres per second on the existing line endpoint',async()=>{
    const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify({lineRevision:2,totalTravelSeconds:10,legs:[]}),{status:200}));vi.stubGlobal('fetch',fetch);
    const profile:SimulationProfile={profileType:'TRAIN_TEMPLATE',profileId:'EMU',direction:'UP',maximumSpeedMetersPerSecond:20,destinationIds:['origin@1','terminal@1']};
    await api.simulateLine('world/a','diagram/line',profile,12500);
    expect(fetch).toHaveBeenCalledWith('/api/v1/worlds/world%2Fa/lines/diagram%2Fline/simulate',expect.objectContaining({method:'POST'}));
    const body=JSON.parse(fetch.mock.calls[0][1].body);expect(body.executionTimeoutMillis).toBe(12500);expect(body.maximumSpeedMetersPerSecond).toBe(20);expect(body.timetableId).toBeUndefined();expect(body.serviceId).toBeUndefined();expect(body.destinationIds).toEqual(profile.destinationIds);
  });
  it('sends the user wall-clock budget on full timetable simulation',async()=>{
    const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify({status:'COMPLETED',runs:[],simulatedTicks:0}),{status:200}));vi.stubGlobal('fetch',fetch);
    await api.simulateTimetable('world',{id:'t',periodDays:1,services:[]},[],1200,90001);
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({timetable:{id:'t',periodDays:1,services:[]},profileAssignments:[],delayThresholdTicks:1200,executionTimeoutMillis:90001});
  });
  it('preserves omitted and null cap semantics without inventing stored context',async()=>{
    const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify({lineRevision:2,totalTravelSeconds:10,legs:[]}),{status:200}));vi.stubGlobal('fetch',fetch);
    const profile:SimulationProfile={profileType:'WORLD_TRAIN',profileId:'train',direction:'DOWN'};
    await api.simulateLine('world','line',profile,60000);expect(JSON.parse(fetch.mock.calls[0][1].body)).not.toHaveProperty('maximumSpeedMetersPerSecond');
    await api.simulateLine('world','line',{...profile,maximumSpeedMetersPerSecond:null},60000);expect(JSON.parse(fetch.mock.calls[1][1].body).maximumSpeedMetersPerSecond).toBeNull();
  });
});
