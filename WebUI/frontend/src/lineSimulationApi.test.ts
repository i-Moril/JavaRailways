import { afterEach,describe,expect,it,vi } from 'vitest';
import { api } from './api';
import type { SimulationProfile } from './types';
afterEach(()=>vi.unstubAllGlobals());
describe('line Reset inline maximum speed contract',()=>{
  it('sends an unsaved explicit cap in metres per second on the existing line endpoint',async()=>{
    const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify({lineRevision:2,totalTravelSeconds:10,legs:[]}),{status:200}));vi.stubGlobal('fetch',fetch);
    const profile:SimulationProfile={profileType:'TRAIN_TEMPLATE',profileId:'EMU',direction:'UP',maximumSpeedMetersPerSecond:20,destinationIds:['origin@1','terminal@1']};
    await api.simulateLine('world/a','diagram/line',profile);
    expect(fetch).toHaveBeenCalledWith('/api/v1/worlds/world%2Fa/lines/diagram%2Fline/simulate',expect.objectContaining({method:'POST'}));
    const body=JSON.parse(fetch.mock.calls[0][1].body);expect(body.maximumSpeedMetersPerSecond).toBe(20);expect(body.timetableId).toBeUndefined();expect(body.serviceId).toBeUndefined();expect(body.destinationIds).toEqual(profile.destinationIds);
  });
  it('preserves omitted and null cap semantics without inventing stored context',async()=>{
    const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify({lineRevision:2,totalTravelSeconds:10,legs:[]}),{status:200}));vi.stubGlobal('fetch',fetch);
    const profile:SimulationProfile={profileType:'WORLD_TRAIN',profileId:'train',direction:'DOWN'};
    await api.simulateLine('world','line',profile);expect(JSON.parse(fetch.mock.calls[0][1].body)).not.toHaveProperty('maximumSpeedMetersPerSecond');
    await api.simulateLine('world','line',{...profile,maximumSpeedMetersPerSecond:null});expect(JSON.parse(fetch.mock.calls[1][1].body).maximumSpeedMetersPerSecond).toBeNull();
  });
});
